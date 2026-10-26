package com.winlator.cmod.input;

import android.app.AlertDialog;
import android.content.Context;
import java.util.Arrays;
import java.util.function.Consumer;

/** Opening, restoring or dismissing the selector must never save a mode. */
public final class TouchInputModeDialog {
    private TouchInputModeDialog() {}

    public static AlertDialog show(Context context, String title, String[] labels, String[] modes,
                                   String current, Consumer<String> selected) {
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title)
                .setSingleChoiceItems(labels, Arrays.asList(modes).indexOf(current), (choice, position) -> {
                    String mode = modes[position];
                    if (!mode.equals(current)) selected.accept(mode);
                    choice.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).create();
        dialog.show();
        return dialog;
    }
}
