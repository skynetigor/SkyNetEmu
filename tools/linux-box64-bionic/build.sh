#!/bin/sh
# Builds the Box64 that runs a glibc x86-64 program (a Linux game) straight from the app on Android (bionic), without proot and without a glibc runtime:
# the Android build of Box64 v0.4.4 plus patches/ (the glibc names, structures and behaviours a glibc program expects, on top of Android's libc).
#   ANDROID_NDK_HOME=/path/to/ndk ./build.sh [output file]      (default: ../../app/src/main/assets/linux/box64-bionic)
# Needs git, cmake, ninja, python3 and an NDK (r29 was used).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/../../app/src/main/assets/linux/box64-bionic}
NDK=${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/29.0.14206865}
TAG=${BOX64_TAG:-v0.4.4}
WORK=${WORK:-$(mktemp -d)}

git clone https://github.com/ptitSeb/box64.git "$WORK/src"
git -C "$WORK/src" checkout "$TAG"
for patch in "$HERE"/patches/*.patch; do
  git -C "$WORK/src" apply "$patch"
done

# ANDROID: the Android build (the one Wine's containers use). Box64's CMakeLists sets -O1 for it; the build is -O1 (not compared with -O2).
cmake -S "$WORK/src" -B "$WORK/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID=ON -DARM_DYNAREC=ON -DBOX32=OFF -DNO_CONF_INSTALL=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,--export-dynamic"
cmake --build "$WORK/build"
STRIP=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip)
mkdir -p "$(dirname "$OUT")"
"$STRIP" -o "$OUT" "$WORK/build/box64"
ls -l "$OUT"
