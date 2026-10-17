package com.winlator.cmod.linux;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The x86 emulator a Linux program runs under: the container's choice, with whatever its shortcut sets laid over it. A
 * shortcut's extras have the container's names; an empty one follows the container ("Container default"). The settings are
 * decided when the session is built (the emulator's variables, the FEX configuration, the binds of its root filesystem), so
 * the session gets {@link #of} instead of the container.
 */
public final class LinuxEmulatorSetup {
    /** Container (or shortcut) extra: "1" runs Turnip without its tiled (GMEM) rendering, TU_DEBUG=sysmem. */
    public static final String EXTRA_TU_SYSMEM = "linuxTuSysmem";

    /** The shortcut extras that belong to the emulator. */
    public static final String[] EXTRAS = {
            LinuxEmulator.EXTRA_EMULATOR, LinuxEmulator.EXTRA_ID, LinuxEmulator.EXTRA_PRESET, LinuxEmulator.EXTRA_ROOTFS,
            LinuxEmulator.EXTRA_FEX_PRESET, LinuxEmulator.EXTRA_FEX_THUNKS, LinuxFex.EXTRA_GL_32,
            LinuxFex.EXTRA_GL_VENDOR, EXTRA_TU_SYSMEM,
            LinuxDriverManager.EXTRA_DRIVER, LinuxSession.EXTRA_GL_DRIVER, LinuxSession.EXTRA_VULKAN_PRESENT,
            LinuxBionicSession.EXTRA
    };

    private LinuxEmulatorSetup() {}

    /** Whether the shortcut sets any of the emulator's settings. */
    public static boolean overrides(Shortcut shortcut) {
        if (shortcut == null) return false;
        for (String name : EXTRAS) if (!shortcut.getExtra(name).isEmpty()) return true;
        return false;
    }

    /**
     * The container the session is built for: this one when the shortcut leaves the emulator alone, otherwise a copy that has the
     * shortcut's settings. Choosing another emulator drops the container's build and preset (they name builds of the other one).
     */
    public static Container of(Container container, Shortcut shortcut) {
        if (container == null || !container.isLinux() || !overrides(shortcut)) return container;
        Map<String, String> over = new LinkedHashMap<>();
        String emulator = shortcut.getExtra(LinuxEmulator.EXTRA_EMULATOR);
        boolean switched = !emulator.isEmpty() && !emulator.equals(LinuxEmulator.choice(container));
        if (switched) {
            over.put(LinuxEmulator.EXTRA_ID, "");
            over.put(LinuxEmulator.EXTRA_PRESET, "");
        }
        for (String name : EXTRAS) {
            String value = shortcut.getExtra(name);
            if (!value.isEmpty()) over.put(name, value);
        }
        return container.copyWithExtras(over);
    }
}
