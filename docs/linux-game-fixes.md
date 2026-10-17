# Game fixes (Linux containers)

One entry per problem: symptom, cause, fix, source. **Search here first**, then the internet (see the `fix-game` skill).
"Found by experiment" means no outside source was used; replace it with a link when one turns up.

## BeamNG.drive 0.34: failed CPU detection and slow terrain loading (2026-10-17)

- **Setup:** native Linux 0.34.0.0, `BinLinux/BeamNG.drive.x64 -nosteam` from shared storage; Box64 0.4.4 Performance,
  glibc `bannerlator-r9` without proot (`linuxBionic=0`, `linuxNoProot=1`), Turnip a8xx / Adreno 840, SM-F976B / Android 17.
- **Observed bottleneck:** the Italy load enters `Loading TerrainMaterialTextureSet`, then
  `Texture added for packing, because cache [/temp/art/terrainMaterialCache/<hash>.dds] not found` and
  `TextureCooker ... started importing`. The loading screen stays at 0% with no presented frames while eight worker
  threads consume CPU. This alone is not a deadlock: the live log subsequently reports successful imports.
- **Measured progress:** in the 20:09:57 run, the first uncached texture started at 53.592 s and completed at 165.229 s
  (111.6 s); the next two took 10.4 and 11.1 s. A second large texture then took 112.1 s, followed by two about 14 s
  each; by 327.85 s six new textures were complete. The preceding 20:04:03 run had already cooked six different textures
  (one took 124 s); the new run skipped those and continued with the next missing texture.
- **Persistence checked:** the generated DDS files are in the Linux container's user folder,
  `.local/share/BeamNG.drive/0.34/temp/art/terrainmaterialcache/` (lowercase on disk). Nine files were present at 20:14,
  including the three newly completed files, and twelve by 20:15. Their DDS/DX10 headers and allocated sizes were checked; a 4096x4096 BC7
  texture with mipmaps was about 21 MiB. Do not clear this cache or restart in the middle of cooking to diagnose a
  supposed stall. Let conversion finish; cached textures need not be cooked again on the next load.
- **Separate observations:** the game displayed a one-physical-core warning, but its process was allowed on CPUs 0-7
  and eight workers ran concurrently. System memory usage was high, with about 3 GiB of swap in use; this may add pressure,
  but the measured progress identifies texture conversion as the current loading stage. Full map completion and the
  warm-cache total load time still need checking; no emulator or graphics settings were changed for these observations.
- **Cache difference in the earlier fast run:** `beamng.drive_2026-10-17_00-32-28.txt` records the same
  shared-storage 0.34 executable and Box64 Performance variables, but its user folder was in `xuser-1`. Italy loaded in
  16.375 s, with terrain material setup taking about 45 ms and no texture imports at that point. The current shortcut
  belongs to `xuser-2`; its separate `.local/share/BeamNG.drive/0.34` user data was created this evening (settings/temp
  folders at 20:01). The earlier `xuser-1/.local/share/BeamNG.drive` directory is no longer present. The new folder
  therefore has to build terrain textures again. The logs establish the folder/cache change, but not which action
  removed the old data. This is a warm-cache versus cold-cache comparison and does not establish the cause of the
  full performance regression. Imports of several identical texture hashes in the October 16 12:42 run took
  approximately 5-9 s versus 8-14 s now; thermal state and memory pressure were not controlled in that comparison.
- **New hardware warning:** a screenshot confirms `This application will not run well on only one physical core`
  alongside the memory and unsupported-distribution warnings. The installed `core/hardwareinfo.lua` selects this
  error when `Engine.Platform.getCPUInfo().coresPhysical <= 1` for a non-AMD vendor (AMD uses logical cores).
  The game's console query `dump(Engine.Platform.getCPUInfo())` actually returned **zero** logical and physical cores,
  name `Unknown`, empty vendor/features and zero cache counts. The process still had eight allowed CPUs and concurrent workers.
