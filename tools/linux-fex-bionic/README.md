# FEX on Android's C library (experiment)

The same idea as [linux-box64-bionic](../linux-box64-bionic): run a Linux x86-64 game straight from the app, with no glibc runtime
and no proot. Here the emulator is FEX instead of Box64. FEX runs the guest's *own* glibc (from the x86-64 root filesystem), so
there is no `libc.so.6` wrapper to map onto bionic, which is what the Box64 port spends most of its patch on; what has to give
instead is FEX's own use of the host's C library.

FEX says Android is not its target ("Android is not a target and will never be a target", [its FAQ](https://wiki.fex-emu.com/index.php/FAQ)),
mainly because Android blocks syscalls through SECCOMP. Its Termux package (now in `disabled-packages`) was the hint that a bionic
build is possible at all; `patches/0001-android-fex-for-bionic.patch` is this project's own version of it, on top of `FEX-2610`.

    ANDROID_NDK_HOME=/path/to/ndk-r29 ./build.sh        # writes out/FEX and out/FEXServer

## What the patch changes

| Where | Why |
|---|---|
| `CMakeLists.txt` | the build refuses any system but Linux and Windows; `HAS_PROGRAM_INVOCATION_NAME` is left empty by its own probe, which its `#if` cannot read; bionic has no `libpthread` to link |
| `AndroidCompat.h` (new, force-included) | what bionic has not: POSIX shared memory (an object becomes a file in `FEX_SHM_DIR`, the way Box64 uses `BOX64_SHM_DIR`), `memfd_create`, `getdents64`, `ELF32_ST_VISIBILITY`, `MFD_CLOEXEC` |
| `FEXCore/Source/Utils/AllocatorHooks.cpp` | no `valloc` in bionic |
| `FEXCore/include/FEXCore/fextl/functional.h` | `fextl::move_only_function` asserts a property of libstdc++ that LLVM's libc++ (Android's) does not have; the check becomes one on the size of the wrapped lambda, which is what it is really after |
| `ELFCodeLoader.h`, `SyscallsSMCTracking.cpp` | `Elf64_Addr` is `unsigned long long` on Android and `uint64_t` is `unsigned long`: `std::min`/`std::max` need the type spelled out |

## Status

`FEX` and `FEXServer` build and run x86-64 glibc programs inside the app's sandbox (`uname -a` answers `x86_64`, the root
filesystem's `ldd` reports glibc 2.39). Not done yet:

* **The GPU.** This build has no thunks, so a guest draws with the root filesystem's Mesa, on the CPU. Box64 wraps the host's
  Vulkan itself; FEX needs the thunks' host side built for bionic (their guest side, x86-64, can come from the glibc package of
  the same version, see [../linux-fex/package-fex.sh](../linux-fex/package-fex.sh)). Until then there is no point in measuring a game.
* **FEXServer does not start itself.** FEX tries, fails, and works when the server is already running (`FEXServer --foreground`).
* Nothing in the app starts FEX in this mode yet: `linux/LinuxBionicSession` knows only Box64.
