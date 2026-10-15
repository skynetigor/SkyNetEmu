---
name: fix-game
description: Use when a game (Steam/Linux/Windows) crashes, shows a black screen, throws an error, runs slowly or draws on the CPU in Winlator skyNET. Collect the evidence from the phone, then SEARCH THE INTERNET FOR AN EXISTING FIX BEFORE EXPERIMENTING (ProtonDB, GitHub/GitLab issues of Box64, FEX, Mesa, Winlator forks, Steam/Feral support); apply one sourced change at a time, verify, and record the result in docs/linux-game-fixes.md.
---

# Fixing a game

Someone has almost certainly met this error before. **Search first, experiment second.** The long hand-made trial
runs of earlier sessions (rebuild, install, wait 2 minutes, read a log) cost far more than a good search.

Order: **evidence -> docs/linux-game-fixes.md -> internet -> one sourced change -> verify -> record.**

## 1. Evidence (a few minutes, no changes yet)

Take what is already on the phone; do not start experiments to "see what happens".

- What the screen shows: `adb shell screencap -p /sdcard/x.png; adb pull ...` (the phone has two displays: it works, the
  warning is harmless).
- The logs, newest first (all in `/storage/emulated/0/Winlator/logs/`):
  `linux-steam.log` (the Steam session), `steam-game-launch.log` (what the stand-in launcher ran and the game's own
  output), `steam-logs/` (copy of Steam's logs: `cef_log.txt`, `webhelper_gpu.txt`, `compat_log.txt`,
  `gameprocess_log.txt`, `console_log.txt`), `linux-desktop.log`, and `<game>_<date>.txt` per shortcut run.
- What runs: `adb shell "ps -A | grep -i -E 'steam|FEX|box64|<game>'"`, `top -b -n 1`.
- What the program is: `od -An -tx1 -N20 <binary>` (byte 5 = class: 1 is 32-bit, 2 is 64-bit; bytes 19-20 =
  machine: `3e 00` x86-64, `03 00` i386, `b7 00` arm64), which emulator the container uses, which driver and GL path.
- The container's settings are in `files/imagefs/home/xuser-<id>/.container` (`run-as <package>`). **Re-read them: the
  user may have changed them between turns** (a Steam container was switched from Box64 to FEX behind my back once).
- Debug files in the container's home: `.xkeys` (turns on key, focus and X error logging, logcat tags `XKeys`,
  `XErrors`), `.xtrace` (every request; huge, logd drops lines, never leave it on).

Write down the **exact error strings** and the versions: Box64 0.4.4, FEX 2610 (Ubuntu 24.04 rootfs), Mesa 26.x
(Arch Linux ARM runtime `bannerlator-r9`), Turnip a8xx, Adreno 840, Android 17, proot.

## 2. Our own notes

`grep -rn -i "<error text or game>" docs/` first, especially `docs/linux-game-fixes.md`, `docs/linux-fex.md`,
`docs/linux-game-profiles.md`, `docs/linux-cef-x11.md`, `docs/linux-input-findings.md`, `docs/linux-beamng-039.md`.

## 3. Search the internet

The search tools are deferred: load them with ToolSearch (`select:WebSearch,WebFetch`) and use them. Start with
mode "standard"; use "extended" for niche errors, very recent bugs or when the first pass is thin. Send independent
searches in the same turn.

Query forms that work:

- The exact error in quotes, alone, then with the project: `"GLXBadFBConfig" FEX thunk`, `"version lookup error"
  "GCC_7.0.0" steam runtime`.
- Game + platform + symptom: `Tomb Raider 2013 Linux crash on start`, ProtonDB report for the Steam app id.
- Emulator + symptom: `FEX 32-bit game libGL thunk`, `box64 <library> crash`.
- Android/proot specifics: `proot FEX`, `winlator <game>`, `GameNative <game>`.

Where the answers are, in the order to try:

