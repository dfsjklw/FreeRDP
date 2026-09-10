/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android Event System
 *
 * Copyright 2010-2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

#ifndef FREERDP_CLIENT_ANDROID_EVENT_H
#define FREERDP_CLIENT_ANDROID_EVENT_H
#include <freerdp/freerdp.h>
#include <freerdp/api.h>

#define EVENT_TYPE_KEY 1
#define EVENT_TYPE_CURSOR 2
#define EVENT_TYPE_DISCONNECT 3
#define EVENT_TYPE_KEY_UNICODE 4
#define EVENT_TYPE_CLIPBOARD 5
#define EVENT_TYPE_TOUCH 6

typedef struct
{
	int type;
} ANDROID_EVENT;

typedef struct
{
	int type;
	int flags;
	UINT16 scancode;
} ANDROID_EVENT_KEY;

typedef struct
{
	int type;
	UINT16 flags;
	UINT16 x;
	UINT16 y;
} ANDROID_EVENT_CURSOR;

typedef struct
{
	int type;
	void* data;
	int data_length;
	char* mimeType;
} ANDROID_EVENT_CLIPBOARD;

/* Native (RDPEI / MS-RDPINPUT) touch contact event. */
typedef struct
{
	int type;
	UINT32 flags;      /* FREERDP_TOUCH_* */
	INT32 contactId;   /* finger id, stable for the lifetime of the contact */
	UINT32 pressure;   /* only used if FREERDP_TOUCH_HAS_PRESSURE is set */
	INT32 x;
	INT32 y;
} ANDROID_EVENT_TOUCH;

typedef struct
{
	int size;
	int count;
	HANDLE isSet;
	ANDROID_EVENT** events;
} ANDROID_EVENT_QUEUE;

FREERDP_LOCAL BOOL android_push_event(freerdp* inst, ANDROID_EVENT* event);

FREERDP_LOCAL HANDLE android_get_handle(freerdp* inst);
FREERDP_LOCAL BOOL android_check_handle(freerdp* inst);

FREERDP_LOCAL ANDROID_EVENT_KEY* android_event_key_new(int flags, UINT16 scancode);
FREERDP_LOCAL ANDROID_EVENT_KEY* android_event_unicodekey_new(UINT16 flags, UINT16 key);
FREERDP_LOCAL ANDROID_EVENT_CURSOR* android_event_cursor_new(UINT16 flags, UINT16 x, UINT16 y);
FREERDP_LOCAL ANDROID_EVENT* android_event_disconnect_new(void);
FREERDP_LOCAL ANDROID_EVENT_TOUCH* android_event_touch_new(UINT32 flags, INT32 contactId,
                                                           UINT32 pressure, INT32 x, INT32 y);
FREERDP_LOCAL ANDROID_EVENT_CLIPBOARD*
android_event_clipboard_new(const void* data, size_t data_length, const char* mimeType);

FREERDP_LOCAL void android_event_free(ANDROID_EVENT* event);

FREERDP_LOCAL BOOL android_event_queue_init(freerdp* inst);
FREERDP_LOCAL void android_event_queue_uninit(freerdp* inst);

#endif /* FREERDP_CLIENT_ANDROID_EVENT_H */
