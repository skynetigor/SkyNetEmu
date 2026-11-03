package com.winlator.cmod.linux;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Runs one {@link SteamDownloadJob} inside a Steam client session: types {@code download_depot <app> <depot> <manifest>} into the client's
 * console (through {@link SteamConsoleBridge}, whose queue file the bridge of the same session reads), one depot at a time, and watches the
 * client's console log for the end of each; then puts the depots together into the game's folder and has the shortcut made.
 * Progress is the size of the depot folder against the size Steam listed. Listeners are called on the main thread.
 */
public final class SteamVersionDownloader {
    private static final String TAG = "SteamVersionDownload";
    private static final long POLL_MS = 1000;
    private static final long READY_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long STALL_MS = 10 * 60 * 1000L;
    private static final double SPACE_MARGIN = 1.1;
    private static final int COMMAND_ATTEMPTS = 4;
    private static final long COMMAND_RETRY_MS = 60 * 1000L;

    /** What the UI shows. */
    public static final class Status {
        public final SteamDownloadJob.State state;
        public final String message;
        public final long doneBytes, totalBytes;
        public final long bytesPerSecond;
        public final int depotIndex, depotCount;
        public final String error;

        Status(SteamDownloadJob.State state, String message, long doneBytes, long totalBytes, long bytesPerSecond, int depotIndex, int depotCount, String error) {
            this.state = state;
            this.message = message;
            this.doneBytes = doneBytes;
            this.totalBytes = totalBytes;
            this.bytesPerSecond = bytesPerSecond;
            this.depotIndex = depotIndex;
            this.depotCount = depotCount;
            this.error = error;
        }

        /** 0..1, or -1 when the total is unknown. */
        public float fraction() {
            return totalBytes <= 0 ? -1f : Math.min(1f, (float) doneBytes / totalBytes);
        }

        /** Seconds left at the current speed, or -1. */
        public long etaSeconds() {
            return bytesPerSecond <= 0 || totalBytes <= 0 ? -1 : Math.max(0, (totalBytes - doneBytes) / bytesPerSecond);
        }
    }

    public interface Listener {
        void onStatus(Status status);
    }

    /** Makes the shortcut of the finished download; returns the path of the .desktop file. Runs on the downloader's thread. */
    public interface ShortcutMaker {
        String make(SteamDownloadJob job, File gameFolder) throws Exception;
    }

    private final SteamDownloadJob job;
    private final SteamPaths paths;
    private final ShortcutMaker shortcutMaker;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    /** Set when the Steam session ended under the job: the job fails (and can be resumed), its files are kept. */
    private volatile String lostReason;
    private Thread thread;
    private volatile Status status;

    public SteamVersionDownloader(SteamDownloadJob job, File linuxHome, ShortcutMaker shortcutMaker, Listener listener) {
        this.job = job;
        this.paths = new SteamPaths(linuxHome);
        this.shortcutMaker = shortcutMaker;
        this.listener = listener;
    }

    public Status status() {
        return status;
    }

    public SteamDownloadJob job() {
        return job;
    }

