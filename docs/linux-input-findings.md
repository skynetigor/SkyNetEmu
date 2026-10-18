# Input in Linux containers: what is known

Working notes from tracking down why BeamNG.drive takes neither mouse clicks nor a gamepad in its menu.
Everything below was measured on the device (Galaxy Z Fold8 Ultra, container 6, runtime `bannerlator-r9`).

## Settled

**The gamepad pipeline works.** The app writes evdev events into `imagefs/dev/input/`, proot binds that
folder over `/dev/input`, and `libskyshim` answers the evdev ioctls on the files there. SDL lists an
`Xbox 360 Controller` with its built-in mapping and receives buttons, axes and the hat, natively and under
Box64. The bundled testers (`tools/input-test`, a shortcut in every Linux container) show this.

What had been missing was not code but a bind: SDL finds a pad by *reading the directory*, and Android's own
`/dev/input` is unreadable by an app, so nothing was ever listed. The shim alone only answers a path a
program already knows.

**BeamNG receives everything and acts on none of it.** Measured:

| Layer | How it was checked | Result |
|---|---|---|
| evdev file | size grows when a pad button is pressed | written |
| the game reading it | `pos` of its fd catches up with the size | read in full |
| the game seeing a pad | its own log | `Controller connected: X360 Controller`, 56 bindings |
| mouse | X protocol trace | ButtonPress and ButtonRelease sent to its window |
| focus | X protocol trace | FocusIn sent |

**Steam Input is not the cause.** With `-nosteam` the Steam errors leave the game's log, the pad is still
detected, and clicks and the D-pad still do nothing.

**BeamNG is an SDL2 program.** Its binary carries 992 `SDL_*` symbols (`SDL_Init`, `SDL_PollEvent`,
`SDL_GameControllerOpen`, `SDL_GetKeyboardState`) and ships no SDL library: SDL2 is compiled into it.

## Why mouse and keyboard did nothing (solved)

The server's GLX extension reported `first_event = 0`. A program that loads libGL (SDL does, for its OpenGL
support) makes Xlib hand GLX the 17 event codes from that number, and GLX's wire-to-event converter drops what
it does not know. So the core events 0..16 (KeyPress, ButtonPress, MotionNotify, EnterNotify, FocusIn,
KeymapNotify) never reached the program, while ConfigureNotify, PropertyNotify and the XInput2 generic events
(codes above 17) did. GLX now reports 111, the one run of 17 free codes.

How it was found, for next time:

- A bare Xlib client (`tools/input-test/xtest.c`) receives every event, natively and under Box64, with the
  XInput2 selections SDL makes. So neither the server's delivery nor Box64's libX11 wrapper was at fault.
- The Debian SDL 2.32.4 built for aarch64 worked, the same version built for x86-64 under Box64 did not. SDL
  built by hand (zig, `DEBUG_XEVENTS` on) worked until OpenGL support was compiled in: libGL's GLX is what
  broke it. BeamNG's SDL2 is static and has the same OpenGL support.
- The tester's "X events seen by SDL" counter (SYSWM events) says nothing about what SDL dispatches: native SDL
  showed no core events there and still got every click. The earlier conclusion drawn from it was wrong.

With the fix: SDL has keyboard and mouse focus, clicks arrive in the testers and in BeamNG, and the touch
simulation (`simTouchScreen`) clicks too. The pointer is not drawn in either direct-touch mode.

Tools: `tools/input-test/build.sh` builds the testers (copied into `app/src/debug/assets/linux`, so only debug
builds carry them and only debug builds add their shortcuts) and, given `SYSROOT`, the `xtest` probe. A debug
build also logs per-window event delivery when the container has a `.xtrace` file in its home.

## Why the pad was silent in BeamNG (confirmed on device)

