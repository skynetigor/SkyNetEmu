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

## OPEN: Tomb Raider on the GPU (FEX OpenGL thunk)

- **Symptom:** `X Error of failed request: GLXBadFBConfig, Major opcode 151 (GLX)`, serial 83, on the host side, right
  after `glXCreateContext`; then SIGSEGV. The server logs no GLX error (`XErrors`); the message is raised by the host's
  libGL.
- **What works:** small 32-bit test programs (`glXChooseVisual`/`glXChooseFBConfig` + `glXCreateContextAttribsARB`) draw
  with Zink on Turnip through `HostThunks_32/libGL-host.so`.
- **Next:** search for FEX 32-bit GL thunk issues (`FEX-Emu/FEX`, "libGL" "32-bit" thunk, `glXCreateContext`), Feral/SDL2
  specifics; a host-side `LD_PRELOAD` interposer on the `glX*` calls showed the game uses `glXCreateContext`.
- Default stays the root filesystem's software OpenGL (container extra `linuxFexGl32` is off).

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
- **Open:** the game stays at the Feral logo for minutes (Vulkan swapchain alive, 47-60 FPS, CPU 20-30%, Feral's HTML UI loaded and
  fetching its remote resources). The CEF line `gles2_command_buffer_stub: Failed to create default offscreen surface` is probably
  harmless: CEF never dlopens libGL/libEGL/libosmesa (Box64's log shows no attempt), so it has no GL and falls back to software.
  Tried without effect: `-nolauncher` (CEF still starts), `--disable-gpu --disable-gpu-compositing` as game arguments (the game
  exits right after SDL init: it rejects unknown arguments, like Tomb Raider 2013 with `-windowed`).
  Next: find out what the game waits for (its HTML UI has a startup video and a loading overlay: `cache/GameFeralUI/emergency.js`).
