package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Steam's {@code appcache/appinfo.vdf} says about one app: its depots (with platform, language, DLC) and, per branch, the manifest
 * of every depot. This is the source of the versions a user can download.
 */
public final class SteamAppInfo {
    /** The manifest a depot has on one branch. Sizes are 0 when Steam does not give them. */
    public static final class Manifest {
        public final String gid;
        public final long size;
        public final long download;

        Manifest(String gid, long size, long download) {
            this.gid = gid;
            this.size = size;
            this.download = download;
        }
    }

    public static final class Depot {
        public final long id;
        /** Comma separated, empty for every platform. */
        public final String osList;
        /** Empty for every language. */
        public final String language;
        public final long dlcAppId;
        /** Non-zero: the content belongs to another app (shared redistributables) and is not downloaded here. */
        public final long fromApp;
        /** Branch name to manifest. */
        public final Map<String, Manifest> manifests;

        Depot(long id, String osList, String language, long dlcAppId, long fromApp, Map<String, Manifest> manifests) {
            this.id = id;
            this.osList = osList;
            this.language = language;
            this.dlcAppId = dlcAppId;
            this.fromApp = fromApp;
            this.manifests = manifests;
        }

        public boolean forPlatform(String platform) {
            if (osList.isEmpty()) return true;
            for (String os : osList.split(",")) {
                if (os.trim().equalsIgnoreCase(platform)) return true;
            }
            return false;
        }
    }

    public static final class Branch {
        public final String name;
        public final long buildId;
        /** Seconds since the epoch, 0 when unknown. */
        public final long updated;
        public final boolean passwordRequired;
        /** What Steam shows next to the branch name, empty when none. */
        public final String description;

        Branch(String name, long buildId, long updated, boolean passwordRequired, String description) {
            this.description = description;
            this.name = name;
            this.buildId = buildId;
            this.updated = updated;
            this.passwordRequired = passwordRequired;
        }
    }

    public final long appId;
    public final String name;
    public final String installDir;
    public final List<Depot> depots;
    public final Map<String, Branch> branches;

    private SteamAppInfo(long appId, String name, String installDir, List<Depot> depots, Map<String, Branch> branches) {
        this.appId = appId;
        this.name = name;
        this.installDir = installDir;
        this.depots = Collections.unmodifiableList(depots);
        this.branches = Collections.unmodifiableMap(branches);
    }

    /** The whole file, kept in memory so several apps can be read from it. */
    public static final class Store {
        private final byte[] data;
        private final String[] strings;
        private final Map<Long, int[]> entries = new LinkedHashMap<>();

        private Store(byte[] data) throws IOException {
            this.data = data;
            if (data.length < 16) throw new IOException("appinfo.vdf is too short");
            ByteBuffer head = SteamVdf.little(data, 0, 16);
            long magic = head.getInt() & 0xffffffffL;
            // 0x07564429 is the format with a string table (version 41); older versions inline the keys.
            if ((magic & 0xffffff00L) != 0x07564400L || (magic & 0xff) < 41) throw new IOException("Unsupported appinfo.vdf version " + Long.toHexString(magic));
            head.getInt();
            long tableOffset = head.getLong();
            if (tableOffset < 16 || tableOffset + 4 > data.length) throw new IOException("Bad string table offset");
            ByteBuffer table = SteamVdf.little(data, (int) tableOffset, data.length - (int) tableOffset);
            int count = table.getInt();
            if (count < 0 || count > data.length) throw new IOException("Bad string table size");
            strings = new String[count];
            for (int i = 0; i < count; i++) strings[i] = SteamVdf.cString(table);
            int offset = 16;
            while (offset + 8 <= tableOffset) {
                ByteBuffer entry = SteamVdf.little(data, offset, 8);
                long id = entry.getInt() & 0xffffffffL;
                int size = entry.getInt();
                if (id == 0) break;
                if (size < 0 || offset + 8L + size > tableOffset) throw new IOException("Bad app entry for " + id);
                entries.put(id, new int[] {offset + 8, size});
                offset += 8 + size;
            }
        }

