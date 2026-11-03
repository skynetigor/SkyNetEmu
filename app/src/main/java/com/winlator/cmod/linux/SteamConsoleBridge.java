package com.winlator.cmod.linux;

import android.util.Log;

import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Types commands into the Steam client's console of a running Steam session.
 *
 * <p>Commands are lines appended to {@code ~/.skynet/steam-console.in} (see {@link #queue}); the bridge, which lives next to the
 * session's X server, opens the console (a URL written to the client's {@code steam.pipe}) and types each line through the
 * server's own key injection, then presses Enter. Used for {@code download_depot <app> <depot> <manifest>}, which the client
 * only offers in its console.
 */
public final class SteamConsoleBridge {
    private static final String TAG = "SteamConsole";
    private static final long KEY_DELAY_MS = 12;
    private static final long CONSOLE_SETTLE_MS = 2500;

    private final XServer xServer;
    private final File input;
    private final File pipe;
    private volatile boolean running;
    private Thread thread;
    private boolean consoleOpened;

    public SteamConsoleBridge(XServer xServer, File linuxHome) {
        this.xServer = xServer;
        this.input = new File(linuxHome, ".skynet/steam-console.in");
        this.pipe = new File(linuxHome, ".steam/steam.pipe");
    }

    /** Adds a command for the bridge of the session that runs in the container whose home is given. */
    public static void queue(File linuxHome, String command) throws IOException {
        File file = new File(linuxHome, ".skynet/steam-console.in");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((command.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    public void start() {
        if (running) return;
        running = true;
        // Commands left over from an earlier session must not be typed into this one.
        //noinspection ResultOfMethodCallIgnored
        input.delete();
        thread = new Thread(this::loop, "steam-console");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                if (input.isFile() && input.length() > 0) {
                    List<String> lines = Files.readAllLines(input.toPath(), StandardCharsets.UTF_8);
                    // Consumed before typing: a command is never typed twice.
                    //noinspection ResultOfMethodCallIgnored
                    input.delete();
                    for (String line : lines) {
                        String command = line.trim();
                        if (!command.isEmpty() && running) run(command);
                    }
                }
                Thread.sleep(500);
            }
            catch (InterruptedException e) {
                return;
            }
            catch (IOException | RuntimeException e) {
                Log.w(TAG, "Console bridge failed", e);
            }
        }
    }

    private void run(String command) throws InterruptedException {
        if (!consoleOpened) {
            openConsole();
            consoleOpened = true;
            Thread.sleep(CONSOLE_SETTLE_MS);
        }
        Log.i(TAG, "typing: " + command);
        typeLine(command);
    }

    /** The client's pipe only opens for a reader: the write is made on its own thread so a missing client cannot hold the bridge. */
    private void openConsole() {
        Thread writer = new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream(pipe)) {
                out.write("steam://open/console\n".getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException e) {
                Log.w(TAG, "Cannot open the console through " + pipe, e);
            }
        }, "steam-console-open");
        writer.setDaemon(true);
        writer.start();
    }

    private void typeLine(String text) throws InterruptedException {
        for (char c : text.toCharArray()) {
            XKeycode key = keyFor(c);
            if (key == null) {
                Log.w(TAG, "Cannot type '" + c + "'");
                continue;
            }
            boolean shift = c == '_' || Character.isUpperCase(c);
            if (shift) xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
            xServer.injectKeyPress(key, c);
            Thread.sleep(KEY_DELAY_MS);
            xServer.injectKeyRelease(key);
            if (shift) xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
            Thread.sleep(KEY_DELAY_MS);
        }
        xServer.injectKeyPress(XKeycode.KEY_ENTER);
        Thread.sleep(KEY_DELAY_MS);
        xServer.injectKeyRelease(XKeycode.KEY_ENTER);
    }

    /** The characters of a console command: letters, digits, space, '_' and '-'. */
    static XKeycode keyFor(char c) {
        if (c == ' ') return XKeycode.KEY_SPACE;
        if (c == '_' || c == '-') return XKeycode.KEY_MINUS;
        if (c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
            try {
                return XKeycode.valueOf("KEY_" + Character.toUpperCase(c));
            }
            catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
