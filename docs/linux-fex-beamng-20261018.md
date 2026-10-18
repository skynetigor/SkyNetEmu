# BeamNG FEX tuning investigation — October 18, 2026

## Result

The tested overrides have not established a repeatable improvement over the existing Performance preset for five-vehicle physics. Keep the current default. This is a bounded comparison, not proof that the configuration is globally optimal or that Android has reached its hardware limit.

The proot-free path still cannot complete initialization, so there is no measured speedup from removing proot. No experimental launch change should be shipped from this investigation.

## Setup and measurement limits

- Connected Samsung SM-F976B/q8q, Oryon-3; BeamNG 0.39.4.0 Linux; installed `fex-2610` and Ubuntu 24.04 rootfs, `bannerlator-r9` runtime.
- Temporary shortcut, separate from the user's original BeamNG shortcut. Performance preset; disk cache off; the four optional app performance-tuning controls explicitly off for this comparison. Test wrapper calls the game's built-in `lua/console/bananabench` physics benchmark with `pickup`, counts 1 through 5.
- No rendering, traffic AI, map workload, UI or Vulkan calls in this headless test. It does not test the Vulkan thunk or diagnose the Xiaomi device directly.
- The game's benchmark advances physics at 2,000 Hz. It reports **minimum batch time multiplied by batch count**, rather than elapsed mean or a latency percentile. Values below are its optimistic percentage of real-time simulation throughput, **not FPS**. 100% means matching simulated time in this isolated benchmark.
- Each run increases vehicle count, so later counts also experience different elapsed load and CPU limits. No reported physics instability marker appeared in the completed logs.
- CPU frequency caps changed substantially during normal runs, sometimes ending around 1 GHz. Thermal service status was 0 in sampled captures; that does not identify why the caps changed. Do not attribute all changes to thermal throttling or battery saver.
- Android fixed-performance mode was tested as a control. It reduced the clock range on this phone, but the sampled caps still varied (about 1–2 GHz in the final pair); it did not provide a constant-frequency control or maximum-performance setting. It was disabled again after the comparison.

## Results

All numeric columns are the built-in benchmark's percentage of real time.

| Run / override | Android mode | 1 pickup | 2 | 3 | 4 | 5 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1792354819-baseline | normal | 856.8 | 450.5 | 399.1 | 167.4 | 124.0 |
| 1792354885-static-l1 | normal | 899.7 | 482.0 | 227.3 | 128.9 | 121.5 |
| 1792354955-small-blocks | normal | 868.7 | 821.1 | 188.9 | 57.6 | 76.8 |
| 1792355070-no-avx | normal | 857.8 | 796.8 | 203.0 | 140.4 | 111.8 |
| 1792355138-baseline | normal | 814.9 | 223.8 | 178.3 | 134.3 | 115.5 |
| 1792355311-baseline | fixed | 242.8 | 199.6 | 114.6 | 125.0 | 124.6 |
| 1792355397-large-blocks | fixed | 251.6 | 201.2 | 125.0 | 121.5 | 105.9 |
| 1792356003-single-block | normal | 668.0 | 648.4 | 304.6 | 133.1 | 125.3 |
| 1792356082-tso | normal | invalid | 428.5 | 126.8 | 115.3 | 102.4 |
| 1792356161-baseline | normal | 883.0 | 416.4 | 140.0 | 156.4 | 150.9 |

Overrides relative to Performance:

- `static-l1`: `FEX_DYNAMICL1CACHE=0`.
- `small-blocks`: `FEX_MAXINST=1000`.
- `large-blocks`: `FEX_MAXINST=10000`.
- `no-avx`: `FEX_HOSTFEATURES=disableavx`.
- `single-block`: `FEX_MULTIBLOCK=0`.
- `tso`: `FEX_TSOENABLED=1 FEX_HALFBARRIERTSOENABLED=1`; remaining Performance options retained. This is not the full Compatibility preset.

Normal-mode five-vehicle baseline results ranged from about 115% to 151%. The TSO control also produced an implausible 265929% one-vehicle value; that cell is not usable evidence. This reinforces the need to avoid relying on minimum-batch timing alone. Smaller blocks, disabling AVX and larger blocks did not show a useful five-vehicle win. The large variation with one to three vehicles and changing CPU caps prevents assigning small changes to a particular FEX option.

