# Linux session without proot (the default)

The Linux container runs its programs (Box64, FEX, Steam) in a glibc runtime. By default that is done under proot, which stops the program at every system call that
names a path and translates it from outside: about 100 us for a `stat` or an `open` (the call itself: about 1 us). A game that opens thousands of files while loading pays for all of them.

`LinuxSession.noProotLaunch` starts the same session without proot:

- the first program is run through the runtime's own loader (`ld-linux-aarch64.so.1 --library-path ... program`), under `env -i` (Android's own environment is not the guest's);
- `libskypath.so` (`tools/linux-shim/skypath.c`, preloaded) translates guest paths inside the process, in libc, from the table of what proot's `-b` options bound over the
  runtime (`SKY_PATHMAP`); it also sends every program the session starts later (`exec*`, `posix_spawn*`, `system`, `popen`) through the loader, with the same environment;
- Android's seccomp filter kills a process that makes a system call the app is not allowed (proot absorbs that signal): `libskypath` installs a `SIGSYS` handler that answers
  `-ENOSYS`; the loader and libc make two of those calls (`set_robust_list`, `rseq`) before any library exists, so a patched copy of both is used (made once, in
  `files/linux-shim/libc-v2-<runtime>`);
- Box64 looks libc's functions up with `dlsym(libc handle, ...)`, which skips an `LD_PRELOAD` library: `libskypath` also hooks `dlsym`/`dlvsym` so those lookups find its versions;
- glibc's `mkdtemp`/`mkstemp` and a raw `syscall(...)` do not go through the hooked functions: they are hooked as well;
- POSIX `shm_open`/`shm_unlink` also use hidden libc file calls: explicit hooks translate `/dev/shm` to the session's
  shared-memory directory. Without these, Box64 cannot synthesize x86 `/proc/cpuinfo` and BeamNG 0.34 reports zero
  cores / an unknown CPU (see [game findings](linux-game-fixes.md));
- a socket path (`sockaddr_un`) that gets longer than 107 bytes once translated is reached through `/proc/self/fd/<folder>/<name>`;
- `/sys` answers EACCES where a PC answers ENOENT (`/sys/class/power_supply/BAT0`): hooks turn one into the other.

It is the default. The switch "Run without proot" (Container settings > Runtime) sets the extra `linuxNoProot` ("1" on, "0" under proot); the file `Winlator/linux-proot` on the shared storage keeps every container under proot.
When the patched loader cannot be made for a runtime, the session falls back to proot by itself.

A session that runs FEX (the container's emulator is FEX, or the program is a 32-bit x86 one) stays under proot: see [linux-fex.md](linux-fex.md).

Measured on BeamNG.drive 0.39 (Steam, Box64, the Steam card of the library; three runs each, tap on Play to the main menu): about 18 s without proot, about 23 s under proot
(on the game's own clock: 14.8 s against 19.5 s). Not yet tried: the Steam client, FEX games, programs that read `/proc/self/exe`-relative paths in unusual ways.
Rebuild the library with `tools/linux-shim/build.sh` and copy `libskypath.so` to `app/src/main/assets/linux/`.

The shared-memory regression test is `tools/linux-shim/shm-test.c`. Cross-compile it with
`zig cc -target aarch64-linux-gnu.2.35 -O2 -o shm-test tools/linux-shim/shm-test.c -lrt`, then run it on the device
through the runtime loader with `libskypath.so` preloaded, `SKY_ROOT` set and `/dev/shm` mapped in `SKY_PATHMAP` to a
writable app directory. It checks object sharing/mapping and unlink lifetime, CLOEXEC, exclusive creation and invalid
names. With the old no-proot library the first `shm_open` fails with ENOENT; with the fixed library the test passes.
