package com.winlator.cmod.telemetry;

import java.util.ArrayDeque;

/** A bounded, scrubbed excerpt of renderer messages, rather than the application's full logcat. */
final class RendererLog {
    private static final int MAX_LINES = 24;
    private static final int MAX_CHARS = 3000;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private int chars;

    synchronized void add(String line) {
        int tag = line.indexOf("Winlator_Renderer:");
        if (tag < 0) return;
        String safe = Scrub.text(line.substring(tag), 512);
        if (safe.equals(lines.peekLast())) return;
        lines.addLast(safe);
        chars += safe.length() + 1;
        while (lines.size() > MAX_LINES || chars > MAX_CHARS) chars -= lines.removeFirst().length() + 1;
    }

    synchronized String snapshot() { return String.join("\n", lines); }

    synchronized void clear() { lines.clear(); chars = 0; }
}