## Sustained timing follow-up

The saved [benchmark harness](../tools/linux-fex/beamng-physics-bench.lua) replaces the minimum-batch result with elapsed time around the original helper, subtracting spawning time. It records both timings. Each run below uses only five pickups: 200 batches represent approximately 10 seconds of simulated physics, following a separate warmup.

| Run | Measured seconds | Sustained % real time | Median CPU caps, MHz (two policies) |
| --- | ---: | ---: | --- |
| 1792356277-mean-baseline | 8.03 | 124.5% | 2746 / 2669 |
| 1792356304-mean-tso | 24.64 | 40.6% | 1555 / 1498 |
| 1792356345-mean-baseline | 19.45 | 51.4% | 1786 / 1747 |
| 1792356404-mean-fixed-baseline | 14.66 | 68.2% | 998 / 1018 |
| 1792356456-mean-fixed-tso | 25.44 | 39.3% | 1114 / 1133 |
| 1792356541-mean-harness | 26.42 | 37.9% | 1555 / 1498 |

Caps are sampled across the whole process, including loading and warmup, rather than just the timed loop. Normal-mode baseline variation alone is large; do not attribute the full difference between normal-mode TSO and baseline to FEX. In the pair with fixed-performance mode requested, TSO-off was faster, but even that mode did not pin a constant clock. These runs support retaining the existing preset and investigating scheduling/frequency policy before claiming a tuning win. They do not show that five active traffic cars can sustain real time in gameplay.

The exact saved Lua harness completed on the phone with result and end markers. Its final normal-mode result is a smoke test, not an additional configuration candidate. No `INSTABILITY` marker appeared in the sustained runs.

## Proot-free initialization experiment

A temporary native ARM shell script launched the installed ARM FEX binary through the app's existing direct launch path. It did not run the game through Box64. With the normal empty rootfs `dev`, `proc` and `sys` directories, BeamNG exited with `Could not initialize UDEV` / `Failed to initialize beamng app`.

After explicit user approval, the three empty directories were preserved under temporary names and replaced with links to the phone's `/dev`, `/proc` and `/sys`. The immediate UDEV error disappeared, but initialization stalled before the benchmark's start marker and was stopped. All three links were removed and their original directories restored. This does not prove UDEV was fully initialized or identify the remaining stall.

The existing direct path interposes libc file operations, while FEX can issue guest system calls directly. The earlier X11 socket-path failure remains another blocker for graphical direct sessions. A native FEX integration needs correct guest path and socket handling, not just removal of the `runsOnFex` proot guard.

## What to improve next

1. Profile the full game with identical traffic in FEX and Box64, recording per-thread CPU, scheduling and frequency limits alongside physics-paused and active intervals. The headless test omits AI and synchronization with the renderer, so its five-vehicle result cannot explain gameplay's 15 FPS by itself.
2. Use FEX JIT symbol naming with a compatible profiler to determine whether hot time is translated physics/SIMD, memory-ordering code, guest synchronization or host syscalls. A larger block size or forced ISA feature should follow a measured hot path.
3. Treat proot removal as a compatibility project: resolve native initialization, raw guest path/socket syscalls and Vulkan thunk loading first, then measure gameplay. An Android-native FEX build without functioning GPU thunks is not a substitute for the current Vulkan path.
4. Retain Vulkan thunks and the current Performance preset for now. TSO-off is a compatibility tradeoff, not an unconditional safe optimization; use stronger ordering if the game exhibits correctness problems. Box64 variables do not tune FEX.

## Evidence and restoration

Raw logs, JSON results, thread/frequency samples and local harness are under `/tmp/skynet-fex-beamng-20261018`. The shareable evidence bundle excludes unrelated device logcat, app preferences, and copied game Lua source. No game binary was modified. Cleanup restored the original FEX configuration and all three rootfs directories, disabled Android fixed-performance mode, and removed the temporary shortcut and scripts. The original shortcut and saved game settings matched their pre-test copies byte for byte. The persisted game settings were not changed by this headless test.
