#!/bin/sh
# Build device test fixtures outside the packaged assets.
# LLVM_MINGW=/path/to/llvm-mingw ./tools/winhandler/build-probes.sh /tmp/windows-probes
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
output=${1:?Pass an output directory for the test executables}
mkdir -p "$output"
for target in aarch64:arm64 x86_64:x64 i686:x86; do
    triple=${target%%:*}
    name=${target#*:}
    compiler="${LLVM_MINGW:+$LLVM_MINGW/bin/}$triple-w64-mingw32-clang"
    "$compiler" -std=c11 -O2 -Wall -Wextra -Werror -mwindows -static -s \
        -Wl,--no-insert-timestamp "$repo/tools/winhandler/probe.c" \
        -o "$output/windows-probe-$name.exe" -luser32 -lgdi32
done