SDL2 drops joystick events while no window has keyboard focus (`SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS`
defaults to off). BeamNG's window never gets `SDL_WINDOW_INPUT_FOCUS`, so a pad that SDL lists and reads is
filtered at the last step. `LinuxSession` now sets `SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS=1`. Confirmed: the pad then
works in BeamNG's menu and in a map. Focus and mouse clicks remain a separate, open problem.

Also found: the XI2 device event wrote `XIGroupState` as 16 bytes instead of 4 (framing stayed consistent, but
the button mask and valuators were read from the wrong offsets). Fixed.

## Fixed along the way

- `ButtonRelease` was looked up and delivered by the *pointer-motion* mask. A program that reads motion some
  other way selects no motion at all, so its press arrived and its release was dropped, and a menu acts on
  the release. Wine never noticed because it selects motion.
- `SDL_Init` hung for ever on an unanswered Xkb `GetNames`.
- Nothing handed a new window the focus, because a Linux session has no window manager. The server does it
  now, on map, and repeats it over the next seconds: the announcement at map time lands before a toolkit has
  asked for focus events.
- The shim answered `EVIOCSCLOCKID` with success and kept stamping events on the app's wall clock, so a
  program that asked for the monotonic clock read every button as decades out of date.

## Things worth knowing about the runtime

- `libSDL2-2.0.so.0` is sdl2-compat; `libSDL3.so.0` is the real library behind it.
- **Box64 puts a native library in place of a bundled one** where it has a wrapper for it. A game that ships
  its own SDL2 gets the runtime's sdl2-compat instead, which lacks symbols such as `SDL_roundf` and
  `SDL_RWsize`. `BOX64_EMULATED_LIBS` turns that off per library.
- Box64 0.4.4 cannot run a binary built against glibc 2.38 (`wcslcpy`, `wcslcat` are missing from its libc
  wrapper), which is why the SuperTux AppImage does not start. It is the only AppImage SuperTux publishes,
  and the components registry offers no newer Box64.


## Native Linux touchscreen (2026-10-18)

The Linux **Touchscreen** setting now sends XI 2.2 direct-touch contacts. **Touchpad** retains mouse gestures. Windows
keeps its existing input path. This supplies native finger events to applications that implement touch; mouse-driven game
menus still need Touchpad or a physical mouse.

Device 6 (`skyNET direct touchscreen`) is an enabled/disabled slave pointer attached to master 2. It has absolute X/Y
valuators, an `XITouchClass` with direct mode, and capacity 32. XI 2.2 clients can select the master, source, AllDevices or
AllMasterDevices streams. Touch contact IDs are separate from Android pointer IDs, and remain stable until completion.
Coordinates retain fractions; contacts are targeted at their initial window. Raw touch and ownership events are supported,
including passive touch grabs, accept/reject, pending physical ends, ungrabs, destroyed windows and disconnected clients. A
late owner receives the original down position followed by the latest coalesced update. Raw sequences continue to their
physical end even when all normal listeners reject or disappear.
Native fingers never move the server pointer or inject its buttons/wheel. Deliberately configured overlay bindings and
physical mouse input continue independently.

`linuxTouchMode=native|touchpad` is saved per shortcut (Steam included), in defaults and profiles. Existing shortcuts without
this key migrate their resolved `simTouchScreen` value once: `1` becomes native, `0` becomes touchpad. Explicit shortcut
settings take priority. Linux defaults `SDL_TOUCH_MOUSE_EVENTS=0` and `SDL_MOUSE_TOUCH_EVENTS=0` in both modes so that
switching from touchpad to native in the sidebar cannot enable SDL synthesis accidentally; explicit environment variables
still override these defaults. The global Windows `touchscreen_toggle` preference does not control Linux routing.

Touch updates run on an input worker with update coalescing. Linux clients' replies/events share a per-client FIFO bounded
to 64 MiB (large enough for a native-resolution GetImage reply), with duplicated ancillary descriptors and complete packet writes. A client which stops reading is shut down
within two seconds instead of blocking Android input. Windows socket output retains its existing synchronous path.

