package com.winlator.cmod.contents;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.gameprofile.GameProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * The container configs of the registry (containers/index.json): ready settings for a Wine prefix, each a .wcfg profile that
 * {@link com.winlator.cmod.container.ContainerProfile} reads. A config belongs to no game; a game version lists the ones that
 * work for it (see {@link com.winlator.cmod.gameprofile.GameProfile.Version#containers}).
 *
 * Methods that allow the network block: call them off the main thread.
 */
public final class ContainerConfigCatalog {
    private static final String TAG = "ContainerConfigCatalog";
    public static final String INDEX = "containers/index.json";

    public static final class Config {
        public final String id;
        public final String name;
        /** Path of the .wcfg, relative to the registry. */
        public final String file;
        public final String description;
        /** Processor families (socs.json) it was made for; empty for any. */
        public final List<String> socs;
        /** "stable" or "prerelease". */
        public final String channel;
        public final List<String> tags;
        public final List<String> testedOn;
        /** What the config is for: a Wine prefix (the default) or a Linux container ("platform": "linux"). */
        public final GameProfile.Platform platform;
        /** Optional Windows guest architecture. Empty on older indexes; ARM64 means native ARM64 games. */
        public final String windowsArchitecture;

        Config(String id, String name, String file, String description, List<String> socs, String channel, List<String> tags, List<String> testedOn,
               GameProfile.Platform platform, String windowsArchitecture) {
            this.platform = platform;
            this.windowsArchitecture = windowsArchitecture;
            this.id = id;
            this.name = name;
            this.file = file;
            this.description = description;
            this.socs = socs;
            this.channel = channel;
            this.tags = tags;
            this.testedOn = testedOn;
        }

        public boolean suits(String socFamily) {
            return socs.isEmpty() || (socFamily != null && socs.contains(socFamily));
        }
    }

    private ContainerConfigCatalog() {}

    /** The configs by id, in the registry's order; empty when there is no index anywhere. */
    public static Map<String, Config> all(Context context, boolean allowNetwork) {
        Map<String, Config> configs = new LinkedHashMap<>();
        String text = RegistryCache.get(context, INDEX, RegistryCache.TTL_INDEX, allowNetwork);
        // A new app may ship a config before the online registry has caught up, just as game
        // profiles do. A matching or newer remote revision remains authoritative.
        String bundled = RegistryCache.bundledCopy(context, INDEX);
        if (bundled != null && (text == null || revision(bundled) > revision(text))) text = bundled;
        if (text == null) return configs;
        try {
            JSONArray array = new JSONObject(text).optJSONArray("containers");
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject c = array.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("id", "");
                String file = c.optString("file", "");
                // Only containers/<name>.wcfg: nothing the index says can name another file.
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || !file.matches("containers/[A-Za-z0-9][A-Za-z0-9._-]*\\.wcfg")) continue;
                String channel = "prerelease".equals(c.optString("channel")) ? "prerelease" : "stable";
                configs.put(id, new Config(id, c.optString("name", id), file, c.optString("description", ""), strings(c.optJSONArray("socs")),
                        channel, strings(c.optJSONArray("tags")), strings(c.optJSONArray("testedOn")),
                        "linux".equals(c.optString("platform")) ? GameProfile.Platform.LINUX : GameProfile.Platform.WINDOWS,
                        c.optString("windowsArchitecture", "").trim().toLowerCase(Locale.ROOT)));
            }
        }
        catch (org.json.JSONException e) {
            Log.w(TAG, "Bad container index", e);
        }
        return configs;
    }

    public static Config get(Context context, String id, boolean allowNetwork) {
        return all(context, allowNetwork).get(id);
    }

    /** The text of a config's .wcfg, or null when it cannot be had. */
    public static String profileText(Context context, Config config, boolean allowNetwork) {
        return RegistryCache.get(context, config.file, RegistryCache.TTL_GAME, allowNetwork);
    }

    private static int revision(String text) {
        try { return new JSONObject(text).optInt("revision", 0); }
        catch (org.json.JSONException ignored) { return 0; }
    }

    private static List<String> strings(JSONArray array) {
        List<String> list = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) list.add(array.optString(i));
        return Collections.unmodifiableList(list);
    }
}
