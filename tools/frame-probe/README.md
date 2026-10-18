# Opt-in frame pipeline diagnostics

Enable **Settings → Performance tuning → Frame pipeline diagnostics**, then fully restart the game. A compact touch-through overlay shows live rates during gameplay; details are also in the in-game **Graphics → Performance tuning** dialog. The existing opt-in Linux performance telemetry includes the same cumulative counters and sample timestamps; this feature does not independently enable uploads. It is off by default.

Remove experimental presentation overrides such as `MESA_VK_WSI_PRESENT_MODE=immediate` before comparing. The probe never sets an ICD, present mode, swapchain size, or a GPU clock policy. Disable frame generation for this comparison: with additional Vulkan layers, the guest probe observes presents at its own chain position, which can include generated frames.

## What is counted

- `guest_queued`: successful/suboptimal `vkQueuePresentKHR` **swapchain presents** observed by an aarch64 glibc Vulkan layer inside the guest loader. It also records attempts, failures, acquire/present CPU wall time, the X11 surface window ID, actual requested game-side present mode, and minimum image count. This is a queued-present count, not proof of completed GPU rendering.
- `received`: X Present requests for the selected game window reaching our X server. Invalid/missing pixmaps may subsequently fail.
- `pipe_accepted`: accepted DMA-BUF/AHB source updates for that game content. Each update advances a sequence, even if it reuses the same image/buffer.
- `pipe_unique`: distinct source updates included in successful/suboptimal Android presents. These are source sequence identities, not pixel hashes or GPU-completion counts.
- `pipe_repeats`: successful Android presents reusing the last queued source sequence (for example cursor redraws).
- `pipe_skipped`: source updates superseded before being included in a successful Android present. Counts overwritten source updates, not a driver-reported dropped-frame statistic.
- `pipe_android_queued`: successful/suboptimal Android presents containing the tracked game source.
- `pipe_displayed`: surface presentation records with a nonzero `actualPresentTime` from optional `VK_GOOGLE_display_timing`, only while feedback IDs remain complete. The driver keeps a bounded history: missing IDs or swapchain recreation make this **N/A / -1**, rather than misreporting the rate of the few retained records as display FPS. Unsupported, failed, or not-yet-observed feedback also remains unavailable. This is feedback for the **whole Android surface**, which may include startup/overlay frames, not a unique-game-frame count or a physical pixel measurement.

Counters are sampled on a background worker roughly once per second; the overlay uses a rolling window of about five seconds (shorter during warm-up). The guest publishes cumulative numeric snapshots at most once per second per swapchain (plus creation/destruction), so short intervals have sampling skew. Raw counts, guest snapshot age and timestamps allow longer-window comparisons. No per-frame log stream, GPU readback, `vkDeviceWaitIdle`, or extra fence wait is introduced. Counting/timing still has overhead; benchmark with the option off/on when evaluating its cost.

The native compositor counter state uses its existing render mutex. Guest counters match the selected X11 window so CEF/Steam/other surfaces are not silently counted as gameplay. No matching probe reports means **unavailable**, not measured zero. Matching, unknown and unmatched surface counts help distinguish discovery problems. Swapchain recreation contributes separate reports within a fresh private directory for each session. Concurrent graphics layers can change which presents the probe sees.

The guest probe is enabled only for the 64-bit Box64/glibc path; FEX and bionic are not instrumented by this library. The Steam launcher strips this explicit layer for 32-bit games. Android compositor/receiver counters can still work on other paths. Completed frames that are never presented, GPU execution times and CPU-thread stalls require additional instrumentation.

## Build and checks

```sh
python3 tools/frame-probe/build.py --headers /path/to/NDK/sysroot/usr/include --test
clang++ -std=c++17 tools/frame-probe/counters_test.cpp -o /tmp/frame-counters-test
/tmp/frame-counters-test
```

The build copies only Vulkan/vk_video headers into a temporary include root, avoiding accidental use of NDK bionic libc headers when building a glibc library. It uses Zig, targets glibc 2.35, and bundles the result at `assets/linux/libVkLayer_skynet_frame_probe.so`. `--test` runs a mock next-layer test and builds the ARM64 Linux-loader smoke program for the test APK. The C layer is MIT licensed; Vulkan headers retain their upstream licenses and are not copied into this repo.

Build/install the debug and androidTest APKs, then:

```sh
adb -s DEVICE shell am instrument -w -e framePipeline 1 \
  com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
```

This checks opt-in environment preservation, per-window filtering, unavailable display feedback, the installed Linux Vulkan loader/device chain (requires an existing runtime and staged seccomp-compatible libc), and real offscreen Android rendering. The native test checks source reuse, cursor-only repeats and rejected imports. Temporary diagnostic settings are restored. These checks do not establish overhead or frame rates in a game on Xiaomi.