### Reproducing the checks

Build the app and tests, install both APKs with `adb install -r`, then use the existing instrumentation runner:

```sh
# Protocol encoding, ownership, lifecycle, multi-pointer routing, transforms and stalled clients
adb shell am instrument -w -e nativeTouch 1 \
  com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
# Existing container/shortcut regressions, including touch migration and environment priority
adb shell am instrument -w \
  com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
# Real SDL guest: probe=touchtest, touchtest_x64 or touchtest_x86; emulator=box64 or fex; bionic=0 or 1
adb shell am instrument -w -e liveTouch 1 -e probe touchtest_x64 -e emulator fex \
  com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
# Bare libXi decoder: selector=1 (masters), 6 (source), 0 (both); optional grab=accept, reject or pending
adb shell am instrument -w -e liveTouch 1 -e probe xitouchtest -e selector 1 \
  com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
```

The live harness creates a temporary shortcut, starts its own guest session, dismisses the game startup overlay for the
minimal probe, injects two simultaneous Android contacts (with pointer indices reordered), and removes its shortcut. It
must run by itself: instrumentation owns the app process. The native harness uses copied samples and memory streams to
check wire lengths and signed fixed-point fields independently of libXi. `tools/input-test/build.sh` builds the SDL probes;
set `X11_HEADERS=/path/to/sysroot/usr/include` to also build the libXi probes with Zig. They ship only in debug assets.

### Measured results

Test device: Samsung SM-F976B, shared Linux container 1, `bannerlator-r9`, Box64 0.4.4 and FEX 2610. Tests use genuine
multi-pointer Android `MotionEvent`s through `UiAutomation`, not independent single-pointer ADB swipes.

| Path | Result |
|---|---|
| SDL native ARM64/glibc | Two independent contacts, finger motion, complete releases, no finger-derived mouse events |
| SDL x86-64/glibc Box64 | Same |
| SDL x86-64/FEX | Same |
| SDL i386/FEX, thunk settings enabled | Same; software SDL renderer, not an OpenGL rendering test |
| SDL x86-64/bionic Box64 | Same, using the bionic build's native wrapped SDL2 |
| Android/in-process input suite | 111 checks pass: protocol, ownership, original-down replay, raw rejection/disconnect lifetime, transforms, overlays, mouse regression, packet/FD handling and slow clients |
| Lifecycle/mixed input | Window destruction, disconnected owner transfer, two guest fingers plus one overlay finger, resize/hide release and cancellation pass |
| Slow client | UI enqueue stays responsive; unresponsive socket is disconnected at the write deadline |
| Socket packet/FD handling | FIFO order and SCM_RIGHTS descriptor survive the sender closing its original descriptor; a 20 MiB reply passes complete partial socket writes |
| Touchpad/Windows boundary | Touchpad clicks/releases work, mode changes release held finger buttons, and Linux ignores the global Windows touchscreen toggle |
| Shortcut settings | 38 device checks pass, including one-time migration, independent shortcuts and explicit SDL environment priority |
| libXi ARM64, Box64/FEX x86-64, FEX i386, bionic Box64 | Real class/event decoding; source, master and AllDevices selectors; accept, reject and pending-end grabs pass |
| Existing host regressions | Launch-option/Steam environment tests and shared-home migration pass |

The SDL probes report a small number of mouse-motion events during initial window placement (three on glibc native/Box64,
one on FEX/bionic). Those counts do not increase during native finger input. The strict probes distinguish this startup
behavior from finger-derived mouse events. Applications can still synthesize mouse input themselves if configured to do so.

The i386 software probe reports a Zink/Vulkan initialization warning while SDL probes video drivers, then initializes and
receives both contacts. That warning is separate from XI touch decoding. Manual physical pinches/folding and an independent
X.Org reference capture have not been claimed as automated coverage. No BeamNG or Tomb Raider touch-control support is
implied by these results; their mouse-driven menus should use Touchpad.
