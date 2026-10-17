# Box64 for glibc programs on Android's libc

The Box64 that `linux/LinuxBionicSession` starts (`app/src/main/assets/linux/box64-bionic`): Box64 v0.4.4 built with the NDK as an Android (bionic) executable, plus
`patches/0001-android-box64-for-glibc-programs.patch`, which makes the Box64 wrapper of `libc.so.6` behave the way a *glibc* x86-64 program expects on top of
bionic. What the patch changes, and why, is in [docs/linux-bionic.md](../../docs/linux-bionic.md).

    ANDROID_NDK_HOME=/path/to/ndk-r29 ./build.sh            # writes app/src/main/assets/linux/box64-bionic

`patches/` holds one patch, made with `git diff` against the `v0.4.4` tag (the generated wrapper files under `src/wrapped/generated` are part of it: rebuilding them
with `rebuild_wrappers.py` after editing a `*_private.h` file is a step of changing the patch; `GOM(name, sig) //%noE` entries must come in flat `#ifdef ANDROID` /
`#else` / `#endif` pairs with the same function name on both sides, or the script refuses them).

`../linux-shim/libskynogl_x64.so` (source `skynogl.c`) stands in for the OpenGL libraries of the root filesystem.
