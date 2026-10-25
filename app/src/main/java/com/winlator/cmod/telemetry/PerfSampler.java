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
import com.winlator.cmod.core.CpuUsageSampler;
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
    private final CpuUsageSampler cpuSampler = new CpuUsageSampler();
    private final DevicePerformanceSnapshot deviceSnapshot = new DevicePerformanceSnapshot();
    private int latestCpu = -1, latestGpu = -1, latestCpuTemp = -1, latestBatteryTemp = -1;
    private int latestThermal = -1, latestCharging = -1, latestPowerSave = -1;

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

    /** One current sample for Linux diagnostics; no sampler thread is started for this use. */
    String snapshot() {
        latestCpu = latestGpu = latestCpuTemp = latestBatteryTemp = latestThermal = latestCharging = latestPowerSave = -1;
        try { sample(); } catch (RuntimeException ignored) {}
        return deviceSnapshot.capture() + " cpu_pct=" + latestCpu + " gpu_pct=" + latestGpu
                + " cpu_c=" + latestCpuTemp + " battery_c=" + latestBatteryTemp
                + " thermal=" + latestThermal + " charging=" + latestCharging + " power_save=" + latestPowerSave;
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
        latestCpuTemp = temp;
        if (temp > cpuTempMaxC) cpuTempMaxC = temp;

        int usage = cpuSampler.sample();
        latestCpu = usage;
        if (usage >= 0) {
            cpuUsageSum += usage;
            cpuUsageCount++;
        }

        if (!gpuDetected) {
            gpu.detect();
            gpuDetected = true;
        }
        latestGpu = -1;
        if (!gpu.unavailable()) {
            int load = gpu.read();
            latestGpu = load;
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
            latestBatteryTemp = tempC;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            latestCharging = status < 0 ? -1 : (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL ? 1 : 0);
            if (tempC > batteryTempMaxC) batteryTempMaxC = tempC;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            if (level >= 0) {
                if (batteryFirstPct < 0) batteryFirstPct = level;
                batteryLastPct = level;
            }
        }

        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            latestPowerSave = power.isPowerSaveMode() ? 1 : 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                latestThermal = power.getCurrentThermalStatus();
                thermalMax = Math.max(thermalMax, latestThermal);
            }
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
