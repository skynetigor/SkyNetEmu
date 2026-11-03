package com.winlator.cmod.linux;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads what {@code download_depot} prints in the client's console log ({@code logs/console_log.txt}). */
public final class SteamConsoleLog {
    public enum Kind { NONE, COMPLETE, FAILED }

    public static final class Result {
        public final Kind kind;
        /** For COMPLETE the folder Steam says it wrote (guest path); for FAILED the line that says so. */
        public final String text;

        Result(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static final Pattern COMPLETE = Pattern.compile("Depot download complete\\s*:\\s*(.+?)\\s*\\(manifest\\s+(\\d+)\\)");
    private static final Pattern FAILED = Pattern.compile("(?i)(depot download failed|download_depot[^\\n]*(fail|error|denied)|ERROR!?\\s*[^\\n]*depot)");
    private static final Pattern LOGGED_ON = Pattern.compile("post-logon took");

    private SteamConsoleLog() {
    }

    /** True once the client has signed in (the line it writes after the logon). */
    public static boolean loggedOn(String log) {
        return LOGGED_ON.matcher(log).find();
    }

    /** What the log says about this depot and manifest: the first "complete" line for it, else the first failure line, else nothing. */
    public static Result find(String log, long depot, String manifest) {
        Matcher complete = COMPLETE.matcher(log);
        while (complete.find()) {
            // Steam prints the folder in quotes and with the separators of the system it was written for:
            // "/root/.local/share/Steam/steamrtarm64\steamapps\content\app_214490\depot_214502"
            String path = complete.group(1).replace("\"", "").replace('\\', '/');
            if (complete.group(2).equals(manifest) && path.contains("depot_" + depot)) return new Result(Kind.COMPLETE, path);
        }
        Matcher failed = FAILED.matcher(log);
        if (failed.find()) {
            int end = log.indexOf('\n', failed.start());
            return new Result(Kind.FAILED, log.substring(failed.start(), end < 0 ? log.length() : end).trim());
        }
        return new Result(Kind.NONE, "");
    }
}
