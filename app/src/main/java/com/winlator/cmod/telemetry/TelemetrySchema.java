package com.winlator.cmod.telemetry;

import android.content.Context;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** Dataset allow-lists, numeric limits and text scrubbing, shared with the Elasticsearch schema. */
final class TelemetrySchema {
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}");
    private static final Pattern PATH = Pattern.compile("(?:^|[\\s=:(])/[\\w.@-]+/[\\w.@-]+|\\b[A-Za-z]:[\\\\/]");
    private final JSONObject definition;

    TelemetrySchema(JSONObject definition) { this.definition = definition; }

    static TelemetrySchema load(Context context) throws JSONException {
        return new TelemetrySchema(new JSONObject(FileUtils.readString(context, "telemetry-schema.json")));
    }

    JSONObject clean(JSONObject raw) throws JSONException {
        String dataset = raw.optString("event.dataset", "");
        JSONObject datasetSpec = definition.getJSONObject("datasets").optJSONObject(dataset);
        if (datasetSpec == null) throw new JSONException("Unknown telemetry dataset");
        JSONArray actions = datasetSpec.optJSONArray("actions");
        if (actions != null && !contains(actions, raw.optString("event.action", ""))) throw new JSONException("Unknown telemetry action");
        Set<String> allowed = new HashSet<>();
        add(allowed, definition.getJSONObject("common").getJSONArray("fields"));
        add(allowed, datasetSpec.getJSONArray("fields"));
        JSONObject fields = definition.getJSONObject("fields");
        JSONObject clean = new JSONObject();
        Iterator<String> keys = raw.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (!allowed.contains(name)) continue;
            Object value = cleanValue(fields.getJSONObject(name), raw.get(name));
            if (value != null) clean.put(name, value);
        }
        return clean;
    }

    boolean hasRequired(JSONObject doc) throws JSONException {
        JSONArray required = definition.getJSONObject("common").getJSONArray("required");
        for (int i = 0; i < required.length(); i++) if (!doc.has(required.getString(i))) return false;
        return true;
    }

    private static Object cleanValue(JSONObject spec, Object value) throws JSONException {
        String type = spec.getString("type");
        if (type.equals("keyword") || type.equals("text") || type.equals("wildcard")) {
            if (!(value instanceof String)) return null;
            String text = (String) value;
            int max = spec.optInt("max", 300);
            if (spec.optBoolean("free_text")) text = Scrub.text(text, max);
            if (PATH.matcher(text).find()) return null;
            if (text.length() > max) text = text.substring(0, max);
            if (spec.optBoolean("uuid") && !UUID.matcher(text).matches()) return null;
            JSONArray values = spec.optJSONArray("enum");
            if (values != null && !contains(values, text)) return null;
            if (spec.has("pattern") && !text.matches(spec.getString("pattern"))) return null;
            return text;
        }
        if (type.equals("long") || type.equals("integer") || type.equals("float")) {
            if (!(value instanceof Number)) return null;
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) return null;
            number = Math.min(spec.optDouble("max", Double.POSITIVE_INFINITY), Math.max(spec.optDouble("min", Double.NEGATIVE_INFINITY), number));
            if (type.equals("float")) return number;
            return (long) number;
        }
        if (type.equals("boolean")) return value instanceof Boolean ? value : null;
        if (type.equals("date")) {
            if (spec.optBoolean("epoch_millis") && value instanceof Number) return ((Number) value).longValue();
            if (!(value instanceof String)) return null;
            try { Instant.parse((String) value); return value; }
            catch (DateTimeParseException e) { return null; }
        }
        return null;
    }

    private static boolean contains(JSONArray values, String value) throws JSONException {
        for (int i = 0; i < values.length(); i++) if (value.equals(values.getString(i))) return true;
        return false;
    }

    private static void add(Set<String> fields, JSONArray names) throws JSONException {
        for (int i = 0; i < names.length(); i++) fields.add(names.getString(i));
    }
}
