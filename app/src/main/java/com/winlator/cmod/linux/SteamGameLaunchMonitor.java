package com.winlator.cmod.linux;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;

import java.io.File;
import java.util.function.BiConsumer;

/** Steam stays alive when a game child fails, so session-exit reporting cannot observe that failure. */
public final class SteamGameLaunchMonitor {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File directory;
    private final BiConsumer<String, String> failure;
    private final long startedAt = System.currentTimeMillis();
    private boolean running;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!running) return;
            File[] files = directory.listFiles((dir, name) -> name.matches("(\\d+|unknown)\\.error"));
            if (files != null) {
                for (File file : files) {
                    if (file.lastModified() < startedAt || file.length() > 32) continue;
                    String text = FileUtils.readString(file);
                    if (text == null) {
                        Log.w("SteamGameLaunch", "Cannot read " + file.getName());
                        continue;
                    }
                    String code = text.trim();
                    if (!file.delete()) {
                        Log.w("SteamGameLaunch", "Cannot consume " + file.getName());
                        continue;
                    }
                    if (!code.matches("\\d+")) {
                        Log.w("SteamGameLaunch", "Invalid exit status in " + file.getName());
                        continue;
                    }
                    failure.accept(file.getName().replace(".error", ""), code);
                }
            }
            if (running) handler.postDelayed(this, 1000);
        }
    };

    public SteamGameLaunchMonitor(File home, BiConsumer<String, String> failure) {
        directory = new File(home, ".skynet/steam-results");
        this.failure = failure;
    }

    public void start() {
        running = true;
        handler.post(check);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(check);
    }
}
