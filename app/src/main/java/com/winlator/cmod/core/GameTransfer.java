package com.winlator.cmod.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies or moves a game's folder from shared storage to the app's own storage, where Wine reads it much faster.
 *
 * One job at a time. The state can be read from any thread and listeners are called on the main thread, so a dialog can
 * attach to a job that is already running. Steps: measure, check the free space, copy, verify (every file is there with
 * its size), point every shortcut of that game at the copy, and for a move delete the original. A cancelled or failed job
 * removes the partial copy and leaves the original alone.
 */
public final class GameTransfer {
    private static final String TAG = "GameTransfer";
    private static final long SPARE_BYTES = 1L << 30;

    public enum Mode { COPY, MOVE }

    public enum Phase { IDLE, MEASURING, COPYING, VERIFYING, REPOINTING, DELETING, DONE, FAILED, CANCELLED }

    /** What the user sees. Replaced as a whole on every change, so a reader always gets a consistent picture. */
    public static final class State {
        public final Phase phase;
        public final Mode mode;
        public final String gameName;
        public final String source;
        public final String target;
        public final long bytesDone, bytesTotal;
        public final int filesDone, filesTotal;
        public final String currentFile;
        public final long startedAtMs;
        /** Result or error text for DONE, FAILED and CANCELLED. */
        public final String message;
        public final int shortcutsChanged;

        State(Phase phase, Mode mode, String gameName, String source, String target, long bytesDone, long bytesTotal,
              int filesDone, int filesTotal, String currentFile, long startedAtMs, String message, int shortcutsChanged) {
            this.phase = phase;
            this.mode = mode;
            this.gameName = gameName;
            this.source = source;
            this.target = target;
            this.bytesDone = bytesDone;
            this.bytesTotal = bytesTotal;
            this.filesDone = filesDone;
            this.filesTotal = filesTotal;
            this.currentFile = currentFile;
            this.startedAtMs = startedAtMs;
            this.message = message;
            this.shortcutsChanged = shortcutsChanged;
        }

        public boolean running() {
            return phase == Phase.MEASURING || phase == Phase.COPYING || phase == Phase.VERIFYING
                    || phase == Phase.REPOINTING || phase == Phase.DELETING;
        }

        public boolean finished() {
            return phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.CANCELLED;
        }
    }

    private static final GameTransfer INSTANCE = new GameTransfer();

    public static GameTransfer get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile State state = new State(Phase.IDLE, Mode.COPY, "", "", "", 0, 0, 0, 0, "", 0, "", 0);
    private volatile Thread worker;
    private long lastPublishMs;

    private GameTransfer() {}

    public State getState() {
        return state;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void cancel() {
        cancelled.set(true);
    }

    /** Forgets a finished job (the dialog calls this when the user closes the result). */
    public void acknowledge() {
        if (state.finished()) publish(new State(Phase.IDLE, state.mode, "", "", "", 0, 0, 0, 0, "", 0, "", 0), true);
    }

    private void publish(State next, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPublishMs < 250) return;
        lastPublishMs = now;
        state = next;
        main.post(() -> {
            for (Runnable listener : listeners) listener.run();
        });
    }

    /**
     * Starts a job for the game a shortcut starts.
     * @return an error text when it cannot start (another job is running, the exe cannot be found), otherwise null
     */
    public synchronized String start(Context context, Shortcut shortcut, Mode mode) {
        if (state.running()) return "Another copy is already running.";
        Context app = context.getApplicationContext();
        ImageFs imageFs = ImageFs.find(app);
        GameFolder game = GameFolder.of(imageFs, shortcut.container, shortcut.path);
        if (game == null || !game.folder.isDirectory()) return "Cannot find this game's folder.";
        if (GameFolder.isInternal(app, game.folder)) return "This game is already on internal storage.";
        cancelled.set(false);
        final String name = game.folder.getName();
        publish(new State(Phase.MEASURING, mode, name, game.folder.getPath(), "", 0, 0, 0, 0, "", System.currentTimeMillis(), "", 0), true);
        worker = new Thread(() -> run(app, imageFs, game.folder, mode, name), "GameTransfer");
        worker.start();
        return null;
    }

