# FreeRDP: A Remote Desktop Protocol Implementation

FreeRDP is a free implementation of the Remote Desktop Protocol (RDP), released under the Apache license.
Enjoy the freedom of using your software wherever you want, the way you want it, in a world where
interoperability can finally liberate your computing experience.

## Code Quality Status

[![abi-checker](https://github.com/FreeRDP/FreeRDP/actions/workflows/abi-checker.yml/badge.svg)](https://github.com/FreeRDP/FreeRDP/actions/workflows/abi-checker.yml)
[![clang-tidy-review](https://github.com/FreeRDP/FreeRDP/actions/workflows/clang-tidy.yml/badge.svg?event=pull_request_target)](https://github.com/FreeRDP/FreeRDP/actions/workflows/clang-tidy.yml)
[![CodeQL](https://github.com/FreeRDP/FreeRDP/actions/workflows/codeql-analysis.yml/badge.svg?branch=master)](https://github.com/FreeRDP/FreeRDP/actions/workflows/codeql-analysis.yml)
[![mingw-builder](https://github.com/FreeRDP/FreeRDP/actions/workflows/mingw.yml/badge.svg)](https://github.com/FreeRDP/FreeRDP/actions/workflows/mingw.yml)
[![macos-builder](https://github.com/FreeRDP/FreeRDP/actions/workflows/macos.yml/badge.svg)](https://github.com/FreeRDP/FreeRDP/actions/workflows/macos.yml)
[![[arm,ppc,ricsv] architecture builds](https://github.com/FreeRDP/FreeRDP/actions/workflows/alt-architectures.yml/badge.svg)](https://github.com/FreeRDP/FreeRDP/actions/workflows/alt-architectures.yml)
[![[freebsd] architecture builds](https://github.com/FreeRDP/FreeRDP/actions/workflows/freebsd.yml/badge.svg)](https://github.com/FreeRDP/FreeRDP/actions/workflows/freebsd.yml)
[![coverity](https://scan.coverity.com/projects/616/badge.svg)](https://scan.coverity.com/projects/freerdp)

## Changes in this fork

This fork tracks FreeRDP upstream (synced up to `ea41cb5d4`) and adds the changes
below. They live in the Android client unless stated otherwise.

### Native touch input (MS-RDPEI)

* Real multi touch contacts are forwarded to the remote desktop instead of being
  translated into mouse gestures: new `ANDROID_EVENT_TOUCH` event type,
  `freerdp_send_touch_event()` / `freerdp_is_native_touch_supported()` JNI entry
  points, per pointer dispatch in `SessionView` (scaled coordinate mapping, system
  gesture exclusion rects) and contact forwarding with pressure mapping in
  `SessionInputManager`.
* New "Native touch input" preference. Enabling it requests the RDPEI channel
  (`/multitouch`), without which `freerdp_client_load_addins()` never loads rdpei.
  A failing touch event is dropped instead of tearing the session down.
* rdpei (affects every client using `/multitouch`): `rdpei_add_frame()` appended all
  active contacts to the outgoing frame on each 20 ms poll tick even when nothing
  changed, so holding a finger still produced an identical UPDATE frame every 20 ms.
  The server then treated the touch as ongoing: press and hold popped the context
  menu and the release afterwards also fired a click. An active contact is now only
  reported when it changed (or once, when a stale DOWN flag has to be rewritten into
  UPDATE). A stationary 1.2 s press went from 65 frames to 4. Background:
  [docs/rdpei-periodic-frame-resend.md](docs/rdpei-periodic-frame-resend.md).

### Session UI

* Floating toolbar: new disconnect button.
* Keyboard aware panning: the session picture is shifted while the soft keyboard or
  the extended key bar is up, with a grip above the keyboard to pan to the area the
  keyboard hides. The grip only appears when the picture is actually larger than the
  visible area.
* Quality profile button: switches the running bookmark between bandwidth saver,
  balanced and best quality. The profile drives the RDPGFX/RemoteFX options, the
  progressive codec and video optimisation, and applies on the next connect.
* New connection settings: network type (auto, modem, broadband low/high, wan, lan),
  progressive codec and video optimised, stored in three new bookmark columns
  (database version 18 -> 19).

### Network statistics

* In session overlay showing the round trip time, the throughput in MB/s and the
  frame rate counted from the graphics updates the server pushes to the client.
  It can be switched off in the application settings (User Interface).
* Client side NETCHAR measurement: the RTT/bandwidth measure callbacks are installed
  for the client role, the measurement starts with the client info and is repeated
  once the session became active (Windows 11 ignores the connect time request). The
  measured values are handed to the Android client over JNI.

### Fixes

* Multi touch drag no longer kills the session. The Android event queue is shared
  between the JNI (UI) thread and the RDP thread; without a lock, dragging with three
  or four fingers could place the same event pointer in two slots, free it twice and
  abort the process in scudo (`android_event_touch_free`). The queue is now protected
  by a critical section, the wake up event is reset only once the queue is drained,
  and pending events are freed on shutdown instead of leaking.

### Build speed

* The Android build no longer rebuilds the whole external tree on every invocation. The `openh264`
  ExternalProject never wrote the stamp file of its git update step, which kept openh264 - and
  everything depending on it (`ffmpeg`, `freerdp`) - dirty, so every build re-ran the complete
  external build (`ninja -d explain` reported
  `output .../openh264-stamp/openh264-update doesn't exist`). The tag is pinned, so that step is
  now a no-op which creates its stamp like the other external projects do.
* The `freerdp` ExternalProject builds always (`BUILD_ALWAYS TRUE`). An ExternalProject stamp only
  tracks its own steps, not the sources below `SOURCE_DIR`: with a stable stamp a modified
  `libfreerdp`/`winpr` file would have been left uncompiled and a stale `libfreerdp3.so` shipped.
  The inner ninja/make now does the real change detection, which costs a few seconds when nothing
  changed.
* Measured on the build machine (24 threads): an unchanged tree went from 218 s to 1-3 s, and
  changing one C file from 240 s to 2-3 s. Verified with a `#error` probe - injecting one into
  `libfreerdp/codec/h264.c` fails the build again, so sources are still recompiled.
* `org.gradle.jvmargs` raised from 4 GB to 8 GB (the build machine has 23 GB), and the build script
  keeps the Gradle daemon alive between builds instead of stopping it (`gradle --stop` before every
  build cost about 60 s).

The Android client is built from `client/Android/Studio` (`gradle assembleDebug`
produces `aFreeRDP-arm64-v8a-debug.apk`, install with `adb install -r -t`).

## Resources

Project website: https://www.freerdp.com/

Issue tracker: https://github.com/FreeRDP/FreeRDP/issues

Sources: https://github.com/FreeRDP/FreeRDP/

Downloads: https://pub.freerdp.com/releases/

Wiki: https://github.com/FreeRDP/FreeRDP/wiki

API documentation: https://pub.freerdp.com/api/

Security policy: https://github.com/FreeRDP/FreeRDP/security/policy

FAQ: https://github.com/FreeRDP/FreeRDP/wiki/FAQ

### Contact

* Matrix room : `#FreeRDP:matrix.org` (main)
  * IRC channel : `#freerdp @ irc.oftc.net` (bridged)
* Mailing list: https://lists.sourceforge.net/lists/listinfo/freerdp-devel

## Microsoft Open Specifications

Information regarding the Microsoft Open Specifications can be found at:
https://www.microsoft.com/openspecifications/

A list of reference documentation is maintained here:
https://github.com/FreeRDP/FreeRDP/wiki/Reference-Documentation

## Compilation

Instructions on how to get started compiling FreeRDP can be found on the wiki:
https://github.com/FreeRDP/FreeRDP/wiki/Compilation
