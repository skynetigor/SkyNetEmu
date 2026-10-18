package com.winlator.cmod.telemetry;

import java.util.regex.Pattern;

/** Takes what could identify a person or their files out of a text before it is sent: paths, drive paths, quoted names, e-mail addresses. */
final class Scrub {
    private static final Pattern UNIX_PATH = Pattern.compile("(/(?:storage|sdcard|data|mnt|home|root|tmp|opt|run|var|usr|private|media|dev|proc|sys|system|vendor|Users)[^\\s\"'():,;]*)");
    private static final Pattern WIN_PATH = Pattern.compile("\\b[A-Za-z]:[\\\\/][^\\s\"']*");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"\\n]*\"|'[^'\\n]*'");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

    private Scrub() {}

    /** Per-field limits shared with the ingest schema; diagnostic excerpts must survive serialization. */
    static String field(String key, String value) {
        int limit;
        switch (key) {
            case "error.stack_trace": limit = 8192; break;
            case "skynet.linux.logs": limit = 6000; break;
            case "skynet.renderer.diagnostics": limit = 2048; break;
            case "skynet.linux.setup": limit = 1000; break;
            default: limit = 300;
        }
        return text(value, limit);
    }

    static String text(String value, int maxLength) {
        if (value == null) return "";
        String s = EMAIL.matcher(value).replaceAll("<email>");
        s = WIN_PATH.matcher(s).replaceAll("<path>");
        s = UNIX_PATH.matcher(s).replaceAll("<path>");
        s = QUOTED.matcher(s).replaceAll("<text>");
        return s.length() > maxLength ? s.substring(0, maxLength) : s;
    }

    /** A stack trace without file paths in messages, at most {@code maxFrames} frames of the first exception and its cause chain. */
    static String stack(Throwable error, int maxFrames) {
        StringBuilder out = new StringBuilder();
        int frames = 0;
        for (Throwable t = error; t != null && frames < maxFrames; t = t.getCause()) {
            out.append(t.getClass().getName()).append(": ").append(text(t.getMessage(), 300)).append('\n');
            for (StackTraceElement e : t.getStackTrace()) {
                if (frames++ >= maxFrames) break;
                out.append("  at ").append(e.getClassName()).append('.').append(e.getMethodName()).append('(')
                        .append(e.getFileName() == null ? "?" : e.getFileName()).append(':').append(e.getLineNumber()).append(")\n");
            }
        }
        return out.toString();
    }
}
