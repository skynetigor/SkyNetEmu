package com.winlator.cmod.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Whole-device CPU utilization between consecutive {@link #sample()} calls.
 *
 * Android blocks apps from reading /proc/stat (SELinux proc_stat). The fallback uses per-core
 * cpuidle residency (/sys/devices/system/cpu/cpuN/cpuidle/stateM/time, microseconds), which apps
 * can read: usage = 1 - idle time / (wall time * online cores). Clock frequency is never used.
 * Each instance keeps its own baseline, so independent readers do not disturb each other.
 */
public final class CpuUsageSampler {
    public interface Clock {
        long nanoTime();
    }

    private final String procStat;
    private final File cpuRoot;
    private final Clock clock;
    private boolean procStatDenied;
    private long previousTotal = -1L, previousIdle = -1L;
    private String previousCores;
    private long previousIdleMicros = -1L, previousNanos;

    public CpuUsageSampler() {
        this("/proc/stat", new File("/sys/devices/system/cpu"), System::nanoTime);
    }

    public CpuUsageSampler(String procStat, File cpuRoot, Clock clock) {
        this.procStat = procStat;
        this.cpuRoot = cpuRoot;
        this.clock = clock;
    }

    /** Percent 0-100, or -1 when unknown (first call, or no readable source). */
    public synchronized int sample() {
        if (!procStatDenied) {
            int usage = fromProcStat();
            if (!procStatDenied) return usage;
        }
        return fromCpuIdle();
    }

    private int fromProcStat() {
        String line;
        try (BufferedReader reader = new BufferedReader(new FileReader(procStat))) {
            line = reader.readLine();
        }
        catch (Exception e) {
            procStatDenied = true;
            return -1;
        }
        try {
            String[] parts = line != null ? line.trim().split("\\s+") : new String[0];
            if (parts.length < 5 || !"cpu".equals(parts[0])) return -1;
            long total = 0L;
            for (int i = 1; i < parts.length && i <= 8; i++) total += Long.parseLong(parts[i]);
            long idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0L);
            long oldTotal = previousTotal, oldIdle = previousIdle;
            previousTotal = total;
            previousIdle = idle;
            if (oldTotal < 0L || total <= oldTotal) return -1;
            long deltaTotal = total - oldTotal;
            return clamp((int) ((deltaTotal - Math.max(0L, idle - oldIdle)) * 100L / deltaTotal));
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    private int fromCpuIdle() {
        long now = clock.nanoTime();
        List<Integer> cores = onlineCores();
        long idleMicros = 0L;
        for (int core : cores) {
            long coreIdle = coreIdleMicros(core);
            if (coreIdle < 0L) {
                previousIdleMicros = -1L;
                return -1;
            }
            idleMicros += coreIdle;
        }
        String coreKey = cores.toString();
        boolean comparable = !cores.isEmpty() && previousIdleMicros >= 0L && coreKey.equals(previousCores);
        long oldIdle = previousIdleMicros, oldNanos = previousNanos;
        previousIdleMicros = cores.isEmpty() ? -1L : idleMicros;
        previousNanos = now;
        previousCores = coreKey;
        if (!comparable || now <= oldNanos) return -1;
        double capacityMicros = (now - oldNanos) / 1000.0 * cores.size();
        double idle = Math.max(0L, idleMicros - oldIdle);
        // The kernel accounts a sleep when the core wakes up, so a short window can be over 100% idle.
        return clamp((int) Math.round(100.0 * (1.0 - Math.min(1.0, idle / capacityMicros))));
    }

    private long coreIdleMicros(int core) {
        File[] states = new File(cpuRoot, "cpu" + core + "/cpuidle")
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

    private List<Integer> onlineCores() {
        List<Integer> cores = new ArrayList<>();
        String online = readLine(new File(cpuRoot, "online"));
        if (online == null) return cores;
        try {
            for (String range : online.trim().split(",")) {
                if (range.isEmpty()) continue;
                int dash = range.indexOf('-');
                int first = Integer.parseInt(dash < 0 ? range : range.substring(0, dash));
                int last = dash < 0 ? first : Integer.parseInt(range.substring(dash + 1));
                for (int core = first; core <= last; core++) cores.add(core);
            }
        }
        catch (NumberFormatException e) {
            cores.clear();
        }
        return cores;
    }

    private static long readLong(File file) {
        String line = readLine(file);
        try {
            return line != null ? Long.parseLong(line.trim()) : -1L;
        }
        catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String readLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        }
        catch (Exception e) {
            return null;
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
