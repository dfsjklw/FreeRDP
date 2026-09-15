/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android Event System
 *
 * Copyright 2010-2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
 * Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

#include <freerdp/config.h>

#include <winpr/crt.h>

#include <freerdp/freerdp.h>
#include <freerdp/log.h>
#include <freerdp/client.h>

#define TAG CLIENT_TAG("android")

#include "android_freerdp.h"
#include "android_cliprdr.h"

BOOL android_push_event(freerdp* inst, ANDROID_EVENT* event)
{
	androidContext* aCtx = (androidContext*)inst->context;

	WINPR_ASSERT(aCtx);
	WINPR_ASSERT(event);

	ANDROID_EVENT_QUEUE* queue = aCtx->event_queue;

	WINPR_ASSERT(queue);
	WINPR_ASSERT(queue->lockInitialized);

	BOOL rc = FALSE;

	/* The RDP thread pops from the same queue, so the capacity check, the reallocation and
	 * the append must all happen under the lock. */
	EnterCriticalSection(&queue->lock);

	if (queue->count >= queue->size)
	{
		size_t new_size = queue->size;
		do
		{
			if (new_size >= SIZE_MAX - 128ull)
				goto out;

			new_size += 128ull;
		} while (new_size <= (size_t)queue->count);

		void* new_events = realloc((void*)queue->events, sizeof(ANDROID_EVENT*) * new_size);

		if (!new_events)
			goto out;

		queue->events = (ANDROID_EVENT**)new_events;
		queue->size = new_size;
	}

	queue->events[(queue->count)++] = event;
	rc = SetEvent(queue->isSet);

out:
	LeaveCriticalSection(&queue->lock);
	return rc;
}

static ANDROID_EVENT* android_pop_event(ANDROID_EVENT_QUEUE* queue)
{
	ANDROID_EVENT* event = nullptr;

	WINPR_ASSERT(queue);
	WINPR_ASSERT(queue->lockInitialized);

	EnterCriticalSection(&queue->lock);

	if (queue->count < 1)
		goto out;

	event = queue->events[0];
	(queue->count)--;

	for (size_t i = 0; i < (size_t)queue->count; i++)
	{
		queue->events[i] = queue->events[i + 1];
	}

out:
	LeaveCriticalSection(&queue->lock);
	return event;
}

static BOOL android_process_event(ANDROID_EVENT_QUEUE* queue, freerdp* inst)
{
	rdpContext* context;

	WINPR_ASSERT(queue);
	WINPR_ASSERT(inst);

	context = inst->context;
	WINPR_ASSERT(context);

	while (true)
	{
		BOOL rc = FALSE;
		androidContext* afc = (androidContext*)context;
		ANDROID_EVENT* event = android_pop_event(queue);

		if (!event)
		{
			/* Queue drained: clear the wake up signal. A push racing with us either
			 * happened before this check (queue not empty, signal stays set) or sets the
			 * event again afterwards, so no contact can get stranded. */
			EnterCriticalSection(&queue->lock);
			if (queue->count == 0)
				(void)ResetEvent(queue->isSet);
			LeaveCriticalSection(&queue->lock);
			break;
		}

		switch (event->type)
		{
			case EVENT_TYPE_KEY:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_keyboard_event(context->input, key_event->flags,
				                                       key_event->scancode);
			}
			break;

			case EVENT_TYPE_KEY_UNICODE:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_unicode_keyboard_event(context->input, key_event->flags,
				                                               key_event->scancode);
			}
			break;

			case EVENT_TYPE_CURSOR:
			{
				ANDROID_EVENT_CURSOR* cursor_event = (ANDROID_EVENT_CURSOR*)event;

				rc = freerdp_input_send_mouse_event(context->input, cursor_event->flags,
				                                    cursor_event->x, cursor_event->y);
			}
			break;

			case EVENT_TYPE_CLIPBOARD:
			{
				ANDROID_EVENT_CLIPBOARD* clipboard_event = (ANDROID_EVENT_CLIPBOARD*)event;
				const char* mimeType = clipboard_event->mimeType;
				UINT32 formatId = ClipboardRegisterFormat(afc->clipboard, mimeType);
				UINT32 size = clipboard_event->data_length;

				if (size)
					ClipboardSetData(afc->clipboard, formatId, clipboard_event->data, size);
				else
					ClipboardEmpty(afc->clipboard);

				rc = (android_cliprdr_send_client_format_list(afc->cliprdr) == CHANNEL_RC_OK);
			}
			break;

			case EVENT_TYPE_TOUCH:
			{
				ANDROID_EVENT_TOUCH* touch_event = (ANDROID_EVENT_TOUCH*)event;

				/* freerdp_client_handle_touch() forwards the contact through the
				 * RDPEI (MS-RDPINPUT) channel and falls back to mouse emulation
				 * when the remote does not support native touch input. */
				rc = freerdp_client_handle_touch(&afc->common, touch_event->flags,
				                                 touch_event->contactId, touch_event->pressure,
				                                 touch_event->x, touch_event->y);
			}
			break;

			case EVENT_TYPE_DISCONNECT:
			default:
				break;
		}

		const BOOL isTouch = (event->type == EVENT_TYPE_TOUCH);
		android_event_free(event);

		if (!rc)
		{
			/* A failing touch event (e.g. contact bookkeeping mismatch) must never tear
			 * down the session, drop it instead. */
			if (isTouch)
			{
				WLog_WARN(TAG, "Failed to handle touch event, dropping it");
				continue;
			}
			return FALSE;
		}
	}

	return TRUE;
}

