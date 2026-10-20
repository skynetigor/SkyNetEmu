# Linux shortcut GPU memory reporting

`linuxGpuMemoryMB` is a per-shortcut value: `0` (driver default), `512`, `1024`,
`2048`, `4096`, `8192`, `12288`, or `16384` MiB. The launcher exports
`SKY_GPU_MEMORY_MB` and opts the game's Vulkan loader into
`VK_LAYER_SKYNET_gpu_memory`. Steam itself remains uncapped. Its per-app environment
is applied after client inheritance, including an explicit zero for Default.

The layer reports at most the selected aggregate size across device-local heaps.
It changes legacy/core/KHR memory properties and clamps EXT_memory_budget budgets
consistently. Heap flags, memory types, host-only heaps and real heap usage remain
unchanged. It does not intercept allocation, rendering, submission or presentation.
A selection above the native total never increases the reported heap sizes.

This is a hint for Vulkan games and host Zink, including Box64's wrapped Vulkan and
FEX's GPU thunks. It does not limit total process RAM, reserve RAM, create dedicated
VRAM, change DXGI independently, or guarantee an engine will allocate less memory.
Software OpenGL does not use this Vulkan layer. Restart the game to apply changes.

The four packaged layer ABIs are glibc aarch64, x86_64, i686 and Android aarch64
(for the experimental Bionic path). Native/thunked loaders use aarch64; unthunked
FEX loaders use their guest ABI. Steam's existing 32-bit game detection selects
host aarch64 for OpenGL thunks and i686 otherwise.

Build and verify with the installed Zig compiler and Android NDK Vulkan headers:

```
python3 tools/gpu-memory/build.py --test
bash tools/gpu-memory/shell_test.sh
```

`ANDROID_NDK_HOME` overrides the local NDK path. The test build creates smoke
executables in androidTest assets. After installing the debug and test APKs, run:

```
adb shell am instrument -w -e gpuMemory 1 \
  com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
```

This checks shortcut save/reload, duplication, imported settings, override isolation,
other-layer preservation, and Default/2 GiB/4 GiB/Default queries through the installed
Linux Turnip driver. It uses temporary fixtures, without modifying game shortcuts.