    private void run(Context context, ImageFs imageFs, File source, Mode mode, String name) {
        long started = System.currentTimeMillis();
        File target = null;
        try {
            long[] size = GameFolder.measure(source, cancelled);
            if (cancelled.get()) { finish(Phase.CANCELLED, mode, name, source, null, started, "Cancelled.", 0); return; }
            long needed = size[0];
            long free = new StatFs(context.getFilesDir().getPath()).getAvailableBytes();
            if (free < needed + SPARE_BYTES) {
                finish(Phase.FAILED, mode, name, source, null, started, "Not enough free space on the device: the game needs "
                        + formatSize(needed) + " and about " + formatSize(SPARE_BYTES) + " must stay free, "
                        + formatSize(free) + " is free.", 0);
                return;
            }

            File games = GameFolder.gamesRoot(imageFs);
            games.mkdirs();
            target = new File(games, name);
            for (int n = 2; target.exists(); n++) target = new File(games, name + " (" + n + ")");

            // 1. copy, remembering every file for the check
            List<String> relative = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            copyTree(source, target, mode, name, size, started, relative, sizes);
            if (cancelled.get()) { FileUtils.delete(target); finish(Phase.CANCELLED, mode, name, source, null, started, "Cancelled. The partial copy was removed.", 0); return; }

            // 2. every file must be there with the same size
            publish(snapshot(Phase.VERIFYING, mode, name, source, target, size, size[0], (int) size[1], "", started, "", 0), true);
            for (int i = 0; i < relative.size(); i++) {
                File copy = new File(target, relative.get(i));
                if (!Files.isSymbolicLink(copy.toPath()) && copy.length() != sizes.get(i))
                    throw new IOException("The copy of " + relative.get(i) + " is not complete.");
                if (cancelled.get()) { FileUtils.delete(target); finish(Phase.CANCELLED, mode, name, source, null, started, "Cancelled. The partial copy was removed.", 0); return; }
            }

            // 3. shortcuts of this game now start the copy
            publish(snapshot(Phase.REPOINTING, mode, name, source, target, size, size[0], (int) size[1], "", started, "", 0), true);
            int changed = repointShortcuts(context, imageFs, source, target);

            // 4. a move removes the original
            String message = "Copied " + formatSize(size[0]) + " to internal storage.";
            if (mode == Mode.MOVE) {
                publish(snapshot(Phase.DELETING, mode, name, source, target, size, size[0], (int) size[1], "", started, "", changed), true);
                int left = deleteTree(source);
                message = left == 0 ? "Moved " + formatSize(size[0]) + " to internal storage."
                        : "Moved " + formatSize(size[0]) + " to internal storage, but " + left
                                + " files of the original could not be deleted.";
            }
            finish(Phase.DONE, mode, name, source, target, started, message, changed);
        } catch (Exception e) {
            Log.e(TAG, "Transfer failed", e);
            if (target != null) FileUtils.delete(target);
            finish(Phase.FAILED, mode, name, source, null, started, "The copy failed: " + e.getMessage()
                    + ". The original was not changed and the partial copy was removed.", 0);
        }
    }

    private void finish(Phase phase, Mode mode, String name, File source, File target, long started, String message, int changed) {
        publish(new State(phase, mode, name, source.getPath(), target == null ? "" : target.getPath(), 0, 0, 0, 0, "", started, message, changed), true);
    }

    private State snapshot(Phase phase, Mode mode, String name, File source, File target, long[] size, long bytesDone, int filesDone,
                           String current, long started, String message, int changed) {
        return new State(phase, mode, name, source.getPath(), target == null ? "" : target.getPath(), bytesDone, size[0], filesDone,
                (int) size[1], current, started, message, changed);
    }

