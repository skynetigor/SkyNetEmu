package com.winlator.cmod.linux;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.gameprofile.GameProfiles;

/** Linux-only setting. Read old shortcuts/imports once without changing Windows touch settings. */
public final class LinuxTouchMode {
    public static final String EXTRA = "linuxTouchMode", NATIVE = "native", TOUCHPAD = "touchpad";
    private LinuxTouchMode() {}
    public static boolean valid(String value) { return NATIVE.equals(value) || TOUCHPAD.equals(value); }
    public static String legacy(String value) { return "0".equals(value) ? TOUCHPAD : NATIVE; }
    public static String resolve(Container container, Shortcut shortcut, GameProfiles.Effective profile) {
        if (shortcut != null) {
            String value = shortcut.getExtra(EXTRA);
            if (valid(value)) return value;
            value = shortcut.getExtra("simTouchScreen");
            if (!value.isEmpty()) return legacy(value);
        }
        if (profile != null) {
            String value = profile.extras.get(EXTRA);
            if (valid(value)) return value;
            value = profile.extras.get("simTouchScreen");
            if (value != null && !value.isEmpty()) return legacy(value);
        }
        String value = container.getExtra(EXTRA);
        return valid(value) ? value : legacy(container.getExtra(LinuxSession.EXTRA_TOUCHSCREEN));
    }
}
