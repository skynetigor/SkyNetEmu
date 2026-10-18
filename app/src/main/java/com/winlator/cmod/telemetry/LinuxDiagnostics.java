package com.winlator.cmod.telemetry;

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
    private static volatile long startedElapsed;
    private static volatile boolean active;
    private static volatile String renderer = "";
    private static volatile long rendererElapsed;
    private static volatile long generation;
    private static final RendererLog rendererLog = new RendererLog();
    private static final java.util.concurrent.atomic.AtomicBoolean importReported = new java.util.concurrent.atomic.AtomicBoolean();
    private static ScheduledThreadPoolExecutor executor;

    private LinuxDiagnostics() {}

    /** A Linux session is starting. {@code logs} is Steam's log folder, {@code programOutput} the program's last lines, {@code description} the setup. */
    public static synchronized void start(android.content.Context context, File logs, Supplier<String> programOutput, String description) {
        if (!Telemetry.isEnabled()) return;
        stop();
        steamLogs = logs;
        output = programOutput;
        setup = description;
        active = true;
        importReported.set(false);
        windowDrawn = false;
        startedAt = System.currentTimeMillis();
        startedElapsed = android.os.SystemClock.elapsedRealtime();
        GameSessionRecorder.resetFrameCount();
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "SkyNet-linux-watchdog");
            t.setDaemon(true);
            return t;
        });
        for (long seconds : CHECKS_S) executor.schedule(() -> check(seconds), seconds, TimeUnit.SECONDS);
        // Keep device conditions even when the process is killed without a session-end event.
        // Fixed delay prevents catch-up bursts after a slow filesystem read or a suspended phone.
        PerfSampler perf = new PerfSampler(context, 0);
        long run = generation;
        executor.scheduleWithFixedDelay(() -> performance(perf, run), 10, 30, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        generation++;
        if (executor != null) executor.shutdownNow();
        executor = null;
        if (active) {
            renderer = "";
            rendererElapsed = 0;
            rendererLog.clear();
        }
        active = false;
    }

    /** Called with a bounded native snapshot; it contains API results and buffer geometry, never file paths. */
    public static void rendererSnapshot(String snapshot) {
        if (Telemetry.isEnabled()) {
            renderer = Scrub.field("skynet.renderer.diagnostics", snapshot);
            rendererElapsed = android.os.SystemClock.elapsedRealtime();
        }
    }

    public static void rendererLogLine(String line) {
        if (active && Telemetry.isEnabled()) rendererLog.add(line);
    }

    /** A rejected frame must be reported even if the client continues sending Present requests. One report per session. */
    public static void rendererImportFailed(String snapshot) {
        if (!active || !Telemetry.isEnabled() || !importReported.compareAndSet(false, true)) return;
        rendererSnapshot(snapshot);
        final String details = renderer;
        final String description = setup;
        final long seconds = (System.currentTimeMillis() - startedAt) / 1000;
        final Supplier<String> programOutput = output;
        final File logDirectory = steamLogs;
        final String renderedLog = rendererLog.snapshot();
        // Reading log tails is never done on the X server's frame/update thread.
        Uploader.background(() -> Telemetry.event("skynet.error", "launch", "failure",
                "skynet.error.stage", "launch",
                "error.type", "linux_frame_import_failed",
                "error.message", Scrub.text(details, 300),
                "skynet.renderer.diagnostics", details,
                "skynet.linux.setup", description,
                "skynet.linux.seconds_running", seconds,
                "skynet.linux.logs", collect(programOutput, logDirectory, renderedLog)));
    }

    /** The session's first window drew. */
    public static void onWindow() {
        windowDrawn = true;
    }

    private static void performance(PerfSampler perf, long run) {
        if (!active || generation != run || !Telemetry.isEnabled()) return;
        try {
            String sample = perf.snapshot();
            long now = android.os.SystemClock.elapsedRealtime();
            sample += " accepted_game_frames=" + GameSessionRecorder.frameCount()
                    + " renderer_age_ms=" + (rendererElapsed > 0 ? Math.max(0, now - rendererElapsed) : -1);
            if (!active || generation != run || !Telemetry.isEnabled() || Thread.currentThread().isInterrupted()) return;
            Telemetry.event("skynet.session", "linux_performance", (String) null,
                    "skynet.performance.diagnostics", sample,
                    "skynet.renderer.diagnostics", renderer,
                    "skynet.linux.setup", setup,
                    "skynet.linux.seconds_running", (now - startedElapsed) / 1000);
        } catch (RuntimeException ignored) {}
    }

    private static void check(long seconds) {
        if (!active || !Telemetry.isEnabled()) return;
        if (seconds == CHECKS_S[0]) {
            // A successful import can still display black. Send one neutral snapshot even when the watchdog sees frames.
            Telemetry.event("skynet.session", "linux_renderer", (String) null,
                    "skynet.renderer.diagnostics", renderer,
                    "skynet.linux.setup", setup,
                    "skynet.linux.seconds_running", (System.currentTimeMillis() - startedAt) / 1000,
                    "skynet.linux.logs", collect());
        }
        if (GameSessionRecorder.frameCount() > 0) return;
        Telemetry.event("skynet.error", "linux_render_stalled", "failure",
                "skynet.error.stage", "linux_render_stalled",
                "error.type", windowDrawn ? "window_without_frames" : "no_window",
                "error.message", "No frame was presented " + seconds + " s after the session started",
                "skynet.linux.seconds_running", (System.currentTimeMillis() - startedAt) / 1000,
                "skynet.linux.setup", setup,
                "skynet.renderer.diagnostics", renderer,
                "skynet.linux.logs", collect());
    }

    /** The program's last output lines and the tail of Steam's newest log files, with paths and personal text taken out. */
    public static String collect() {
        return collect(output, steamLogs, rendererLog.snapshot());
    }

    private static String collect(Supplier<String> programOutput, File logDirectory, String nativeLog) {
        StringBuilder out = new StringBuilder();
        if (!nativeLog.isEmpty()) out.append("== renderer\n").append(nativeLog).append('\n');
        try {
            out.append("== output\n").append(lastLines(programOutput.get(), 30)).append('\n');
        }
        catch (RuntimeException ignored) {}
        File dir = logDirectory;
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