    public void start() {
        if (thread != null) return;
        thread = new Thread(this::run, "steam-version-download");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops after the command in progress; the partial files are removed by the thread. */
    public void cancel() {
        cancelled = true;
        if (thread != null) thread.interrupt();
    }

    /** The session that runs the client has ended (it crashed, or was closed): a download in progress fails so it can be resumed. */
    public void sessionEnded(String why) {
        SteamDownloadJob.State state = job.state;
        if (state == SteamDownloadJob.State.DONE || state == SteamDownloadJob.State.FAILED || state == SteamDownloadJob.State.CANCELLED) return;
        lostReason = why;
        if (thread != null) thread.interrupt();
    }

    /** True once the job has reached its end, one way or the other. */
    public boolean over() {
        SteamDownloadJob.State state = job.state;
        return state == SteamDownloadJob.State.DONE || state == SteamDownloadJob.State.FAILED || state == SteamDownloadJob.State.CANCELLED;
    }

    /** The message to show instead of starting, or null when the files fit (the sum of the depots and a tenth more). */
    public static String spaceProblem(File linuxHome, long bytes) {
        File check = linuxHome;
        while (check != null && !check.exists()) check = check.getParentFile();
        if (check == null || bytes <= 0) return null;
        long need = (long) (bytes * SPACE_MARGIN), free = check.getUsableSpace();
        if (free >= need) return null;
        return "There is not enough room: " + mb(need) + " MB are needed and " + mb(free) + " MB are free.";
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private void publish(SteamDownloadJob.State state, String message, long done, long speed, int index, String error) {
        Status s = new Status(state, message, done, job.totalBytes(), speed, index, job.depots.size(), error);
        status = s;
        main.post(() -> listener.onStatus(s));
    }

    private void saveJob() {
        try {
            job.save(paths.jobsDir());
        }
        catch (IOException e) {
            Log.w(TAG, "Cannot save the job", e);
        }
    }

    private void fail(String error) {
        Log.w(TAG, "Failed: " + error);
        job.state = SteamDownloadJob.State.FAILED;
        job.error = error;
        saveJob();
        publish(SteamDownloadJob.State.FAILED, "Failed", doneBytes(null, 0), 0, 0, error);
    }

    private void run() {
        try {
            download();
        }
        catch (InterruptedException e) {
            if (lostReason != null) fail(lostReason);
            else finishCancelled();
        }
        catch (Exception e) {
            Log.w(TAG, "Download failed", e);
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void finishCancelled() {
        job.state = SteamDownloadJob.State.CANCELLED;
        // The depots Steam left half-written are of no use to anyone else; the finished-and-merged ones are not here any more.
        for (File root : paths.contentRoots()) SteamDepotMerger.deleteRecursively(new File(root, "app_" + job.appId));
        job.delete(paths.jobsDir());
        publish(SteamDownloadJob.State.CANCELLED, "Cancelled", 0, 0, 0, "");
    }

    private void download() throws Exception {
        job.state = SteamDownloadJob.State.DOWNLOADING;
        job.error = "";
        saveJob();
        File log = paths.consoleLog();
        long sessionStart = log.length();
        publish(job.state, "Waiting for Steam to sign in…", doneBytes(null, 0), 0, 0, "");
        long waited = 0;
        while (!SteamConsoleLog.loggedOn(readFrom(log, sessionStart))) {
            checkCancelled();
            Thread.sleep(POLL_MS);
            waited += POLL_MS;
            if (waited > READY_TIMEOUT_MS) {
                fail("Steam did not sign in. Open Steam once and sign in, then try again.");
                return;
            }
        }
        for (int i = 0; i < job.depots.size(); i++) {
            SteamDownloadJob.Depot depot = job.depots.get(i);
            if (depot.done) continue;
            if (!downloadDepot(depot, i)) return;
        }
        merge();
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled) throw new InterruptedException("cancelled");
    }

    private boolean downloadDepot(SteamDownloadJob.Depot depot, int index) throws Exception {
        File log = paths.consoleLog();
        long offset = log.length();
        String command = "download_depot " + job.appId + " " + depot.id + " " + depot.manifest;
        Log.i(TAG, "queue: " + command);
        SteamConsoleBridge.queue(paths.home(), command);
        long queuedAt = System.currentTimeMillis();
        int attempts = 1;
        long lastLogLength = offset, lastSize = -1, lastChange = System.currentTimeMillis();
        long[] windowBytes = new long[10];
        long[] windowTime = new long[10];
        int samples = 0;
        while (true) {
            checkCancelled();
            Thread.sleep(POLL_MS);
            String text = readFrom(log, offset);
            SteamConsoleLog.Result result = SteamConsoleLog.find(text, depot.id, depot.manifest);
            if (result.kind == SteamConsoleLog.Kind.COMPLETE) {
                depot.done = true;
                depot.path = result.text;
                saveJob();
                publish(job.state, "Depot " + (index + 1) + " of " + job.depots.size() + " done", doneBytes(null, 0), 0, index + 1, "");
                return true;
            }
            if (result.kind == SteamConsoleLog.Kind.FAILED) {
                fail("Steam said: " + result.text);
                return false;
            }
            long size = SteamDepotMerger.size(depotDir(depot));
            long now = System.currentTimeMillis();
            // A command that fell on a console that was not ready does nothing: nothing in the log about the depot and no files after a
            // minute means it has to be typed again (Steam ignores a repeat of a download that is running).
            if (size == 0 && attempts < COMMAND_ATTEMPTS && now - queuedAt > COMMAND_RETRY_MS && !text.contains(String.valueOf(depot.id))) {
                attempts++;
                queuedAt = now;
                Log.i(TAG, "queue again (" + attempts + "): " + command);
                SteamConsoleBridge.queue(paths.home(), command);
            }
            long logLength = log.length();
            if (size != lastSize || logLength != lastLogLength) {
                lastSize = size;
                lastLogLength = logLength;
                lastChange = now;
            }
            else if (now - lastChange > STALL_MS) {
                fail("Steam made no progress on depot " + depot.id + " for ten minutes.");
                return false;
            }
            int slot = samples % windowBytes.length;
            windowBytes[slot] = size;
            windowTime[slot] = now;
            samples++;
            long speed = 0;
            if (samples >= 2) {
                int oldest = samples >= windowBytes.length ? samples % windowBytes.length : 0;
                long seconds = Math.max(1, (now - windowTime[oldest]) / 1000);
                speed = Math.max(0, (size - windowBytes[oldest]) / seconds);
            }
            publish(job.state, "Downloading depot " + (index + 1) + " of " + job.depots.size(), doneBytes(depot, size), speed, index, "");
        }
    }

    /** Bytes of the finished depots plus those of the one in progress (as far as its listed size). */
    private long doneBytes(SteamDownloadJob.Depot current, long currentSize) {
        long total = 0;
        for (SteamDownloadJob.Depot depot : job.depots) {
            if (depot.done) total += depot.size;
            else if (depot == current) total += depot.size > 0 ? Math.min(depot.size, currentSize) : currentSize;
        }
        return total;
    }

    /** The folder Steam writes a depot to, found among the places it may use. */
    private File depotDir(SteamDownloadJob.Depot depot) {
        File fromLog = depot.path.isEmpty() ? null : paths.hostFile(depot.path);
        if (fromLog != null && fromLog.isDirectory()) return fromLog;
        for (File root : paths.contentRoots()) {
            File dir = new File(root, "app_" + job.appId + "/depot_" + depot.id);
            if (dir.exists()) return dir;
        }
        return new File(paths.contentRoots()[0], "app_" + job.appId + "/depot_" + depot.id);
    }

    private void merge() throws Exception {
        job.state = SteamDownloadJob.State.MERGING;
        saveJob();
        publish(job.state, "Putting the files together…", job.totalBytes(), 0, job.depots.size(), "");
        File root = paths.versionsRoot(job.platform);
        File target = new File(root, job.folderName);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create " + root);
        File marker = new File(target, SteamDepotMerger.PART_MARKER);
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create " + target);
        if (!marker.exists() && !marker.createNewFile()) throw new IOException("Cannot write in " + target);
        for (SteamDownloadJob.Depot depot : job.depots) {
            checkCancelled();
            File dir = depotDir(depot);
            if (!dir.isDirectory()) {
                // Merged by an earlier attempt that was killed before it saved the job: the folder is gone and its files are in the target.
                if (depot.done) continue;
                throw new IOException("Depot " + depot.id + " has no files");
            }
            SteamDepotMerger.moveInto(dir, target);
        }
        for (File content : paths.contentRoots()) cleanContent(new File(content, "app_" + job.appId));
        //noinspection ResultOfMethodCallIgnored
        marker.delete();
        job.state = SteamDownloadJob.State.SHORTCUT;
        saveJob();
        publish(job.state, "Making the shortcut…", job.totalBytes(), 0, job.depots.size(), "");
        job.shortcutPath = shortcutMaker.make(job, target);
        job.state = SteamDownloadJob.State.DONE;
        saveJob();
        publish(job.state, "Ready", job.totalBytes(), 0, job.depots.size(), "");
    }

    /** Removes the patch/state files Steam leaves beside the depot folders, and the app folder when nothing else is in it. */
    private void cleanContent(File appDir) {
        File[] files = appDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("state_")) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
        String[] rest = appDir.list();
        if (rest != null && rest.length == 0) //noinspection ResultOfMethodCallIgnored
            appDir.delete();
    }

    /** The console log from an offset to its end; the whole log when it was cut short (Steam rotates it). */
    static String readFrom(File file, long offset) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long length = in.length();
            long start = offset > length ? 0 : offset;
            int count = (int) Math.min(length - start, 8 * 1024 * 1024);
            byte[] data = new byte[count];
            in.seek(start);
            in.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return "";
        }
    }
}
