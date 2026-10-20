package com.winlator.cmod.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Bounded launch output, independent of whether debug logging or telemetry is enabled. */
public final class ProgramOutput {
    private static final int MAX_LINES = 80;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    // Wine's launcher can return zero after a child aborts. Ordinary Wine err/fixme messages are not fatal.
    private static final Pattern FATAL = Pattern.compile(
            "Pointer tag for .* was truncated|"
                    + "err:seh:NtRaiseException.*unable to dispatch exception|"
                    + "wine: Unhandled (?:page fault|exception)|"
                    + "wine: could not load kernel32\\.dll", Pattern.CASE_INSENSITIVE);
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private String fatal = "";
    private boolean startFailed;

    public synchronized void clear() {
        lines.clear();
        fatal = "";
        startFailed = false;
    }

    public synchronized void append(String line) {
        if (line == null) return;
        line = ANSI.matcher(line).replaceAll("").replace("\u0000", "").trim();
        if (line.isEmpty()) return;
        if (line.length() > MAX_LINE_LENGTH) line = line.substring(0, MAX_LINE_LENGTH) + "…";
        if (FATAL.matcher(line).find()) fatal = line;
        lines.addLast(line);
        while (lines.size() > MAX_LINES) lines.removeFirst();
    }

    public synchronized void failedToStart(String reason) {
        startFailed = true;
        append("Unable to start Wine/Proton: " + reason);
    }

    public synchronized boolean hasFailure(int status) {
        return status != 0 || startFailed || !fatal.isEmpty();
    }

    public synchronized String lastOutput(int maxLines) {
        if (maxLines <= 0) return "";
        ArrayList<String> result = new ArrayList<>(lines);
        result = new ArrayList<>(result.subList(Math.max(0, result.size() - maxLines), result.size()));
        if (!fatal.isEmpty() && !result.contains(fatal)) {
            if (result.size() == maxLines) result.remove(0);
            result.add(0, fatal);
        }
        return String.join("\n", result);
    }

    public synchronized String exitMessage(int status, long seconds) {
        String reason;
        if (startFailed || status < 0) reason = "The program could not be started.";
        else if (status == 0 && !fatal.isEmpty()) reason = "The program reported a fatal error before closing.";
        else if (status > 128 && status <= 192) reason = "The program was stopped by signal " + (status - 128)
                + (status == 139 ? " (a crash)" : "") + " after " + seconds + " s.";
        else reason = "The program exited with code " + status + " after " + seconds + " s.";
        String output = lastOutput(12);
        return reason + (output.isEmpty() ? "\n\nNo error output was captured." : "\n\n" + output);
    }
}
