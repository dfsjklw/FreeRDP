# RDPEI client: stop resending unchanged touch contacts every 20 ms

Upstream bug report / patch description for FreeRDP.

* Component: `channels/rdpei` (client side, MS-RDPEI / MS-RDPINPUT)
* Affected clients: **all** clients that enable native touch input
  (`/multitouch`), e.g. X11, Windows, iOS and Android
* Found while adding native touch support to the Android client
* Symptom was reproducible against Windows 11 over a normal RDP session

---

## 1. Symptom

With native touch input enabled (`+multitouch`), on the remote **Windows 11** side:

* pressing and holding a finger on the desktop correctly pops the
  press-and-hold (context) menu after ~500 ms, **but**
* releasing the finger immediately afterwards is **also handled as a click** —
  a "long press followed by a phantom click".

Dragging, tapping, and two finger gestures otherwise worked as expected, and the
problem was not reproducible on RDP clients that only emulate mouse events.

## 2. Root cause

`channels/rdpei/client/rdpei_main.c`, `rdpei_add_frame()` is called by the RDPEI
scheduling thread every 20 ms
(`rdpei_schedule_thread()` → `WaitForSingleObject(rdpei->event, 20)` → `rdpei_poll_run()`).

The function walked **all** contact slots and, in the `else if (contactPoint->active)`
branch, unconditionally appended the contact to the outgoing frame:

```c
for (UINT16 i = 0; i < rdpei->maxTouchContacts; i++)
{
    RDPINPUT_CONTACT_POINT* contactPoint = &rdpei->contactPoints[i];
    RDPINPUT_CONTACT_DATA* contact = &contactPoint->data;

    if (contactPoint->dirty)
    {
        contacts[frame.contactCount] = *contact;
        rdpei->contactPoints[i].dirty = FALSE;
        frame.contactCount++;
    }
    else if (contactPoint->active)
    {
        if (contact->contactFlags & RDPINPUT_CONTACT_FLAG_DOWN)
        {
            contact->contactFlags = RDPINPUT_CONTACT_FLAG_UPDATE;
            contact->contactFlags |= RDPINPUT_CONTACT_FLAG_INRANGE;
            contact->contactFlags |= RDPINPUT_CONTACT_FLAG_INCONTACT;
        }

        contacts[frame.contactCount] = *contact;   /* <-- resent even without changes */
        frame.contactCount++;
    }
    ...
}
```

The original intent of that branch is only to rewrite a stale `DOWN` flag of an
already reported contact into `UPDATE` (a contact may carry `DOWN` in exactly one
frame). Because the contact was appended unconditionally, *holding a finger still*
kept the client busy transmitting **one identical `UPDATE | INRANGE | INCONTACT`
frame every 20 ms** for the whole duration of the touch.

Measured on the Android client (frame log enabled with `-DWITH_DEBUG_RDPEI=ON`),
a 1.2 s press without any movement produced:

```
21:03:27.059  APP   flags=257                       (DOWN from the platform)
21:03:27.064  FRAME 0x19 DOWN|INRANGE|INCONTACT
21:03:27.084  FRAME 0x1A UPDATE|INRANGE|INCONTACT   <-- repeated every ~20 ms
   ... 59 more identical frames, x/y never changed ...
21:03:28.348  FRAME 0x1A UPDATE|INRANGE|INCONTACT
21:03:28.352  APP   flags=258                       (UP from the platform)
21:03:28.353  FRAME 0x1A
21:03:28.368  FRAME 0x04 UP
```

65 frames were sent for a single stationary contact. This flood of "the contact
is still active and updating" frames makes the server keep treating the touch as
ongoing, so the release is evaluated as an independent tap in addition to the
already triggered press-and-hold.

## 3. Fix

Only report an active contact when it actually changed, or once when a stale
`DOWN` has to be rewritten to `UPDATE`:

```diff
 		else if (contactPoint->active)
 		{
+			/* A contact that is still active but did not change anything must not be
+			 * reported again: only the first frame of a contact may carry DOWN, every
+			 * following frame has to use UPDATE. Report the contact once for that
+			 * rewrite, then stay silent until it actually changes again.
+			 *
+			 * Reporting unchanged contacts on every 20ms poll tick floods the server
+			 * with identical UPDATE frames, which breaks its press-and-hold vs. tap
+			 * detection (a long press is followed by a phantom click on release). */
 			if (contact->contactFlags & RDPINPUT_CONTACT_FLAG_DOWN)
 			{
-				contact->contactFlags = RDPINPUT_CONTACT_FLAG_UPDATE;
-				contact->contactFlags |= RDPINPUT_CONTACT_FLAG_INRANGE;
-				contact->contactFlags |= RDPINPUT_CONTACT_FLAG_INCONTACT;
+				contact->contactFlags = RDPINPUT_CONTACT_FLAG_UPDATE |
+				                        RDPINPUT_CONTACT_FLAG_INRANGE |
+				                        RDPINPUT_CONTACT_FLAG_INCONTACT;
+
+				contacts[frame.contactCount] = *contact;
+				frame.contactCount++;
 			}
-
-			contacts[frame.contactCount] = *contact;
-			frame.contactCount++;
 		}
```

## 4. Verification

Same setup, same 1.2 s press (frames captured with `-DWITH_DEBUG_RDPEI=ON`):

| Scenario | Before | After |
| --- | --- | --- |
| single finger, held still 1.2 s | 65 frames | **4 frames** |
| single finger drag (3 moves) | — | DOWN, UPDATE (rewrite), 3 × UPDATE with exact coordinates, UPDATE (position hold before UP), UP |
| two finger drag (~1.5 s) | ~75 frames × 2 contacts | 22 frames, both contacts (`contactId` 0/1) get DOWN → synchronised UPDATEs → position-hold UPDATE + UP, no ghost contacts |

Resulting sequence for a stationary long press after the fix:

```
21:13:30.689  APP   flags=257
21:13:30.705  FRAME 0x19 DOWN|INRANGE|INCONTACT
21:13:30.725  FRAME 0x1A UPDATE|INRANGE|INCONTACT   (only the DOWN rewrite)
        <-- 1.2 s of complete silence while the finger is held -->
21:13:31.970  APP   flags=258
21:13:31.971  FRAME 0x1A                            (position hold, required by MS-RDPEI)
21:13:31.984  FRAME 0x04 UP
```

* Press-and-hold still works and the phantom click on release is gone
  (confirmed on Windows 11 with the Android client).
* Movement is still reported per touch update, so dragging is unaffected.

## 5. Suggested commit message

```
[rdpei] do not resend unchanged touch contacts

rdpei_add_frame() appended every active contact to the outgoing frame on each
20 ms poll tick, even when the contact did not change. Holding a finger still
therefore transmitted an identical UPDATE frame every 20 ms, which makes the
server treat the touch as ongoing and evaluate the release as an additional
tap (long press pops the context menu, releasing it clicks).

Only report an active contact when it changed, or once when a stale DOWN flag
has to be rewritten into UPDATE.
```
