# Performance tuning

Global defaults are in Settings → Performance tuning. Controls start off. GPU turbo is currently disabled following a severe device regression. Linux and Windows shortcut editors expose Inherit global / On / Off for each setting. The in-game graphics sidebar opens the same controls and applies edits immediately. A session launched without a shortcut uses the global defaults.

- **Sustained performance** calls Android's window API only when the device reports support. It asks the OEM for a sustainable policy; it can reduce peak speed.
- **Boost thread priority** tries nice -8, -4, then -2 for same-UID guest process descendants and selected application audio/render workers. It reads back priority, preserves already higher priorities, and reports denied operations.
- **Prefer performance cores** uses maximum CPU frequencies, or CPU capacities when those readings cover more cores. Cores at least 70% of the maximum and unknown cores remain eligible. The resulting mask intersects each thread's original allowed mask. It never widens a game's CPU selection. Standard Snapdragon 8 Elite frequencies retain all eight cores under this rule.
- **GPU turbo** is disabled in global and shortcut UI, the session controller ignores saved On values, and JNI returns `EOPNOTSUPP` before any device IO for enable requests. Legacy `KGSL_PROP_PWRCTRL` enable calls returned success but caused severe lag on SM-F976B. Only release/recovery of old requests remains enabled. A successful ioctl did not establish compatibility or improved performance.

Thread policies are checked on a background worker every five seconds while priority or core tuning is requested. Initial tuning waits for the launcher's normal affinity setup. New guest threads/processes are discovered, including inherited policies. Original nice values and affinities are restored when disabled, backgrounded (except picture-in-picture), or closed. Thread start times guard against PID reuse. Restoration avoids overwriting a policy that another component has changed since our request. Kernel denials are reported rather than treated as success.

GPU requests are device-global and cannot be queried for their previous policy through this API. The app releases its accepted request on suspension/exit. A persisted marker permits recovery on the next app startup after an unexpected kill; immediate cleanup after a hard kill cannot be guaranteed.

The live dialog shows support and operation results. Existing opt-in Linux performance telemetry includes bounded numeric tuning diagnostics; Wine session summaries include the same field. There is no per-frame tuning or additional telemetry upload loop. The five-second process scan has some overhead when CPU tuning is enabled; game FPS comparisons are still needed to establish its practical cost and benefit on each device.

## Device checks

Build `:app:assembleDebug :app:assembleDebugAndroidTest`, install both APKs, then run:

```sh
adb -s DEVICE shell am instrument -w -e performanceTuning 1 \
  com.winlator.cmod.dev2.test/com.winlator.cmod.SharedLinuxInstrumentation
```

This mode uses a temporary cache shortcut and child processes. It checks inheritance, core selection, JNI affinity changes/readback, priority behavior, live Off, suspend/resume/exit restoration, newly inherited descendant cleanup, both Compose cards, blocked KGSL enable requests, and legacy recovery. It restores the one global preference used by the checks and leaves user containers and game shortcuts untouched. It does not measure game FPS.

Verified on 2026-10-18 on Samsung SM-F976B: debug build/install and the device checks passed. The controlled child changed from nice 0 to -8 and restored to 0; affinity and newly inherited descendant policies restored on exit; KGSL enable/release returned success and recovery markers cleared. Android reported sustained performance mode unsupported. No game FPS benchmark was performed.

Regression follow-up: the user reported severe lag and 0% GPU load when GPU turbo was enabled. The earlier tests verified API acceptance/restoration only, and were insufficient to establish safe performance under a game workload. The backend is now quarantined; updated checks must verify enable blocking and legacy cleanup without re-enabling the override. The precise cause of the slowdown is unconfirmed.

Telemetry for the reported regression (2026-10-18 UTC): turbo requested at 16:32–16:34, GPU load reported 0%, and accepted frame deltas fell to about 9 frames/s during 16:33:15–16:34:15. A following turbo-Off run reported GPU load 94–98% after recovery and about 29–47 accepted frames/s. Different runs/scenes were not controlled, so these data corroborate the report but do not isolate the underlying kernel/firmware mechanism.
