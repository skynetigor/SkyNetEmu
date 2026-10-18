# Linux native touchscreen implementation plan

Status: implemented, with device verification recorded in `docs/linux-input-findings.md`. Linux's simulated touchscreen
is replaced by native XInput 2.2 multitouch. Android fingers must reach
touch-aware Linux applications as independent touch contacts. The Linux input choices become **Touchscreen** and
**Touchpad**; Touchscreen sends native touch, while Touchpad remains the way to operate mouse-driven applications.

Native touch must not inject mouse movement, buttons, scrolling or right-click gestures from fingers. Physical mouse
input and Windows input retain their existing paths. Touch support does not create touch controls inside games that
only implement mouse, keyboard or controller input.

## Starting implementation and integration points

| Location | Current behavior | Required change |
| --- | --- | --- |
| `widget/TouchpadView.java` | Both direct-touch paths inject mouse events; fingers can be suppressed when a physical mouse is connected | Route Linux native touch before mouse gestures and physical-mouse suppression |
| `widget/InputControlsView.java` | Routes fingers to overlay controls; can forward a full move event repeatedly inside a per-finger loop | Capture each finger once and forward only guest-owned contacts, once per sample |
| `xserver/XServer.java` | Has pointer injection methods but no touch API | Add contact begin, update, end and cancel operations |
| `xserver/extensions/XInput2Extension.java` | Reports XI 2.2, exposes four mouse/keyboard devices, lacks touch classes/events; several grab requests are stubbed | Implement device discovery, version negotiation, touch event delivery and ownership |
| `xserver/XClient.java` | Event sending writes synchronously to the client stream | Prevent touch delivery and slow clients from blocking Android's UI thread |
| `ui/shortcut/LinuxShortcutEditor.kt`, `ui/container/LinuxContainerCreateFragment.kt`, `XServerDisplayActivity.java` | Persist simulated touch as a boolean and change it from the sidebar | Resolve and persist a Linux-specific native-touch mode |

All Java paths above are relative to `app/src/main/java/com/winlator/cmod/`. The shared Linux container continues to
hold game data; input mode belongs to each shortcut, including Steam.

## 1. Establish protocol and event probes

Add a debug-only Xlib/libXi touch probe under `tools/input-test/` and an SDL touch visualizer. Build native ARM64,
x86-64 and i386 variants for the supported execution paths.

The XI2 probe must print device classes, selected masks, contact IDs, source/device IDs, coordinates and timestamps.
Count core mouse, XI2 mouse, raw touch and normal touch events separately. The SDL visualizer must show independent
contacts and report finger events separately from mouse events. Disable SDL mouse-to-touch and touch-to-mouse
synthesis in these probes so they cannot produce a false pass.

Capture a reference trace from an X.Org touchscreen session for device discovery, event layout and ownership cases.
Use it alongside the protocol specification when testing our server's wire encoding.

Exit criterion: the probes demonstrate today's missing touch device and distinguish genuine touch from mouse synthesis.

## 2. Implement contact state and responsive delivery

Introduce a Linux touch router and an X-server touch state manager. Keep them separate from mouse gesture recognition.

- Track Android pointer IDs, never pointer indices or an assumed pointer 0. Allocate a separate XI touch ID for each
  contact lifetime, including when Android reuses an ID.
- Copy immutable input samples before returning from `onTouchEvent`; do not retain a recycled `MotionEvent`.
- Use the renderer's coordinate transform for resolution, aspect ratio, letterboxing, window scaling and rotation.
  Reject contacts beginning outside the guest surface. Preserve a captured contact when it moves beyond that surface.
- Preserve fractional coordinates and ordered timestamps. Translate Android's monotonic event time into a consistent
  X-server timestamp domain; validate it against existing mouse/keyboard events and grab timestamps.
- Dispatch through a dedicated input worker. Coalesce redundant updates per contact under load, preserving ordering
  around begin/end, ownership changes and mode changes.
- Keep socket writes off Android's UI thread and outside shared input/window locks. Preserve per-client ordering with
  replies and other events. Bound pending output; disconnect an unresponsive client rather than blocking input for
  every client or losing lifecycle events silently.

Exit criterion: reordered pointer indices and a stalled client cannot duplicate contacts, leave them stuck or freeze
the Android UI.

## 3. Expose and deliver a real XI2 touchscreen

Extend `XInput2Extension` and add touch-specific event encoding and device state.