HANDLE android_get_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return nullptr;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return nullptr;

	return aCtx->event_queue->isSet;
}

BOOL android_check_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return FALSE;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return FALSE;

	if (WaitForSingleObject(aCtx->event_queue->isSet, 0) == WAIT_OBJECT_0)
	{
		/* android_process_event() resets the signal once the queue is drained, so an
		 * event pushed while we are processing is never lost. */
		if (!android_process_event(aCtx->event_queue, inst))
			return FALSE;
	}

	return TRUE;
}

ANDROID_EVENT_KEY* android_event_key_new(int flags, UINT16 scancode)
{
	ANDROID_EVENT_KEY* event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_KEY;
	event->flags = flags;
	event->scancode = scancode;
	return event;
}

static void android_event_key_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_KEY* android_event_unicodekey_new(UINT16 flags, UINT16 key)
{
	ANDROID_EVENT_KEY* event;
	event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_KEY_UNICODE;
	event->flags = flags;
	event->scancode = key;
	return event;
}

static void android_event_unicodekey_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_CURSOR* android_event_cursor_new(UINT16 flags, UINT16 x, UINT16 y)
{
	ANDROID_EVENT_CURSOR* event;
	event = (ANDROID_EVENT_CURSOR*)calloc(1, sizeof(ANDROID_EVENT_CURSOR));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_CURSOR;
	event->x = x;
	event->y = y;
	event->flags = flags;
	return event;
}

static void android_event_cursor_free(ANDROID_EVENT_CURSOR* event)
{
	free(event);
}

ANDROID_EVENT_TOUCH* android_event_touch_new(UINT32 flags, INT32 contactId, UINT32 pressure,
                                             INT32 x, INT32 y)
{
	ANDROID_EVENT_TOUCH* event = (ANDROID_EVENT_TOUCH*)calloc(1, sizeof(ANDROID_EVENT_TOUCH));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_TOUCH;
	event->flags = flags;
	event->contactId = contactId;
	event->pressure = pressure;
	event->x = x;
	event->y = y;
	return event;
}

static void android_event_touch_free(ANDROID_EVENT_TOUCH* event)
{
	free(event);
}

ANDROID_EVENT* android_event_disconnect_new(void)
{
	ANDROID_EVENT* event;
	event = (ANDROID_EVENT*)calloc(1, sizeof(ANDROID_EVENT));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_DISCONNECT;
	return event;
}

