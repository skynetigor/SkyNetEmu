package com.winlator.cmod.input;

import android.util.Log;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.gameprofile.GameProfiles;

/** Windows retains its simulated touchscreen; Linux retains its separate native-touch setting. */
public final class TouchInputMode {
    public static final String EXTRA = "touchInputMode";
    public static final String TOUCHSCREEN = "touchscreen", TOUCHPAD = "touchpad", MOUSE = "mouse";
    private TouchInputMode() {}

    public static boolean valid(String value) {
        return TOUCHSCREEN.equals(value) || TOUCHPAD.equals(value) || MOUSE.equals(value);
    }

    public static String checked(String value) {
        if (value == null || value.isEmpty()) return "";
        if (valid(value)) return value;
        Log.w("TouchInputMode", "Invalid touchInputMode: " + value);
        return "";
    }

    public static String resolve(Container container, Shortcut shortcut, GameProfiles.Effective profile) {
        String value;
        if (shortcut != null) {
            value = level(shortcut.getExtra(EXTRA), shortcut.getExtra("simTouchScreen"));
            if (!value.isEmpty()) return value;
        }
        if (profile != null) {
            value = level(profile.extras.get(EXTRA), profile.extras.get("simTouchScreen"));
            if (!value.isEmpty()) return value;
        }
        value = level(container.getExtra(EXTRA), container.getExtra("simTouchScreen"));
        return value.isEmpty() ? TOUCHPAD : value;
    }

    private static String level(String explicit, String legacy) {
        String value = checked(explicit);
        return !value.isEmpty() ? value : legacy == null || legacy.isEmpty() ? ""
                : "1".equals(legacy) ? TOUCHSCREEN : TOUCHPAD;
    }
}
