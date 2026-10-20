#!/usr/bin/env bash
# On ARM64 Ubuntu 24.04: build.sh <local Box64 git checkout> <output directory>
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SOURCE=${1:?local Box64 git checkout}
OUT=${2:?output directory}
COMMIT=2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a
mkdir -p "$OUT/source"
OUT=$(cd "$OUT" && pwd)
git -C "$SOURCE" archive "$COMMIT" | tar -xf - -C "$OUT/source"
for patch in "$HERE"/patches/*.patch; do git -C "$OUT/source" apply "$patch"; done
cmake -S "$OUT/source" -B "$OUT/build" -G Ninja \
    -DARM_DYNAREC=ON -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUNDLE_X86_LIBS=OFF \
    -DNO_CONF_INSTALL=ON -DNOGIT=ON
cmake --build "$OUT/build" --target box64 -j"${JOBS:-4}"
cp "$OUT/build/box64" "$OUT/box64.debug"
strip -o "$OUT/box64" "$OUT/box64.debug"
cp "$OUT/source/LICENSE" "$OUT/LICENSE"
sha256sum "$OUT/box64" > "$OUT/box64.sha256"
