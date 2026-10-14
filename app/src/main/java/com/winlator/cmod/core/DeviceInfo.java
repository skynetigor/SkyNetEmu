package com.winlator.cmod.core;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.winlator.cmod.contents.RegistryCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Which processor the device has, as a family of the registry's socs.json ("snapdragon-8-elite-gen-5"): the registry's choice of
 * a container config depends on it. The model (SM8850) is what Android reports; the registry maps models to families so that
 * a new device is added to the map, not to the app.
 */
public final class DeviceInfo {
    private static final String TAG = "DeviceInfo";

    public static final class Soc {
        /** Registry family, or null when the registry does not know this model. */
        public final String family;
        /** "Snapdragon 8 Elite Gen 5" for a known one, otherwise what the device reports. */
        public final String name;
        /** "SM8850", or "" when the device does not say. */
        public final String model;

        Soc(String family, String name, String model) {
            this.family = family;
            this.name = name;
            this.model = model;
        }

        public String label() {
            return model.isEmpty() || name.contains(model) ? name : name + " (" + model + ")";
        }
    }

    private static Soc cached;

    private DeviceInfo() {}

    /** Reads the registry from what is on hand (never the network); the answer is kept until the app restarts. */
    public static synchronized Soc soc(Context context) {
        if (cached != null && cached.family != null) return cached;
        String model = model();
        String text = RegistryCache.get(context, "socs.json", RegistryCache.TTL_INDEX, false);
        Soc found = null;
        try {
            JSONArray socs = text == null ? null : new JSONObject(text).optJSONArray("socs");
            for (int i = 0; socs != null && i < socs.length() && found == null; i++) {
                JSONObject soc = socs.optJSONObject(i);
                JSONArray models = soc == null ? null : soc.optJSONArray("models");
                for (int j = 0; models != null && j < models.length(); j++) {
                    if (models.optString(j).equalsIgnoreCase(model)) {
                        found = new Soc(soc.optString("family"), soc.optString("name", model), model);
                        break;
                    }
                }
            }
        }
        catch (org.json.JSONException e) {
            Log.w(TAG, "Bad socs.json", e);
        }
        cached = found != null ? found : new Soc(null, model.isEmpty() ? Build.HARDWARE : model, model);
        return cached;
    }

    private static String model() {
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null && !Build.SOC_MODEL.isEmpty() && !Build.UNKNOWN.equals(Build.SOC_MODEL)) {
            return Build.SOC_MODEL;
        }
        String property = getprop("ro.soc.model");
        return property.isEmpty() ? getprop("ro.board.platform") : property;
    }

    private static String getprop(String name) {
        try {
            Process process = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                return line == null ? "" : line.trim();
            }
        }
        catch (java.io.IOException e) {
            return "";
        }
    }
}
