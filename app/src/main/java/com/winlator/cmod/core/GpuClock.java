package com.winlator.cmod.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * The GPU's current clock in MHz, from whichever of the usual sysfs files the device lets the app
 * read (Adreno kgsl, devfreq, Mali and MediaTek vendor nodes). Mirrors {@link GpuLoad}'s pattern:
 * {@link #detect} finds the candidates once, {@link #read} returns the first that answers, and after
 * three failed reads in a row the clock counts as {@link #unavailable} and nothing is read again.
 * Values are normalized from Hz or kHz to MHz by magnitude. Not thread-safe: one reader per thread.
 */
public final class GpuClock {
    private static final String[] STATIC_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpuclk",             // Adreno, Hz
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",   // Adreno devfreq, Hz
            "/sys/class/kgsl/kgsl-3d0/clock_mhz",          // some kernels, already MHz
            "/sys/kernel/gpu/gpu_clock",                   // Mali vendor node, MHz
            "/sys/devices/platform/gpusysfs/gpu_clock",    // Exynos Mali vendor node, MHz
            "/sys/class/misc/mali0/device/clock",          // Mali, Hz or kHz
            "/sys/class/devfreq/gpu/cur_freq",             // generic devfreq gpu, Hz
            "/sys/kernel/ged/hal/current_freqency",        // MediaTek GED (the vendor's spelling), kHz
            "/sys/kernel/ged/hal/gpu_clock",               // MediaTek GED alt, MHz
    };

    private static final String[] NODE_TOKENS = {
            "gpu", "mali", "g3d", "kgsl", "panfrost", "pvr", "powervr", "xclipse", "sgpu"
    };

    private String[] paths = new String[0];
    private boolean unavailable;
    private int consecutiveFailures;

    /** Looks for the files that report the clock. */
    public void detect() {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String path : STATIC_PATHS) {
            try {
                if (new File(path).canRead()) found.add(path);
            } catch (Exception ignored) {}
        }
        scanDevfreq(new File("/sys/class/devfreq"), found);
        scanDevfreq(new File("/sys/devices/virtual/devfreq"), found);
        paths = found.toArray(new String[0]);
        unavailable = paths.length == 0;
    }

    public boolean unavailable() {
        return unavailable;
    }

    /** The clock in MHz, or -1 when this read failed (unavailable after the third failure in a row). */
    public int read() {
        if (unavailable) return -1;
        for (String path : paths) {
            long raw = readLong(path);
            if (raw <= 0L) continue;
            long mhz;
            if (raw >= 1_000_000L) mhz = raw / 1_000_000L;  // Hz
            else if (raw >= 10_000L) mhz = raw / 1_000L;    // kHz
            else mhz = raw;                                 // already MHz
            if (mhz >= 1L && mhz <= 5000L) {
                consecutiveFailures = 0;
                return (int) mhz;
            }
        }
        if (++consecutiveFailures >= 3) unavailable = true;
        return -1;
    }

    private void scanDevfreq(File root, LinkedHashSet<String> found) {
        try {
            if (!root.isDirectory()) return;
            File[] nodes = root.listFiles(File::isDirectory);
            if (nodes == null) return;
            for (File node : nodes) {
                String lower = node.getPath().toLowerCase(Locale.US);
                boolean gpu = false;
                for (String token : NODE_TOKENS) {
                    if (lower.contains(token)) { gpu = true; break; }
                }
                if (!gpu) continue;
                File candidate = new File(node, "cur_freq");
                if (candidate.canRead()) found.add(candidate.getPath());
            }
        } catch (Exception ignored) {}
    }

    private static long readLong(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line != null ? Long.parseLong(line.trim().split("\\s+")[0]) : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }
}