- **Reproduced cause of the CPU detection regression:** in the glibc no-proot path, native glibc `shm_open` bypassed
  `libskypath`'s `open` hooks and tried Android's nonexistent `/dev/shm`. Box64 uses `shm_open` to generate its x86
  `/proc/cpuinfo`; on failure it falls back to the real ARM file. An isolated x86 probe with the running game's
  environment reproduced that ARM data. The same binary under proot with the session's `/dev/shm` binding produced
  Intel CPU data with eight cores. Adding `shm_open`/`shm_unlink` path translation to `libskypath` restored that same
  eight-core Intel data without proot. This regression belongs to the no-proot implementation introduced October 16
  (`63b77c1`, default in `eb772a2`), rather than evidence of the bionic Box64 being used: the shortcut has bionic off,
  and the installed glibc Box64's SHA-256 matches the October 12 override. The bionic work also changed shared asset
  staging, but this reproduction does not depend on it.
- **Fix/validation:** rebuilt `libskypath.so` with the shared-memory hooks and updated the bundled asset. An on-device
  native regression test (`tools/linux-shim/shm-test.c`, assertions enabled) passed mapped create/reopen/mmap/unlink,
  CLOEXEC, exclusive creation, lifetime after unlink and invalid-name rejection. The matching x86 CPU probe failed
  before the fix and returned eight-core Intel information afterward. Debug APK assembly and in-place installation
  passed. After restarting the same shortcut, the game's own console reported eight physical/logical cores and
  populated x86 feature flags; the CPU error disappeared and the distribution banner returned to yellow. The live
  run subsequently entered Italy and resumed cooking uncached textures.
- **Remaining load failure after the CPU fix:** the 20:49:59 run finished Italy terrain material setup at 219.34 s
  and loaded level objects at 233.47 s (about 52 s after starting the map, including five remaining uncached texture
  imports of 6.4-7.9 s). It reached `Physics collision reloading...`, then Android killed the app at 20:53:57 with
  `ApplicationExitInfo reason=3 (LOW_MEMORY)`. This was not verified gameplay or a successful complete load; the
  memory kill and earlier socket-output ANRs remain separate unresolved issues.
- **GridV2 verification:** in the 20:55:06 run, the visually checked console command
  `freeroam_freeroam.startFreeroamByName([[gridmap_v2]])` loaded the correct map. Full level loading completed in
  242.992 s, including the fresh terrain texture conversions; physics and vehicle spawn completed and gameplay
  rendered. The CPU error stayed absent, leaving yellow memory/distribution warnings. One unobstructed gameplay
  snapshot showed 35 FPS, 97% GPU usage and 88% system RAM. Android reported thermal status 4 (critical), with skin
  temperature about 47 C. Samsung's idle touch-protection overlay also appeared during the unattended load and was
  dismissed before that snapshot. This verifies startup/map loading and CPU detection, **not** restoration of the
  user's earlier 150-200 FPS. A cool-device comparison with matching graphics/frame-generation settings remains
  necessary; neither the measured hot FPS nor the different cold-cache workloads establish a remaining code culprit.
- **Confirmed separate ANR:** Android exit history records input-dispatch ANRs at 20:03:59 and 20:20:00. The latter's
  saved 20:16 trace shows the Android main thread blocked in native `write`, called by `ClientSocket.write` ->
  `XOutputStream.flush` -> `XInput2Extension.XI2Event.send` -> `onMotion` -> `TouchpadView.handleFingerMove`.
  Synchronous X11 socket output can therefore block the Android UI while the game is not draining input. This is
  a confirmed failure during loading; its relationship to texture conversion speed is unproven. The socket-output
  ANR is separate from the shared-memory fix and remains unresolved.
