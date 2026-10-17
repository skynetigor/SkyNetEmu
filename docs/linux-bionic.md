# A Linux game on Android's own C library (experimental)

A Linux container normally runs an x86-64 game as: the glibc runtime (Arch Linux ARM) + a glibc build of Box64 (or FEX), under proot or, since
`linuxNoProot`, without it. This mode drops all of that: **a Box64 built for Android (bionic) is started straight from the app**, like the Box64 of a Wine
container, and the game's own x86-64 libraries come from the x86-64 root filesystem (Ubuntu 24.04, the one FEX uses). There is no glibc runtime, no proot, no
`ld-linux`: the process is `/system/bin/linker64 box64 <game>`.

Switch: container editor → Runtime → *Run on Android's C library, without glibc (experimental, Box64)* (container extra `linuxBionic=1`, off by default).
It applies to a program started from a shortcut (a game's executable), not to the Steam client, which stays in the glibc session (Steam is arm64 glibc).

Status: BeamNG.drive 0.39 starts, shows its main menu (3D scene, Adreno 840 through the Wine wrapper ICD and a Turnip adrenotools driver) and runs for minutes
with no crash.

## Measurements (Galaxy Z Fold8 Ultra, 640x360, low settings)

* Main menu: about 12 s on both this mode and the glibc no-proot session.
* BeamNG Italy, this mode with a Turnip Gen8 (a8xx) driver, no cooling: about 40-50 FPS, GPU about 95%. Not compared with the glibc session on the same map yet.
* Menu scene FPS seen: 60 here, 120 on the glibc session (one test each).
* With the Turnip 26.2 driver (not built for a8xx) the GPU hung on the 3D world after a level loaded; install "Turnip Gen8" from the Drivers screen, it is preferred automatically.

## How it works

`linux/LinuxBionicSession.java` builds the launch: the environment is the one of a Wine container (the Java X server socket in `imagefs/usr/tmp`,
PulseAudio, `libandroid-sysvshm`, `VK_ICD_FILENAMES` → `libvulkan_wrapper.so` over the adrenotools driver the container names) plus what the glibc session
decided for the program (Box64's settings, the game's own variables). Box64 runs the program with `BOX64_LD_LIBRARY_PATH` = the OpenGL stand-ins, the Box64
package's x86-64 libraries, the root filesystem's libraries (without the OpenGL/Mesa ones) and the game's folder.

The x86-64 program is a *glibc* program: it expects glibc's names, structures and behaviours from `libc.so.6`, and the Box64 wrapper of that library maps them
1:1 onto the native libc, which here is bionic. `tools/linux-box64-bionic/patches/0001-android-box64-for-glibc-programs.patch` (on top of Box64 v0.4.4) is
the translation layer; `build.sh` builds it with the NDK. What it had to fix, in the order BeamNG found it:

| Where | What differs |
|---|---|
| names | `__errno_location`, `__ctype_b_loc` & co (tables in glibc's layout, also read straight from `struct __locale_struct` by libstdc++/libc++), `__h_errno_location`, `__assert_fail`, `gettext`/`textdomain`, `__strdup`, `__xpg_strerror_r`, `bcmp`, `__uflow`, `__getgroups_chk`, … |
| locale | `newlocale`/`uselocale`/`duplocale` hand back a glibc-layout object (libc++'s `ctype<char>` reads `->__ctype_b` from it) |
| `nl_langinfo` | glibc items are `category << 16 \| index`, bionic's a flat list: `CODESET` was answered with a weekday name, which glib took for the charset |
| `sysconf` | other numbering (`_SC_NPROCESSORS_CONF` 83 / 0x60, …) |
| `strerror_r` | GNU (returns the message) vs XSI (returns an int) |
| `__fread_chk`, `__fgets_chk` | other argument order |
| `mallinfo` | `struct mallinfo` is ten `int` in glibc (40 bytes), ten `size_t` in bionic (80): the native call overflowed the guest's local and trashed its saved registers (a crash in Chromium's memory dump provider) |
| `struct sigaction` | handler / 128-byte mask / flags in glibc, flags first and an 8-byte mask in bionic: the mask was read as the handler |
| pthread mutexes | a static glibc `PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP` carries the type in `kind` (offset 16), bionic reads it from the top bits of the first word: a recursive mutex deadlocked on itself |
| `pthread_getattr_np` | returned the native thread's stack, not the emulated one (Chromium's stack-bounds CHECK) |
| IFUNC | a symbol that is an IFUNC (libatomic's `__atomic_load_16`) was bound to its *resolver* when the program is linked with `-z now`; a 16-byte atomic load returned the address of the function |
| heap pointers | Android's malloc tags pointers (`0xB4…` in the top byte): code that packs a pointer into 48 bits (rapidjson) lost it. Box64 turns heap tagging off at start |
| signals | a signal landing just after a native call, in the dynablock, read the guest's registers from ARM registers the callee had overwritten (and used a garbage stack pointer for the handler's frame) |
| `vfork` | Box64 waits `waitpid(child, WEXITED)` after emulating it as fork: glibc's kernel call refuses the option and the parent goes on, bionic waits for the child to end (CEF's helper processes) |
| paths | `/dev/shm`, `/tmp` and `/sys/class/power_supply` (Android answers EACCES where a machine without a battery says ENOENT; `std::filesystem::status` throws) |
| libraries | no native glib/gtk/pango/cairo on Android: `BOX64_EMULATED_LIBS` runs the root filesystem's; the root filesystem's OpenGL (Mesa on LLVM) is replaced by `libskynogl_x64.so`, which answers "no GLX/EGL" |
| shared memory | Android has no `/dev/shm`: `BOX64_SHM_DIR` |

`vkCmdDispatchTileQCOM`-style functions the driver knows and Box64 does not wrap return NULL (patch 0002 of the glibc build, included).

Debugging a run: `Winlator/linux-bionic.env` (a file on the shared storage, `KEY=value` lines) adds variables to the environment, for example `BOX64_LOG=1`,
`BOX64_SHOWSEGV=1`, `BOX64_SHOWBT=1`; `SKY_BIONIC_PIPE=<command>` pipes the program's output through it (a `grep -B` for a trace).

## Known limits

* The Steam client and a game started through it are not in this mode.
* A game that opens `/proc/self/...` paths beyond the ones Box64 emulates, uses `/dev/input` evdev gamepads or `execveat` needs the path translation of
  `libskypath` (the glibc no-proot session), which this mode does not have yet.
* The root filesystem's gtk stack runs emulated (BeamNG's CEF loads it) and costs start-up time.
