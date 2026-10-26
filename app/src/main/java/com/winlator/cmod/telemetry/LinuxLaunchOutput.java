package com.winlator.cmod.telemetry;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Pattern;

/** Scrubbed startup, crash signatures/backtraces/registers, suspicious lines, and recent output with separate budgets. */
public final class LinuxLaunchOutput {
    // Box64 names the guest location as x64pc=0x…/"<module path>/<symbol> + 0x…"; only the symbol part is kept.
    private static final Pattern GUEST_SYMBOL = Pattern.compile("(x64pc=0x[0-9a-fA-F]+)/\"(?:[^\"]*/)?([^\"/]*)\"");
    // The first of Box64's crash register lines (RAX/RSP/R8/R12 rows), with leading zeros dropped to fit the budget.
    private static final Pattern REGISTER_ROW = Pattern.compile("^(?:\\[BOX64\\] )?(?:RAX|RSP|R8|R12):0x[0-9a-fA-F]+ .*");
    private static final Pattern ZERO_PADDING = Pattern.compile("0x0+(?=[0-9a-fA-F])");
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder nativeTrace = new StringBuilder(), guestTrace = new StringBuilder(), registers = new StringBuilder();
    private final ArrayDeque<String> errors = new ArrayDeque<>(), tail = new ArrayDeque<>();
    private long lines;
    private String fatal = "", crash = "";
    public synchronized void append(String line) {
        if (line == null || line.isEmpty()) return;
        String plain = line.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "");
        String safe = Scrub.text(GUEST_SYMBOL.matcher(plain).replaceAll("$1/[$2]"), 400);
        lines++;
        appendFirst(head, safe, 250);
        String lower = safe.toLowerCase(Locale.ROOT);
        boolean signal = lower.contains("sigsegv") || lower.contains("sigabrt") || lower.contains("sigill")
                || lower.contains("sigbus") || lower.contains("sigfpe") || lower.contains("sigsys");
        if (crash.isEmpty() && signal)
            crash = safe;
        if (lower.contains("nativebt:")) appendFirst(nativeTrace, safe, 450);
        if (lower.contains("emulatedbt:")) appendFirst(guestTrace, safe, 450);
        if (!crash.isEmpty() && REGISTER_ROW.matcher(safe).matches())
            appendFirst(registers, ZERO_PADDING.matcher(safe.trim()).replaceAll("0x"), 300);
        if (fatal.isEmpty() && (lower.contains("fatal") || lower.contains("sigsys") || lower.contains("seccomp") || lower.contains("assert")))
            fatal = Scrub.text(safe, 200);
        if (lower.contains("error") || lower.contains("fatal") || lower.contains("signal") || lower.contains("seccomp")
                || signal || lower.contains("syscall") || lower.contains("assert") || lower.contains("cannot dlopen"))
            add(errors, safe, 150);
        add(tail, safe, 200);
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
                + "== guest backtrace\n" + guestTrace + "== registers\n" + registers + "== first fatal\n" + fatal + "\n== startup\n" + head + "== notable\n" + String.join("\n", errors)
                + "\n== recent\n" + String.join("\n", tail);
    }
}