1. **ProtonDB** (`protondb.com/app/<appid>`) and **PCGamingWiki**: the game's known problems and launch options.
2. **GitHub/GitLab issues and discussions**: `ptitSeb/box64`, `FEX-Emu/FEX`, Mesa (`gitlab.freedesktop.org/mesa/mesa`),
   `ValveSoftware/steam-for-linux`, `ValveSoftware/Proton`, Winlator forks (`brunodev85/winlator`, `StevenMXZ`,
   `GameNative`, `Pipetto-crypto`). Search the closed issues too.
3. **Publisher support** for ported games (Feral, Aspyr): their launch options and known issues.
4. Steam Community guides, Reddit (`r/EmulationOnAndroid`, `r/linux_gaming`, `r/winlator`), Stack Overflow for a
   plain library error.

**Read the page, not the snippet** (WebFetch the issue or thread). Check its date and versions against ours: a fix for
an old FEX or Mesa may not apply, and a bug fixed upstream may only need a newer build (see `add-component`).

Result of the search: a short list, each item with the link, what it says to change, and the risk. If nothing is found,
say so and widen the query once (the library, the call, the engine) before experimenting.

## 4. Apply one sourced change at a time

- The cheapest sourced candidate first. One change per run. Say which source each change comes from.
- Prefer a launch option, an environment variable, a container or game-profile setting, or a file on the phone over
  a rebuild (a rebuild + install + game start is ~4 minutes).
- Verify with evidence (log line, screenshot, process list), not "looks fine". A black screen is not a result: look at
  what runs and what the logs say.
- Revert what did not help. Leave no debug hooks in product code (the stand-in launcher once had a "source this file from
  shared storage" hook: that is a code-injection door, it must not be committed) and no test files on the phone.

## 5. Things this project has already taught us (do not re-learn)

- **Never uninstall the app** (`adb install -r`); the installed package is `com.winlator.cmod.dev2` (debug). The Steam
  login lives in the container; force-stopping can lose it, and a silent (`-silent`) Steam cannot show a login window.
  If Steam shows "you are logged in elsewhere, playing X", it will disconnect the user's other session: **ask first**.
- `stageAsset` copies an asset out of the APK only when the size differs: after editing `skynet-launcher.sh` (same size)
  delete `files/linux-shim/skynet-launcher.sh` on the phone.
- A 32-bit x86 game cannot run on Box64. It runs on FEX with the x86-64 root filesystem (it has the i386 libraries);
  libskyshim hands a 32-bit ELF to FEX (`SKY_X86_32_EMULATOR`), a 64-bit one to Box64 (`SKY_X86_EMULATOR`).
  FEX maps programs as executable: shared storage (`/storage/...`) is noexec, copy the program into app storage.
- An old game's own `lib/i686` has what the Steam scout runtime would give it (`libssl.so.1.0.0`). Never put the
  whole scout library folder on the path: its old `libgcc_s` breaks the root filesystem's Mesa (`GCC_7.0.0` not found).
  Link only the scout libraries the root filesystem lacks.
- 32-bit GPU OpenGL goes through FEX's OpenGL thunk only (there is no 32-bit Vulkan guest thunk): off by default
  (container extra `linuxFexGl32`), see `docs/linux-fex.md`.
- Feral games keep their settings in `~/.local/share/feral-interactive/<game>/preferences`; a full-screen mode the X
  server cannot set shows as "Failed to initialize Direct3D with current settings": try windowed.
- `ThunksDB` and thunk folders are per FEX config; `FEX_THUNKCONFIG` overrides the selection for one process.
- The X server is our own Java one (`xserver/`): a request it does not implement shows up as `BadImplementation` or as a
  client-side error that names the wrong thing. Turn on `.xkeys` to see `XErrors` (opcode, minor, code, value).
- GL on the host side (the runtime's Mesa) is Zink on Turnip; the Steam client itself runs on software GL on purpose.

## 6. Record it

Add the finding to **`docs/linux-game-fixes.md`**: symptom (exact error), cause, fix, **source link** (or "found by
experiment"), versions. A fix that belongs to one game also goes into the game profile of the registry
(`docs/linux-game-profiles.md`). Commit only when the user asks.

## 7. Report to the user

Short: what the search found (links), what was changed and why, what the evidence shows now, and what is still open.
If the search found nothing, say that plainly before describing any experiment.
