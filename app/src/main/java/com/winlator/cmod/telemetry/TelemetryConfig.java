package com.winlator.cmod.telemetry;

import android.content.Context;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.contents.RegistryCache;

import org.json.JSONObject;

/**
 * Where and how telemetry is sent. The numbers come from registry/telemetry.json (downloaded and kept like the rest of the registry,
 * with a copy bundled in the app); the build supplies the endpoint and key when the registry names none.
 */
final class TelemetryConfig {
    final String endpoint;
    final String key;
    /** The registry's kill switch: false stops all sending whatever the user chose. */
    final boolean enabled;
    final int batchMax;
    final long flushMs;
    final int maxAttempts;
    final int sampleSec;

    private TelemetryConfig(String endpoint, String key, boolean enabled, int batchMax, long flushMs, int maxAttempts, int sampleSec) {
        this.endpoint = endpoint;
        this.key = key;
        this.enabled = enabled;
        this.batchMax = batchMax;
        this.flushMs = flushMs;
        this.maxAttempts = maxAttempts;
        this.sampleSec = sampleSec;
    }

    static TelemetryConfig defaults() {
        return new TelemetryConfig(BuildConfig.TELEMETRY_ENDPOINT, BuildConfig.TELEMETRY_KEY, true, 200, 2000, 3, 5);
    }

    /** Reads telemetry.json from the registry (the network is used when the kept copy is old: call off the main thread). */
    static TelemetryConfig load(Context context, String debugEndpoint) {
        TelemetryConfig base = defaults();
        String text = RegistryCache.get(context, "telemetry.json", RegistryCache.TTL_INDEX, true);
        String endpoint = base.endpoint;
        boolean enabled = base.enabled;
        int batchMax = base.batchMax, maxAttempts = base.maxAttempts, sampleSec = base.sampleSec;
        long flushMs = base.flushMs;
        if (text != null) {
            try {
                JSONObject json = new JSONObject(text);
                String remote = json.optString("endpoint", "");
                if (remote.startsWith("https://")) endpoint = remote;
                enabled = json.optBoolean("enabled", true);
                batchMax = clamp(json.optInt("batchMax", batchMax), 1, 500);
                flushMs = clamp(json.optInt("flushMs", (int) flushMs), 500, 60000);
                maxAttempts = clamp(json.optInt("maxAttempts", maxAttempts), 1, 10);
                sampleSec = clamp(json.optInt("sampleSec", sampleSec), 0, 300);
            }
            catch (org.json.JSONException ignored) {}
        }
        // Debug builds may point at a test receiver, which can be plain http.
        if (debugEndpoint != null && !debugEndpoint.isEmpty() && BuildConfig.DEBUG) endpoint = debugEndpoint;
        return new TelemetryConfig(endpoint, base.key, enabled, batchMax, flushMs, maxAttempts, sampleSec);
    }

    boolean usable() {
        return enabled && endpoint != null && !endpoint.isEmpty();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
