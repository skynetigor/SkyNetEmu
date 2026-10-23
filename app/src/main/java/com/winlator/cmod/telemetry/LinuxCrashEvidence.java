package com.winlator.cmod.telemetry;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import java.io.InputStream;
import java.util.Set;

/** Best-effort Android records for observed session PIDs only, never another session or another package. */
public final class LinuxCrashEvidence {
    public static String collect(Context context, long since, Set<Integer> pids) {
        if (!Telemetry.isEnabled()) return "";
        if (Build.VERSION.SDK_INT < 30) return "android_exit_info=unsupported";
        if (pids.isEmpty()) return "android_exit_info=no_observed_pids";
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            StringBuilder out = new StringBuilder(); int count = 0;
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 32)) {
                if (exit.getTimestamp() < since || !pids.contains(exit.getPid())) continue;
                if (count++ >= 3) break;
                out.append("pid=").append(exit.getPid()).append(" reason=").append(exit.getReason()).append(" status=").append(exit.getStatus())
                        .append(" age_ms=").append(Math.max(0, System.currentTimeMillis() - exit.getTimestamp())).append('\n');
                if (exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
                    try (InputStream input = exit.getTraceInputStream()) { out.append(NativeCrashTrace.read(input)).append('\n'); }
                    catch (Exception e) { out.append("tombstone=unavailable\n"); }
                }
            }
            return out.length() == 0 ? "android_exit_info=no_matching_records" : Scrub.text(out.toString(), 2000);
        } catch (RuntimeException e) { return "android_exit_info=unavailable"; }
    }
    private LinuxCrashEvidence() {}
}
