# FEX in Linux containers

FEX (FEX-Emu) is the second x86-64 emulator a Linux container can use, next to Box64. BeamNG 0.39 has been run on a
device with Vulkan thunks under proot. The sections below retain the earlier bring-up findings; see
[the October 18 tuning investigation](linux-fex-beamng-20261018.md) for the later physics benchmark and its limits.

## What FEX needs that Box64 does not

Box64 runs a program against the runtime's own libraries through wrappers. FEX translates the program and its system
calls, and the program needs an x86-64 userland to run in: a libc, a dynamic loader, the libraries it links against.
That is a **root filesystem** (`files/linux-rootfs/<id>`), a second package kind next to the emulator
(`files/linux-emulators/<id>`). FEX looks a program's files up in it first and on the phone's own file system second, so
the game folder under `/storage/emulated/0/...` is seen as it is.

## The packages

| Package | Made by | Size |
| --- | --- | --- |
| `fex-2610` (emulator) | `tools/linux-fex/package-fex.sh`: FEX-Emu's PPA build (Ubuntu 22.04 series, glibc >= 2.34) | 2.1 MB |
| `rootfs-ubuntu-24-04-2026-08-11` | `tools/linux-fex/package-rootfs.sh`: FEX's official Ubuntu 24.04 SquashFS, unpacked | 437 MB (1.8 GB unpacked) |

FEX's own images are SquashFS or EroFS files that FEX mounts through FUSE, which a proot session does not have, so the
root filesystem is unpacked on a computer and shipped as a tar.zst like the others. The emulator package keeps only
what a session uses: `bin/FEX`, `bin/FEXServer`, the Vulkan/OpenGL/drm/ALSA thunks and their database.

Both go into the registry (`winlator-skynet-components/linux.json`, `emulators` and `rootfs`); each script prints the
entry. Publishing them is a release in that repository (see the add-component notes).

## A session with FEX (`LinuxFex`, `LinuxSession`)

* The program is started as `FEX <program>`.
* A container's settings: the FEX build, the root filesystem, a preset and the thunks switch. The presets are the ones
  of FEXCore for Wine (`FEX_TSOENABLED`, `FEX_MULTIBLOCK`, ...), which FEX reads from the environment.
* `$HOME/.config/fex-emu/Config.json` is written for each start: the root filesystem, the thunk library folders, and the
  guest's `LD_PRELOAD` of our x86-64 shims. FEX can take any option from a `FEX_<OPTION>` variable, but it splits the
  variable at the last `=`, so the guest's environment (`LD_PRELOAD=...`) can only come from the file. A list (`Env`) is
  written as a repeated key, which is what FEX's JSON reader expects.
* Thunks: with them on, a game's x86-64 `libvulkan.so.1` and `libGL.so.1` are replaced by FEX's thunk libraries, which
  call the runtime's own libraries; so the game draws through the container's Turnip like a native program, and
  Vulkan layers of the runtime (frame generation) apply to it. Off: the root filesystem's own Mesa runs under emulation.
* The program's own x86-64 libraries (libXss for a game with an embedded Chromium, SDL) come from the root filesystem,
  not from the runtime's staged aarch64 copies, which Box64 needs and FEX does not.

## What was checked on the device (Z Fold8 Ultra, Android 17)

Installed by hand into the app's folders (the registry has no entries yet), in a container "Linux FEX":

* **FEX runs under proot.** An x86-64 program reports `machine=x86_64`, sees the root filesystem (`/etc/os-release` of
  Ubuntu 24.04), the phone's file system for paths the root filesystem lacks (`/root`, `/storage/emulated/0`,
  `/tmp/.X11-unix`), and its guest `LD_PRELOAD`. File calls through a symbolic link into shared storage work.
* **The Vulkan thunk works.** An x86-64 `vkdmabuf` finds `Adreno (TM) 840` with the dma-buf extensions through FEX.
* **BeamNG 0.34 starts under FEX** and runs its Lua engine, but it was not taken to a window. Three things stood in the way:
  1. *Shared storage is mounted noexec.* FEX maps a program and its libraries as executable (`mmap` failed with
     `EACCES`: "Failed to map 64-bit elf file"), where Box64 reads them. A program for FEX has to be copied into the
     container, like an ARM64 one. The Linux programs screen now does that for FEX and offers to copy the whole game
     folder: BeamNG finds `content`, `lua`, `settings` ... beside `BinLinux`, and its virtual file system does not follow
     symbolic links, so links back to the data on shared storage leave it with "cannot open file '/lua/...'".
  2. *The root filesystem lacks `libatomic.so.1`*, which BeamNG's binary links. `package-rootfs.sh` adds it (the
     files in the installed copy were put there by hand). Found by comparing every library the game's files need with the
     root filesystem's x86-64 libraries; nothing else was missing.
  3. *With the OpenGL thunk on, the start hung* (every thread asleep behind eight llvmpipe worker threads, no output).
     With it off the game went on. The thunk setting is therefore Vulkan only.