- **Source:** device logs and process/cache snapshots; BeamNG's official
  [Texture Cooker documentation](https://docs.beamng.com/modding/materials/texture_cooker/) explains automatic PNG-to-DDS
  conversion, temporary slowdowns, and persistence under the user folder's `temp` directory. That documentation describes
  the feature; the timings above were measured here. The CPU failure was reproduced on the device, explained by
  [glibc's hidden open call in shm_open](https://github.com/bminor/glibc/blob/master/rt/shm_open.c) and
  [Box64's CPU-info fallback](https://github.com/ptitSeb/box64/blob/v0.4.4/src/wrapped/wrappedlibc.c).

## Tomb Raider 2013: black screen / SIGSEGV with FEX Performance (2026-10-17)

- **Setup:** Steam Linux build, app 203160, ELF i386, build ID `cbbc3f63d3737b1b0d790414e472cc64738b8d1e`;
  FEX 2610 / Ubuntu 24.04 rootfs, `bannerlator-r9`, Zink / Turnip `turnip-v26.3.0-20261011-a8xx-linux`,
  Adreno 840 / Android 17. The shortcut selected FEX Performance (`FEX_TSOENABLED=0`).
- **Symptom:** the game disappears with `TombRaider: crash reporter failed`, `Segmentation fault`, `exit: 139`;
  the emulator keeps showing a black screen or Steam. The crash reporter leaves a zero-byte minidump.
- **Evidence:** a temporary i386 signal tracer, preloaded through FEX's `Config.json`, caught a write fault at
  `TombRaider+0x34eda5` (`0x08396da5`, writing through an invalid graphics-buffer pointer after a Map call).
  Disabling Feral's threaded GL (`UseMTGL=0`) still crashed, at `libc.so.6+0x1872dc` / `memcpy`, called from
  `TombRaider+0x32bdc5` after another Map call. This points to buffer handoff/synchronization; it is not proof of
  a particular faulty instruction in FEX or the driver.
- **Fix:** keep TSO enabled for this game. In the Linux shortcut's Launch options use
  `FEX_TSOENABLED=1 %command%` (preserve any existing launch options). This overrides Performance for this game;
  the other preset variables need not change. In the successful process, `/proc/<pid>/environ` confirmed TSO=1
  while vector TSO, half-barrier TSO and memcpy/memset TSO remained 0. The game reached its rendered main menu
  (about 75 FPS in that snapshot), with `UseMTGL=1` again. A long gameplay session was not measured.
- **Did not fix it:** skipping the pre-game launcher (`GameOptionsDialogShouldShow=0`), Low textures
  (`TextureQuality=0`), or disabling `UseMTGL` alone. Keep these separate from the TSO workaround.
- **Pre-game launcher:** restoring `GameOptionsDialogShouldShow=1` with TSO enabled displayed the Feral options
  page, but the Play click did not advance it. The launcher also logged `glXChooseFBConfig failed` / CEF GPU-process
  initialization errors. Keep `GameOptionsDialogShouldShow=0` to bypass this separate UI problem; TSO is still needed
  for the game itself. The diagnostic Low texture setting and `UseMTGL=0` were reverted.
- **Final check:** restored the shortcut's normal hidden Steam startup (removed diagnostic `steamMinimal=0`),
  original Auto textures (`TextureQuality=-1`), and `UseMTGL=1`; kept TSO=1 and the pre-game options page disabled.
  After the user's Play action, the live game rendered Lara in the level (HUD snapshot: 32 FPS, GPU 99%, about 1 GB
  process RSS). The game process's environment again confirmed TSO=1. Temporary tracing libraries, X11 helper and
  diagnostic files were removed from the phone; the running game was left intact. This is a startup/gameplay check,
  not a long stability test or a controlled performance benchmark.
- **Other diagnostic detail:** Feral's unsupported-driver notice can be behind Steam. The helper process is
  `FeralLinuxMessage`, but its X window is named `FEX`; focusing that window and sending Return dismisses its
  selected Continue Anyway button. An independently started game can exit 0 without reproducing the Steam-started
  crash, so test through the emulator's Steam-game shortcut.
- **Source:** found by experiment on the device. The upstream [FEX wiki](https://wiki.fex-emu.com/index.php/Tomb_Raider_(2013))
  recommends Proton for the native port generally; it does not describe this TSO workaround. The bundled
  `games/tomb-raider-2013.json` now supports Linux only and sets `FEX_TSOENABLED=1`; its recommended
  `tomb-raider-2013-linux-fex-performance.wcfg` carries the same override and the tested GPU/emulator settings.
  The previous Windows profile/config was removed. Global FEX defaults are unchanged.

## 32-bit x86 game: `cannot execute: required file not found`

- **Game:** Tomb Raider (2013, Steam 203160), any 32-bit ELF (`od` shows class 1, machine `03 00`).
- **Cause:** Box64 runs only 64-bit programs and the runtime has no 32-bit loader (`/lib/ld-linux.so.2`).
- **Fix:** run it on FEX with the x86-64 root filesystem (`rootfs-ubuntu-24-04`, which has the i386 libraries).
  `libskyshim` sends a 32-bit ELF to FEX (`SKY_X86_32_EMULATOR`). The app has no installer screen for FEX and the
  rootfs yet; they were unpacked by hand into `files/linux-emulators/` and `files/linux-rootfs/`.
- **Source:** found by experiment.

## Segmentation fault right after the Steam API starts (Mesa does not load)

- **Symptom:** `ld.so: ... libgcc_s.so.1: version lookup error: version GCC_7.0.0 not found`, then SIGSEGV.
- **Cause:** the Steam scout runtime's 32-bit libraries on `LD_LIBRARY_PATH` shadow the root filesystem's modern ones.
- **Fix:** put only the game's `lib/i686` and `bin` on the path, plus the scout libraries the root filesystem lacks
  (`skynet-launcher.sh`). Needed from the runtime: `libSDL2_image-2.0.so.0`; from the game: `libssl.so.1.0.0`.
- **Source:** found by experiment (`LD_DEBUG=libs`).

## `SteamAPI_Init failed ... /root/.steam/sdk32/steamclient.so`

- **Cause:** only `sdk64` was linked. **Fix:** link `~/.steam/sdk32` to `Steam/linux32` (launcher). **Source:** experiment.

## "Failed to initialize Direct3D with current settings" (Feral game)

- **Cause:** full screen 640x480 in the game's `preferences`; the X server cannot change modes.
- **Fix:** `FullScreen` 0, `ScreenW` 1280, `ScreenH` 720; `GameOptionsDialogShouldShow` 0 skips the launcher dialog.
- **Source:** found by experiment.

## Launcher dialog of the game crashes the process

- Feral's launcher is an embedded Chromium; with the library problems above fixed it opens. Not investigated further.

## Tomb Raider (32-bit) segfaults when FEX is the container's main emulator, not when it is the second one

- **Cause:** `FEX_ROOTFS` was set only in the Box64 branch of the session setup. The launcher's check "is this scout library in the root
  filesystem?" used it, found nothing, and linked the scout runtime's old `libgcc_s` into `~/.skynet/lib32`: the same `GCC_7.0.0 not found`
  that breaks Mesa. **Fix:** set `FEX_ROOTFS` in the FEX branch too. Found with `LD_DEBUG=libs` (the library came from `/root/.skynet/lib32`).
- **Source:** found by experiment (web search for it found nothing: the FEX issues on 32-bit thunks are about something else).

## Tomb Raider (32-bit) on the GPU: FEX OpenGL thunk, `GLXBadFBConfig`

- **Symptom:** with the OpenGL thunk on (`linuxFexGl32`), `X Error of failed request: GLXBadFBConfig, Major opcode 151 (GLX)` right
  after `glXCreateContext`, exit status 1 (the launcher now logs it). The server logs no GLX error: the host's libGL raises it.
  Small test programs that used `glXCreateContextAttribsARB` drew fine, so the thunk itself was not the problem.
- **Cause:** Mesa raises GLXBadFBConfig when it cannot create the context. The container's `envVars` carry
  `MESA_GL_VERSION_OVERRIDE=3.3`, and for Mesa a version of 3.2 or more means a **core** profile; a compatibility one needs the
  `COMPAT` suffix. A game that makes its context with the old `glXCreateContext` (Feral's Tomb Raider does) asks for a compatibility
  profile, and Zink offers only a core one under that override.
- **Fix:** for a 32-bit game on the OpenGL thunk the launcher sets `MESA_GL_VERSION_OVERRIDE=4.6COMPAT` and
  `MESA_GLSL_VERSION_OVERRIDE=460` (before the shortcut's launch options, which may override them). The thunk is now on by
  default for 32-bit games (`linuxFexGl32` = "0" turns it off; per container and per shortcut in the editors).
- **Sources:** Mesa's environment variables (profile follows the version, `COMPAT` suffix:
  https://docs.mesa3d.org/envvars.html); the same error on Zink with the same cure for another game
  (https://gitlab.freedesktop.org/mesa/mesa/-/issues/2495) and under Wine
  (https://forum.winehq.org/viewtopic.php?t=34889, `MESA_GL_VERSION_OVERRIDE=4.5`). Nothing FEX-specific was found.
- **Checked on the device (2026-10-15):** FEX 2610, Mesa 26 Zink on Turnip a8xx, Adreno 840. Before: thunk on, override 3.3 ->
  `GLXBadFBConfig`, `exit: 1`. After: no error; the game's threads show Zink's `WSI swapchain` and no `llvmpipe-N`; the Adreno
  busy percentage is 75-79 % (`/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`), 47 FPS in the main menu with no dithering of
  the software path. When the thunk was off (or its extra was only on the shortcut, which was ignored then) the game's threads
  were `llvmpipe-0..7` and the GPU stood at 0 %.
- **Not checked:** other 32-bit games; the game itself past its menu.

## X server: GrabServer / UngrabServer sent a reply

- **Symptom:** a GTK client (xfce4-terminal) took 3000 requests/s of `XIQueryPointer` after its `GrabServer`.
- **Cause:** both requests have no reply in the protocol; the server wrote one, corrupting the client's reply stream.
- **Fix:** no reply. **Source:** found by experiment (client stuck in a pointer-query loop).

## xfwm4 / XFCE desktop (see also `docs/linux-fex.md`)

- Window title changes made the terminal ignore the keyboard: same GrabServer bug.
- `GetGeometry` on an InputOnly window answered `BadDrawable`; fixed in `WindowRequests`.

## Feral game (Shadow of the Tomb Raider): `undefined symbol: __libc_dlopen_mode, version GLIBC_PRIVATE`

- **Symptom:** `ShadowOfTheTombRaider` exits at once under Box64 with that symbol lookup error (also `__libc_dlsym`).
- **Cause:** the binary imports glibc's private loader entry points, which glibc 2.34 folded into libc and removed.
- **Fix:** `libskyshim_x64.so` (already preloaded under Box64) exports both on top of `dlopen`/`dlsym`.
  **Source:** the same fix for Life is Strange: https://blog.hofstede.it/reviving-life-is-strange-before-the-storm-on-modern-linux-with-a-glibc-shim/
- **Result:** the game starts, draws through Vulkan on the Adreno (HUD: Vulkan, 60 FPS) and shows the Feral logo.
- **Also fixed on the way:** the game's first `CreateWindow` asked for depth 24 and got `BadMatch` (the screen has one visual, depth 32).
  `WindowManager.createWindow` now accepts depth 24 on that visual. Found with `.xkeys` (logcat tag `XErrors`).
- **Cause of the logo hang: the game was on Box64.** The Feral pre-game window (the "Options" launcher, a CEF page with a PLAY
  button) never drew under Box64: only the looping logo overlay was visible, and the game quit itself with exit code 8 after
  about four minutes. With the shortcut on FEX (`linuxEmulator=fex`, `fex-2610`, the Ubuntu rootfs, thunks on) the window draws and
  shows its dialogs (usage statistics, CPU governor, unsupported card, Vulkan drivers).
- **Second bug found on the way (`skynet-launcher.sh`):** every game started through the Steam runtime got the 32-bit thunk
  selection (`FEX_THUNKCONFIG=ThunkConfig32.json`, Vulkan off), the 64-bit Shadow too: the guest loader then looked for the host
  Turnip ICD, failed (`libvulkan_freedreno.so: cannot open shared object file`) and the launcher said "Vulkan-capable drivers
  required". The launcher now reads the ELF class of the executables in `bin/` and applies the 32-bit selection only to 32-bit-only
  games (`game32`). After that the log shows the Vulkan thunk working (`Linking address ... to host invoker`).
- **Launcher log:** the launcher no longer `exec`s the game; it writes `exit: <status>` to `steam-game-launch.log`.
- **Input into the Feral window:** taps reach the window (`XTrace: XI button press ... pointWin`), but the page updates slowly
  and the screenshots lag; the dialogs need several clicks. Not solved: after "Continue Anyway" on the last dialog the game went on
  to crash once (exit 11) while it still had no Vulkan; with the thunk on it has not been through that step yet.
- **Tried without effect:** `-nolauncher` (CEF still starts), `--disable-gpu --disable-gpu-compositing` as game arguments (the
  game exits right after SDL init), Enter, Space.

## 32-bit game (Tomb Raider 2013) has no gamepad

- **Symptom:** the app's gamepad (the on-screen one or a real one) does nothing in a 32-bit game on FEX; the same pad works in 64-bit
  games. The game's log shows `libskyshim_x64.so ... wrong ELF class: ELFCLASS64` for it.
- **Cause:** `libskyshim` (it answers the evdev ioctls of the app's fake `/dev/input/event0`, hides the udev socket and turns
  the app's events into what a reader expects) existed for aarch64 and x86-64 only; a 32-bit guest had none, so SDL found no
  usable pad. A 32-bit reader also wants 16-byte events (a 32-bit `timeval`); the app's file holds 24-byte ones.
- **Fix:** `libskyshim_x86.so` (`tools/linux-shim/build.sh`, i386, with a `read` that converts the 24-byte events to 16-byte ones
  and keeps a half event for the next read), put in the guest's `LD_PRELOAD` in both FEX configurations (`LinuxFex.prepare`
  and `prepareFor32Bit`). The `stat` hooks now use the fields of the type they were given (`struct stat64` differs from
  `struct stat` on 32-bit).
- **Checked on the device (2026-10-15):** the game's process maps `libskyshim_x86.so` and keeps `/dev/input/event0` open (it used
  to drop it, the ioctls were not answered); with the stick pushed up by events written into that file, Lara walks forward within
  three seconds (the scene had stood still for minutes). **Not checked:** the buttons, the right stick and the triggers one by
  one, a real controller, other 32-bit games; an old binary that calls `__xstat` rather than `stat` is not hooked.
- **Source:** found by experiment (reading `skyshim.c` and `FakeInputWriter`).

## 32-bit Tomb Raider on the GPU: black patches on lit surfaces, "cartoon" look

- **Symptom:** on the GPU (Zink on Turnip) the game ran, but lit surfaces had hard black patches with stair-stepped edges (Lara's
  skin and clothes, rocks, beams), the menus were hatched, and the game looked flat. The same game in a Proton / Wine container
  on the same Turnip is fine, so the cause is in the OpenGL path, not the driver.
- **Evidence:** the game's preferences showed everything at the lowest setting (shadows, SSAO, reflections, post-processing,
  DOF, LOD off) and vendor workaround switches (`UseAMDGatherWorkaround`, `UseAMDTempVariablesWorkaround`, ...): Feral's ports choose
  these by GL_VENDOR / GL_RENDERER, which here read "Mesa / zink / Adreno". With the strings changed to an NVIDIA card the picture
  was right (shadows, torch light, no patches) and the game picked heavier defaults at once (at TextureQuality auto it ran out of
  the 32-bit address space: `Out of memory`, exit 134, until the textures were set by hand).
- **Not the cause (tried):** `TU_DEBUG=sysmem` (patches stayed; the menu ran faster), a lower `MESA_GL_VERSION_OVERRIDE`
  (`3.3COMPAT`: the game needs 4.x, `GLXBadFBConfig`), shadows on (patches stayed in the game itself, the menu got better).
- **Fix:** for a 32-bit game on the OpenGL thunk the launcher writes a driconf file with `force_gl_vendor` / `force_gl_renderer`
  (NVIDIA by default; "amd" and "mesa" = the real strings are choices) and points `DRIRC_CONFIGDIR` at it for that process only.
  Container and shortcut extra `linuxGlVendor`, in both editors.
- **Source:** Mesa's driconf options (`force_gl_vendor`, `force_gl_renderer`) and `DRIRC_CONFIGDIR`
  (https://docs.mesa3d.org/envvars.html); the cause was found by experiment, no source describes this game on Zink.
- **Checked on the device (2026-10-15):** with the launcher alone (no `~/.drirc`, the game's own preferences untouched) the game
  draws as intended at 60 FPS, GPU 79 %. **Not checked:** the TextureQuality auto path on the product build over a long session
  (32-bit address space), other Feral or 32-bit games, the "amd" choice.

## Turnip without tiled rendering (`TU_DEBUG=sysmem`): a setting, off by default

- **Source:** a community note for Turnip builds that GMEM rendering is glitchy on Adreno 8xx and `TU_DEBUG=sysmem` helps
  (https://github.com/whitebelyash/freedreno_turnip-CI/releases); Mesa issue 10466 (SuperTux Kart on Adreno 630,
  https://gitlab.freedesktop.org/mesa/mesa/-/issues/10466) cured the same way on the native driver.
- **What is added:** container and shortcut extra `linuxTuSysmem` ("1" = on; a shortcut's "Container default / On / Off"), in
  both editors. The session sets `TU_DEBUG=sysmem` (appended to an existing `TU_DEBUG`); a game started by Steam gets it from its
  `<appId>.env` like the emulator's variables.
- **What it does not do:** it did not remove the black patches in Tomb Raider (that was the GL vendor string, see above).
- **Measured, but not controlled:** in the first test the menu ran at 57 FPS with it against 35-47 without; in a later pair of runs
  the scenes differed (a cutscene, the menu, a loading screen) and the numbers (52 -> 92 without, 103 -> 0 with) say nothing.
  Whether it is faster, and what it costs in power, is **not established**.

## 32-bit games on the GPU: not only those Steam starts

- **Gap found:** the GPU path of a 32-bit game (the thunk selection `FEX_THUNKCONFIG`, `MESA_GL_VERSION_OVERRIDE=4.6COMPAT`, the NVIDIA
  strings through `DRIRC_CONFIGDIR`) was set only by `skynet-launcher.sh`, which only a game that Steam starts goes through. A 32-bit
  program started from a shortcut directly (`LinuxSession.directCommand`) still drew on the software OpenGL.
- **Fix:** the Mesa part is one file, `skynet-gl32.sh` (`sky_gl32_mesa_env`), sourced by the launcher and by the shortcut's wrapper;
  the wrapper applies it (with the thunk selection) to a program that is a 32-bit ELF run through an emulator. The session passes its
  path as `SKY_FEX_GL32_SCRIPT`.
- **Checked on the device (2026-10-15):** a 32-bit test ELF started from a shortcut sees `FEX_THUNKCONFIG`, `4.6COMPAT`, `460`,
  `DRIRC_CONFIGDIR` and `GALLIUM_DRIVER=zink` (it writes them to a file; `sizeof(long)` = 4); Tomb Raider through Steam still draws on
  the GPU (GPU 78 %, 70 FPS in its menu, no `GLXBadFBConfig`).
- **Not covered:** a shortcut to a `.sh` that starts the 32-bit binary later (the wrapper tests the file it starts); a 32-bit Vulkan
  game (the Vulkan thunk has no 32-bit guest side); a game that needs another vendor than the three choices. Only Tomb Raider (2013)
  has been played; the other 32-bit games are untested.
