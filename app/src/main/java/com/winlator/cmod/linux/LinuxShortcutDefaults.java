package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.gameprofile.GameProfiles;
import com.winlator.cmod.lsfg.LsfgManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A Linux shortcut keeps its own copy of every setting a session is started with. The container's settings are what a new
 * shortcut starts from, nothing more: changing the container afterwards leaves its shortcuts alone, and the shortcut's editor
 * shows the values that are really used instead of "container default".
 *
 * <p>A shortcut written before this has no {@link #EXTRA_OWN} and still follows its container for whatever it does not set;
 * it is given its own copy the first time its settings are opened.
 */
public final class LinuxShortcutDefaults {
    /** Marks a shortcut that carries its own settings. */
    public static final String EXTRA_OWN = "linuxOwnSettings";

    /** The settings outside the emulator group (those are {@link LinuxEmulatorSetup#EXTRAS}). */
    public static final String SCREEN_SIZE = "screenSize";
    public static final String FULLSCREEN_STRETCHED = "fullscreenStretched";
    public static final String UPSCALER = "graphicsFilterMode";
    public static final String TOUCHSCREEN = "simTouchScreen";

    private LinuxShortcutDefaults() {}

    public static boolean isOwn(Shortcut shortcut) {
        return shortcut != null && "1".equals(shortcut.getExtra(EXTRA_OWN));
    }

    /**
     * What a new shortcut of this container starts with: every setting as a value of its own, with the ones the container
     * leaves to the runtime (its Vulkan driver, the build of the emulator when only one is installed) resolved where they can be.
     */
    public static Map<String, String> of(Context context, Container container) {
        Map<String, String> values = new LinkedHashMap<>();
        if (container == null || !container.isLinux()) return values;
        values.put(EXTRA_OWN, "1");

        values.put(SCREEN_SIZE, container.getScreenSize());
        values.put(FULLSCREEN_STRETCHED, container.isFullscreenStretched() ? "1" : "0");
        values.put(UPSCALER, container.getExtra(UPSCALER, "0"));
        values.put(TOUCHSCREEN, on(container, LinuxSession.EXTRA_TOUCHSCREEN));
        values.put(LinuxSession.EXTRA_KEYBOARD_BUTTON, on(container, LinuxSession.EXTRA_KEYBOARD_BUTTON));
        values.put(LinuxSession.EXTRA_AUTO_KEYBOARD, on(container, LinuxSession.EXTRA_AUTO_KEYBOARD));
        values.put(LsfgManager.EXTRA_MULTIPLIER, String.valueOf(LsfgManager.getMultiplier(container)));
        values.put(LsfgManager.EXTRA_FLOW_SCALE,
                String.format(Locale.US, "%.2f", LsfgManager.getFlowScale(context, container)));

        String choice = LinuxEmulator.choice(container);
        values.put(LinuxEmulator.EXTRA_EMULATOR, choice);
        LinuxEmulator.Installed build = LinuxEmulator.resolve(context, container);
        values.put(LinuxEmulator.EXTRA_ID, build != null ? build.id : container.getExtra(LinuxEmulator.EXTRA_ID));
        String preset = container.getExtra(LinuxEmulator.EXTRA_PRESET);
        values.put(LinuxEmulator.EXTRA_PRESET, preset.isEmpty() ? Box64Preset.COMPATIBILITY : preset);
        LinuxRootfs.Installed rootfs = LinuxRootfs.resolve(context, container);
        values.put(LinuxEmulator.EXTRA_ROOTFS, rootfs != null ? rootfs.id : container.getExtra(LinuxEmulator.EXTRA_ROOTFS));
        values.put(LinuxEmulator.EXTRA_FEX_PRESET, LinuxFex.presetId(container));
        values.put(LinuxEmulator.EXTRA_FEX_THUNKS, on(container, LinuxEmulator.EXTRA_FEX_THUNKS));
        values.put(LinuxFex.EXTRA_GL_32, on(container, LinuxFex.EXTRA_GL_32));
        values.put(LinuxFex.EXTRA_GL_VENDOR, LinuxFex.glVendor(container));
        values.put(LinuxEmulatorSetup.EXTRA_TU_SYSMEM, off(container, LinuxEmulatorSetup.EXTRA_TU_SYSMEM));
        // Empty: the driver the Linux runtime brings. The container names no other one, so neither does the shortcut.
        values.put(LinuxDriverManager.EXTRA_DRIVER, container.getExtra(LinuxDriverManager.EXTRA_DRIVER));
        values.put(LinuxSession.EXTRA_GL_DRIVER,
                "zink".equals(container.getExtra(LinuxSession.EXTRA_GL_DRIVER)) ? "zink" : "software");
        values.put(LinuxSession.EXTRA_VULKAN_PRESENT,
                "native".equals(container.getExtra(LinuxSession.EXTRA_VULKAN_PRESENT)) ? "native" : "sw");
        values.put(LinuxBionicSession.EXTRA, off(container, LinuxBionicSession.EXTRA));
        return values;
    }

    /**
     * The settings the shortcut's game profile recommends (the resolution it is known to work at, and the rest), written into
     * the shortcut. The profile is read when the shortcut is made and when its profile is changed, not at every start.
     */
    public static void applyProfile(Context context, Container container, Shortcut shortcut) {
        if (shortcut == null || container == null || !container.isLinux()) return;
        GameProfiles.Effective effective = GameProfiles.resolve(context, container, shortcut);
        if (effective == null) return;
        if (!effective.resolution.isEmpty()) shortcut.putExtra(SCREEN_SIZE, effective.resolution);
        for (Map.Entry<String, String> entry : effective.extras.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) shortcut.putExtra(entry.getKey(), entry.getValue());
        }
        shortcut.saveData();
    }

    /** A setting that is on unless the container turned it off. */
    private static String on(Container container, String name) {
        return "0".equals(container.getExtra(name)) ? "0" : "1";
    }

    /** A setting that is off unless the container turned it on. */
    private static String off(Container container, String name) {
        return "1".equals(container.getExtra(name)) ? "1" : "0";
    }

    /**
     * Gives a shortcut written before this its own copy of the settings: what it already sets stays, the rest is taken from
     * the container as it is now. Does nothing to a shortcut that has one.
     */
    public static void snapshot(Context context, Container container, Shortcut shortcut) {
        if (shortcut == null || container == null || !container.isLinux() || isOwn(shortcut)) return;
        for (Map.Entry<String, String> entry : of(context, container).entrySet()) {
            if (shortcut.getExtra(entry.getKey()).isEmpty()) shortcut.putExtra(entry.getKey(), entry.getValue());
        }
        shortcut.putExtra(EXTRA_OWN, "1");
        shortcut.saveData();
    }
}
