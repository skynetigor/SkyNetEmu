package com.winlator.cmod.telemetry;

import java.util.ArrayDeque;
import java.util.Locale;

/** Scrubbed startup, crash signatures/backtraces, suspicious lines, and recent output with separate budgets. */
public final class LinuxLaunchOutput {
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder nativeTrace = new StringBuilder(), guestTrace = new StringBuilder();
    private final ArrayDeque<String> errors = new ArrayDeque<>(), tail = new ArrayDeque<>();
    private long lines;
    private String fatal = "", crash = "";
    public synchronized void append(String line) {
        if (line == null || line.isEmpty()) return;
        String safe = Scrub.text(line.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", ""), 400);
        lines++;
        appendFirst(head, safe, 350);
        String lower = safe.toLowerCase(Locale.ROOT);
        boolean signal = lower.contains("sigsegv") || lower.contains("sigabrt") || lower.contains("sigill")
                || lower.contains("sigbus") || lower.contains("sigfpe") || lower.contains("sigsys");
        if (crash.isEmpty() && signal)
            crash = safe;
        if (lower.contains("nativebt:")) appendFirst(nativeTrace, safe, 500);
        if (lower.contains("emulatedbt:")) appendFirst(guestTrace, safe, 500);
        if (fatal.isEmpty() && (lower.contains("fatal") || lower.contains("sigsys") || lower.contains("seccomp") || lower.contains("assert")))
            fatal = Scrub.text(safe, 200);
        if (lower.contains("error") || lower.contains("fatal") || lower.contains("signal") || lower.contains("seccomp")
                || signal || lower.contains("syscall") || lower.contains("assert") || lower.contains("cannot dlopen"))
            add(errors, safe, 200);
        add(tail, safe, 300);
    }
    private static void appendFirst(StringBuilder buffer, String line, int limit) {
        if (buffer.length() < limit)
            buffer.append(line, 0, Math.min(line.length(), limit - buffer.length() - 1)).append('\n');
    }
    private static void add(ArrayDeque<String> buffer, String line, int limit) {
        line = Scrub.text(line, limit - 1);
        if (line.equals(buffer.peekLast())) return;
        buffer.addLast(line);
        int chars = buffer.stream().mapToInt(s -> s.length() + 1).sum();
        while (chars > limit) chars -= buffer.removeFirst().length() + 1;
    }
    public synchronized String snapshot() {
        return "output_lines=" + lines + "\n== crash\n" + crash + "\n== native backtrace\n" + nativeTrace
                + "== guest backtrace\n" + guestTrace + "== first fatal\n" + fatal + "\n== startup\n" + head + "== notable\n" + String.join("\n", errors)
                + "\n== recent\n" + String.join("\n", tail);
    }
}
