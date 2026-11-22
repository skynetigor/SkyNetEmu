package com.winlator.cmod.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Per-core CPU load and clock between consecutive {@link #sample} calls, for the advanced HUD.
 *
 * The per-core lines of /proc/stat are used where the system lets the app read them; Android's
 * SELinux usually does not, so the fallback is each core's cpuidle residency — the same source
 * {@link CpuUsageSampler} uses for the whole-device number — read per core:
 * busy = 1 - idle time / wall time. An offline core reports 0 busy and 0 MHz. One instance per
 * reading thread; each keeps its own baselines.
 */
public final class CpuCoreStats {
    private final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
    private boolean procStatDenied;
    private final long[] prevTotal = new long[cores];
    private final long[] prevIdle = new long[cores];
    private final long[] prevIdleMicros = new long[cores];
    private long prevNanos;
    private boolean seeded;

    public CpuCoreStats() {
        java.util.Arrays.fill(prevTotal, -1L);
        java.util.Arrays.fill(prevIdleMicros, -1L);
    }

    public int coreCount() {
        return cores;
    }

    /**
     * Fills {@code busy} (percent 0-100, -1 while unknown) and {@code mhz} (current clock, 0 for an
     * offline or unreadable core). Both arrays must be {@link #coreCount()} long.
     */
    public synchronized void sample(int[] busy, short[] mhz) {
        boolean[] online = onlineMask();
        for (int i = 0; i < cores; i++) {
            int khz = online[i] ? FileUtils.readInt("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq") : 0;
            mhz[i] = (short) (khz > 0 ? khz / 1000 : 0);
        }
        if (!procStatDenied && fromProcStat(busy, online)) return;
        fromCpuIdle(busy, online);
    }

    /** Per-core busy from the cpuN lines of /proc/stat. False when the file cannot be used at all. */
    private boolean fromProcStat(int[] busy, boolean[] online) {
        java.util.Arrays.fill(busy, -1);
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line;
            boolean any = false;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("cpu") || line.length() < 4 || !Character.isDigit(line.charAt(3))) continue;
                String[] parts = line.trim().split("\\s+");
                int core = Integer.parseInt(parts[0].substring(3));
                if (core < 0 || core >= cores || parts.length < 5) continue;
                long total = 0L;
                for (int i = 1; i < parts.length && i <= 8; i++) total += Long.parseLong(parts[i]);
                long idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0L);
                long oldTotal = prevTotal[core], oldIdle = prevIdle[core];
                prevTotal[core] = total;
                prevIdle[core] = idle;
                if (oldTotal >= 0L && total > oldTotal) {
                    long deltaTotal = total - oldTotal;
                    busy[core] = clamp((int) ((deltaTotal - Math.max(0L, idle - oldIdle)) * 100L / deltaTotal));
                }
                any = true;
            }
            if (!any) procStatDenied = true;   // the aggregate-only /proc/stat of some kernels: use cpuidle
            for (int i = 0; i < cores; i++) if (!online[i]) busy[i] = 0;
            return any;
        } catch (Exception e) {
            procStatDenied = true;
            return false;
        }
    }

    /** Per-core busy from cpuidle residency: busy = 1 - idle time / wall time. */
    private void fromCpuIdle(int[] busy, boolean[] online) {
        long now = System.nanoTime();
        long[] idleMicros = new long[cores];
        for (int i = 0; i < cores; i++) idleMicros[i] = online[i] ? coreIdleMicros(i) : -2L;
        long oldNanos = prevNanos;
        boolean comparable = seeded && now > oldNanos;
        for (int i = 0; i < cores; i++) {
            if (!online[i]) {
                busy[i] = 0;
            } else if (idleMicros[i] < 0L || !comparable || prevIdleMicros[i] < 0L) {
                busy[i] = -1;
            } else {
                double wallMicros = (now - oldNanos) / 1000.0;
                double idle = Math.max(0L, idleMicros[i] - prevIdleMicros[i]);
                // a sleep is accounted when the core wakes, so a short window can read over 100% idle
                busy[i] = clamp((int) Math.round(100.0 * (1.0 - Math.min(1.0, idle / wallMicros))));
            }
            prevIdleMicros[i] = idleMicros[i] >= 0L ? idleMicros[i] : -1L;
        }
        prevNanos = now;
        seeded = true;
    }

    private long coreIdleMicros(int core) {
        File[] states = new File("/sys/devices/system/cpu/cpu" + core + "/cpuidle")
                .listFiles((dir, name) -> name.startsWith("state"));
        if (states == null || states.length == 0) return -1L;
        long sum = 0L;
        for (File state : states) {
            long value = readLong(new File(state, "time"));
            if (value < 0L) return -1L;
            sum += value;
        }
        return sum;
    }

    private static long readLong(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line != null ? Long.parseLong(line.trim()) : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private boolean[] onlineMask() {
        boolean[] online = new boolean[cores];
        String ranges = readLine("/sys/devices/system/cpu/online");
        if (ranges == null) {
            java.util.Arrays.fill(online, true);
            return online;
        }
        try {
            for (String range : ranges.trim().split(",")) {
                if (range.isEmpty()) continue;
                int dash = range.indexOf('-');
                int first = Integer.parseInt(dash < 0 ? range : range.substring(0, dash));
                int last = dash < 0 ? first : Integer.parseInt(range.substring(dash + 1));
                for (int core = first; core <= last && core < cores; core++) if (core >= 0) online[core] = true;
            }
        } catch (NumberFormatException e) {
            java.util.Arrays.fill(online, true);
        }
        return online;
    }

    private static String readLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
