package com.winlator.cmod.perf;

public final class RefreshRatePolicy {
    private RefreshRatePolicy() {}

    public static float choose(float[] rates, int cap) {
        float highest = 0, above = Float.POSITIVE_INFINITY, multiple = Float.POSITIVE_INFINITY;
        for (float rate : rates) {
            if (!Float.isFinite(rate) || rate <= 0) continue;
            highest = Math.max(highest, rate);
            if (cap <= 0 || rate + 0.25f < cap) continue;
            above = Math.min(above, rate);
            int divisor = Math.round(rate / cap);
            if (divisor > 0 && Math.abs(rate - divisor * (float) cap) <= 0.25f)
                multiple = Math.min(multiple, rate);
        }
        if (cap <= 0) return highest;
        return Float.isFinite(multiple) ? multiple : Float.isFinite(above) ? above : highest;
    }
}
