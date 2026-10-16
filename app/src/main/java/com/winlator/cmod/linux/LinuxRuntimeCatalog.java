package com.winlator.cmod.linux;

import android.util.Log;

import com.winlator.cmod.contents.Downloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The Linux runtimes that can be installed. */
public final class LinuxRuntimeCatalog {
    private static final String TAG = "LinuxRuntimeCatalog";
    static final String BANNERLATOR_NAME = "Bannerlator (gamescope + ARM Steam)";
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linuxfs.json";

    public static final class Entry {
        public final String id;
        public final String name;
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;
        public final String channel;
        /** One of the {@link LinuxPackages} kinds. */
        public final String kind;
        /** For emulators: "box64" or "fex"; null otherwise. */
        public final String emulator;

        Entry(String id, String name, String version, String url, String sha256, long size, String channel,
              String kind, String emulator) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.channel = channel;
            this.kind = kind;
            this.emulator = emulator;
        }
    }

    private LinuxRuntimeCatalog() {}

    /** Blocking. Returns null when the catalog cannot be read. */
    public static List<Entry> fetch() {
        String json = Downloader.downloadString(CATALOG_URL);
        if (json == null) return null;
        try {
            JSONObject data = new JSONObject(json);
            List<Entry> entries = new ArrayList<>();
            JSONArray runtimes = data.optJSONArray("runtimes");
            if (runtimes != null) {
                for (int i = 0; i < runtimes.length(); i++) addEntry(entries, runtimes.getJSONObject(i), null);
            }
            else {
                // Bannerlator publishes a single release object.
                addEntry(entries, data, "bannerlator");
            }
            return entries;
        }
        catch (Exception e) {
            Log.e(TAG, "Bad runtime catalog", e);
            return null;
        }
    }

    private static void addEntry(List<Entry> entries, JSONObject item, String idPrefix) throws Exception {
        String url = item.getString("url");
        if (!url.startsWith("https://")) return;
        String version = item.getString("version");
        String id = idPrefix != null ? idPrefix + "-" + version : item.getString("id");
        String name = idPrefix != null ? BANNERLATOR_NAME + " " + version : item.optString("name", id);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return;
        entries.add(new Entry(id, name, version, url, item.getString("sha256"), item.optLong("size", 0),
                item.optString("channel", "prerelease"), LinuxPackages.KIND_RUNTIME, null));
    }

    private static final String SKYNET_CATALOG_URL =
            com.winlator.cmod.contents.ContentsManager.COMPONENTS_BASE + "linux.json";

    private static volatile android.content.Context appContext;

    /** Gives the catalog the app's context, to read the catalog that ships in the app (see {@link #skynetCatalog}). */
    public static void init(android.content.Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * The published catalog, with what the app's own copy (assets/registry/linux.json) has that the published one does not: a build
     * the published catalog has not caught up with (FEX, the root filesystem) is still installable. A published entry wins over
     * the app's by the same id. Null when the published catalog cannot be read (the connection is down, whatever the app has).
     */
    private static String skynetCatalog() {
        String remote = Downloader.downloadString(SKYNET_CATALOG_URL);
        if (remote == null) return null;
        android.content.Context context = appContext;
        if (context == null) return remote;
        try (java.io.InputStream in = context.getAssets().open("registry/linux.json")) {
            JSONObject merged = new JSONObject(remote);
            JSONObject bundled = new JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            for (String key : new String[]{"emulators", "drivers", "rootfs"}) {
                JSONArray extra = bundled.optJSONArray(key);
                if (extra == null) continue;
                JSONArray have = merged.optJSONArray(key);
                if (have == null) {
                    have = new JSONArray();
                    merged.put(key, have);
                }
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (int i = 0; i < have.length(); i++) ids.add(have.getJSONObject(i).optString("id"));
                for (int i = 0; i < extra.length(); i++) {
                    JSONObject item = extra.getJSONObject(i);
                    if (ids.add(item.optString("id"))) have.put(item);
                }
            }
            return merged.toString();
        }
        catch (Exception e) {
            Log.w(TAG, "Cannot merge the catalog of the app", e);
            return remote;
        }
    }

    /** Blocking. The x86 emulators that can be installed; null when the catalog cannot be read. */
    public static List<Entry> fetchEmulators() {
        String json = skynetCatalog();
        if (json == null) return null;
        try {
            List<Entry> entries = new ArrayList<>();
            JSONArray emulators = new JSONObject(json).optJSONArray("emulators");
            if (emulators == null) return entries;
            for (int i = 0; i < emulators.length(); i++) {
                JSONObject item = emulators.getJSONObject(i);
                String url = item.getString("url");
                String id = item.getString("id");
                String emulator = item.getString("emulator");
                if (!url.startsWith("https://") || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) continue;
                if (!emulator.equals("box64") && !emulator.equals("fex")) continue;
                String version = item.getString("version");
                entries.add(new Entry(id, item.optString("name", emulator) + " " + version, version, url,
                        item.getString("sha256"), item.optLong("size", 0), item.optString("channel", "prerelease"),
                        LinuxPackages.KIND_EMULATOR, emulator));
            }
            return entries;
        }
        catch (Exception e) {
            Log.e(TAG, "Bad Linux catalog", e);
            return null;
        }
    }

    /** Blocking. The x86-64 root filesystems FEX can run in; null when the catalog cannot be read. */
    public static List<Entry> fetchRootfs() {
        String json = skynetCatalog();
        if (json == null) return null;
        try {
            List<Entry> entries = new ArrayList<>();
            JSONArray rootfs = new JSONObject(json).optJSONArray("rootfs");
            if (rootfs == null) return entries;
            for (int i = 0; i < rootfs.length(); i++) {
                JSONObject item = rootfs.getJSONObject(i);
                String url = item.getString("url");
                String id = item.getString("id");
                if (!url.startsWith("https://") || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) continue;
                entries.add(new Entry(id, item.optString("name", id), item.optString("version", ""), url,
                        item.getString("sha256"), item.optLong("size", 0), item.optString("channel", "prerelease"),
                        LinuxPackages.KIND_ROOTFS, null));
            }
            return entries;
        }
        catch (Exception e) {
            Log.e(TAG, "Bad Linux catalog", e);
            return null;
        }
    }

    /** Blocking. The Linux Vulkan drivers (Turnip zips) that can be downloaded; null when the catalog cannot be read. */
    public static List<Entry> fetchDrivers() {
        String json = skynetCatalog();
        if (json == null) return null;
        try {
            List<Entry> entries = new ArrayList<>();
            JSONArray drivers = new JSONObject(json).optJSONArray("drivers");
            if (drivers == null) return entries;
            for (int i = 0; i < drivers.length(); i++) {
                JSONObject item = drivers.getJSONObject(i);
                String url = item.getString("url");
                String id = item.getString("id");
                if (!url.startsWith("https://") || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) continue;
                entries.add(new Entry(id, item.optString("name", id), item.optString("version", ""), url,
                        item.getString("sha256"), item.optLong("size", 0), item.optString("channel", "prerelease"),
                        LinuxPackages.KIND_DRIVER, null));
            }
            return entries;
        }
        catch (Exception e) {
            Log.e(TAG, "Bad Linux catalog", e);
            return null;
        }
    }

    /** Blocking. The desktops (XFCE) that can be installed into the runtimes; null when the catalog cannot be read. */
    public static List<Entry> fetchDesktops() {
        String json = skynetCatalog();
        if (json == null) return null;
        try {
            List<Entry> entries = new ArrayList<>();
            JSONArray desktops = new JSONObject(json).optJSONArray("desktops");
            if (desktops == null) return entries;
            for (int i = 0; i < desktops.length(); i++) {
                JSONObject item = desktops.getJSONObject(i);
                String url = item.getString("url");
                String id = item.getString("id");
                if (!url.startsWith("https://") || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) continue;
                entries.add(new Entry(id, item.optString("name", id), item.optString("version", ""), url,
                        item.getString("sha256"), item.optLong("size", 0), item.optString("channel", "prerelease"),
                        LinuxPackages.KIND_DESKTOP, null));
            }
            return entries;
        }
        catch (Exception e) {
            Log.e(TAG, "Bad Linux catalog", e);
            return null;
        }
    }
}
