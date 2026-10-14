package com.winlator.cmod.telemetry;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * Errors that end the app. A crash cannot wait for the network, so the handler only writes one small file; the next start sends it.
 * The reasons the system gives for how the previous run ended (a freeze the system killed, a native crash, being killed for memory)
 * are read at the next start too. The same buffer, flush and retry rules as every other event apply.
 */
public final class ErrorReporter {
    private static final String CRASH_FILE = "telemetry/crash.json";

    private ErrorReporter() {}

    /** Called from the Application. Costs nothing until the app actually crashes. */
    public static void install(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (Telemetry.isEnabled()) writeCrash(file, error);
            }
            catch (Throwable ignored) {
                // Reporting must not hide the crash.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Called once telemetry is on: reports what the last run left behind, on the uploader's thread. */
    static void reportPreviousRun(Context context) {
        Uploader.background(() -> {
            reportCrashFile(new File(context.getFilesDir(), CRASH_FILE));
            reportExitReasons(context);
        });
    }

    /** Throwables the app itself catches but wants counted (a failed container creation, for example). */
    public static void report(String stage, String type, String message) {
        Telemetry.event("skynet.error", stage, "failure", "skynet.error.stage", stage, "error.type", type, "error.message", message);
    }

    /** A failed download: the server's name and what went wrong, never the path of the file. */
    public static void reportDownload(String address, Exception error) {
        String host = "";
        try {
            host = new java.net.URI(address).getHost();
        }
        catch (Exception ignored) {}
        report("download", error.getClass().getSimpleName(), "host=" + host + " " + error.getMessage());
    }

    private static void writeCrash(File file, Throwable error) throws JSONException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        JSONObject json = new JSONObject();
        json.put("time", System.currentTimeMillis());
        json.put("type", error.getClass().getName());
        json.put("message", Scrub.text(error.getMessage(), 300));
        json.put("stack", Scrub.stack(error, 20));
        FileUtils.writeString(file, json.toString());
    }

    private static void reportCrashFile(File file) {
        if (!file.isFile()) return;
        String text = FileUtils.readString(file);
        file.delete();
        if (text == null) return;
        try {
            JSONObject json = new JSONObject(text);
            Telemetry.event("skynet.error", "app_crash", "failure",
                    "skynet.error.stage", "app_crash",
                    "error.type", json.optString("type"),
                    "error.message", json.optString("message"),
                    "error.stack_trace", json.optString("stack"),
                    "skynet.error.occurred_at", json.optLong("time"));
        }
        catch (JSONException ignored) {}
    }

    private static void reportExitReasons(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long last = prefs.getLong("telemetry_last_exit_reported", 0);
        long newest = last;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            for (ApplicationExitInfo exit : exits) {
                newest = Math.max(newest, exit.getTimestamp());
                // The first time there is no history to report: only exits from now on count.
                if (last == 0 || exit.getTimestamp() <= last) continue;
                if (!context.getPackageName().equals(exit.getProcessName())) continue;
                String stage = stageFor(exit.getReason());
                if (stage == null) continue;
                Telemetry.event("skynet.error", stage, "failure",
                        "skynet.error.stage", stage,
                        "error.type", "ApplicationExitInfo",
                        "error.message", exit.getDescription(),
                        "skynet.exit.code", exit.getStatus(),
                        "skynet.exit.rss_kb", exit.getRss() / 1024,
                        "skynet.error.occurred_at", exit.getTimestamp());
            }
        }
        catch (RuntimeException ignored) {}
        prefs.edit().putLong("telemetry_last_exit_reported", newest == 0 ? System.currentTimeMillis() : newest).apply();
    }

    /** What the system's reason means for us; null for exits that are not errors (the app closing itself, the user stopping it) or are reported already. */
    private static String stageFor(int reason) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native_crash";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low_memory_kill";
            case ApplicationExitInfo.REASON_SIGNALED: return "killed_by_signal";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive_resource_kill";
            default: return null;
        }
    }
}
