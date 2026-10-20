#!/bin/sh
set -eu
[ "$(uname -m)" = aarch64 ] || { echo 'An ARM64 Linux build container is required' >&2; exit 1; }
cmake -S /work/generator -B /work/build -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang-18 -DCMAKE_CXX_COMPILER=clang++-18 \
    -DClang_DIR=/usr/lib/llvm-18/lib/cmake/clang -DLLVM_DIR=/usr/lib/llvm-18/lib/cmake/llvm \
    -DCLANG_RESOURCE_DIR=/usr/lib/llvm-18/lib/clang/18
cmake --build /work/build --target thunkgen -j4
/work/build/generator/thunkgen /work/FEX/ThunkLibs/libvulkan/libvulkan_interface.cpp \
    libvulkan -host /work/thunkgen_host_libvulkan.inl / -- -std=c++20 \
    -DARCHITECTURE_arm64=1 -isystem /work/FEX/ThunkLibs/include \
    -isystem /work/FEX/External/Vulkan-Headers/include
clang++-18 -std=c++20 -shared -fPIC -O2 -fwrapv -DARCHITECTURE_arm64=1 \
    -I/work -I/work/FEX/ThunkLibs/include -I/work/FEX/External/Vulkan-Headers/include \
    -I/work/FEX/FEXCore/include -I/work/FEX/FEXHeaderUtils -I/work/FEX/External/fmt/include \
    /work/FEX/ThunkLibs/libvulkan/Host.cpp -ldl -o /work/libfex_vulkan_host_2610.so
llvm-strip-18 --strip-unneeded /work/libfex_vulkan_host_2610.so
