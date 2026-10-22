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

    /** A caught throwable, with its stack trace (paths and personal text taken out). {@code extra} are more fields, as for {@link Telemetry#event}. */
    public static void report(String stage, Throwable error, Object... extra) {
        if (!Telemetry.isEnabled()) return;
        Object[] fields = new Object[8 + extra.length];
        fields[0] = "skynet.error.stage"; fields[1] = stage;
        fields[2] = "error.type"; fields[3] = error.getClass().getName();
        fields[4] = "error.message"; fields[5] = Scrub.text(error.getMessage(), 300);
        fields[6] = "error.stack_trace"; fields[7] = Scrub.stack(error, 30);
        System.arraycopy(extra, 0, fields, 8, extra.length);
        Telemetry.event("skynet.error", stage, "failure", fields);
    }

    private static final java.util.Set<String> SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * A failure of the Linux side (a session that did not start, a failed install, a missing file): the message, the stack when there is one, and what a
     * report needs to place it. The same failure is sent once per run.
     */
    public static void linux(String tag, String message, Throwable error) {
        if (!Telemetry.isEnabled()) return;
        String key = tag + "|" + message;
        if (!SEEN.add(key) || SEEN.size() > 200) return;
        String stage = "linux_" + tag;
        Object[] fields = {
                "skynet.error.stage", stage,
                "error.type", error != null ? error.getClass().getName() : "LinuxFailure",
                "error.message", Scrub.text(message + (error != null && error.getMessage() != null ? ": " + error.getMessage() : ""), 400),
                "error.stack_trace", error != null ? Scrub.stack(error, 30) : null
        };
        Telemetry.event("skynet.error", stage, "failure", fields);
    }

    /** A Linux program that ended badly: how, after how long, in which setup, and the last lines it printed. */
    public static void linuxExit(int status, long seconds, String setup, String lastOutput) {
        if (!Telemetry.isEnabled()) return;
        java.util.function.Supplier<String> evidence = LinuxDiagnostics.evidenceCollector();
        Uploader.background(() -> {
            if (!Telemetry.isEnabled()) return;
            Telemetry.event("skynet.error", "linux_program_exit", "failure",
                "skynet.error.stage", "linux_program_exit",
                "error.type", status > 128 ? "signal_" + (status - 128) : "exit_" + status,
                "error.message", Scrub.text(lastOutput.isEmpty() ? "no output" : lastOutput.substring(Math.max(0, lastOutput.length() - 1500)), 1500),
                "skynet.exit.code", status,
                "skynet.linux.seconds_running", seconds,
                "skynet.linux.setup", setup,
                "skynet.linux.logs", evidence.get());
        });
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
                String description = Scrub.text(exit.getDescription(), 300);
                Telemetry.event("skynet.error", stage, "failure",
                        "skynet.error.stage", stage,
                        "error.type", "ApplicationExitInfo",
                        "error.message", description.isEmpty() ? stage + " status=" + exit.getStatus() : description,
                        "skynet.exit.code", exit.getStatus(),
                        "skynet.exit.reason", stage,
                        "skynet.exit.signal", exit.getReason() == ApplicationExitInfo.REASON_SIGNALED || exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE ? exit.getStatus() : null,
                        "skynet.exit.importance", exit.getImportance(),
                        "skynet.exit.pss_kb", exit.getPss(),
                        "error.stack_trace", exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE ? tombstoneText(exit) : null,
                        "skynet.exit.rss_kb", exit.getRss() / 1024,
                        "skynet.error.occurred_at", exit.getTimestamp());
            }
        }
        catch (RuntimeException ignored) {}
        prefs.edit().putLong("telemetry_last_exit_reported", newest == 0 ? System.currentTimeMillis() : newest).apply();
    }

    /** The readable strings of a native crash's tombstone (the frames with their library names), scrubbed; the file is a protobuf on current Android. */
    private static String tombstoneText(ApplicationExitInfo exit) {
        try (java.io.InputStream in = exit.getTraceInputStream()) {
            if (in == null) return null;
            byte[] data = in.readNBytes(256 * 1024);
            StringBuilder out = new StringBuilder();
            StringBuilder word = new StringBuilder();
            for (byte b : data) {
                if (b >= 32 && b < 127) word.append((char) b);
                else {
                    if (word.length() >= 6) out.append(word).append('\n');
                    word.setLength(0);
                }
                if (out.length() > 6000) break;
            }
            return Scrub.text(out.toString(), 6000);
        }
        catch (Exception e) {
            return null;
        }
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
