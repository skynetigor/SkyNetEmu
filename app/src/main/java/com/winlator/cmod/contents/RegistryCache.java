package com.winlator.cmod.contents;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The JSON files of the components repository's registry/ folder (games.json, games/&lt;id&gt;.json, containers/index.json, socs.json), downloaded afresh whenever the
 * network is allowed and one is on hand, and kept for when it is not.
 *
 * One lookup goes: the network (when it is allowed: always asked, never trusted to an old copy), then the copy in memory,
 * the copy on disk and the copy bundled in the app (assets/registry). A download is kept only when it parses as JSON, and is written through a temporary file so a
 * dropped connection never leaves half a file. The disk copies are per branch of the components repository (debug builds
 * read the branch that carries the app's name), so switching branches never serves the other one's files.
 */
public final class RegistryCache {
    private static final String TAG = "RegistryCache";
    private static final String DIR = "registry-cache";
    private static final String ASSET_DIR = "registry/";
    /** Where the registry lives in the components repository. */
    private static final String REMOTE_DIR = "registry/";

    public static final long TTL_INDEX = 6L * 60 * 60 * 1000;
    public static final long TTL_GAME = 24L * 60 * 60 * 1000;
    /** Always ask the network first (a "refresh" button). */
    public static final long TTL_NONE = 0;

    public enum Source { MEMORY, NETWORK, CACHE, STALE_CACHE, BUNDLED, NONE }

    /** What a lookup found, and where from. */
    public static final class Result {
        /** The JSON text, or null when nothing was available anywhere. */
        public final String json;
        public final Source source;
        /** When the copy was downloaded (milliseconds); 0 for the bundled copy and for no copy. */
        public final long time;

        Result(String json, Source source, long time) {
            this.json = json;
            this.source = source;
            this.time = time;
        }
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Result> MEMORY = new HashMap<>();
    /** When each file was last downloaded in this process. */
    private static final Map<String, Long> FETCHED = new HashMap<>();
    /** A file downloaded this recently is not downloaded again. */
    private static final long REUSE_MS = 30_000;
    private static final Map<String, Result> LAST = new HashMap<>();

    private RegistryCache() {}

    /** The text of {@code relPath}, relative to the registry folder (for example "games.json"), or null. Never blocks on the network when {@code allowNetwork} is false. */
    public static String get(Context context, String relPath, long ttlMs, boolean allowNetwork) {
        return load(context, relPath, ttlMs, allowNetwork).json;
    }

    /**
     * One lookup. With the network allowed, the file is downloaded every time (a copy downloaded in the last {@link #REUSE_MS} is
     * reused, so one screen asking for the same file twice does not download it twice); the saved copy is only an answer when
     * the download fails. With the network not allowed (the launch path, which never waits) it is the copy in memory, the saved
     * copy and the bundled one. {@code ttlMs} is kept for the callers' sake and no longer decides anything.
     */
    public static Result load(Context context, String relPath, long ttlMs, boolean allowNetwork) {
        if (!validPath(relPath)) {
            Log.w(TAG, "Not a registry path: " + relPath);
            return new Result(null, Source.NONE, 0);
        }
        File file = file(context, relPath);
        if (allowNetwork) {
            synchronized (LOCK) {
                Result memory = MEMORY.get(relPath);
                Long fetched = FETCHED.get(relPath);
                if (memory != null && fetched != null && System.currentTimeMillis() - fetched < REUSE_MS) {
                    return remember(relPath, new Result(memory.json, Source.MEMORY, memory.time), false);
                }
            }
            // The network is asked outside the lock: a lookup that never waits (a game starting) must not queue behind a download.
            String text = Downloader.downloadString(ContentsManager.COMPONENTS_BASE + REMOTE_DIR + relPath);
            if (text != null && isJson(text)) {
                synchronized (LOCK) {
                    long now = System.currentTimeMillis();
                    store(file, text);
                    FETCHED.put(relPath, now);
                    return remember(relPath, new Result(text, Source.NETWORK, now), true);
                }
            }
            Log.w(TAG, "Cannot download " + relPath + "; using what is on hand");
        }
        synchronized (LOCK) {
            Result memory = MEMORY.get(relPath);
            if (memory != null) return remember(relPath, new Result(memory.json, allowNetwork ? Source.STALE_CACHE : Source.MEMORY, memory.time), false);
            String disk = file.isFile() ? FileUtils.readString(file) : null;
            if (disk != null && isJson(disk)) {
                return remember(relPath, new Result(disk, allowNetwork ? Source.STALE_CACHE : Source.CACHE, file.lastModified()), true);
            }
            String bundled = bundled(context, relPath);
            if (bundled != null) return remember(relPath, new Result(bundled, Source.BUNDLED, 0), false);
            return remember(relPath, new Result(null, Source.NONE, 0), false);
        }
    }

    /**
     * Downloads every registry file the app reads without waiting (the index, each game's file, the Linux defaults, the SoC list and
     * the container index), so the next game started works from what the registry says now. Blocks: call it off the main thread.
     * @return true when every file came from the network
     */
    public static boolean refreshAll(Context context) {
        boolean all = true;
        for (String rel : new String[]{"games.json", "linux.json", "socs.json", "containers/index.json"}) {
            Result result = load(context, rel, TTL_NONE, true);
            all &= result.source == Source.NETWORK || result.source == Source.MEMORY;
        }
        for (com.winlator.cmod.gameprofile.GameProfileCatalog.IndexEntry entry : com.winlator.cmod.gameprofile.GameProfileCatalog.index(context, false)) {
            Result result = load(context, "games/" + entry.id + ".json", TTL_NONE, true);
            all &= result.source == Source.NETWORK || result.source == Source.MEMORY;
        }
        return all;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean REFRESHING = new java.util.concurrent.atomic.AtomicBoolean();

    /** {@link #refreshAll} on a thread of its own; a refresh that is still running is not started twice. */
    public static void refreshInBackground(Context context) {
        final Context app = context.getApplicationContext();
        if (!REFRESHING.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                refreshAll(app);
            }
            finally {
                REFRESHING.set(false);
            }
        }, "RegistryRefresh").start();
    }

    /** What the last lookup of this file found (for a status line), or null when it was never looked up. */
    public static Result last(String relPath) {
        synchronized (LOCK) {
            return LAST.get(relPath);
        }
    }

    /** Forgets every downloaded copy, so the next lookups download again or fall back to the bundled ones. */
    public static void clear(Context context) {
        synchronized (LOCK) {
            MEMORY.clear();
            FETCHED.clear();
            LAST.clear();
            File dir = new File(context.getFilesDir(), DIR);
            if (dir.exists()) FileUtils.delete(dir);
        }
    }

    public static String branch() {
        return BuildConfig.COMPONENTS_BRANCH;
    }

    private static Result remember(String relPath, Result result, boolean keep) {
        if (keep && result.json != null) MEMORY.put(relPath, new Result(result.json, Source.MEMORY, result.time));
        LAST.put(relPath, result);
        return result;
    }

    private static boolean validPath(String relPath) {
        return relPath != null && relPath.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") && !relPath.contains("..") && !relPath.endsWith("/");
    }

    private static File file(Context context, String relPath) {
        return new File(new File(new File(context.getFilesDir(), DIR), safe(BuildConfig.COMPONENTS_BRANCH)), relPath);
    }

    private static String safe(String branch) {
        return branch.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean isJson(String text) {
        try {
            new JSONObject(text);
            return true;
        }
        catch (org.json.JSONException e) {
            return false;
        }
    }

    private static void store(File file, String text) {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        File temp = new File(dir, file.getName() + ".tmp");
        if (!FileUtils.writeString(temp, text) || !temp.renameTo(file)) {
            temp.delete();
            Log.w(TAG, "Cannot keep " + file.getName());
        }
    }

    /** The copy of a registry file that ships inside the app, or null when the app has none. Never touches the network or the cache. */
    public static String bundledCopy(Context context, String relPath) {
        return validPath(relPath) ? bundled(context, relPath) : null;
    }

    private static String bundled(Context context, String relPath) {
        try (InputStream in = context.getAssets().open(ASSET_DIR + relPath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (java.io.IOException e) {
            return null;
        }
    }
}