- Add a stable virtual direct-touch device separate from the mouse, with `XITouchClass` and absolute X/Y valuators.
  Advertise supported contact capacity accurately. Expose master/source relationships and touch capabilities
  consistently, including selectors for the source device, `XIAllDevices` and `XIAllMasterDevices`.
- Negotiate and retain each client's XI version instead of replying 2.2 unconditionally. Send touch only to eligible
  clients, and validate touch selections and conflicting selectors according to the protocol.
- Serialize `XI_TouchBegin`, `XI_TouchUpdate`, `XI_TouchEnd` and requested raw touch events with correct lengths,
  padding, masks, fixed-point coordinates, timestamps and device/source/contact IDs. Add pressure or contact-size
  axes only if both reporting and encoding are implemented; X/Y are the initial required axes.
- Implement touch ownership notifications, passive touch grabs, ungrabs, and accept/reject through XIAllowEvents.
  Replace fake success for the relevant requests with real state transitions and protocol errors.
- Route each sequence according to its initial hit test and protocol ownership rules. Do not retarget it on every move.
  Handle competing clients, rejected grabs, window destruction and client disconnects.
- Generate no finger-derived core or XI pointer events in native mode and do not mark touches as pointer-emulating.
  Physical mouse events remain independent.

Exit criterion: libXi decodes the events correctly, SDL discovers the touch device, and two fingers produce distinct
complete sequences with zero server-generated mouse events.

## 4. Connect Android fingers and overlay controls

Route Linux touchscreen input from `TouchpadView` to the native touch API before existing simulated-touch handling.
Retain the existing mouse gesture code for Windows and Linux touchpad mode.

Assign ownership at finger-down to either an overlay control or the guest surface. Keep it until that finger ends;
moving across a button must not turn a guest contact into an overlay press or leak a held overlay finger to the guest.
Process each move sample once, filtering contacts captured by controls. Remove the current repeated full-event
forwarding inside `InputControlsView`'s move loop.

Handle `ACTION_CANCEL` for every active contact, plus overlay dismissal, view detachment, activity pause, session exit,
surface replacement and screen folding/rotation. End existing contacts before applying a changed coordinate transform.
Distinguish Android cancellation from normal lifting internally, and terminate the corresponding XI sequences using
the available protocol semantics. Stylus events retain their existing separate handling for this change.

Exit criterion: two guest fingers work while a third finger holds a virtual control, a connected mouse does not disable
native touch, and cancellation leaves no active contacts.

## 5. Replace Linux settings and migrate shortcuts

Add a Linux-only shortcut setting, `linuxTouchMode`, with `native` and `touchpad` values. Store it in shortcut snapshots,
shared-container defaults, supported game-profile extras, and import/export paths. Explicit shortcut mode wins over
profile/container defaults.

- New Linux shortcuts default to native touchscreen mode.
- Migrate existing resolved `simTouchScreen=1` to `native`, and `0` to `touchpad`, once. Resolve missing legacy values
  using the old Linux container/default rules before recording the new value.
- Keep the old keys readable for imported/older Linux shortcuts. Windows continues to use its existing setting.
- Update the Linux editor and sidebar wording to describe actual touchscreen input. Shortcut changes apply at the
  next launch. A sidebar change ends all contacts, updates device enabled/hierarchy state and routing, then accepts
  a new gesture. It must never switch the destination of an active contact halfway through its sequence.
- In native mode, default `SDL_TOUCH_MOUSE_EVENTS=0` and `SDL_MOUSE_TOUCH_EVENTS=0` to avoid SDL synthesis where these
  hints are honored. Preserve the project's explicit shortcut-environment priority. Test server output separately
  from toolkit-generated mouse events, since applications can implement their own compatibility behavior.
- Hide the finger-driven mouse cursor in native mode while retaining cursor behavior for an actual mouse and touchpad.
  Remove Linux reliance on the global `touchscreen_toggle` preference for native routing.

Exit criterion: existing Linux shortcuts migrate deterministically, Steam has its own persisted mode, and changing one
shortcut leaves other shortcuts and Windows settings unchanged.

## 6. Verify native and emulated application paths