## Measured (2026-10-16, Z Fold8 Ultra, BeamNG 0.39 on the Steam container, under proot)

Same game, same map, 640x360 so the GPU is not the limit, one run after the other:

| | FPS |
| --- | --- |
| FEX, preset Intermediate (TSO on) | 67-70 |
| FEX, preset Performance (TSO off) | 115-145 |
| Box64 (Performance; its default is no strong memory model either) | about 68, with the truck driving, so not the same scene |

At 2560x1440 the GPU is the limit (98 %) and FEX and Box64 both give 35-38 FPS. **TSO, the emulation of x86's memory ordering, costs FEX
about half of its speed in this game**; Box64 does not emulate it by default, so a FEX preset without TSO is what matches Box64. A game that
needs the ordering (a threaded one that misbehaves) wants Intermediate or Compatibility.

* Under proot a FEX game keeps proot's thread at 30-36 % of one core for the whole run (every path system call, twice, because FEX looks in the
  root filesystem first); loading a level took 40 s on most starts and 129 s on one.
* FEX's disk cache (`DiskCache`, FEX 2609+, `linuxFexDiskCache=1` here): it did not make BeamNG load or run faster, took 355 MB, and one start with
  a warm cache stopped at the loading screen with every thread asleep. Off by default.
* **FEX without proot does not work yet.** FEX makes its guest system calls itself (inline `svc`; `NeedsSeccomp` did not change what the kernel
  saw), so libskypath, which translates paths in libc, never sees them: the game's `connect("/tmp/.X11-unix/X198")` reached the kernel
  untranslated and BeamNG stopped with "x11 not available". A session that runs FEX, or a 32-bit program (always FEX), therefore stays under
  proot (`LinuxSession.runsOnFex`). Ways forward: a FEX build that sends its system calls through libc, or x86 shims that translate the socket
  and home paths inside the guest.

## Remaining unknowns

* A controlled FEX-versus-Box64 comparison with identical active traffic, camera, vehicle models and CPU frequency limits.
* Whether the guest's OpenGL (llvmpipe of the root filesystem or the runtime's, through the thunk) can be made to work.

## Adding a 32-bit program as a game

A 32-bit x86 ELF is a Linux program (`ExecutableKind.LINUX_X86`, `isLinux()` is true): "Add game" no longer takes it for a Windows one and
offers no Wine. It shows "Standard Linux, x86 32-bit", the Linux configs of the registry, and "An environment you already have", which lists
only the Linux containers that can run the program (`LinuxProgramSupport.refusal`: an x86-64 program needs an installed x86 emulator and, on
FEX, a root filesystem; a 32-bit one needs FEX and a root filesystem; an ARM64 program or a script needs nothing). A container that cannot is
left out; when none can, the tile says so and a tap shows why for each. The same check refuses the program when it is added by any of the
ways, with a sentence naming what is missing. Checked on the device (2026-10-15) with a 32-bit ELF in Download: the file manager lists it
with the Linux icon and offers "Add this game"; the screen offers no Windows environment; the list holds the two Linux containers that fit.
Not checked: that the standard Linux container is made and the program started from it (needs FEX and the root filesystem installed).

### "Copy into the container?" and why it appears for a Box64 container

A Box64 container copies nothing for an x86-64 program on the internal shared storage (Box64 reads the program where it lies). It copies
for: a **32-bit program** (always run by FEX, even in a Box64 container: Box64 cannot run it; FEX maps the program and its libraries as
executable code and shared storage is mounted noexec), an **ARM64 program** and a **script** (started as they are), a file on **another
volume** (not bound in the container). The dialog now says which of these it is (`LinuxProgramInstaller.copyReason`), and the refusal at
start names FEX for a 32-bit program. The dialog showed no choices at all before (an `AlertDialog` with a message does not list its items:
the reason and the list are in one view now), so "Program only / Whole game / Folder" could not be chosen.
