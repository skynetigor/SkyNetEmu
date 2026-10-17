# Linux containers: Box64 / FEX chosen per shortcut (plan)

Status: **implemented**. The current model enforces one protected Linux container for every Linux game and Steam; see
[Shared Linux container](linux-shared-container.md). The design notes below describe its earlier implementation.

Original goal: one Linux container is enough; the x86 emulator (and its settings) can be
kept by each shortcut as its own copy of the container's settings (see "Every setting is the shortcut's own" below).

## What is already global

Emulator builds (`LinuxEmulator.list`, `baseDir`) and FEX root filesystems (`LinuxRootfs`) are installed per app, not per
container. A container only stores a choice (`linuxEmulator`, `linuxEmulatorId`, `linuxEmulatorPreset`, `linuxRootfsId`,
`linuxFexPreset`, `linuxFexThunks`). So no install changes are needed.

## Where the container's choice is read today

All of these take a `Container`; none knows the shortcut:
- `LinuxEmulator.choice(container)` / `resolve(context, container)`
- `LinuxRootfs.resolve(context, container)`
- `LinuxSession.programCommand(...)`: chooses the emulator path for an x86-64 ELF, refuses when none / not installed / FEX
  without rootfs.
- `LinuxSession` session setup (~line 352): the FEX rootfs binds (`/proc /sys /dev`, shm, input) and (~451-510) `guestEnv`:
  Box64 variables and preset, `SKY_X86_EMULATOR`, FEX preset.
- `LinuxFex` writes `$HOME/.config/fex-emu/Config.json` (rootfs, thunks, `Env` LD_PRELOAD of the x86 shims).

The proot binds, the guest environment and Config.json are decided when the session is built, so the override must reach
that point, not only `programCommand`.

## Design

1. **One value object**: `LinuxEmulatorSetup` (choice, installed build, rootfs, presets, thunks) built by
   `LinuxEmulatorSetup.of(context, container, shortcut)`: every field is the shortcut's extra when not empty, else the
   container's. `shortcut == null` gives today's behaviour (Steam client, tools, debug shell). Replace the direct
   `choice/resolve` calls in `LinuxSession` and `LinuxFex` with it.
