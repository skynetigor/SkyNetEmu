`build.sh` builds `winhandler.c` as a native Windows ARM64 GUI executable. It
requires [LLVM-MinGW](https://github.com/mstorsjo/llvm-mingw/releases/tag/20261006),
whose UCRT 20261006 release was used for the bundled asset. Extract the appropriate
host archive outside the repository, then run:

```sh
LLVM_MINGW=/path/to/llvm-mingw ./tools/winhandler/build.sh
```

The output is `app/src/main/assets/windows/arm64/winhandler.exe`. The linker omits
timestamps and strips debug data so rebuilding with the same toolchain produces
the same binary. Only Windows system DLL imports are required; no Box64 or FEX
runtime is linked. The existing x64 helper inside `container_pattern_common.tzst`
is preserved. Install the ARM64 asset under its own name in the Wine prefix and
select it only for native ARM64 launches.

The UDP protocol keeps the Java `WinHandler` layout: little-endian fixed-width
fields, 58-byte packed process records, and ports 7946/7947. Compile-time checks
verify the layout on each target. Packet reads use `memcpy`, so unaligned protocol
fields do not depend on the host C ABI.

The input server binds before starting the game so controls remain available
during shell startup. The helper waits up to five seconds for that server,
reports startup failures to stderr, and disables shell error dialogs. Both the
executable and its working directory should be mapped Windows paths.

`build-probes.sh /tmp/windows-probes` builds ARM64, x64, and x86 GUI fixtures with
the same toolchain. These stay outside the APK. Pass a Windows file path as the
first argument, for example `C:\\windows-probe.log`; each fixture records startup,
window readiness, key presses, mouse clicks, and normal window exit there. This
lets device tests verify the app-to-helper input path as well as process startup.

Run `SANITIZE=1 python3 tools/winhandler/test-quoting.py` to verify native argument
serialization with the host compiler. Plain switches remain unquoted for games
which parse their raw Windows command line; empty arguments, spaces, quotes,
and trailing backslashes retain standard Windows escaping.
