package com.winlator.cmod.telemetry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class DevicePerformanceSnapshotTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void write(Path root, String path, String value) throws Exception {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("telemetry-device-");
        try {
            DevicePerformanceSnapshot sampler = new DevicePerformanceSnapshot(root.toFile());
            String unavailable = sampler.capture();
            check(unavailable.contains("MemAvailable_kb=-1"), "unreadable memory stays unknown");
            check(unavailable.contains("gpu_hz=-1"), "unreadable clocks stay unknown");
            write(root, "proc/meminfo", "MemAvailable: 1200000 kB\nSwapTotal: 4000000 kB\nSwapFree: 3000000 kB\n");
            write(root, "proc/vmstat", "pswpin 10\npswpout 20\npgmajfault 30\n");
            write(root, "proc/self/status", "Name: private-game\nVmRSS: 123456 kB\nVmSwap: 789 kB\n");
            write(root, "proc/pressure/memory", "some avg10=2.00 avg60=0.00 avg300=0.00 total=1234\nfull avg10=1.00 total=456\n");
            write(root, "sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "2000000\n");
            write(root, "sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq", "3000000\n");
            write(root, "sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq", "4000000\n");
            write(root, "proc/stat", "cpu 100 0 0 100 0 0 0 0\ncpu0 100 0 0 100 0 0 0 0 500 500\n");
            String first = sampler.capture();
            check(first.contains("cpu0_pct=-1"), "first CPU reading has no delta");
            check(first.contains("memory_psi_some_us=1234") && first.contains("memory_psi_full_us=456"), "PSI categories stay distinct");
            check(first.contains("app_rss_kb=123456") && first.contains("app_swap_kb=789"), "app memory is separate from device memory");
            check(first.contains("pswpin=10") && first.contains("SwapFree_kb=3000000"), "swap evidence survives");
            check(!first.contains("private-game"), "process names never enter snapshots");
            write(root, "proc/stat", "cpu0 175 0 0 125 0 0 0 0 1000 1000\n");
            check(sampler.capture().contains("cpu0_pct=75"), "per-core load excludes duplicated guest counters");
            write(root, "proc/stat", "cpu0 1 0 0 1\n");
            check(sampler.capture().contains("cpu0_pct=-1"), "counter reset does not masquerade as idle");
            write(root, "sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "denied-or-malformed\n");
            write(root, "sys/class/kgsl/kgsl-3d0/gpuclk", "800000000\n");
            check(sampler.capture().contains("gpu_hz=800000000"), "GPU frequency fallback works");
            for (int cpu = 0; cpu < 32; cpu++) {
                write(root, "sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_cur_freq", "9999999\n");
            }
            String bounded = sampler.capture();
            check(!bounded.contains("cpu16_"), "CPU enumeration is capped");
            check(bounded.length() < 3500, "device fields leave room for Android readings within the 4096 limit");
            check(DevicePerformanceSnapshot.pressureTotal("some total=garbage", "some") == -1, "malformed pressure stays unknown");
            check(Scrub.field("skynet.performance.diagnostics", "x".repeat(9000)).length() == 4096, "performance payload is bounded");
            System.out.println("PASS device performance missing data, CPU deltas, PSI, swap, privacy and bounds");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
