package com.winlator.cmod.telemetry;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;

import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.GpuLoad;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Samples the device while a game runs and keeps only the totals (peak, average, first and last), a few numbers however long the game
 * lasts. It runs on its own background-priority thread every few seconds and does a handful of small reads of /proc and sysfs; it
 * does not depend on the HUD being on. {@link #stop} ends the thread; the totals stay readable afterwards.
 */
final class PerfSampler {
    private final Context context;
    private final long intervalMs;
    private final IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    private final GpuLoad gpu = new GpuLoad();
    private HandlerThread thread;
    private Handler handler;
    private boolean gpuDetected;
    private long lastCpuTotal = -1, lastCpuIdle = -1;

    // Totals. Written by the sampler's thread, read after stop().
    volatile int cpuTempMaxC = -1;
    private long cpuUsageSum;
    private int cpuUsageCount;
    private long gpuSum;
    private int gpuCount;
    volatile long ramPeakUsedBytes = -1;
    volatile int batteryTempMaxC = -1;
    volatile int batteryFirstPct = -1;
    volatile int batteryLastPct = -1;
    volatile int thermalMax = -1;

    PerfSampler(Context context, int intervalSec) {
        this.context = context.getApplicationContext();
        this.intervalMs = intervalSec * 1000L;
    }

    void start() {
        if (intervalMs <= 0 || thread != null) return;
        thread = new HandlerThread("SkyNet-telemetry-perf", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
    }

    void stop() {
        HandlerThread t = thread;
        thread = null;
        if (t != null) t.quitSafely();
    }

    float cpuAvgPercent() {
        return cpuUsageCount == 0 ? -1 : (float) cpuUsageSum / cpuUsageCount;
    }

    float gpuAvgPercent() {
        return gpuCount == 0 ? -1 : (float) gpuSum / gpuCount;
    }

    private void tick() {
        try {
            sample();
        }
        catch (RuntimeException ignored) {}
        Handler h = handler;
        if (thread != null && h != null) h.postDelayed(this::tick, intervalMs);
    }

    private void sample() {
        int temp = CPUStatus.getCpuTempC();
        if (temp > cpuTempMaxC) cpuTempMaxC = temp;

        int usage = cpuUsage();
        if (usage >= 0) {
            cpuUsageSum += usage;
            cpuUsageCount++;
        }

        if (!gpuDetected) {
            gpu.detect();
            gpuDetected = true;
        }
        if (!gpu.unavailable()) {
            int load = gpu.read();
            if (load >= 0) {
                gpuSum += load;
                gpuCount++;
            }
        }

        long used = ramUsedBytes();
        if (used > ramPeakUsedBytes) ramPeakUsedBytes = used;

        Intent battery = context.registerReceiver(null, batteryFilter);
        if (battery != null) {
            int raw = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int tempC = raw > 0 ? Math.round(raw / 10f) : -1;
            if (tempC > batteryTempMaxC) batteryTempMaxC = tempC;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            if (level >= 0) {
                if (batteryFirstPct < 0) batteryFirstPct = level;
                batteryLastPct = level;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) thermalMax = Math.max(thermalMax, power.getCurrentThermalStatus());
        }
    }

    /** CPU use since the previous call, from /proc/stat; kept here so the HUD's own reading is not disturbed. -1 for the first call. */
    private int cpuUsage() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) return -1;
            String[] parts = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < parts.length && i <= 8; i++) total += Long.parseLong(parts[i]);
            long idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0);
            int result = -1;
            if (lastCpuTotal >= 0 && total > lastCpuTotal) {
                result = (int) (100L * ((total - lastCpuTotal) - (idle - lastCpuIdle)) / (total - lastCpuTotal));
                result = Math.max(0, Math.min(100, result));
            }
            lastCpuTotal = total;
            lastCpuIdle = idle;
            return result;
        }
        catch (Exception e) {
            return -1;
        }
    }

    private static long ramUsedBytes() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = -1, available = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = kb(line);
                else if (line.startsWith("MemAvailable:")) {
                    available = kb(line);
                    break;
                }
            }
            return total > 0 && available >= 0 ? (total - available) * 1024 : -1;
        }
        catch (Exception e) {
            return -1;
        }
    }

    private static long kb(String line) {
        return Long.parseLong(line.trim().split("\\s+")[1]);
    }
}
