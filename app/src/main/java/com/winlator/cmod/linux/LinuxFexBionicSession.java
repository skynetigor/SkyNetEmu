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
public final class LinuxFexBionicSession {
    private LinuxFexBionicSession() {}

    static LinuxSession.Launch build(Context context, ImageFs imageFs, Container container,
            LinuxEmulator.Installed emulator, LinuxRootfs.Installed rootfs, File home,
            File directHome, File executable, List<String> arguments, Map<String, String> guestEnv,
            String libraries, List<String> preloads, JSONObject config) throws IOException, JSONException {
        return build(context, imageFs, container, emulator, rootfs, home, directHome, executable,
                arguments, guestEnv, libraries, preloads, config, "391220");
    }

    private static LinuxSession.Launch build(Context context, ImageFs imageFs, Container container,
            LinuxEmulator.Installed emulator, LinuxRootfs.Installed rootfs, File home,
            File directHome, File executable, List<String> arguments, Map<String, String> guestEnv,
            String libraries, List<String> preloads, JSONObject config, String appId) throws IOException, JSONException {
        if (emulator == null || !"fex-2610".equals(emulator.id)
                || !new File(emulator.dir, "share/fex-emu/GuestThunks/libvulkan-guest.so").isFile())
            throw new IOException("Android FEX requires the installed matching FEX-2610 guest Vulkan thunk");
        File fex = stage(context, "FEX");
        stage(context, "FEXServer");
        stage(context, "libvulkan-host.so");
        File parent = LinuxSession.stageAsset(context, "linux-bionic", "bionic-launch-parent");
        if (parent == null) throw new IOException("Android FEX launch parent is unavailable");
        File directory = fex.getParentFile();
        Files.createDirectories(new File(rootfs.dir, "dev/shm").toPath());
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
        if (appId != null) {
            guest.put("SteamAppId", appId);
            guest.put("SteamGameId", appId);
            guest.put("STEAM_COMPAT_APP_ID", appId);
        }
        guest.put("BL_SYSV_DIR", new File(context.getFilesDir(), "linux-session/shm/wnsysv").getPath());
        guest.put("SKY_DIRECT_TMP", new File(directory, "tmp").getPath());
        guest.put("SKY_DIRECT_X11", new File(imageFs.getRootDir(), "usr/tmp/.X11-unix").getPath());
        guest.put("SKY_DIRECT_HOME", home.getPath());
        guest.put("SKY_DIRECT_SHM", new File(rootfs.dir, "dev/shm").getPath());
        guest.put("PULSE_SERVER", nativeEnv.get("PULSE_SERVER"));
        prepareGuestAudio(directory, rootfs.dir, guest);
        JSONArray entries = new JSONArray();
        guest.forEach((key, value) -> entries.put(key + "=" + value));
        settings.put("Env", entries);
        File configFile = new File(directory, appId != null ? "Rise.json" : "Direct.json");
        Files.write(configFile.toPath(), config.toString().getBytes(StandardCharsets.UTF_8));
        nativeEnv.remove("LD_PRELOAD");
        guestEnv.forEach((key, value) -> {
            if (key.startsWith("FEX_") || key.startsWith("TU_") || key.startsWith("MESA_"))
                nativeEnv.put(key, value);
        });
        nativeEnv.put("FEX_APP_CONFIG", configFile.getPath());
        nativeEnv.put("FEX_ROOTFS", rootfs.dir.getPath());
        if (appId != null) {
            nativeEnv.put("FEX_THUNKCONFIG",
                    new File(home, ".skynet/steam-emulator/391220.thunks64.json").getPath());
        } else {
            File thunkConfig = new File(directory, "ThunkConfig64.json");
            Files.write(thunkConfig.toPath(),
                    new JSONObject().put("ThunksDB", config.getJSONObject("ThunksDB")).toString()
                            .getBytes(StandardCharsets.UTF_8));
            nativeEnv.put("FEX_THUNKCONFIG", thunkConfig.getPath());
        }
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

    static LinuxSession.Launch buildDirect(Context context, ImageFs imageFs, Container container,
            String[] command, List<String> environment, File home) throws IOException, JSONException {
        int at = validateDirectCommand(command);
        int variables = Integer.parseInt(command[at + 3]);
        int first = at + 5;
        File executable = LinuxSession.hostFile(context, container, command[at + 1]);
        if (executable == null || LinuxSession.elfMachine(executable) != 62)
            throw new IOException("Android FEX requires an installed x86-64 executable");
        File originalExecutable = executable;
        if (LinuxExecutableStaging.isBeamNG(executable)
                && LinuxProgramPaths.isWithin(android.os.Environment.getExternalStorageDirectory(), executable)) {
            executable = LinuxExecutableStaging.stageBeamNG(executable,
                    new File(context.getFilesDir(), "linux-executable-staging"));
        }
        LinuxEmulator.Installed emulator = LinuxEmulator.resolve(context, container);
        LinuxRootfs.Installed rootfs = LinuxRootfs.resolve(context, container);
        if (rootfs == null) throw new IOException(LinuxFex.NEEDS_ROOTFS);
        Map<String, String> guest = new LinkedHashMap<>();
        for (String entry : environment) {
            int separator = entry.indexOf('=');
            if (separator > 0) guest.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        for (int index = first; index < first + variables; ++index) {
            String entry = command[index];
            int separator = entry.indexOf('=');
            if (separator <= 0) throw new IOException("Invalid Android FEX guest environment entry");
            guest.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        if (!originalExecutable.equals(executable)) guest.put("SKY_RESOURCE_DIR", originalExecutable.getParent());
        List<String> preloads = new ArrayList<>();
        for (String name : new String[]{"libskyplatform_x64.so", "libskysysv_x64.so",
                "libskysockets_x64.so", "libskyshim_x64.so", "libcefnosb_x64.so", "libskyshm_x64.so"}) {
            File asset = LinuxSession.stageAsset(context, "linux-shim", name);
            if (asset == null) throw new IOException("Android FEX guest shim unavailable: " + name);
            preloads.add(asset.getPath());
        }
        if (!originalExecutable.equals(executable)) {
            File adapter = LinuxSession.stageAsset(context, "linux-shim", "libskygamepath_x64.so");
            if (adapter == null) throw new IOException("BeamNG shared-data adapter unavailable");
            preloads.add(adapter.getPath());
            guest.put("SKY_DIRECT_ORIGINAL_EXECUTABLE", originalExecutable.getCanonicalPath());
            guest.put("SKY_DIRECT_STAGED_EXECUTABLE", executable.getCanonicalPath());
        }
        JSONObject config = new JSONObject(new String(Files.readAllBytes(
                new File(home, ".config/fex-emu/Config.json").toPath()), StandardCharsets.UTF_8));
        String libraries = executable.getParent() + ":" + guest.getOrDefault("LD_LIBRARY_PATH", "")
                .replace("/root", home.getPath());
        if (!originalExecutable.equals(executable))
            libraries = libraries.replace(originalExecutable.getParent(), executable.getParent());
        LinuxSession.Launch launch = build(context, imageFs, container, emulator, rootfs, home, home, executable,
                Arrays.asList(command).subList(first + variables, command.length),
                guest, libraries, preloads, config, null);
        File workingDirectory = originalExecutable.getParentFile();
        if ("BeamNG.drive.x64".equals(executable.getName()) && "BinLinux".equals(workingDirectory.getName())) {
            String gameRoot = container.getExtra("gameRoot");
            workingDirectory = gameRoot.isEmpty() ? workingDirectory.getParentFile()
                    : LinuxSession.hostFile(context, container, gameRoot);
            if (workingDirectory == null || !workingDirectory.isDirectory())
                throw new IOException("BeamNG game data root is unavailable: " + gameRoot);
        }
        return new LinuxSession.Launch(launch.argv, launch.hostEnv, workingDirectory, launch.executionMode);
    }

    static int validateDirectCommand(String[] command) throws IOException {
        int at = Arrays.asList(command).indexOf("bannerlator-run");
        if (at < 0 || at + 4 >= command.length)
            throw new IOException("Android FEX requires a direct executable shortcut");
        try {
            int variables = Integer.parseInt(command[at + 3]);
            int wrappers = Integer.parseInt(command[at + 4]);
            if (variables < 0 || wrappers != 0 || variables > command.length - (at + 5))
                throw new IOException("Invalid Android FEX direct command or unsupported command wrapper");
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Android FEX direct command counts", e);
        }
        return at;
    }

    public static com.winlator.cmod.core.ContainerProcess startServer(
            com.winlator.cmod.xenvironment.XEnvironment environment, LinuxSession.Launch launch,
            com.winlator.cmod.core.Callback<String> output) throws IOException, InterruptedException {
        File executable = stage(environment.getContext(), "FEXServer");
        java.util.concurrent.atomic.AtomicInteger exit = new java.util.concurrent.atomic.AtomicInteger(-1);
        com.winlator.cmod.core.ContainerProcess server = environment.startProcess(
                new String[]{executable.getPath(), "--foreground", "--persistent", "15"},
                launch.hostEnv, launch.workingDir, exit::set, output);
        File endpoint = new File(executable.getParentFile(), "server");
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        IOException last = null;
        boolean ready = false;
        try {
            while (exit.get() == -1 && android.os.SystemClock.elapsedRealtime() < deadline) {
                try (android.net.LocalSocket probe = new android.net.LocalSocket()) {
                    probe.connect(new android.net.LocalSocketAddress(endpoint.getPath(),
                            android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                    ready = true;
                    return server;
                } catch (IOException error) {
                    last = error;
                    Thread.sleep(100);
                }
            }
            throw new IOException("Android FEXServer did not become ready (exit=" + exit.get() + ")", last);
        } finally {
            if (!ready) server.requestStop();
        }
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
