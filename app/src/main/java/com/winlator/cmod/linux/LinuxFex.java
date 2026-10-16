package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything FEX needs to start a program in a Linux container: a configuration file in the container's home and the
 * FEX_* variables of its preset. FEX reads $HOME/.config/fex-emu/Config.json by itself (HOME is /root in a session).
 *
 * Why a file and not variables for most of it: FEX takes any option from a FEX_<OPTION> variable, but splits the
 * variable at its last "=", so a value that holds an "=" (the guest's environment, "LD_PRELOAD=...") cannot be given
 * that way. The file also names the thunk libraries, which forward the guest's Vulkan and OpenGL calls to the
 * runtime's own libraries instead of emulating a driver.
 */
public final class LinuxFex {
    private static final String CONFIG_DIR = ".config/fex-emu";
    static final String NEEDS_ROOTFS = "FEX needs an x86-64 root filesystem. Install one in Components, Linux Root "
            + "Filesystem, and choose it in the container's settings.";

    private LinuxFex() {}

    /**
     * Container extra: "2" when the container's preset was chosen on the settings screen of this version. Before it the screen filled in
     * Intermediate for everyone, so an Intermediate without this mark is the old default and not a choice.
     */
    public static final String EXTRA_PRESET_VERSION = "linuxFexPresetVersion";

    /**
     * The preset used when a container names none: Performance, which runs without TSO (the emulation of x86's memory ordering). On the
     * device BeamNG runs about twice as fast with it as with Intermediate, and Box64 does not emulate the ordering by default either.
     */
    static String presetId(Container container) {
        String id = container.getExtra(LinuxEmulator.EXTRA_FEX_PRESET);
        if (id.isEmpty()) return FEXCorePreset.PERFORMANCE;
        if (FEXCorePreset.INTERMEDIATE.equals(id) && !"2".equals(container.getExtra(EXTRA_PRESET_VERSION))) return FEXCorePreset.PERFORMANCE;
        return id;
    }

    /** Adds the container's preset (FEX_TSOENABLED and the others) to the session's environment. */
    static void applyPreset(Context context, Container container, Map<String, String> env) {
        EnvVars preset = FEXCorePresetManager.getEnvVars(context, presetId(container));
        for (String pair : preset.toStringArray()) {
            int index = pair.indexOf('=');
            if (index > 0) env.put(pair.substring(0, index), pair.substring(index + 1));
        }
    }

    /** GPU thunks are on unless the container turned them off, and only when the package carries them. */
    static boolean thunksEnabled(Container container, LinuxEmulator.Installed emulator) {
        return !"0".equals(container.getExtra(LinuxEmulator.EXTRA_FEX_THUNKS)) && new File(emulator.dir, "share/fex-emu/ThunksDB.json").isFile()
                && new File(emulator.dir, "lib/fex-emu/HostThunks").isDirectory()
                && new File(emulator.dir, "share/fex-emu/GuestThunks").isDirectory();
    }

    /**
     * Writes the configuration and adds the preset's variables to the session's environment.
     *
     * @return null when FEX is ready to run, otherwise why it is not
     */
    static String prepare(Context context, Container container, LinuxEmulator.Installed emulator, Map<String, String> env) {
        LinuxRootfs.Installed rootfs = LinuxRootfs.resolve(context, container);
        if (rootfs == null) return NEEDS_ROOTFS;

        File configDir = new File(container.getLinuxHomeDir(), CONFIG_DIR);
        if (!configDir.isDirectory() && !configDir.mkdirs()) return "Cannot create " + configDir;

        boolean thunks = thunksEnabled(container, emulator);
        StringBuilder config = new StringBuilder("{\n  \"Config\": {\n");
        appendOption(config, "RootFS", rootfs.dir.getPath());
        appendDiskCache(config, container);
        if (thunks) {
            appendOption(config, "ThunkHostLibs", new File(emulator.dir, "lib/fex-emu/HostThunks").getPath());
            appendOption(config, "ThunkGuestLibs", new File(emulator.dir, "share/fex-emu/GuestThunks").getPath());
        }
        // The guest's own environment: our x86-64 shims (the gamepad's evdev ioctls and uevent socket, and CEF's
        // sandbox check) are the ones Box64 preloads, and the 32-bit one is for a 32-bit game (the loader skips a library of the other
        // class). FEX falls back to the phone's file system for a path the root filesystem does not have, so the files under the
        // app's data folder can be named as they are.
        List<String> preload = new ArrayList<>();
        for (String name : new String[]{"libskyshim_x64.so", "libskyshim_x86.so", "libcefnosb_x64.so"}) {
            File shim = LinuxSession.stageAsset(context, "linux-shim", name);
            if (shim != null) preload.add(shim.getPath());
        }
        if (!preload.isEmpty()) appendOption(config, "Env", "LD_PRELOAD=" + String.join(":", preload));
        // Trailing commas are not JSON: the last option is the one written last, so close it here.
        int last = config.lastIndexOf(",\n");
        if (last >= 0 && last == config.length() - 2) config.setLength(last);
        config.append("\n  }");
        if (thunks) {
            // The overlays a thunk replaces are listed in the database; which of them are on is chosen here. Vulkan is
            // what a game draws with. The OpenGL thunk is left off: with it on BeamNG's start hung (every thread asleep
            // behind eight llvmpipe workers) and without it the game ran on.
            config.append(",\n  \"ThunksDB\": { \"Vulkan\": 1, \"GL\": 0, \"drm\": 0, \"asound\": 0, \"WaylandClient\": 0, \"cuda\": 0 }");
        }
        config.append("\n}\n");

        if (!FileUtils.writeString(new File(configDir, "Config.json"), config.toString())) return "Cannot write FEX's configuration";
        if (thunks) {
            File database = new File(emulator.dir, "share/fex-emu/ThunksDB.json");
            FileUtils.copy(database, new File(configDir, "ThunksDB.json"));
        }

        // FEX's thunk loader prints a line for every function it links (thousands for Vulkan), and a game's log is no
        // place for them. The container's own variables are applied after this, so FEX_SILENTLOG=0 there brings them back.
        env.put("FEX_SILENTLOG", "1");

        applyPreset(context, container, env);
        return null;
    }

    /**
     * The 32-bit thunks of a package: FEX takes them from the folders of the 64-bit ones with "_32" added. Only OpenGL and EGL
     * have a 32-bit guest side (the Vulkan thunk's is not built), which is what a 32-bit OpenGL game needs.
     */
    static boolean thunks32Available(LinuxEmulator.Installed emulator) {
        return new File(emulator.dir, "share/fex-emu/ThunksDB.json").isFile()
                && new File(emulator.dir, "lib/fex-emu/HostThunks_32/libGL-host.so").isFile()
                && new File(emulator.dir, "share/fex-emu/GuestThunks_32/libGL-guest.so").isFile();
    }

    /**
     * Container (or shortcut) extra: "0" keeps a 32-bit game on the root filesystem's software OpenGL (slow, but it needs nothing from
     * the host); anything else, which is the default, has it draw with the OpenGL thunk on the GPU (Zink on Turnip). The launcher gives
     * such a game MESA_GL_VERSION_OVERRIDE=4.6COMPAT: a game that makes its context with glXCreateContext asks for a compatibility
     * profile, and the container's usual override (3.3) is a core one, which fails as GLXBadFBConfig (Tomb Raider 2013).
     */
    public static final String EXTRA_GL_32 = "linuxFexGl32";

    /**
     * Container (or shortcut) extra: the GPU a 32-bit game on the OpenGL thunk is told it draws on: "nvidia" (the default), "amd", or
     * "mesa" for the real strings (Mesa / zink / Adreno). Feral's ports (Tomb Raider 2013) pick their workarounds and their
     * defaults by the vendor in GL_VENDOR / GL_RENDERER: reported as an unknown card, the game ran at its lowest settings and drew
     * black patches on lit surfaces; reported as an NVIDIA card it draws as intended.
     */
    public static final String EXTRA_GL_VENDOR = "linuxGlVendor";

    /** The vendor choice of a container ("nvidia", "amd" or "mesa"). */
    static String glVendor(Container container) {
        String value = container.getExtra(EXTRA_GL_VENDOR);
        return "amd".equals(value) || "mesa".equals(value) ? value : "nvidia";
    }

    /** What a 32-bit program's thunk selection is kept in (as the program sees the file; FEX reads it from FEX_THUNKCONFIG). */
    private static final String THUNK_CONFIG_32 = "/root/" + CONFIG_DIR + "/ThunkConfig32.json";

    /** Whether a 32-bit game of this container draws with the OpenGL thunk (opt-in, see {@link #EXTRA_GL_32}). */
    static boolean gl32Enabled(Container container, LinuxEmulator.Installed emulator) {
        return !"0".equals(container.getExtra(EXTRA_GL_32)) && !"0".equals(container.getExtra(LinuxEmulator.EXTRA_FEX_THUNKS))
                && thunks32Available(emulator);
    }

    /**
     * The thunk selection of a 32-bit program, in a file of its own that the launcher hands to FEX for a 32-bit program only
     * (FEX_THUNKCONFIG). The container's own selection is made for 64-bit programs: its Vulkan thunk has no 32-bit guest side, and with
     * the OpenGL thunk on a 64-bit game's start hung. A 32-bit program gets every thunk off, the OpenGL one on when {@link #gl32Enabled}:
     * its OpenGL calls then go to the runtime's own Mesa (Zink on Turnip) instead of the root filesystem's software renderer.
     *
     * @return the file as the program sees it, or null when the package has no 32-bit thunks
     */
    static String writeThunkConfig32(Container container, LinuxEmulator.Installed emulator) {
        if (!thunks32Available(emulator)) return null;
        File configDir = new File(container.getLinuxHomeDir(), CONFIG_DIR);
        if (!configDir.isDirectory() && !configDir.mkdirs()) return null;
        String gl = gl32Enabled(container, emulator) ? "1" : "0";
        if (!FileUtils.writeString(new File(configDir, "ThunkConfig32.json"),
                "{ \"ThunksDB\": { \"Vulkan\": 0, \"GL\": " + gl + ", \"drm\": 0, \"asound\": 0, \"WaylandClient\": 0, \"cuda\": 0 } }\n")) return null;
        FileUtils.copy(new File(emulator.dir, "share/fex-emu/ThunksDB.json"), new File(configDir, "ThunksDB.json"));
        return THUNK_CONFIG_32;
    }

    /**
     * FEX as the second emulator of a Box64 container: it runs only the 32-bit programs Box64 cannot (see libskyshim). Writes the
     * configuration for them: the root filesystem and the thunk folders; the selection is {@link #writeThunkConfig32}'s.
     *
     * @return the 32-bit thunk selection (see above), or null
     */
    static String prepareFor32Bit(Context context, Container container, LinuxEmulator.Installed emulator, LinuxRootfs.Installed rootfs) {
        File configDir = new File(container.getLinuxHomeDir(), CONFIG_DIR);
        if (!configDir.isDirectory() && !configDir.mkdirs()) return null;
        String thunks = writeThunkConfig32(container, emulator);
        StringBuilder config = new StringBuilder("{\n  \"Config\": {\n");
        appendOption(config, "RootFS", rootfs.dir.getPath());
        appendDiskCache(config, container);
        if (thunks != null) {
            appendOption(config, "ThunkHostLibs", new File(emulator.dir, "lib/fex-emu/HostThunks").getPath());
            appendOption(config, "ThunkGuestLibs", new File(emulator.dir, "share/fex-emu/GuestThunks").getPath());
        }
        // A 32-bit game has no shim otherwise: its gamepad (evdev events in the 32-bit layout) and its udev socket need ours.
        File shim32 = LinuxSession.stageAsset(context, "linux-shim", "libskyshim_x86.so");
        if (shim32 != null) appendOption(config, "Env", "LD_PRELOAD=" + shim32.getPath());
        int last = config.lastIndexOf(",\n");
        if (last >= 0 && last == config.length() - 2) config.setLength(last);
        config.append("\n  }");
        if (thunks != null) config.append(",\n  \"ThunksDB\": { \"Vulkan\": 0, \"GL\": 0, \"drm\": 0, \"asound\": 0, \"WaylandClient\": 0, \"cuda\": 0 }");
        config.append("\n}\n");
        return FileUtils.writeString(new File(configDir, "Config.json"), config.toString()) ? thunks : null;
    }

    /**
     * Container (or shortcut) extra: "1" turns on FEX's disk cache of translated code (FEX 2609 and later; experimental): the code FEX has translated
     * is kept in $HOME/.cache/fex-emu (the container's home) and the next start of the same program does not translate it again. Off by default:
     * on the device (BeamNG 0.39) it did not make the game load faster or run faster, took 355 MB for one game, and one start with a warm cache
     * stopped at the loading screen with every thread asleep.
     */
    public static final String EXTRA_DISK_CACHE = "linuxFexDiskCache";

    private static void appendDiskCache(StringBuilder config, Container container) {
        if ("1".equals(container.getExtra(EXTRA_DISK_CACHE))) appendOption(config, "DiskCache", "1");
    }

    /** One option of the file's Config block; a repeated name is how FEX's JSON holds a list ("Env" more than once). */
    private static void appendOption(StringBuilder out, String name, String value) {
        out.append("    \"").append(name).append("\": \"").append(escape(value)).append("\",\n");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
