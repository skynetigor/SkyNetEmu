package com.winlator.cmod.perf;

import android.content.Context;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.container.Shortcut;

/** Missing shortcut values inherit the global default; explicit zero always overrides it. */
public final class PerformanceTuning {
    public static final String[] KEYS = {"perfSustained", "perfPriority", "perfBigCores", "perfGpuTurbo"};
    private PerformanceTuning() {}
    public static boolean resolve(Context context, Shortcut shortcut, int option) {
        String value = shortcut == null ? null : shortcut.getExtra(KEYS[option], null);
        return value == null ? PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEYS[option], false)
                : "1".equals(value);
    }
    /** Retain unknown cores. All fast cores remain eligible on homogeneous designs such as 8 Elite. */
    public static int selectCores(long[] speeds) {
        long max = 0;
        for (long speed : speeds) max = Math.max(max, speed);
        if (max <= 0) return 0;
        int mask = 0;
        for (int i = 0; i < Math.min(32, speeds.length); i++)
            if (speeds[i] <= 0 || speeds[i] >= max * .70) mask |= 1 << i;
        return mask;
    }
}
