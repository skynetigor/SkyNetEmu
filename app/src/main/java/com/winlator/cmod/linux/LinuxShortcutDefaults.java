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

    public static final String VERSION = "linuxSettingsVersion";
    public static final String RUNTIME = "linuxRuntime";
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
        values.put(VERSION, "3");
        values.put(LinuxGpuMemory.EXTRA,LinuxGpuMemory.normalize(container.getExtra(LinuxGpuMemory.EXTRA)));
        values.put(RUNTIME, container.getLinuxRuntime());
        values.put("envVars", Container.DEFAULT_ENV_VARS.equals(container.getEnvVars()) ? "" : container.getEnvVars());
        values.put("cpuList", container.getCPUList(true));
        values.put("graphicsFpsPreset", container.getExtra("graphicsFpsPreset", "0"));
        values.put("hudMode", container.getExtra("hudMode", container.isShowFPS() ? "1" : "0"));
        values.put(LinuxSession.EXTRA_NO_PROOT, off(container, LinuxSession.EXTRA_NO_PROOT));
        values.put(LinuxDirectFexBridge.EXTRA, off(container, LinuxDirectFexBridge.EXTRA));

        values.put(SCREEN_SIZE, container.getScreenSize());
        values.put("rendererDriverId", container.getRendererDriverId());
        values.put(FULLSCREEN_STRETCHED, container.isFullscreenStretched()
                ? ("1".equals(container.getExtra("scaleGameWindow")) ? "2" : "1") : "0");
        values.put(UPSCALER, container.getExtra(UPSCALER, "0"));
        values.put(LinuxTouchMode.EXTRA, LinuxTouchMode.resolve(container, null, null));
        values.put(TOUCHSCREEN, on(container, LinuxSession.EXTRA_TOUCHSCREEN));
        values.put(LinuxSession.EXTRA_KEYBOARD_BUTTON, on(container, LinuxSession.EXTRA_KEYBOARD_BUTTON));
        values.put(LinuxSession.EXTRA_AUTO_KEYBOARD, on(container, LinuxSession.EXTRA_AUTO_KEYBOARD));
        values.put(LsfgManager.EXTRA_MULTIPLIER, String.valueOf(LsfgManager.getMultiplier(container)));
        values.put(LsfgManager.EXTRA_LAYER, LsfgManager.isContainerLayerEnabled(container) ? "1" : "0");
        values.put(LsfgManager.EXTRA_PERFORMANCE_MODE, LsfgManager.isPerformanceMode(context, container) ? "1" : "0");
        values.put(LsfgManager.EXTRA_PRESENT_MODE, LsfgManager.getPresentMode(context, container));
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
        values.put(LinuxEmulatorSetup.EXTRA_ISOLATE_GAME_GPU, off(container, LinuxEmulatorSetup.EXTRA_ISOLATE_GAME_GPU));
        values.put(LinuxProot.EXTRA, container.getExtra(LinuxProot.EXTRA, "runtime"));
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
        if (shortcut.getExtra(LinuxTouchMode.EXTRA).isEmpty()
                && !effective.extras.containsKey(LinuxTouchMode.EXTRA) && effective.extras.containsKey(TOUCHSCREEN))
            shortcut.putExtra(LinuxTouchMode.EXTRA, LinuxTouchMode.legacy(effective.extras.get(TOUCHSCREEN)));
        shortcut.saveData();
    }

    public static Map<String, String> fromData(Context context, com.winlator.cmod.container.ContainerManager manager,
                                               org.json.JSONObject data) throws org.json.JSONException {
        Container template = new Container(-1, manager);
        template.loadData(data);
        Map<String, String> values = of(context, template);
        // Profiles that omit execution mode must inherit the shared container's saved choice.
        // Defaults supplied by of() must not overwrite an explicit container choice.
        org.json.JSONObject profileExtras = data.optJSONObject("extraData");
        if (profileExtras == null || profileExtras.optString(LinuxSession.EXTRA_NO_PROOT).isEmpty())
            values.remove(LinuxSession.EXTRA_NO_PROOT);
        // Older native-output profiles omit the separate Android driver. Give newly imported shortcuts Auto;
        // explicit profile choices and existing shortcuts are never migrated over a user's choice.
        if (!data.has("rendererDriverId")) {
            org.json.JSONObject extra = data.optJSONObject("extraData");
            if (extra != null && "native".equals(extra.optString(LinuxSession.EXTRA_VULKAN_PRESENT)))
                values.put("rendererDriverId", "auto");
            else values.remove("rendererDriverId");
        }
        return values;
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
        if (shortcut == null || container == null || !container.isLinux()) return;
        if (shortcut.getExtra(LinuxTouchMode.EXTRA).isEmpty()) {
            shortcut.putExtra(LinuxTouchMode.EXTRA, LinuxTouchMode.resolve(container, shortcut,
                    GameProfiles.resolve(context, container, shortcut)));
            shortcut.saveData();
        }
        if ("3".equals(shortcut.getExtra(VERSION))) return;
        boolean own = isOwn(shortcut);
        for (Map.Entry<String, String> entry : of(context, container).entrySet()) {
            String key = entry.getKey();
            boolean added = key.equals(RUNTIME) || key.equals("cpuList") || key.equals("graphicsFpsPreset")
                    || key.equals("hudMode") || key.equals(LinuxSession.EXTRA_NO_PROOT)
                    || key.equals(LsfgManager.EXTRA_PERFORMANCE_MODE) || key.equals(LsfgManager.EXTRA_PRESENT_MODE)
                    || key.equals(LsfgManager.EXTRA_LAYER);
            if ((!own || added) && shortcut.getExtra(key).isEmpty()) shortcut.putExtra(key, entry.getValue());
        }
        // Explicit shortcut variables override the old container's variables, preserving unrelated inherited variables.
        com.winlator.cmod.core.EnvVars env = new com.winlator.cmod.core.EnvVars(
                Container.DEFAULT_ENV_VARS.equals(container.getEnvVars()) ? "" : container.getEnvVars());
        env.putAll(shortcut.getExtra("envVars"));
        shortcut.putExtra("envVars", env.toString());
        shortcut.putExtra(VERSION, "3");
        shortcut.putExtra(EXTRA_OWN, "1");
        shortcut.saveData();
    }
}