    private void copyTree(File source, File target, Mode mode, String name, long[] size, long started, List<String> relative,
                          List<Long> sizes) throws IOException {
        if (!target.mkdirs()) throw new IOException("cannot create " + target);
        ArrayDeque<File[]> stack = new ArrayDeque<>();   // {source dir, target dir}
        stack.push(new File[]{source, target});
        long done = 0;
        int files = 0;
        byte[] buffer = new byte[256 * 1024];
        String prefix = source.getPath() + "/";
        while (!stack.isEmpty()) {
            File[] pair = stack.pop();
            File[] children = pair[0].listFiles();
            if (children == null) throw new IOException("cannot read " + pair[0]);
            for (File child : children) {
                if (cancelled.get()) return;
                File copy = new File(pair[1], child.getName());
                if (Files.isSymbolicLink(child.toPath())) {
                    Files.createSymbolicLink(copy.toPath(), Files.readSymbolicLink(child.toPath()));
                    relative.add(child.getPath().substring(prefix.length()));
                    sizes.add(0L);
                    files++;
                } else if (child.isDirectory()) {
                    if (!copy.mkdirs()) throw new IOException("cannot create " + copy);
                    stack.push(new File[]{child, copy});
                } else {
                    long length = child.length();
                    try (FileInputStream in = new FileInputStream(child); FileOutputStream out = new FileOutputStream(copy)) {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                            done += read;
                            if (cancelled.get()) return;
                            publish(snapshot(Phase.COPYING, mode, name, source, target, size, done, files, child.getName(), started, "", 0), false);
                        }
                        out.getFD().sync();
                    }
                    copy.setLastModified(child.lastModified());
                    relative.add(child.getPath().substring(prefix.length()));
                    sizes.add(length);
                    files++;
                    publish(snapshot(Phase.COPYING, mode, name, source, target, size, done, files, child.getName(), started, "", 0), false);
                }
            }
        }
    }

    /** Deletes everything under (and including) the folder; returns how many entries could not be deleted. */
    private int deleteTree(File folder) {
        int failed = 0;
        ArrayDeque<File> dirs = new ArrayDeque<>();
        ArrayList<File> order = new ArrayList<>();
        dirs.push(folder);
        while (!dirs.isEmpty()) {
            File dir = dirs.pop();
            order.add(dir);
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (!Files.isSymbolicLink(child.toPath()) && child.isDirectory()) dirs.push(child);
                else if (!child.delete()) failed++;
            }
        }
        for (int i = order.size() - 1; i >= 0; i--) if (!order.get(i).delete()) failed++;
        return failed;
    }

    /**
     * Every shortcut (of every container) whose exe is inside the old folder gets the same exe in the new one. The .desktop
     * file is edited as text: the Exec line (and a Path line, if there is one) change, everything else stays.
     */
    private int repointShortcuts(Context context, ImageFs imageFs, File source, File target) throws IOException {
        int changed = 0;
        String oldPrefix = source.getPath() + "/";
        ContainerManager manager = new ContainerManager(context);
        for (Shortcut shortcut : manager.loadShortcuts()) {
            String exe = GameFolder.unixExePath(imageFs, shortcut.container, shortcut.path);
            if (exe == null || !exe.startsWith(oldPrefix)) continue;
            String newExe = new File(target, exe.substring(oldPrefix.length())).getPath();
            List<String> lines = java.nio.file.Files.readAllLines(shortcut.file.toPath());
            List<String> out = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("Exec=")) line = rewriteExec(line, newExe);
                else if (line.startsWith("Path=")) line = "Path=" + new File(newExe).getParent();
                out.add(line);
            }
            File temp = new File(shortcut.file.getPath() + ".tmp");
            Files.write(temp.toPath(), out);
            Files.move(temp.toPath(), shortcut.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            changed++;
        }
        return changed;
    }

    /** Exec=env WINEPREFIX="..." wine "OLD.exe" args  ->  the same with the exe replaced, arguments kept. */
    static String rewriteExec(String line, String newExe) {
        int wine = line.lastIndexOf("wine ");
        if (wine < 0) return line;
        int start = wine + 5;
        String rest = line.substring(start);
        boolean quoted = rest.startsWith("\"");
        int exe = rest.toLowerCase(java.util.Locale.ENGLISH).indexOf(".exe");
        if (exe < 0) return line;
        int end = exe + 4 + (quoted && rest.length() > exe + 4 && rest.charAt(exe + 4) == '"' ? 1 : 0);
        String tail = rest.substring(end);
        boolean needsQuotes = quoted || newExe.indexOf(' ') >= 0;
        return line.substring(0, start) + (needsQuotes ? "\"" + newExe + "\"" : newExe) + tail;
    }

    public static String formatSize(long bytes) {
        if (bytes >= (1L << 30)) return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= (1L << 20)) return String.format(java.util.Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(java.util.Locale.US, "%d KB", bytes >> 10);
    }
}
