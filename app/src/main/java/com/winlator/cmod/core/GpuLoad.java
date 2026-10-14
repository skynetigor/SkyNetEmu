package com.winlator.cmod.core;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * The GPU's load in percent, from whichever of the usual sysfs files the device has (Adreno kgsl, Mali, PowerVR, MediaTek, devfreq).
 * Finds the files once with {@link #detect}; {@link #read} then reads the first that answers. After three failed reads in a row the GPU
 * counts as {@link #unavailable} and nothing is read again. Not thread-safe: one reader per thread.
 */
public final class GpuLoad {
    private static final String[] STATIC_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/gpuinfo",
            "/sys/devices/platform/mali/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/devices/platform/gpusysfs/gpu_busy",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/pvr/utilisation",
            "/sys/class/pvr/gpu_utilisation",
            "/sys/class/drm/card0/device/gpu_busy_percent",
            "/sys/class/devfreq/gpu/load",
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/module/ged/parameters/gpu_loading",
            "/proc/mtk_mali/utilization"
    };

    private static final String[] USAGE_FILES = {
            "gpu_busy_percentage", "gpu_busy_percent", "gpu_load", "utilisation",
            "utilization", "load", "gpu_busy", "gpuinfo"
    };

    private static final String[] NODE_TOKENS = {
            "gpu", "mali", "g3d", "kgsl", "panfrost", "pvr", "powervr", "xclipse", "sgpu"
    };

    private String[] paths = new String[0];
    private boolean unavailable;
    private int consecutiveFailures;
    private Long lastMaliGpuInfoMs;
    private long lastMaliGpuInfoWallMs;

    /** Looks for the files that report the load. */
    public void detect() {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String path : STATIC_PATHS) {
            try {
                if (new File(path).canRead()) found.add(path);
            } catch (Exception ignored) {}
        }

        scanNodes(new File("/sys/class/devfreq"), found, true);
        scanNodes(new File("/sys/devices/virtual/devfreq"), found, true);
        scanNodes(new File("/sys/devices/platform"), found, false);

        paths = found.toArray(new String[0]);
        unavailable = paths.length == 0;
    }

    public boolean unavailable() {
        return unavailable;
    }

    /** The load in percent, or -1 when this read failed (and the GPU is marked unavailable after the third failure in a row). */
    public int read() {
        if (unavailable) return -1;
        int value = -1;
        for (String path : paths) {
            value = readSample(path);
            if (value >= 0) break;
        }
        if (value >= 0) {
            consecutiveFailures = 0;
            return value;
        }
        if (++consecutiveFailures >= 3) unavailable = true;
        return -1;
    }

    private void scanNodes(File root, LinkedHashSet<String> found, boolean matchFullPath) {
        try {
            if (!root.isDirectory()) return;
            File[] nodes = root.listFiles(File::isDirectory);
            if (nodes == null) return;
            for (File node : nodes) {
                if (!looksLikeGpuNode(matchFullPath ? node.getPath() : node.getName())) continue;
                for (String fileName : USAGE_FILES) {
                    File candidate = new File(node, fileName);
                    if (candidate.canRead()) found.add(candidate.getPath());
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean looksLikeGpuNode(String path) {
        String lower = path.toLowerCase(Locale.US);
        for (String token : NODE_TOKENS) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    private int readSample(String path) {
        try {
            if (path.endsWith("/gpubusy")) {
                String line = readFirstLine(path);
                if (line == null) return -1;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) return -1;
                long busy = Long.parseLong(parts[0]);
                long total = Long.parseLong(parts[1]);
                return total > 0L ? clampPercent((int) ((busy * 100L) / total)) : -1;
            }

            if (path.endsWith("/gpuinfo")) {
                String line = readFirstLine(path);
                if (line == null) return -1;
                String[] parts = line.trim().split("\\s+");
                long gpuMs = Long.parseLong(parts[parts.length - 1]);
                long nowMs = SystemClock.elapsedRealtime();
                Long oldGpuMs = lastMaliGpuInfoMs;
                long oldWallMs = lastMaliGpuInfoWallMs;
                lastMaliGpuInfoMs = gpuMs;
                lastMaliGpuInfoWallMs = nowMs;
                if (oldGpuMs == null || oldWallMs <= 0L) return -1;
                long wallDelta = nowMs - oldWallMs;
                long gpuDelta = Math.max(0L, gpuMs - oldGpuMs);
                return wallDelta > 0L ? clampPercent((int) ((gpuDelta * 100L) / wallDelta)) : -1;
            }

            if (path.endsWith("/proc/mtk_mali/utilization")) {
                try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int idx = line.indexOf("ACTIVE=");
                        if (idx < 0) continue;
                        int start = idx + 7;
                        int end = line.indexOf(' ', start);
                        String token = end > start ? line.substring(start, end) : line.substring(start);
                        token = token.replaceAll("[^0-9]", "");
                        if (!token.isEmpty()) return clampPercent(Integer.parseInt(token));
                    }
                }
                return -1;
            }

            return readPercent(path);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private int readPercent(String path) {
        String line = readFirstLine(path);
        if (line == null) return -1;
        for (String token : line.trim().split("\\s+")) {
            String digits = token.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return clampPercent(Integer.parseInt(digits));
                } catch (Exception ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String readFirstLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
