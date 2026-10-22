package com.winlator.cmod.telemetry;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.UUID;

/**
 * Opt-in usage and performance telemetry. {@link #event} is what the rest of the app calls: it does nothing unless the user turned
 * telemetry on, and when it is on it only puts a small object into a memory buffer (no I/O, no JSON, nothing the caller waits for).
 * {@link Uploader} sends the buffer in bulk from its own low-priority thread.
 *
 * Fields are passed as alternating names and values: {@code Telemetry.event("skynet.funnel", "game_recognised", "skynet.game.id", id)}.
 * Values may be a String, a number or a boolean; a null value leaves the field out.
 */
public final class Telemetry {
    public static final String PREF_ENABLED = "telemetry_enabled";
    public static final String PREF_ASKED = "telemetry_asked";
    static final String PREF_INSTALL_ID = "telemetry_install_id";
    static final String PREF_DEBUG_ES_HOST = "telemetry_es_host_override";

    /** One event waiting to be sent. Created on any thread, completed and serialized on the uploader's thread. */
    static final class Event {
        final long time = System.currentTimeMillis();
        final String dataset;
        final String action;
        final String outcome;
        final Object[] fields;
        /** Set by the uploader when the event is first serialized, so that a retry carries the same id. */
        String id;
        int attempts;

        Event(String dataset, String action, String outcome, Object[] fields) {
            this.dataset = dataset;
            this.action = action;
            this.outcome = outcome;
            this.fields = fields;
        }
    }

    static final String SESSION_ID = UUID.randomUUID().toString();

    private static volatile boolean enabled;
    private static volatile String installId = "";
    private static Context app;

    private Telemetry() {}

    /** Called once from the Application: reads the user's choice; sends nothing and starts nothing when it is off. */
    public static void init(Context context) {
        try { initialize(context); }
        catch (Exception ignored) { enabled = false; }
    }

    private static void initialize(Context context) {
        app = context.getApplicationContext();
        ErrorReporter.install(app);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        if (!prefs.getBoolean(PREF_ENABLED, false)) return;
        installId = prefs.getString(PREF_INSTALL_ID, "");
        if (installId.isEmpty()) installId = newInstallId(prefs);
        enabled = true;
        started();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Whether the user has been asked (and so is not asked again). */
    public static boolean asked(Context context) {
        try { return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ASKED, false); }
        catch (Exception ignored) { return false; }
    }

    /** The user's answer. Turning it off forgets the install id and everything not yet sent. */
    public static void setEnabled(Context context, boolean on) {
        if (!on) { enabled = false; installId = ""; }
        try { applyEnabled(context, on); }
        catch (Exception ignored) { if (on) enabled = false; }
    }

    private static void applyEnabled(Context context, boolean on) {
        app = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        SharedPreferences.Editor edit = prefs.edit().putBoolean(PREF_ASKED, true).putBoolean(PREF_ENABLED, on);
        if (on) {
            if (installId.isEmpty()) installId = newInstallId(prefs);
            edit.apply();
            if (!enabled) {
                enabled = true;
                started();
            }
        }
        else {
            enabled = false;
            installId = "";
            edit.remove(PREF_INSTALL_ID).apply();
            Uploader.clear(app);
            new java.io.File(app.getFilesDir(), "telemetry/crash.json").delete();
        }
    }

    public static void event(String dataset, String action, Object... fields) {
        event(dataset, action, "success", fields);
    }

    public static void event(String dataset, String action, String outcome, Object... fields) {
        if (!enabled) return;
        try { Uploader.offer(new Event(dataset, action, outcome, fields)); }
        catch (Exception ignored) {}
    }

    /** For the moment before the process ends: one bounded attempt (at most {@code timeoutMs}) to send what is waiting. */
    public static void flushNow(long timeoutMs) {
        try { Uploader.flushNow(timeoutMs); }
        catch (Exception ignored) {}
    }

    static String installId() {
        return installId;
    }

    static Context context() {
        return app;
    }

    static String debugEsHost() {
        try { return app == null ? "" : PreferenceManager.getDefaultSharedPreferences(app).getString(PREF_DEBUG_ES_HOST, ""); }
        catch (Exception ignored) { return ""; }
    }

    private static void started() {
        Uploader.start(app);
        event("skynet.session", "app_started");
        ErrorReporter.reportPreviousRun(app);
    }

    private static String newInstallId(SharedPreferences prefs) {
        String id = UUID.randomUUID().toString();
        prefs.edit().putString(PREF_INSTALL_ID, id).apply();
        return id;
    }
}