static void android_event_disconnect_free(ANDROID_EVENT* event)
{
	free(event);
}

ANDROID_EVENT_CLIPBOARD* android_event_clipboard_new(const void* data, size_t data_length,
                                                     const char* mimeType)
{
	ANDROID_EVENT_CLIPBOARD* event;
	event = (ANDROID_EVENT_CLIPBOARD*)calloc(1, sizeof(ANDROID_EVENT_CLIPBOARD));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_CLIPBOARD;
	event->mimeType = mimeType ? _strdup(mimeType) : nullptr;

	if (mimeType && !event->mimeType)
	{
		free(event);
		return nullptr;
	}

	if (data && data_length > 0)
	{
		const BOOL isText = !mimeType || strcmp(mimeType, "text/plain") == 0;
		/* Text data needs a null terminator; image data is stored as-is. */
		event->data = isText ? calloc(data_length + 1, sizeof(char)) : malloc(data_length);

		if (!event->data)
		{
			free(event->mimeType);
			free(event);
			return nullptr;
		}

		memcpy(event->data, data, data_length);
		event->data_length = isText ? data_length + 1 : data_length;
	}

	return event;
}

static void android_event_clipboard_free(ANDROID_EVENT_CLIPBOARD* event)
{
	if (event)
	{
		free(event->data);
		free(event->mimeType);
		free(event);
	}
}

BOOL android_event_queue_init(freerdp* inst)
{
	androidContext* aCtx = (androidContext*)inst->context;
	ANDROID_EVENT_QUEUE* queue;
	queue = (ANDROID_EVENT_QUEUE*)calloc(1, sizeof(ANDROID_EVENT_QUEUE));

	if (!queue)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		return FALSE;
	}

	queue->size = 16;
	queue->count = 0;
	queue->isSet = CreateEventA(nullptr, TRUE, FALSE, nullptr);

	if (!queue->isSet)
	{
		free(queue);
		return FALSE;
	}

	queue->events = (ANDROID_EVENT**)calloc(queue->size, sizeof(ANDROID_EVENT*));

	if (!queue->events)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		(void)CloseHandle(queue->isSet);
		free(queue);
		return FALSE;
	}

	InitializeCriticalSection(&queue->lock);
	queue->lockInitialized = TRUE;

	aCtx->event_queue = queue;
	return TRUE;
}

void android_event_queue_uninit(freerdp* inst)
{
	androidContext* aCtx;
	ANDROID_EVENT_QUEUE* queue;

	if (!inst || !inst->context)
		return;

	aCtx = (androidContext*)inst->context;
	queue = aCtx->event_queue;

	if (queue)
	{
		if (queue->isSet)
		{
			(void)CloseHandle(queue->isSet);
			queue->isSet = nullptr;
		}

		/* events that were never processed are freed here, they used to leak */
		if (queue->lockInitialized)
		{
			ANDROID_EVENT* event;

			while ((event = android_pop_event(queue)) != nullptr)
				android_event_free(event);

			DeleteCriticalSection(&queue->lock);
			queue->lockInitialized = FALSE;
		}

		if (queue->events)
		{
			free(queue->events);
			queue->events = nullptr;
			queue->size = 0;
			queue->count = 0;
		}

		free(queue);
	}
}

void android_event_free(ANDROID_EVENT* event)
{
	if (!event)
		return;

	switch (event->type)
	{
		case EVENT_TYPE_KEY:
			android_event_key_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_KEY_UNICODE:
			android_event_unicodekey_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_CURSOR:
			android_event_cursor_free((ANDROID_EVENT_CURSOR*)event);
			break;

		case EVENT_TYPE_DISCONNECT:
			android_event_disconnect_free((ANDROID_EVENT*)event);
			break;

		case EVENT_TYPE_CLIPBOARD:
			android_event_clipboard_free((ANDROID_EVENT_CLIPBOARD*)event);
			break;

		case EVENT_TYPE_TOUCH:
			android_event_touch_free((ANDROID_EVENT_TOUCH*)event);
			break;

		default:
			break;
	}
}
