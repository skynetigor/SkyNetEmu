package com.winlator.cmod.telemetry;

import android.os.Build;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * What a Linux session leaves behind when it goes wrong, for the telemetry: the tail of the program's own output and of Steam's log files, and a
 * watchdog that notices a session whose window never presented a frame (a black screen with a process that is still running).
 * Nothing runs unless telemetry is on.
 */
public final class LinuxDiagnostics {
    private static final long[] CHECKS_S = {40, 120};
    private static volatile File steamLogs;
    private static volatile Supplier<String> output = () -> "";
    private static volatile String setup = "";
    private static volatile boolean windowDrawn;
    private static volatile long startedAt;
    private static ScheduledThreadPoolExecutor executor;

    private LinuxDiagnostics() {}

    /** A Linux session is starting. {@code logs} is Steam's log folder, {@code programOutput} the program's last lines, {@code description} the setup. */
    public static synchronized void start(File logs, Supplier<String> programOutput, String description) {
        if (!Telemetry.isEnabled()) return;
        stop();
        steamLogs = logs;
        output = programOutput;
        setup = description;
        windowDrawn = false;
        startedAt = System.currentTimeMillis();
        GameSessionRecorder.resetFrameCount();
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "SkyNet-linux-watchdog");
            t.setDaemon(true);
            return t;
        });
        for (long seconds : CHECKS_S) executor.schedule(() -> check(seconds), seconds, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    /** The session's first window drew. */
    public static void onWindow() {
        windowDrawn = true;
    }

    private static void check(long seconds) {
        if (GameSessionRecorder.frameCount() > 0) return;
        Telemetry.event("skynet.error", "linux_render_stalled", "failure",
                "skynet.error.stage", "linux_render_stalled",
                "error.type", windowDrawn ? "window_without_frames" : "no_window",
                "error.message", "No frame was presented " + seconds + " s after the session started",
                "skynet.linux.seconds_running", (System.currentTimeMillis() - startedAt) / 1000,
                "skynet.linux.setup", setup,
                "skynet.gpu", Build.HARDWARE + " " + Build.SOC_MODEL,
                "skynet.linux.logs", collect());
    }

    /** The program's last output lines and the tail of Steam's newest log files, with paths and personal text taken out. */
    public static String collect() {
        StringBuilder out = new StringBuilder();
        try {
            out.append("== output\n").append(lastLines(output.get(), 30)).append('\n');
        }
        catch (RuntimeException ignored) {}
        File dir = steamLogs;
        File[] files = dir == null ? null : dir.listFiles(f -> f.isFile() && f.getName().endsWith(".txt"));
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 0; i < Math.min(5, files.length); i++) {
                out.append("== ").append(files[i].getName()).append('\n').append(tail(files[i], 25)).append('\n');
            }
        }
        return Scrub.text(out.toString(), 6000);
    }

    private static String lastLines(String text, int n) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        List<String> keep = new ArrayList<>();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) keep.add(lines[i]);
        return String.join("\n", keep);
    }

    private static String tail(File file, int lines) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long length = in.length();
            long from = Math.max(0, length - 6000);
            byte[] data = new byte[(int) (length - from)];
            in.seek(from);
            in.readFully(data);
            return lastLines(new String(data, java.nio.charset.StandardCharsets.UTF_8), lines);
        }
        catch (Exception e) {
            return "";
        }
    }
}
