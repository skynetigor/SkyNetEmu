package com.winlator.cmod.telemetry;

import android.content.Context;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.contents.RegistryCache;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import okhttp3.HttpUrl;

/** Elasticsearch host, public append-only API key and delivery settings from registry/telemetry.json. */
final class TelemetryConfig {
    final String host;
    final String apiKey;
    final boolean enabled;
    final int batchMax;
    final long flushMs;
    final int maxAttempts;
    final int sampleSec;

    private TelemetryConfig(String host, String apiKey, boolean enabled, int batchMax, long flushMs, int maxAttempts, int sampleSec) {
        this.host = host;
        this.apiKey = apiKey;
        this.enabled = enabled;
        this.batchMax = batchMax;
        this.flushMs = flushMs;
        this.maxAttempts = maxAttempts;
        this.sampleSec = sampleSec;
    }

    static TelemetryConfig defaults() {
        return new TelemetryConfig("", "", true, 200, 2000, 3, 5);
    }

    static TelemetryConfig fromJson(JSONObject json) {
        return new TelemetryConfig(normalizeHost(json.optString("host", ""), false), json.optString("apiKey", "").trim(),
                json.optBoolean("enabled", true), clamp(json.optInt("batchMax", 200), 1, 500),
                clamp(json.optInt("flushMs", 2000), 500, 60000), clamp(json.optInt("maxAttempts", 3), 1, 10),
                clamp(json.optInt("sampleSec", 5), 0, 300));
    }

    /** Bundled configuration works offline; a complete remote host/key pair replaces it atomically. */
    static TelemetryConfig load(Context context, String debugHost) {
        TelemetryConfig config = defaults();
        try { config = fromJson(new JSONObject(FileUtils.readString(context, "registry/telemetry.json"))); }
        catch (Exception ignored) {}
        try {
            String text = RegistryCache.get(context, "telemetry.json", RegistryCache.TTL_INDEX, true);
            if (text != null) {
                TelemetryConfig candidate = fromJson(new JSONObject(text));
                if (!candidate.enabled || candidate.usable()) config = candidate;
            }
        } catch (Exception ignored) {}
        if (BuildConfig.DEBUG && debugHost != null && !debugHost.isEmpty()) config = config.withDebugHost(debugHost);
        return config;
    }

    static String normalizeHost(String value, boolean debug) {
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null || !url.encodedPath().equals("/")) return "";
        boolean loopback = url.host().equals("127.0.0.1") || url.host().equals("localhost") || url.host().equals("::1");
        if (!url.isHttps() && !(debug && loopback)) return "";
        String host = url.toString();
        return host.substring(0, host.length() - 1);
    }

    TelemetryConfig withDebugHost(String value) {
        return new TelemetryConfig(normalizeHost(value, BuildConfig.DEBUG), apiKey, enabled,
                batchMax, flushMs, maxAttempts, sampleSec);
    }

    boolean usable() { return enabled && !host.isEmpty() && !apiKey.isEmpty() && !apiKey.contains("\n") && !apiKey.contains("\r"); }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
