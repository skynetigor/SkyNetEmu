package com.winlator.cmod.telemetry;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import java.util.List;

/** The "What is sent" dialog: the rules in words, then the real JSON of a session event from this phone and the last documents sent. */
public final class TelemetryScreen {
    private TelemetryScreen() {}

    public static void show(Activity activity) {
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            String sample = Uploader.sample(app);
            List<String> recent = Uploader.recent();
            StringBuilder text = new StringBuilder(TelemetryText.SENT).append("\n\n").append(TelemetryText.NOT_SENT)
                    .append("\n\nAn example of what one event looks like on this phone:\n").append(sample);
            if (!recent.isEmpty()) {
                text.append("\n\nThe last events sent:");
                for (String line : recent) text.append('\n').append(line);
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                new AlertDialog.Builder(activity)
                        .setTitle("What is sent")
                        .setMessage(text.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "SkyNet-telemetry-preview").start();
    }

    /** The question for installs that were set up before telemetry existed: asked once. */
    public static void askOnce(Activity activity) {
        if (Telemetry.asked(activity)) return;
        new AlertDialog.Builder(activity)
                .setTitle(TelemetryText.TITLE)
                .setMessage(TelemetryText.SENT + "\n\n" + TelemetryText.NOT_SENT + "\n\n" + TelemetryText.CHANGE)
                .setCancelable(false)
                .setPositiveButton("Share", (d, w) -> Telemetry.setEnabled(activity, true))
                .setNegativeButton("No thanks", (d, w) -> Telemetry.setEnabled(activity, false))
                .show();
    }
}
