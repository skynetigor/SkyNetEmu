package com.winlator.cmod.linux;

import android.content.Context;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Android-hosted FEX with real x86 glibc and a matching bionic Vulkan thunk. */
final class LinuxFexBionicSession {
    private LinuxFexBionicSession() {}

    static LinuxSession.Launch build(Context context, ImageFs imageFs, Container container,
            LinuxEmulator.Installed emulator, LinuxRootfs.Installed rootfs, File home,
            File directHome, File executable, List<String> arguments, Map<String, String> guestEnv,
            String libraries, List<String> preloads, JSONObject config) throws IOException, JSONException {
        if (emulator == null || !"fex-2610".equals(emulator.id)
                || !new File(emulator.dir, "share/fex-emu/GuestThunks/libvulkan-guest.so").isFile())
            throw new IOException("Android FEX requires the installed matching FEX-2610 guest Vulkan thunk");
        File fex = stage(context, "FEX");
        stage(context, "FEXServer");
        stage(context, "libvulkan-host.so");
        File parent = LinuxSession.stageAsset(context, "linux-bionic", "bionic-launch-parent");
        if (parent == null) throw new IOException("Android FEX launch parent is unavailable");
        File directory = fex.getParentFile();
        for (String name : new String[]{"tmp", "shm", "data", "config", "cache"})
            Files.createDirectories(new File(directory, name).toPath());
        File configDirectory = new File(directory, "config/fex-emu");
        Files.createDirectories(configDirectory.toPath());
        Files.copy(new File(emulator.dir, "share/fex-emu/ThunksDB.json").toPath(),
                new File(configDirectory, "ThunksDB.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JSONObject settings = config.getJSONObject("Config");
        settings.put("RootFS", rootfs.dir.getPath());
        settings.put("ThunkHostLibs", directory.getPath());
        settings.put("ThunkGuestLibs", new File(emulator.dir, "share/fex-emu/GuestThunks").getPath());
        EnvVars nativeEnv = LinuxBionicSession.nativeEnvironment(context, imageFs, container, directHome);
        Map<String, String> guest = new LinkedHashMap<>(guestEnv);
        guest.put("HOME", directHome.getPath());
        guest.put("XDG_DATA_HOME", new File(home, ".local/share").getPath());
        guest.put("XDG_CONFIG_HOME", new File(home, ".config").getPath());
        guest.put("XDG_CACHE_HOME", new File(home, ".cache").getPath());
        guest.put("LD_LIBRARY_PATH", libraries);
        guest.put("LD_PRELOAD", String.join(":", preloads));
        guest.put("SteamAppId", "391220");
        guest.put("SteamGameId", "391220");
        guest.put("STEAM_COMPAT_APP_ID", "391220");
        guest.put("BL_SYSV_DIR", new File(context.getFilesDir(), "linux-session/shm/wnsysv").getPath());
        guest.put("SKY_DIRECT_TMP", new File(directory, "tmp").getPath());
        guest.put("SKY_DIRECT_X11", new File(imageFs.getRootDir(), "usr/tmp/.X11-unix").getPath());
        guest.put("SKY_DIRECT_HOME", home.getPath());
        guest.put("PULSE_SERVER", nativeEnv.get("PULSE_SERVER"));
        prepareGuestAudio(directory, rootfs.dir, guest);
        JSONArray entries = new JSONArray();
        guest.forEach((key, value) -> entries.put(key + "=" + value));
        settings.put("Env", entries);
        File configFile = new File(directory, "Rise.json");
        Files.write(configFile.toPath(), config.toString().getBytes(StandardCharsets.UTF_8));
        nativeEnv.remove("LD_PRELOAD");
        guestEnv.forEach((key, value) -> {
            if (key.startsWith("FEX_") || key.startsWith("TU_") || key.startsWith("MESA_"))
                nativeEnv.put(key, value);
        });
        nativeEnv.put("FEX_APP_CONFIG", configFile.getPath());
        nativeEnv.put("FEX_ROOTFS", rootfs.dir.getPath());
        nativeEnv.put("FEX_THUNKCONFIG", new File(home, ".skynet/steam-emulator/391220.thunks64.json").getPath());
        nativeEnv.put("FEX_SERVERSOCKETPATH", new File(directory, "server").getPath());
        nativeEnv.put("FEX_SHM_DIR", new File(directory, "shm").getPath());
        nativeEnv.put("FEX_NEEDSSECCOMP", "1");
        nativeEnv.put("XDG_DATA_HOME", new File(directory, "data").getPath());
        nativeEnv.put("XDG_CONFIG_HOME", new File(directory, "config").getPath());
        nativeEnv.put("XDG_CACHE_HOME", new File(directory, "cache").getPath());
        Map<String, String> memoryEnv = new LinkedHashMap<>();
        for (String entry : nativeEnv.toStringArray()) {
            int separator = entry.indexOf('=');
            memoryEnv.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        LinuxGpuMemory.prepare(context, container, memoryEnv, true, true);
        List<String> env = new ArrayList<>();
        memoryEnv.forEach((key, value) -> env.add(key + "=" + value));
        List<String> argv = new ArrayList<>(Arrays.asList(parent.getPath(), fex.getPath(), executable.getPath()));
        argv.addAll(arguments);
        String cores = container.getCPUList();
        if (cores != null && !cores.isEmpty()) {
            int mask = com.winlator.cmod.core.ProcessHelper.getAffinityMask(cores);
            if (mask == 0) throw new IOException("Android FEX CPU affinity selects no cores");
            argv.addAll(0, Arrays.asList("/system/bin/taskset", Integer.toHexString(mask)));
        }
        return new LinuxSession.Launch(argv.toArray(new String[0]), env.toArray(new String[0]),
                executable.getParentFile(), "fex_bionic");
    }

    static void prepareGuestAudio(File directory, File rootfs, Map<String, String> guest) throws IOException {
        File audioConfig = new File(directory, "alsa-pulse.conf");
        File audioPlugins = new File(rootfs, "usr/lib/x86_64-linux-gnu/alsa-lib");
        for (String name : new String[]{"libasound_module_pcm_pulse.so", "libasound_module_ctl_pulse.so"}) {
            if (!new File(audioPlugins, name).isFile())
                throw new IOException("Android FEX guest PulseAudio plugin is unavailable: " + name);
        }
        Files.write(audioConfig.toPath(),
                ("pcm.!default { type pulse }\nctl.!default { type pulse }\n").getBytes(StandardCharsets.UTF_8));
        // Guest ALSA cannot load Android plugins or use its conditional Pulse daemon probe.
        guest.putIfAbsent("ALSA_CONFIG_PATH", audioConfig.getPath());
        guest.putIfAbsent("ALSA_PLUGIN_DIR", "/usr/lib/x86_64-linux-gnu/alsa-lib");
    }

    private static File stage(Context context, String name) throws IOException {
        File file = LinuxSession.stageAsset(context, "linux-fex-bionic", name, "linux-fex-bionic/" + name);
        if (file == null) throw new IOException("Cannot stage Android FEX asset " + name);
        return file;
    }
}
