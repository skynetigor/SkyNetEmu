package com.winlator.cmod.telemetry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/** Elasticsearch create-only wire format and per-item delivery results. */
final class ElasticsearchBulk {
    static final int ACCEPTED = 0, RETRY = 1, DROP = 2;

    private ElasticsearchBulk() {}

    static String indexFor(String dataset) {
        switch (dataset) {
            case "skynet.session":
            case "skynet.funnel":
            case "skynet.game_session":
            case "skynet.error": return "logs-" + dataset + "-default";
            default: throw new IllegalArgumentException("Unknown telemetry dataset");
        }
    }

    static String body(List<String> documents) throws JSONException {
        StringBuilder body = new StringBuilder();
        for (String line : documents) {
            JSONObject document = new JSONObject(line);
            JSONObject create = new JSONObject().put("_index", indexFor(document.getString("event.dataset")))
                    .put("_id", document.getString("event.id"));
            body.append(new JSONObject().put("create", create)).append('\n').append(line).append('\n');
        }
        return body.toString();
    }

    static int[] outcomes(int httpStatus, String response, int size) {
        int[] outcomes = new int[size];
        Arrays.fill(outcomes, retryable(httpStatus) ? RETRY : DROP);
        if (httpStatus != 200) return outcomes;
        try {
            JSONArray items = new JSONObject(response).getJSONArray("items");
            if (items.length() != size) throw new JSONException("Bulk item count mismatch");
            for (int i = 0; i < size; i++) {
                int status = items.getJSONObject(i).getJSONObject("create").getInt("status");
                if (status < 100 || status > 599) throw new JSONException("Invalid bulk status");
                outcomes[i] = (status >= 200 && status < 300) || status == 409 ? ACCEPTED : retryable(status) ? RETRY : DROP;
            }
        } catch (Exception e) {
            Arrays.fill(outcomes, RETRY);
        }
        return outcomes;
    }

    private static boolean retryable(int status) { return status == 0 || status == 408 || status == 425 || status == 429 || status >= 500; }
}
