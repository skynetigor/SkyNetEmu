package com.winlator.cmod.contents;

import android.content.Context;
import android.os.Build;
import com.winlator.cmod.core.GPUInformation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android/bionic display drivers. Linux/glibc game drivers are a separate catalog. */
public final class RendererDriverCatalog {
    public static final String AUTO = "auto";
    public static final String FILE = "renderer-drivers.json";
    private static final Pattern ADRENO = Pattern.compile("\\badreno\\s*(?:\\(tm\\)\\s*)?([0-9]{3,4}(?:v[0-9]+)?)\\b", Pattern.CASE_INSENSITIVE);

    public static final class Entry {
        public final String id, url, sha256;
        public final List<String> gpus;
        public final int minApi, maxApi, priority;
        public final boolean dmaBufImport;
        Entry(JSONObject object) {
            id = object.optString("id"); url = object.optString("url"); sha256 = object.optString("sha256").toLowerCase(Locale.ROOT);
            minApi = object.optInt("minApi", 29); maxApi = object.optInt("maxApi", Integer.MAX_VALUE);
            priority = object.optInt("priority", 0);
            dmaBufImport = object.optBoolean("dmaBufImport", false);
            gpus = new ArrayList<>();
            JSONArray models = object.optJSONArray("gpus");
            for (int i = 0; models != null && i < models.length(); i++) gpus.add(models.optString(i).toLowerCase(Locale.ROOT));
        }
        public boolean matches(String renderer, int api, boolean requireDmaBuf) {
            return gpus.contains(adrenoModel(renderer)) && api >= minApi && api <= maxApi && (!requireDmaBuf || dmaBufImport);
        }
    }

    private RendererDriverCatalog() {}

    public static String adrenoModel(String renderer) {
        Matcher matcher = ADRENO.matcher(renderer == null ? "" : renderer);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    /** Malformed entries are ignored, never interpreted as an all-GPU wildcard. */
    public static List<Entry> parse(String text) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(text);
            if (root.optInt("schema") != 1) return result;
            JSONArray entries = root.optJSONArray("drivers");
            for (int i = 0; entries != null && i < entries.length(); i++) {
                try {
                    JSONObject object = entries.optJSONObject(i);
                    if (object == null || !"android".equals(object.optString("platform"))) continue;
                    Entry entry = new Entry(object);
                    URI uri = URI.create(entry.url);
                    if (entry.id.isEmpty() || entry.id.equals(".") || entry.id.equals("..") || entry.id.matches(".*[/\\\\\\p{Cntrl}].*")
                            || AUTO.equalsIgnoreCase(entry.id) || "system".equalsIgnoreCase(entry.id)
                            || !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                            || !entry.sha256.matches("[a-f0-9]{64}") || entry.gpus.isEmpty()
                            || entry.gpus.stream().anyMatch(gpu -> !gpu.matches("[0-9]{3,4}(?:v[0-9]+)?"))
                            || entry.minApi < 29 || entry.maxApi < entry.minApi) continue;
                    result.add(entry);
                } catch (Exception ignored) { /* One malformed entry must not suppress later valid entries. */ }
            }
        } catch (Exception ignored) { /* A broken catalog cannot select a different hardware family. */ }
        result.sort(Comparator.comparingInt((Entry entry) -> entry.priority).reversed());
        return result;
    }

    private static int revision(String text) {
        try { return new JSONObject(text).optInt("revision", 0); } catch (Exception ignored) { return -1; }
    }

    private static boolean valid(String text) {
        try {
            JSONObject object = new JSONObject(text);
            return object.optInt("schema") == 1 && object.optJSONArray("drivers") != null;
        } catch (Exception ignored) { return false; }
    }

    public static List<Entry> load(Context context) {
        String text = RegistryCache.get(context, FILE, RegistryCache.TTL_INDEX, false);
        String bundled = RegistryCache.bundledCopy(context, FILE);
        if (bundled != null && (revision(bundled) > revision(text) || !valid(text))) text = bundled;
        return parse(text);
    }

    /** Blocking: called only by the launch worker, never the UI thread. Does not overwrite manual selections. */
    public static synchronized String resolve(Context context, String requested, boolean requireDmaBuf, Consumer<String> progress) {
        if (!AUTO.equalsIgnoreCase(requested)) return requested;
        String renderer = GPUInformation.getRenderer(null, context);
        AdrenotoolsManager manager = new AdrenotoolsManager(context);
        List<Entry> entries = load(context);
        if (entries.stream().noneMatch(entry -> entry.matches(renderer, Build.VERSION.SDK_INT, requireDmaBuf))) {
            if (progress != null) progress.accept("Refreshing Android renderer driver mappings…");
            RegistryCache.get(context, FILE, RegistryCache.TTL_NONE, true);
            entries = load(context);
        }
        boolean matched = false;
        for (Entry entry : entries) {
            if (!entry.matches(renderer, Build.VERSION.SDK_INT, requireDmaBuf)) continue;
            matched = true;
            if (usable(manager, entry.id)) return entry.id;
            for (String installed : manager.enumarateInstalledDrivers())
                if (entry.url.equals(manager.getDriverSourceUrl(installed)) && usable(manager, installed)) return installed;
            if (progress != null) progress.accept("Downloading Android renderer driver: " + entry.id + "…");
            String installed = RemoteDriverCatalog.install(context, entry.url, entry.sha256);
            if (installed != null && !installed.isEmpty() && usable(manager, installed)) return installed;
        }
        if (!requireDmaBuf && !matched) return "system";
        throw new IllegalArgumentException(matched
                ? "The compatible Android renderer driver could not be installed. Check the connection, or install it in Components and select it manually."
                : "No Android renderer driver is registered for " + renderer + " on Android API " + Build.VERSION.SDK_INT
                + ". Select a compatible Renderer driver manually. Native Vulkan output requires DMA-BUF import support.");
    }

    private static boolean usable(AdrenotoolsManager manager, String id) {
        if (manager.isFromResources(id) && !new File(manager.getDriverPath(id), "meta.json").isFile())
            manager.extractDriverFromResources(id);
        String library = manager.getLibraryName(id);
        return !library.isEmpty() && !library.contains("/") && !library.contains("\\")
                && new File(manager.getDriverPath(id), library).isFile();
    }
}
