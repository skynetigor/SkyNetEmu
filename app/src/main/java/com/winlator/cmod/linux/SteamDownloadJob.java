package com.winlator.cmod.linux;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One version download, kept in {@code <home>/.skynet/downloads/<key>.json}: what to get, how far it is, and what to make of it. The file is
 * rewritten (atomically) at every step, so a killed app picks the job up again; {@code download_depot} itself resumes a partial depot.
 */
public final class SteamDownloadJob {
    public enum State { QUEUED, DOWNLOADING, MERGING, SHORTCUT, DONE, FAILED, CANCELLED }

    public static final class Depot {
        public final long id;
        public final String manifest;
        /** Installed size in bytes, 0 when unknown. */
        public final long size;
        public boolean done;
        /** Where Steam wrote it (guest path), once known. */
        public String path = "";

        public Depot(long id, String manifest, long size) {
            this.id = id;
            this.manifest = manifest;
            this.size = size;
        }
    }

    public final String key;
    public final long appId;
    /** The registry game and, when the version came from it, its version id ("" for a plain Steam branch). */
    public final String gameId, versionId;
    public final String gameName, versionLabel;
    public final String platform;
    public final String language;
    /** Folder (and shortcut) name under games-versions. */
    public final String folderName;
    public final List<Depot> depots = new ArrayList<>();
    public State state = State.QUEUED;
    public String error = "";
    public long createdAt;
    /** The shortcut made at the end (path of the .desktop file), "" until then. */
    public String shortcutPath = "";

    public SteamDownloadJob(String key, long appId, String gameId, String gameName, String versionId, String versionLabel, String platform,
                            String language, String folderName) {
        this.key = key;
        this.appId = appId;
        this.gameId = gameId;
        this.gameName = gameName;
        this.versionId = versionId;
        this.versionLabel = versionLabel;
        this.platform = platform;
        this.language = language;
        this.folderName = folderName;
        this.createdAt = System.currentTimeMillis();
    }

    public long totalBytes() {
        long total = 0;
        for (Depot depot : depots) total += depot.size;
        return total;
    }

    /** Done or cancelled: nothing more to do. A failed job can be resumed (Steam continues a partial depot). */
    public boolean finished() {
        return state == State.DONE || state == State.CANCELLED;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("key", key).put("appId", appId).put("gameId", gameId).put("gameName", gameName).put("versionId", versionId)
                .put("versionLabel", versionLabel).put("platform", platform).put("language", language).put("folderName", folderName)
                .put("state", state.name()).put("error", error).put("createdAt", createdAt).put("shortcutPath", shortcutPath);
        JSONArray array = new JSONArray();
        for (Depot depot : depots) {
            array.put(new JSONObject().put("id", depot.id).put("manifest", depot.manifest).put("size", depot.size)
                    .put("done", depot.done).put("path", depot.path));
        }
        o.put("depots", array);
        return o;
    }

    public static SteamDownloadJob fromJson(JSONObject o) throws JSONException {
        SteamDownloadJob job = new SteamDownloadJob(o.getString("key"), o.getLong("appId"), o.optString("gameId"), o.optString("gameName"),
                o.optString("versionId"), o.optString("versionLabel"), o.optString("platform", "linux"), o.optString("language", "english"),
                o.getString("folderName"));
        try {
            job.state = State.valueOf(o.optString("state", "QUEUED"));
        }
        catch (IllegalArgumentException e) {
            job.state = State.QUEUED;
        }
        job.error = o.optString("error");
        job.createdAt = o.optLong("createdAt");
        job.shortcutPath = o.optString("shortcutPath");
        JSONArray array = o.optJSONArray("depots");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject d = array.getJSONObject(i);
            Depot depot = new Depot(d.getLong("id"), d.getString("manifest"), d.optLong("size"));
            depot.done = d.optBoolean("done");
            depot.path = d.optString("path");
            job.depots.add(depot);
        }
        return job;
    }

    private static File file(File jobsDir, String key) {
        return new File(jobsDir, key + ".json");
    }

    /** Written next to the target and moved over it, so a reader never sees half a file. */
    public synchronized void save(File jobsDir) throws IOException {
        if (!jobsDir.isDirectory() && !jobsDir.mkdirs()) throw new IOException("Cannot create " + jobsDir);
        File target = file(jobsDir, key);
        File temporary = new File(jobsDir, key + ".json.tmp");
        try {
            Files.write(temporary.toPath(), toJson().toString().getBytes(StandardCharsets.UTF_8));
        }
        catch (JSONException e) {
            throw new IOException(e);
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static SteamDownloadJob load(File jobsDir, String key) {
        File file = file(jobsDir, key);
        if (!file.isFile()) return null;
        try {
            return fromJson(new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
        }
        catch (IOException | JSONException e) {
            return null;
        }
    }

    /** The jobs that were not finished (a killed app leaves them), oldest first. */
    public static List<SteamDownloadJob> unfinished(File jobsDir) {
        List<SteamDownloadJob> jobs = new ArrayList<>();
        File[] files = jobsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return jobs;
        for (File file : files) {
            SteamDownloadJob job = load(jobsDir, file.getName().substring(0, file.getName().length() - 5));
            if (job != null && !job.finished()) jobs.add(job);
        }
        jobs.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
        return jobs;
    }

    public void delete(File jobsDir) {
        //noinspection ResultOfMethodCallIgnored
        file(jobsDir, key).delete();
    }
}