| Check | Acceptance condition |
| --- | --- |
| Native ARM64 Xlib/libXi and SDL | Direct-touch device discovered; independent finger events; no synthetic mouse in strict probes |
| x86-64 under glibc Box64 and FEX | Same device enumeration, contact IDs and event lifecycle as the native probes |
| i386 under FEX, including the OpenGL thunk path | Correct libXi class/event decoding and two independent contacts |
| Supported bionic Box64 path | Same X11 touch behavior; assess wrapper differences if the probe fails |
| Window ownership and grabs | Begin target remains coherent; accept/reject, destroy and disconnect end or transfer sequences correctly |
| Input transforms | Correct points at corners/center and during drags for native/custom resolutions, scaling and orientation changes |
| Lifecycle and mixed input | No stuck fingers after cancel/pause/folding; overlay controls and physical mouse remain independent |
| Slow client and sustained input | Android remains responsive; queue bounds hold; begin/end ordering and other clients remain correct |
| Windows and Linux touchpad regressions | Existing clicks, scrolling, relative mouse and virtual controls still work |

Use Android instrumentation with multi-pointer `MotionEvent` injection for repeatable tests; a single ADB swipe cannot
verify independent simultaneous contacts. Supplement it with physical two-finger, pinch and mixed-input checks.
Validate the actual Box64 wrappers and FEX/libXi paths before changing them; do not assume a translation-layer patch
is required merely because the program is emulated.

Run Steam and representative games after the probes pass. Verify native touch only where the application supports it.
Use touchpad mode to check mouse-driven menus in BeamNG and Tomb Raider; native touch support alone is not a claim
that either game implements finger controls.

## Delivery order and completion

Implement in reviewable commits: probes; contact routing and delivery; XI2 device/events/ownership; Android overlay
integration; settings migration; device verification and findings. Keep incomplete native touch unavailable to users
until protocol, lifecycle and routing tests pass. Enable the replacement and ship its migration together.

Completion requires real SDL finger events on native and emulated paths, two simultaneous contacts, no finger-generated
mouse events from our native path, correct cancellation/grabs, responsive delivery, independent shortcut settings and
passing mouse/Windows regressions. Record test versions and results in `docs/linux-input-findings.md`.

Raw evdev touchscreen emulation, `/dev/uinput`, Wayland input, stylus pressure/tilt and app-level gesture recognition
are separate follow-up work. The X11 path uses Android touch events and the existing X server without root.

## References

- [XInput 2 protocol](https://xorg.freedesktop.org/archive/current/doc/inputproto/XI2proto.txt): touch device classes,
  lifecycle, ownership, selectors, grabs and wire layouts.
- [Android MotionEvent](https://developer.android.com/reference/android/view/MotionEvent): stable pointer IDs,
  changing pointer indices, cancellation and historical samples.
- [SDL2 X11 touch implementation](https://github.com/libsdl-org/SDL/blob/SDL2/src/video/x11/SDL_x11xinput2.c): touch
  discovery, XI selectors, grabs and conversion to SDL finger events.
- [SDL touch to mouse hint](https://wiki.libsdl.org/SDL2/SDL_HINT_TOUCH_MOUSE_EVENTS) and
  [mouse to touch hint](https://wiki.libsdl.org/SDL2/SDL_HINT_MOUSE_TOUCH_EVENTS): toolkit synthesis controls.


## Implementation notes (2026-10-18)

- `XInput2Touch` owns contact lifetimes and ownership; `XITouchEvent` writes the XI 2.2 wire format.
- Linux X11 replies and events share a bounded asynchronous output FIFO. Packet writes handle partial sends and ancillary
  descriptors, with a two-second write deadline. The source socket is duplicated until the writer exits to avoid fd reuse.
- `TouchpadView` routes native fingers before mouse suppression, and `InputControlsView` captures overlay fingers at down.
- `LinuxTouchMode` migrates legacy settings. Both SDL synthesis directions default off for Linux in either mode so a sidebar
  change can enable native touch in an already-running application; explicit shortcut environment still takes priority.
- Debug SDL and libXi probes cover ARM64, x86-64 and i386. Repeatable tests use the existing instrumentation runner with
  `-e nativeTouch 1` (protocol/lifecycle/UI/output) or `-e liveTouch 1` (a real guest session plus Android input injection).
- The implementation does not include evdev/uinput/Wayland or pressure/tilt. Native X11 apps continue to use the keyboard
  button for text input; the existing text-cursor auto-keyboard heuristic belongs to mouse/touchpad input.

An external physical X.Org reference trace and manual fold/rotation testing remain additional verification, not evidence
claimed by the automated tests. See the findings document for exact tested paths and limitations.
