package com.winlator.cmod.telemetry;

import java.util.ArrayDeque;
import java.util.Locale;

/** Scrubbed startup, suspicious lines, and recent output, each with its own character budget. */
public final class LinuxLaunchOutput {
    private final StringBuilder head = new StringBuilder();
    private final ArrayDeque<String> errors = new ArrayDeque<>(), tail = new ArrayDeque<>();
    private long lines;
    private String fatal = "";
    public synchronized void append(String line) {
        if (line == null || line.isEmpty()) return;
        String safe = Scrub.text(line.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", ""), 400);
        lines++;
        if (head.length() < 600) head.append(safe, 0, Math.min(safe.length(), 599 - head.length())).append('\n');
        String lower = safe.toLowerCase(Locale.ROOT);
        if (fatal.isEmpty() && (lower.contains("fatal") || lower.contains("sigsys") || lower.contains("seccomp") || lower.contains("assert")))
            fatal = Scrub.text(safe, 300);
        if (lower.contains("error") || lower.contains("fatal") || lower.contains("signal") || lower.contains("seccomp")
                || lower.contains("sigsys") || lower.contains("syscall") || lower.contains("assert") || lower.contains("cannot dlopen"))
            add(errors, safe, 650);
        add(tail, safe, 900);
    }
    private static void add(ArrayDeque<String> buffer, String line, int limit) {
        if (line.equals(buffer.peekLast())) return;
        buffer.addLast(line);
        int chars = buffer.stream().mapToInt(s -> s.length() + 1).sum();
        while (chars > limit) chars -= buffer.removeFirst().length() + 1;
    }
    public synchronized String snapshot() {
        return "output_lines=" + lines + "\n== first fatal\n" + fatal + "\n== startup\n" + head + "== notable\n" + String.join("\n", errors)
                + "\n== recent\n" + String.join("\n", tail);
    }
}
