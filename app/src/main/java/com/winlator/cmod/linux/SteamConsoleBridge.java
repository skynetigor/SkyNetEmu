package com.winlator.cmod.linux;

import android.util.Log;

import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
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
    private static final long CONSOLE_SETTLE_MS = 8000;
    private static final long CONSOLE_WAIT_MS = 90_000;
    /** Where the console's input line is, as a fraction of the client window's height from its top. */
    private static final double INPUT_LINE_AT = 0.895;

    private final XServer xServer;
    private final File input;
    private final File pipe;
    private final File consoleLog;
    private volatile boolean running;
    private Thread thread;
    private boolean consoleOpened;

    public SteamConsoleBridge(XServer xServer, File linuxHome) {
        this.xServer = xServer;
        this.input = new File(linuxHome, ".skynet/steam-console.in");
        this.pipe = new File(linuxHome, ".steam/steam.pipe");
        this.consoleLog = new SteamPaths(linuxHome).consoleLog();
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
            long offset = consoleLog.length();
            openConsole();
            consoleOpened = true;
            // A client that is still starting takes the URL seconds later: its log says when it has (the tab needs a moment more).
            long waited = 0;
            while (waited < CONSOLE_WAIT_MS && !SteamVersionDownloader.readFrom(consoleLog, offset).contains("steam://open/console")) {
                Thread.sleep(500);
                waited += 500;
            }
            Thread.sleep(CONSOLE_SETTLE_MS);
        }
        Log.i(TAG, "typing: " + command);
        clickInput();
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

    /**
     * The console's input line takes keys only once it has been clicked (opened by the pipe, it shows but is not focused): a click on
     * the bottom of the client's window, where the line is. The client's window is the biggest one on the screen.
     */
    private void clickInput() throws InterruptedException {
        Window client = null;
        long area = 0;
        try {
            for (Window window : xServer.windowManager.rootWindow.getChildren()) {
                if (!window.isMappedTopLevel()) continue;
                long size = (long) window.getWidth() * window.getHeight();
                if (size > area) {
                    area = size;
                    client = window;
                }
            }
        }
        catch (RuntimeException e) {
            Log.w(TAG, "Cannot look for the client's window", e);
        }
        if (client == null) {
            Log.w(TAG, "No client window to click");
            return;
        }
        // The client's text field takes keys only while its window has the focus as far as the web view knows: the window is given the
        // focus anew (FocusOut, FocusIn), and the click that follows puts the caret in the field.
        com.winlator.cmod.xserver.WindowManager manager = xServer.windowManager;
        // The window the client's text goes to: the one that has the focus if it is part of the client's main window, else the main window
        // (a second window of the client, the friends list for one, may have taken the focus when it appeared).
        Window focused = manager.getFocusedWindow();
        Window target = client;
        for (Window w = focused; w != null; w = w.getParent()) {
            if (w == client) {
                target = focused;
                break;
            }
        }
        manager.setFocus(manager.rootWindow, com.winlator.cmod.xserver.WindowManager.FocusRevertTo.POINTER_ROOT);
        Thread.sleep(200);
        manager.setFocus(target, com.winlator.cmod.xserver.WindowManager.FocusRevertTo.POINTER_ROOT);
        Thread.sleep(500);
        int x = client.getRootX() + client.getWidth() / 2;
        int y = client.getRootY() + (int) (client.getHeight() * INPUT_LINE_AT);
        Log.i(TAG, "click " + x + "," + y + " window " + client.id + " " + client.getWidth() + "x" + client.getHeight());
        xServer.injectPointerMove(x, y);
        Thread.sleep(80);
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        Thread.sleep(60);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        Thread.sleep(600);
        Log.i(TAG, "clicked");
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
            // No keysym: the server's own keymap names the key. With one the server rewrites its map and tells the client (MappingNotify)
            // at every key, and the client's text field then ignores the keys.
            xServer.injectKeyPress(key);
            Thread.sleep(KEY_DELAY_MS);
            xServer.injectKeyRelease(key);
            if (shift) xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
            Thread.sleep(KEY_DELAY_MS);
        }
        xServer.injectKeyPress(XKeycode.KEY_ENTER);
        Thread.sleep(KEY_DELAY_MS);
        xServer.injectKeyRelease(XKeycode.KEY_ENTER);
        Log.i(TAG, "typed");
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
