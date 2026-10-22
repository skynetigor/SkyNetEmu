package com.winlator.cmod.linux;

import android.util.Log;

import com.winlator.cmod.telemetry.ErrorReporter;

/** Logs a failure of the Linux side and sends it (with its stack trace, when there is a throwable) when the user has turned telemetry on. */
final class LinuxFail {
    private LinuxFail() {}

    static void e(String tag, String message) { e(tag, message, null); }

    static void e(String tag, String message, Throwable error) {
        Log.e(tag, message, error);
        ErrorReporter.linux(tag, message, error);
    }

    static void w(String tag, String message) { w(tag, message, null); }

    static void w(String tag, String message, Throwable error) {
        Log.w(tag, message, error);
        ErrorReporter.linux(tag, message, error);
    }
}
