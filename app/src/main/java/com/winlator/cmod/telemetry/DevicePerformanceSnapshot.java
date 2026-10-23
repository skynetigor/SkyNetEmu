package com.winlator.cmod.telemetry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/** Numeric-only, bounded device readings. Never reads game files or enumerates other processes.
 * Missing/denied readings are -1, never fabricated zeroes. Called only on the telemetry worker. */
final class DevicePerformanceSnapshot {
    private static final int MAX_CPUS = 16;
    private final File root;
    private final long[] previousTotal = new long[MAX_CPUS];
    private final long[] previousIdle = new long[MAX_CPUS];

    DevicePerformanceSnapshot() { this(new File("/")); }
    DevicePerformanceSnapshot(File root) { this.root = root; }

    String capture() {
        StringBuilder out = new StringBuilder("v=1");
        Map<String, Long> memory = numbers("proc/meminfo", ":");
        for (String key : new String[]{"MemAvailable", "SwapTotal", "SwapFree"})
            add(out, key + "_kb", memory.getOrDefault(key, -1L));
        Map<String, Long> vm = numbers("proc/vmstat", "\\s+");
        for (String key : new String[]{"pswpin", "pswpout", "pgmajfault"})
            add(out, key, vm.getOrDefault(key, -1L));
        Map<String, Long> self = numbers("proc/self/status", ":");
        add(out, "app_rss_kb", self.getOrDefault("VmRSS", -1L));
        add(out, "app_swap_kb", self.getOrDefault("VmSwap", -1L));
        // PSI totals are cumulative microseconds; compare consecutive samples to find stalls.
        for (String kind : new String[]{"cpu", "memory", "io"}) {
            String pressure = read("proc/pressure/" + kind);
            add(out, kind + "_psi_some_us", pressureTotal(pressure, "some"));
            add(out, kind + "_psi_full_us", pressureTotal(pressure, "full"));
        }
        String stat = read("proc/stat");
        for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
            String prefix = "sys/devices/system/cpu/cpu" + cpu + "/";
            if (!new File(root, prefix).isDirectory()) continue;
            add(out, "cpu" + cpu + "_pct", cpuPercent(stat, cpu));
            add(out, "cpu" + cpu + "_khz", number(prefix + "cpufreq/scaling_cur_freq"));
            add(out, "cpu" + cpu + "_cap_khz", number(prefix + "cpufreq/scaling_max_freq"));
            add(out, "cpu" + cpu + "_max_khz", number(prefix + "cpufreq/cpuinfo_max_freq"));
        }
        add(out, "gpu_hz", firstNumber("sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "sys/class/kgsl/kgsl-3d0/gpuclk"));
        add(out, "gpu_cap_hz", number("sys/class/kgsl/kgsl-3d0/devfreq/max_freq"));
        return out.toString();
    }

    private int cpuPercent(String stat, int cpu) {
        for (String line : stat.split("\n")) {
            if (!line.startsWith("cpu" + cpu + " ")) continue;
            try {
                String[] words = line.trim().split("\\s+");
                if (words.length < 5) return -1;
                long total = 0;
                // guest/guest_nice are already included in user/nice.
                for (int i = 1; i < words.length && i <= 8; i++) total += Long.parseLong(words[i]);
                long idle = Long.parseLong(words[4]) + (words.length > 5 ? Long.parseLong(words[5]) : 0);
                long delta = total - previousTotal[cpu], idleDelta = idle - previousIdle[cpu];
                int pct = previousTotal[cpu] > 0 && delta > 0 && idleDelta >= 0
                        ? (int) Math.max(0, Math.min(100, 100L * (delta - idleDelta) / delta)) : -1;
                previousTotal[cpu] = total;
                previousIdle[cpu] = idle;
                return pct;
            } catch (RuntimeException ignored) { return -1; }
        }
        return -1;
    }

    static long pressureTotal(String text, String category) {
        for (String line : text.split("\n")) {
            if (!line.startsWith(category + " ")) continue;
            for (String word : line.split("\\s+")) {
                if (word.startsWith("total=")) return nonnegative(word.substring(6));
            }
        }
        return -1;
    }

    private Map<String, Long> numbers(String path, String separator) {
        Map<String, Long> values = new HashMap<>();
        for (String line : read(path).split("\n")) {
            String[] pair = line.trim().split(separator, 2);
            if (pair.length == 2) values.put(pair[0], nonnegative(pair[1].trim().split("\\s+")[0]));
        }
        return values;
    }

    private long firstNumber(String... paths) {
        for (String path : paths) {
            long value = number(path);
            if (value >= 0) return value;
        }
        return -1;
    }

    private long number(String path) { return nonnegative(read(path).trim()); }

    private static long nonnegative(String text) {
        try { return Math.max(-1, Long.parseLong(text)); }
        catch (RuntimeException ignored) { return -1; }
    }

    private String read(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(root, path)))) {
            char[] buffer = new char[16384];
            int count = reader.read(buffer);
            return count > 0 ? new String(buffer, 0, count) : "";
        } catch (Exception ignored) { return ""; }
    }

    private static void add(StringBuilder out, String key, long value) {
        out.append(' ').append(key).append('=').append(value);
    }
}
