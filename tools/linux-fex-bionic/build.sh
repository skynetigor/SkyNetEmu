#!/bin/sh
# Builds FEX (the other x86-64 emulator) as an Android (bionic) program, so that a Linux game can run from the app without the
# glibc runtime and without proot, the way tools/linux-box64-bionic does for Box64.
#
#   ANDROID_NDK_HOME=/path/to/ndk ./build.sh [output folder]      (default: ./out)
#
# FEX upstream says Android is not a target ("Android is not a target and will never be a target", its FAQ), so what the patch
# in patches/ does is make the build accept bionic: what the C library does not have (POSIX shared memory, memfd_create,
# getdents64, valloc), the libc++ standard library being stricter than libstdc++, and the types of <elf.h> differing.
# The flags follow Termux's (disabled) FEX package: no jemalloc, no LTO, no telemetry, no preserve_all.
#
# Needs git, cmake, ninja, python3 and an NDK (r29 was used). Writes FEX and FEXServer into the output folder.
#
# Not built here: the GPU thunks. Without them a guest draws with the root filesystem's Mesa, on the CPU. The thunks' guest
# side (x86-64) can be taken from the glibc package of the same FEX version (see ../linux-fex/package-fex.sh); their host side
# has to be built for bionic, which this script does not do yet.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/out}
NDK=${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/29.0.14206865}
TAG=${FEX_TAG:-FEX-2610}
WORK=${WORK:-$(mktemp -d)}

git clone --branch "$TAG" --depth 1 --recurse-submodules --shallow-submodules https://github.com/FEX-Emu/FEX.git "$WORK/src"
for patch in "$HERE"/patches/*.patch; do
  git -C "$WORK/src" apply "$patch"
done

cmake -S "$WORK/src" -B "$WORK/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_TESTS=OFF -DBUILD_FEXCONFIG=OFF -DBUILD_THUNKS=OFF \
  -DENABLE_JEMALLOC=OFF -DENABLE_JEMALLOC_GLIBC_ALLOC=OFF -DENABLE_LTO=OFF \
  -DENABLE_OFFLINE_TELEMETRY=OFF -DHAS_CLANG_PRESERVE_ALL=OFF -DENABLE_CCACHE=OFF \
  -DTUNE_ARCH=armv8.4-a -DTUNE_CPU=generic
cmake --build "$WORK/build" --target Bin/FEX Bin/FEXServer

STRIP=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip)
mkdir -p "$OUT"
"$STRIP" -o "$OUT/FEX" "$WORK/build/Bin/FEX"
"$STRIP" -o "$OUT/FEXServer" "$WORK/build/Bin/FEXServer"
ls -l "$OUT/FEX" "$OUT/FEXServer"
