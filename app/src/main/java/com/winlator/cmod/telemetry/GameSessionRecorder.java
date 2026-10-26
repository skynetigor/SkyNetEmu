package com.winlator.cmod.telemetry;

import android.content.Context;
import android.os.SystemClock;

/**
 * Records one play session: how long the game took to show a window and a renderer, its frame rate, and what the device did meanwhile
 * ({@link PerfSampler}). One {@code skynet.game_session} event is written when the session ends.
 *
 * {@link #onFrame} is called for every frame of the game's render window, from whichever thread draws it. It does a clock read and an
 * array increment and nothing else: no locks, no allocation, no I/O. The counts are not atomic on purpose; a rare lost increment does
 * not matter for a frame-time histogram.
 */
public final class GameSessionRecorder {
    /** Frame times in 1 ms bins; the last bin holds everything of 255 ms or more (loading stalls). */
    private static final int BINS = 256;
    private static final int[] FRAME_BINS = new int[BINS];

    private static volatile boolean active;
    private static PerfSampler sampler;
    private static long startMs, startElapsed;
    private static long windowMs = -1, rendererMs = -1;
    private static long firstFrameNs, lastFrameNs;
    private static int frames;
    /** Frames presented since {@link #resetFrameCount}, whether or not a game session is being recorded (a Linux session is not). */
    private static volatile int framesSeen;
    private static int exitCode = Integer.MIN_VALUE;
    private static String exitKind = "user";
    private static Object[] gameFields;

    private GameSessionRecorder() {}

    /**
     * The game is starting. Does nothing when telemetry is off.
     * @param storage "internal" or "shared"
     */
    public static synchronized void start(Context context, String gameId, String gameVersion, String configId, String wine,
                                          String emulator, String driver, boolean fileCache, String storage) {
        if (!Telemetry.isEnabled() || active) return;
        java.util.Arrays.fill(FRAME_BINS, 0);
        frames = 0;
        firstFrameNs = lastFrameNs = 0;
        windowMs = rendererMs = -1;
        exitCode = Integer.MIN_VALUE;
        exitKind = "user";
        startMs = System.currentTimeMillis();
        startElapsed = SystemClock.elapsedRealtime();
        gameFields = new Object[]{
                "skynet.game.id", gameId == null ? "unknown" : gameId,
                "skynet.game.version", gameVersion,
                "skynet.config.id", configId,
                "skynet.wine", wine,
                "skynet.emulator", emulator,
                "skynet.driver", driver,
                "skynet.file_cache", fileCache,
                "skynet.storage", storage};
        Telemetry.event("skynet.funnel", "game_launched", gameFields);
        sampler = new PerfSampler(context, Uploader.sampleSeconds());
        sampler.start();
        active = true;
    }

    static void resetFrameCount() { framesSeen = 0; }

    static int frameCount() { return framesSeen; }

    /** The first window of the game showed content. */
    public static void onWindow() {
        LinuxDiagnostics.onWindow();
        if (active && windowMs < 0) windowMs = SystemClock.elapsedRealtime() - startElapsed;
    }

    /** The game's 3D renderer announced itself. */
    public static void onRenderer() {
        if (active && rendererMs < 0) rendererMs = SystemClock.elapsedRealtime() - startElapsed;
    }

    /** A frame of the game's render window was presented. */
    public static void onFrame() {
        framesSeen++;
        if (!active) return;
        long now = System.nanoTime();
        if (frames == 0) firstFrameNs = now;
        else {
            long ms = (now - lastFrameNs) / 1_000_000L;
            FRAME_BINS[ms >= BINS ? BINS - 1 : (int) ms]++;
        }
        lastFrameNs = now;
        frames++;
    }

    /** The game's own process ended by itself (not the user leaving): its exit status. */
    public static void onGuestExit(int status) {
        if (!active) return;
        exitCode = status;
        exitKind = "wine_exit";
    }

    /** The session is over: writes the summary (a memory write only). The caller flushes with {@link Telemetry#flushNow} before the process ends. */
    public static synchronized void end() {
        if (!active) return;
        active = false;
        PerfSampler s = sampler;
        sampler = null;
        if (s != null) s.stop();
        long durationMs = SystemClock.elapsedRealtime() - startElapsed;

        java.util.List<Object> f = new java.util.ArrayList<>();
        for (Object o : gameFields) f.add(o);
        f.add("event.duration"); f.add(durationMs * 1_000_000L);
        add(f, "skynet.time_to_window_ms", windowMs);
        add(f, "skynet.time_to_renderer_ms", rendererMs);
        f.add("skynet.exit.kind"); f.add(exitKind);
        if (exitCode != Integer.MIN_VALUE) { f.add("skynet.exit.code"); f.add(exitCode); }

        if (frames >= 30 && lastFrameNs > firstFrameNs) {
            double seconds = (lastFrameNs - firstFrameNs) / 1e9;
            f.add("skynet.fps.avg"); f.add(round(frames / seconds));
            int p99ms = percentileMs(0.99);
            f.add("skynet.frametime.p99_ms"); f.add(p99ms);
            if (p99ms > 0) { f.add("skynet.fps.p1_low"); f.add(round(1000.0 / p99ms)); }
        }
        if (s != null) {
            addF(f, "skynet.cpu.avg_pct", s.cpuAvgPercent());
            addF(f, "skynet.gpu.avg_pct", s.gpuAvgPercent());
            add(f, "skynet.temp.cpu_max_c", s.cpuTempMaxC);
            add(f, "skynet.temp.battery_max_c", s.batteryTempMaxC);
            add(f, "skynet.thermal.max_status", s.thermalMax);
            add(f, "skynet.memory.peak_used_bytes", s.ramPeakUsedBytes);
            if (s.batteryFirstPct >= 0 && s.batteryLastPct >= 0) {
                f.add("skynet.battery.drain_pct"); f.add(s.batteryFirstPct - s.batteryLastPct);
            }
        }
        f.add("skynet.performance.diagnostics"); f.add(com.winlator.cmod.perf.PerformanceSession.diagnostics());
        Telemetry.event("skynet.game_session", "session_ended", f.toArray());
    }

    /** The frame time that 99 % of the frames beat, in ms, from the histogram. */
    private static int percentileMs(double fraction) {
        long total = 0;
        for (int c : FRAME_BINS) total += c;
        long limit = (long) Math.ceil(total * fraction), seen = 0;
        for (int ms = 0; ms < BINS; ms++) {
            seen += FRAME_BINS[ms];
            if (seen >= limit) return ms;
        }
        return BINS - 1;
    }

    private static void add(java.util.List<Object> f, String key, long value) {
        if (value >= 0) { f.add(key); f.add(value); }
    }

    private static void addF(java.util.List<Object> f, String key, float value) {
        if (value >= 0) { f.add(key); f.add(round(value)); }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