2. **Shortcut extras** (the shortcut's own values, written when it was made), same names as the container's so the setup code is shared:
   `linuxEmulator` (box64 / fex), `linuxEmulatorId`, `linuxEmulatorPreset`, `linuxRootfsId`, `linuxFexPreset`,
   `linuxFexThunks`. "None" is not offered for a shortcut.
3. **Editor**: a "x86 emulator" group in `LinuxShortcutEditor.kt` (category `program`, not for Steam games), reusing the
   pickers from `LinuxEditorParts.kt` that the container editor uses; the FEX-only rows (rootfs, preset, thunks) shown only
   when FEX is selected; the build picker lists only installed builds of that emulator.
4. **Session**: pass the shortcut into the session build, so the binds, `guestEnv` and `Config.json` use the setup.
   The refusals keep their wording (not installed, FEX needs a rootfs).
5. **FEX needs the program inside the container** (storage is noexec): when FEX is selected for a shortcut whose program
   is on shared storage, refuse with a message telling to use "Program only" / copy, instead of failing in proot.

## Open questions to check first (read the code, then decide)

- How a shortcut start reaches `LinuxSession`: whether a running session (same container, other shortcut) is reused or a
  new one is started per shortcut. If it is reused, the first shortcut's emulator wins and the plan needs "start a new
  session when the setup differs" (or refuse while a session with another setup runs).
- Whether `Config.json` is rewritten at every session start (it is believed to be) and not cached by FEX across sessions.
- Whether Box64 and FEX leave files in `$HOME` that disturb each other (shader caches, Steam files). Test one game on both
  in the same container.

## Steam games (separate step, not in the first change)

Steam games run inside the Steam client's session, and the emulator there is set when that session starts
(`SKY_X86_EMULATOR` and the shim). Only Box64 is supported for them today. Per-game choice would need
`skynet-launcher.sh` to pick the emulator per `appId` and FEX to work for games under Steam (untested). Until then the
emulator rows are hidden for Steam game shortcuts.

## Verification (only when asked to build / run)

- A Box64 container with a shortcut set to FEX (program copied in): the program starts under FEX; and the other way round.
- A shortcut made before `linuxOwnSettings` still follows its container until its settings are opened once; the Steam client and the debug shell are unchanged.
- The refusals: emulator removed, FEX without rootfs, FEX with a program on shared storage.
- Two shortcuts of one container with different emulators, started one after another and at the same time.

## What was built

- `Container.copyWithExtras` (a shallow copy with the extras laid over, never saved) and `LinuxEmulatorSetup.of(container, shortcut)`:
  the container the session is built for. It is the container itself when the shortcut sets nothing, otherwise a copy with the
  shortcut's `linuxEmulator`, `linuxEmulatorId`, `linuxEmulatorPreset`, `linuxRootfsId`, `linuxFexPreset`, `linuxFexThunks`;
  choosing another emulator drops the container's build and preset (they name builds of the other one). The existing
  `choice/resolve`, `guestEnv` and `LinuxFex` code is unchanged: it simply gets this container. Used by `XServerDisplayActivity`
  (the launcher component and `programCommand`) and by the desktop's launcher scripts (`LinuxDesktop.prepareLaunchers`).
- The shortcut editor has an "x86 emulator" group (not for Steam games): emulator (None / Box64 / FEX), build, and
  for FEX the root filesystem, preset and thunk switch; for Box64 the preset.
- `LinuxSession.directCommand`: a program that FEX would run from shared storage is refused with a message (storage is noexec);
  a 32-bit program goes to FEX (any installed build and root filesystem) instead of being refused.

### The open questions, answered

- A shortcut starts its own activity and its own proot session, so there is no reuse across shortcuts; two shortcuts of one
  container with different emulators are two independent sessions. (The desktop session is one more: the container's emulator.)
- `Config.json` of FEX is written at every session start (`LinuxFex.prepare` / `prepareFor32Bit`); two sessions at the same time
  share the container's home and would overwrite each other's file. Not tried; a known risk.
- Verified on the device (Z Fold8 Ultra), with a FEX container: a shortcut set to Box64 runs `mousetest_x64` with no FEX process;
  one with no setting runs it under FEX; a FEX shortcut whose program is on shared storage shows the refusal.

### Steam games

The group is shown for Steam game shortcuts too. A Steam game is started by the Steam client, which may already be running with
the environment of another session, so the shortcut does not rely on its own session's environment: `LinuxSession.writeSteamEmulatorEnv`
leaves `~/.skynet/steam-emulator/<appId>.env` (the BOX64_/FEX_/SKY_X86_/SKY_FEX_ variables of the shortcut's emulator setup) and
`skynet-launcher.sh`, which starts every game, sources the file of `$SteamAppId`. A game started from inside Steam's own interface (not from a
shortcut) has no file and runs under the session's emulator. Checked on the device: a Steam game shortcut set to Box64 in a FEX container
writes the Box64 variables; Tomb Raider (32-bit) still runs on FEX through the Box64 + FEX route and reaches its main menu.

### Launch options of a Steam game shortcut

The Linux shortcut editor groups Launch options and the shared environment-variable editor in the same section. Explicit
variables are saved in the shortcut's `envVars` extra; they are overrides, so an empty editor inherits the container's
environment. Precedence, from lowest to highest: container/runtime and emulator preset defaults, game profile variables,
assignments in Launch options, variables in the shortcut's environment editor. An empty value is still an override;
removing the row restores inheritance. For example, `FEX_TSOENABLED=1` in the editor wins over the Performance preset and
over `FEX_TSOENABLED=0 %command%` in Launch options. The preview shows the merged shortcut variables.

This applies to direct launches (including the Android Box64 path) and to emulated games started through Steam. The Steam
launcher applies the merged profile and shortcut variables after its graphics, thunk and preload defaults, including when
the Steam client was already running. The editor uses the existing whitespace-separated `NAME=value` format; values with
spaces can still be quoted in Launch options instead.

The shortcut's "Launch options" (written like Steam's: `NAME=value wrapper %command% args`) are available for Steam games too.
`LinuxSession.writeSteamLaunchOptions` leaves them in `~/.skynet/steam-emulator/<appId>.launch` (variables as `export`, the wrapper and
the arguments as the arrays `sky_wrap` and `sky_args`) and `skynet-launcher.sh` applies them to the game's command: the wrapper before it, the
arguments after it, after whatever Steam itself put there (the options set in Steam's own properties for the game). They reach a game that
Steam starts through the launcher, which means one that needs an x86 emulator; a native ARM64 game is started by Steam itself. Checked on the
device with Tomb Raider (2013): `SKY_TEST=hello taskset -c 4-7 %command% -windowed` gave `run: taskset -c 4-7 .../TombRaider.sh -vulkan -windowed`
and the variable in the game's environment (this particular game then exited: it did not like the arguments; with only the variable it ran).

### Not done
- A Box64 container with a FEX shortcut was not tried separately (the container used for the check was a FEX one); the code path is
  the same copy-with-extras.

## Graphics settings on a shortcut

The shortcut editor also sets the Turnip driver, the OpenGL driver (Zink or software), the Vulkan output (native or CPU copy), the
keyboard button and the automatic keyboard, next to the settings it already had (resolution, scaling incl. the game window,
upscaler, TU_DEBUG=sysmem, 32-bit GPU, GPU vendor, emulator and its presets, frame generation, touch mode). Empty follows the
container. The driver, OpenGL and output extras are in `LinuxEmulatorSetup.EXTRAS`, so the session is built from the container with
them laid over; the activity reads the output and keyboard ones through `linuxSetting`. The HUD mode is not per shortcut (a Wine
shortcut's HUD choice writes through to its container); the FPS limiter keeps its own shortcut store.

## Every setting is the shortcut's own

A Linux shortcut carries a value for every setting a session is started with, and the editor shows those values: there is no
"container default" entry to pick. The container's settings are what a new shortcut is written with
(`linux/LinuxShortcutDefaults`, marker extra `linuxOwnSettings=1`); changing the container afterwards leaves its shortcuts
alone. A game profile's settings are copied in the same way, when the shortcut is made and when its profile or version is
changed in the editor. A shortcut written before this has no marker and keeps following its container until its settings are
opened, which gives it its own copy of whatever it does not set.
