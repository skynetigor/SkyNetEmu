#!/bin/sh
# Cross compile the native Windows ARM64 helper without replacing the x64 one.
# LLVM-MinGW 20261006 (UCRT) was used for the checked-in executable:
# https://github.com/mstorsjo/llvm-mingw/releases/tag/20261006
# Usage: LLVM_MINGW=/path/to/llvm-mingw ./tools/winhandler/build.sh
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
compiler=${CC_ARM64_WINDOWS:-${LLVM_MINGW:+$LLVM_MINGW/bin/}aarch64-w64-mingw32-clang}
output="$repo/app/src/main/assets/windows/arm64/winhandler.exe"
mkdir -p "$(dirname "$output")"

"$compiler" -std=c11 -O2 -Wall -Wextra -Wno-unused-parameter \
    -Werror -mwindows -static -s -Wl,--no-insert-timestamp \
    "$repo/app/src/main/cpp/winlator/winhandler.c" \
    -o "$output" -lws2_32 -lpsapi -lshell32 -luser32

# Fail early if a host compiler or an ARM64EC target was accidentally selected.
python3 - "$output" <<'PY'
import pathlib
import struct
import sys

path = pathlib.Path(sys.argv[1])
data = path.read_bytes()
pe = struct.unpack_from('<I', data, 60)[0]
assert data[:2] == b'MZ' and data[pe:pe + 4] == b'PE\0\0', 'Missing PE signature'
machine = struct.unpack_from('<H', data, pe + 4)[0]
assert machine == 0xaa64, f'Expected native ARM64 machine, got {machine:#x}'
print(f'{path}: native Windows ARM64, {len(data)} bytes')
PY
