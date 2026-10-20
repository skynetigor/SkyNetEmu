#!/bin/sh
# Rebuild the matched FEX 2610 Vulkan host thunk and x86 callback regression probe.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${WORK:-$(mktemp -d)}
IMAGE=${IMAGE:-skynet-fex-vulkan-builder:2610}
mkdir -p "$WORK"
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    docker build -t "$IMAGE" "$HERE/vulkan-compat"
fi
if [ ! -d "$WORK/FEX/.git" ]; then
    git clone --depth 1 --branch FEX-2610 https://github.com/FEX-Emu/FEX.git "$WORK/FEX"
fi
[ "$(git -C "$WORK/FEX" rev-parse HEAD)" = 14c92681f4d62cf84d901460e0358de09c8847a7 ] || { echo 'Unexpected FEX revision' >&2; exit 1; }
git -C "$WORK/FEX" submodule update --init --depth 1 External/Vulkan-Headers External/fmt
PATCH="$HERE/patches/0001-route-vulkan-debug-callback-proc-addresses.patch"
if git -C "$WORK/FEX" apply --check "$PATCH" 2>/dev/null; then
    git -C "$WORK/FEX" apply "$PATCH"
else
    git -C "$WORK/FEX" apply --reverse --check "$PATCH"
fi
mkdir -p "$WORK/generator"
cp "$HERE/vulkan-compat/CMakeLists.txt" "$WORK/generator/CMakeLists.txt"
cp "$HERE/vulkan-compat/compile.sh" "$WORK/compile.sh"
docker run --rm -v "$WORK:/work" "$IMAGE" /bin/sh /work/compile.sh
cp "$WORK/libfex_vulkan_host_2610.so" "$ROOT/app/src/main/assets/linux/libfex_vulkan_host_2610.so"
cp "$WORK/FEX/LICENSE" "$ROOT/app/src/main/assets/linux/FEX-Vulkan-compat-LICENSE.txt"
zig cc -target x86_64-linux-gnu.2.35 -O2 -fcf-protection=branch -Wall -Wextra -Werror \
    -I "$WORK/FEX/External/Vulkan-Headers/include" "$HERE/vulkan_callback_test.c" -ldl \
    -o "$ROOT/app/src/androidTest/assets/fex_vulkan_callback_test"
shasum -a 256 "$ROOT/app/src/main/assets/linux/libfex_vulkan_host_2610.so"
