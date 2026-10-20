# Linux Box64 thread cleanup candidate

This candidate keeps the Linux Box64 v0.4.4 base (`2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`) and the existing shared-storage and Vulkan lookup patches. Patch 0003 backports upstream commit [`4c231eb0872e580a820bd8d03c4a3e2b0ea23253`](https://github.com/ptitSeb/box64/commit/4c231eb0872e580a820bd8d03c4a3e2b0ea23253), by Yang Liu: thread-local destructor lists, execution on thread exit, and main-thread execution before process cleanup. The quick-exit API introduced after v0.4.4 is unrelated and is omitted from the backport context.

Build on ARM64 Ubuntu 24.04 with git, GCC, CMake, Ninja, Python and binutils:

```sh
bash tools/linux-box64-glibc/build.sh /path/to/box64 /tmp/box64-cleanup-output
```

The output contains a stripped candidate and a separate binary with symbols. Pair it with unchanged copies of the current component's bundled x86 libraries. Keep the stock component installed for rollback; this script does not publish or install anything.

Patch 0004 adds emulator layout headers to the translated-code cache's compatibility hash: generated code embeds offsets into those structures, so caches from before the backport must be rejected. Disable any old `Winlator/box64-override` debug file before a game test; the app otherwise copies that binary over the selected component. Verify the installed candidate's SHA-256 both before and after launch.

Compile `thread-cleanup-test.c` for x86_64 Linux with pthread support, then run through both stock and candidate Box64. `serial` verifies execution at thread exit, the default mode registers 12,288 callbacks concurrently and verifies owning thread/reverse order, and `main` checks main-thread and reentrant callbacks before process atexit. All three must pass on the candidate.

```sh
zig cc -target x86_64-linux-gnu.2.28 -O2 -pthread tools/linux-box64-glibc/thread-cleanup-test.c -o /tmp/thread-cleanup-test
python3 tools/linux-box64-glibc/test-layout-hash.py /tmp/box64-cleanup-output/source
ctest --test-dir /tmp/box64-cleanup-output/build --output-on-failure --timeout 30
```

On the test phone, the stock component failed the serial and main-thread checks and aborted with `realloc(): invalid old size` under concurrent registration. The candidate passed all three checks. These targeted results establish the cleanup defect; game stability still needs end-to-end testing with the selected binary's hash verified.