        public static Store open(File file) throws IOException {
            return new Store(Files.readAllBytes(file.toPath()));
        }

        public static Store open(byte[] data) throws IOException {
            return new Store(data);
        }

        public boolean has(long appId) {
            return entries.containsKey(appId);
        }

        /** Null when the file has no entry for the app (the client never fetched it). */
        public SteamAppInfo app(long appId) throws IOException {
            int[] entry = entries.get(appId);
            if (entry == null) return null;
            // 60 bytes precede the data: state, update time, token, sha1, change number, data sha1.
            if (entry[1] < 60) throw new IOException("Short entry for " + appId);
            ByteBuffer body = SteamVdf.little(data, entry[0] + 60, entry[1] - 60);
            Map<String, Object> root = SteamVdf.readMap(body, strings);
            Map<String, Object> app = SteamVdf.map(root, "appinfo");
            if (app == null) return null;
            return parse(appId, app);
        }
    }

    public static Store open(File file) throws IOException {
        return Store.open(file);
    }

    private static SteamAppInfo parse(long appId, Map<String, Object> app) {
        Map<String, Object> depotNode = SteamVdf.map(app, "depots");
        List<Depot> depots = new ArrayList<>();
        Map<String, Branch> branches = new LinkedHashMap<>();
        if (depotNode != null) {
            for (Map.Entry<String, Object> entry : depotNode.entrySet()) {
                if (!(entry.getValue() instanceof Map) || !isNumber(entry.getKey())) continue;
                depots.add(depot(Long.parseLong(entry.getKey()), SteamVdf.map(depotNode, entry.getKey())));
            }
            Map<String, Object> branchNode = SteamVdf.map(depotNode, "branches");
            if (branchNode != null) {
                for (Map.Entry<String, Object> entry : branchNode.entrySet()) {
                    Map<String, Object> node = SteamVdf.map(branchNode, entry.getKey());
                    if (node == null) continue;
                    branches.put(entry.getKey(), new Branch(entry.getKey(), SteamVdf.number(node, "buildid", 0),
                        SteamVdf.number(node, "timeupdated", 0), SteamVdf.number(node, "pwdrequired", 0) != 0, SteamVdf.string(node, "description", "")));
                }
            }
        }
        Map<String, Object> common = SteamVdf.map(app, "common");
        Map<String, Object> config = SteamVdf.map(app, "config");
        return new SteamAppInfo(appId, SteamVdf.string(common, "name", ""), SteamVdf.string(config, "installdir", ""), depots, branches);
    }

    private static Depot depot(long id, Map<String, Object> node) {
        Map<String, Object> config = SteamVdf.map(node, "config");
        Map<String, Manifest> manifests = new LinkedHashMap<>();
        Map<String, Object> manifestNode = SteamVdf.map(node, "manifests");
        if (manifestNode != null) {
            for (String branch : manifestNode.keySet()) {
                Map<String, Object> entry = SteamVdf.map(manifestNode, branch);
                // Newer files give a plain gid string for the branch, older ones a node with the gid and sizes.
                if (entry != null) {
                    String gid = SteamVdf.string(entry, "gid", "");
                    if (!gid.isEmpty()) manifests.put(branch, new Manifest(gid, SteamVdf.number(entry, "size", 0), SteamVdf.number(entry, "download", 0)));
                }
                else {
                    String gid = SteamVdf.string(manifestNode, branch, "");
                    if (isNumber(gid)) manifests.put(branch, new Manifest(gid, 0, 0));
                }
            }
        }
        return new Depot(id, SteamVdf.string(config, "oslist", "").toLowerCase(Locale.ROOT), SteamVdf.string(config, "language", "").toLowerCase(Locale.ROOT),
            SteamVdf.number(node, "dlcappid", 0), SteamVdf.number(node, "depotfromapp", 0), manifests);
    }

    private static boolean isNumber(String text) {
        if (text == null || text.isEmpty() || text.length() > 19) return false;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        return true;
    }
}
