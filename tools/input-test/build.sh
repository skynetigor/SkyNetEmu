#!/bin/sh
# Builds the input testers for native aarch64 and x86-64; touch probes also cover i386 for
# programs under Box64. zig is the cross compiler, so no Linux machine is needed.
#   ZIG=/path/to/zig ./build.sh   (default: zig from PATH)
set -e
cd "$(dirname "$0")"
ZIG=${ZIG:-zig}
for name in gamepadtest mousetest touchtest; do
    $ZIG cc -target aarch64-linux-gnu.2.35 -O2 -Wall -o "$name" "$name".c -ldl
    $ZIG cc -target x86_64-linux-gnu.2.17 -O2 -Wall -o "$name"_x64 "$name".c -ldl
done
$ZIG cc -target x86-linux-gnu.2.17 -O2 -Wall -o touchtest_x86 touchtest.c -ldl
cp touchtest_x86 ../../app/src/debug/assets/linux/touchtest_x86
ls -l gamepadtest gamepadtest_x64 mousetest mousetest_x64

# The binaries the app ships are copied into src/debug/assets/linux, so a release build does not carry them.
for name in gamepadtest gamepadtest_x64 mousetest mousetest_x64 touchtest touchtest_x64; do
    cp "$name" ../../app/src/debug/assets/linux/"$name"
done

# xtest is a bare Xlib probe for finding out which events a client really gets. It is not shipped: push it to a
# container by hand. It links against X11 headers and libraries, so point SYSROOT at an unpacked Arch Linux ARM
# libx11 / libxi / xorgproto / libxcb set, and X64LIBS at the x86-64 libX11.so and libXi.so to link against:
#   SYSROOT=/path/sysroot X64LIBS=/path/amd64libs ./build.sh
if [ -n "$SYSROOT" ]; then
    $ZIG cc -target aarch64-linux-gnu.2.40 -O2 -Wall -Wl,--allow-shlib-undefined -I"$SYSROOT/usr/include" \
        -L"$SYSROOT/usr/lib" -o xtest xtest.c -lXi -lX11 -lxcb -lXext -lXau -lXdmcp
    [ -n "$X64LIBS" ] && $ZIG cc -target x86_64-linux-gnu.2.17 -O2 -Wall -Wl,--allow-shlib-undefined \
        -I"$SYSROOT/usr/include" -L"$X64LIBS" -o xtest_x64 xtest.c -lXi -lX11
fi

# libXi decoding probe: only headers are needed, libraries are opened from the guest at runtime.
#   X11_HEADERS=/path/to/sysroot/usr/include ./build.sh
if [ -n "$X11_HEADERS" ]; then
    for pair in aarch64:xitouchtest x86_64:xitouchtest_x64 x86:xitouchtest_x86; do
        arch=${pair%%:*}
        name=${pair#*:}
        $ZIG cc -target "$arch-linux-gnu.2.35" -O2 -Wall -I"$X11_HEADERS" -o "$name" xitouchtest.c -ldl
        cp "$name" ../../app/src/debug/assets/linux/"$name"
    done
fi
