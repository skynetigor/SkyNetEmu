# Game fixes (Linux containers)

One entry per problem: symptom, cause, fix, source. **Search here first**, then the internet (see the `fix-game` skill).
"Found by experiment" means no outside source was used; replace it with a link when one turns up.

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
