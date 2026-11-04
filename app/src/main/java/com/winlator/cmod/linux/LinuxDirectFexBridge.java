package com.winlator.cmod.linux;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ContainerProcess;
import com.winlator.cmod.xenvironment.XEnvironment;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;
import org.json.JSONObject;

/** One fixed Steam game, launched by the Android owner rather than a traced guest parent. */
public final class LinuxDirectFexBridge implements AutoCloseable {
    public static final String EXTRA = "linuxDirectFex";
    private static final String TAG = "LinuxDirectFex";
    private static final List<String> VARIABLES = Arrays.asList(
            "FEX_TSOENABLED", "FEX_VECTORTSOENABLED", "FEX_MEMCPYSETTSOENABLED",
            "FEX_HALFBARRIERTSOENABLED", "FEX_X87REDUCEDPRECISION", "FEX_MULTIBLOCK",
            "FEX_DYNAMICL1CACHE", "TU_DEBUG", "MESA_VK_WSI_DEBUG", "LD_LIBRARY_PATH");
    /** A Steam game this bridge may start: its install folder, its x86-64 executable and what it needs around it. */
    private static final class Game {
        final String appId, folder;
        /** The executable, relative to the install folder. */
        final String executable;
        /** The start script that stands for the executable in Steam's request (Feral's), or null. */
        final String script;
        /** Whether the game sees the Steam client's shared memory (/dev/shm); without it the game's Steam API fails to start and the game runs without Steam. */
        final boolean steamIpc;
        /** The folders of the install that carry the game's own libraries, relative to it ("" is the install folder itself). */
        final List<String> libraryDirs;
        /** Old libraries it needs from the Steam Linux Runtime (scout), as paths in that runtime. */
        final List<String> legacyLibraries;

        Game(String appId, String folder, String executable, String script, boolean steamIpc, List<String> libraryDirs, String... legacyLibraries) {
            this.appId = appId;
            this.folder = folder;
            this.executable = executable;
            this.script = script;
            this.steamIpc = steamIpc;
            this.libraryDirs = libraryDirs;
            this.legacyLibraries = Arrays.asList(legacyLibraries);
        }

        String libraryPath(File gameRoot) {
            StringBuilder path = new StringBuilder();
            for (String dir : libraryDirs) path.append(path.length() == 0 ? "" : ":").append(dir.isEmpty() ? gameRoot.getPath() : gameRoot + "/" + dir);
            return path.toString();
        }
    }

    private static final Map<String, Game> GAMES = new LinkedHashMap<>();
    static {
        List<String> feral = Arrays.asList("lib", "bin");
        GAMES.put("391220", new Game("391220", "Rise of the Tomb Raider", "bin/RiseOfTheTombRaider", "RiseOfTheTombRaider.sh", false, feral,
                "usr/lib/x86_64-linux-gnu/libgconf-2.so.4", "usr/lib/x86_64-linux-gnu/libdbus-glib-1.so.2"));
        GAMES.put("750920", new Game("750920", "Shadow of the Tomb Raider", "bin/ShadowOfTheTombRaider", "ShadowOfTheTombRaider.sh", true, feral));
        GAMES.put("383150", new Game("383150", "DIDE", "DeadIslandGame", null, false, Arrays.asList(""),
                "lib/x86_64-linux-gnu/libpng12.so.0", "usr/lib/x86_64-linux-gnu/libGLEW.so.1.10"));
    }

    private final XEnvironment environment;
    private final Container container;
    private final LinuxRuntime.Installed runtime;
    private final Callback<String> output;
    private final LocalServerSocket server;
    private final String socketName = "skynet-fex-" + UUID.randomUUID();
    private final Map<String, String> sessionEnv = new LinkedHashMap<>();
    private final List<String> bindings = new ArrayList<>();
    private final File directory;
    private volatile boolean stopped;
    private volatile boolean suspended;
    private volatile ContainerProcess game;
    private volatile ContainerProcess bionicServer;
    private volatile LocalSocket connection;

    public static boolean enabled(Container container, String[] command) {
        return "1".equals(container.getExtra(EXTRA))
                && (LinuxEmulator.FEX.equals(LinuxEmulator.choice(container))
                        || LinuxBionicSession.enabled(container))
                && Arrays.asList(command).contains(LinuxSession.STEAM_MARKER);
    }

    public LinuxDirectFexBridge(XEnvironment environment, Container container,
            LinuxRuntime.Installed runtime, Callback<String> output) throws IOException {
        this.environment = environment;
        this.container = container;
        this.runtime = runtime;
        this.output = output;
        directory = new File(environment.getContext().getFilesDir(), "linux-session/direct-fex");
        Files.createDirectories(directory.toPath());
        server = new LocalServerSocket(socketName);
    }

    public LinuxSession.Launch attach(LinuxSession.Launch launch, String[] command) {
        if (!"proot".equals(launch.executionMode))
            throw new IllegalArgumentException("Direct FEX bridge requires the native Steam PRoot session");
        List<String> argv = new ArrayList<>(Arrays.asList(launch.argv));
        int end = argv.size() - command.length;
        int start = argv.indexOf("/usr/bin/env");
        if (start < 0 || !"-i".equals(argv.get(start + 1)) || end <= start + 2)
            throw new IllegalArgumentException("Missing isolated Steam environment");
        for (int index = start + 2; index < end; ++index) {
            String entry = argv.get(index);
            int separator = entry.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("Invalid Steam environment entry");
            sessionEnv.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        bindings.addAll(argv.subList(0, start));
        argv.add(end, "SKY_DIRECT_FEX_SOCKET=" + socketName);
        argv.add(end + 1, "SKY_DIRECT_FEX_CLIENT=" + new File(
                environment.getContext().getApplicationInfo().nativeLibraryDir, "libdirect_fex_client.so").getPath());
        List<String> androidBindings = new ArrayList<>(Arrays.asList("-b", "/system"));
        for (String optional : new String[]{"/apex", "/linkerconfig"})
            if (new File(optional).isDirectory()) androidBindings.addAll(Arrays.asList("-b", optional));
        androidBindings.addAll(Arrays.asList("-b",
                environment.getContext().getApplicationInfo().nativeLibraryDir));
        argv.addAll(1, androidBindings);
        Thread listener = new Thread(this::serve, "linux-direct-fex");
        listener.setDaemon(true);
        listener.start();
        return new LinuxSession.Launch(argv.toArray(new String[0]), launch.hostEnv,
                launch.workingDir, launch.executionMode);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 4096) throw new IOException("Direct FEX string exceeds limit");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0 || !Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)))
            throw new IOException("Invalid direct FEX UTF-8 string");
        return text;
    }

    private void serve() {
        while (!stopped) {
            try (LocalSocket socket = server.accept()) {
                connection = socket;
                socket.setSoTimeout(10000);
                if (socket.getPeerCredentials().getUid() != android.os.Process.myUid())
                    throw new IOException("Direct FEX peer belongs to another UID");
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream result = new DataOutputStream(socket.getOutputStream());
                try {
                    if (input.readInt() != 1) throw new IOException("Unsupported direct FEX request");
                    Game requested = GAMES.get(readText(input));
                    if (requested == null) throw new IOException("Unsupported direct FEX game");
                    int count = input.readInt();
                    if (count < 1 || count > 64) throw new IOException("Direct FEX argument count exceeds limit");
                    List<String> command = new ArrayList<>();
                    int bytes = 0;
                    for (int index = 0; index < count; ++index) {
                        String word = readText(input);
                        bytes += word.getBytes(StandardCharsets.UTF_8).length;
                        if (bytes > 32768) throw new IOException("Direct FEX arguments exceed limit");
                        command.add(word);
                    }
                    int variables = input.readInt();
                    if (variables < 0 || variables > VARIABLES.size())
                        throw new IOException("Direct FEX environment count exceeds limit");
                    Map<String, String> overrides = new LinkedHashMap<>();
                    for (int index = 0; index < variables; ++index) {
                        String key = readText(input), value = readText(input);
                        if (!VARIABLES.contains(key) || overrides.put(key, value) != null)
                            throw new IOException("Unsupported or repeated direct FEX variable");
                    }
                    LinuxSession.Launch launch = prepare(requested, command, overrides);
                    socket.setSoTimeout(0);
                    CountDownLatch exited = new CountDownLatch(1);
                    AtomicInteger status = new AtomicInteger(126);
                    synchronized (this) {
                        if (stopped) throw new IOException("Direct FEX session is stopping");
                        if ("fex_bionic".equals(launch.executionMode)) startBionicServer(launch);
                        game = environment.startProcess(launch.argv, launch.hostEnv, launch.workingDir,
                                code -> { status.set(code); exited.countDown(); }, output);
                        game.setSuspended(suspended);
                        Log.i(TAG, "Steam requester=" + socket.getPeerCredentials().getPid()
                                + " direct_supervisor=" + game.getPid());
                    }
                    ContainerProcess owned = game;
                    Thread disconnect = new Thread(() -> {
                        try {
                            input.read();
                        } catch (IOException error) {
                            if (!stopped) Log.i(TAG, "Steam launch connection closed");
                        } finally {
                            owned.requestStop();
                            exited.countDown();
                        }
                    }, "direct-fex-steam-stop");
                    disconnect.setDaemon(true);
                    disconnect.start();
                    if (!exited.await(24, TimeUnit.HOURS)) throw new IOException("Direct FEX launch deadline exceeded");
                    owned.requestStop();
                    if (!owned.awaitStop(7000)) throw new IOException("Direct FEX descendants remain alive");
                    if (bionicServer != null) {
                        bionicServer.requestStop();
                        if (!bionicServer.awaitStop(7000)) throw new IOException("Android FEX server descendants remain alive");
                    }
                    result.writeInt(status.get());
                    result.flush();
                } catch (IOException | JSONException | IllegalArgumentException | IllegalStateException error) {
                    if (!stopped) {
                        Log.e(TAG, "Direct FEX launch refused", error);
                        if (output != null) output.call("SkyNetEmu: direct FEX launch failed: " + error.getMessage());
                        result.writeInt(126);
                        result.flush();
                    }
                } finally {
                    if (game != null) game.requestStop();
                    game = null;
                    if (bionicServer != null) bionicServer.requestStop();
                    bionicServer = null;
                }
            } catch (IOException error) {
                if (!stopped) Log.e(TAG, "Direct FEX connection failed", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Direct FEX launch interrupted", error);
                close();
            } finally {
                connection = null;
            }
        }
    }

    private void startBionicServer(LinuxSession.Launch launch) throws IOException, InterruptedException {
        bionicServer = LinuxFexBionicSession.startServer(environment, launch, output);
        bionicServer.setSuspended(suspended);
    }

    private LinuxSession.Launch prepare(Game game, List<String> command, Map<String, String> overrides)
            throws IOException, JSONException {
        File home = container.getLinuxHomeDir();
        File gameRoot = new File(home, ".local/share/Steam/steamapps/common/" + game.folder);
        File executable = new File(gameRoot, game.executable).getCanonicalFile();
        File requested = LinuxSession.hostFile(environment.getContext(), container, command.get(0));
        if (requested == null || (!requested.getCanonicalFile().equals(executable)
                && (game.script == null || !requested.getCanonicalFile().equals(new File(gameRoot, game.script).getCanonicalFile()))))
            throw new IOException("The direct FEX bridge accepts only the installed Steam entry point of " + game.folder);
        if (!executable.isFile() || LinuxSession.elfMachine(executable) != 62)
            throw new IOException(game.folder + "'s installed x86-64 executable is unavailable");
        LinuxEmulator.Installed emulator = LinuxEmulator.resolve(environment.getContext(), container);
        LinuxRootfs.Installed rootfs = LinuxRootfs.resolve(environment.getContext(), container);
        if (rootfs == null || (!LinuxBionicSession.enabled(container)
                && (emulator == null || !LinuxEmulator.FEX.equals(emulator.emulator))))
            throw new IOException("Direct FEX requires the selected FEX and root filesystem");
        Map<String, String> env = new LinkedHashMap<>(sessionEnv);
        env.putAll(overrides);
        File rt = LinuxRuntime.rootDir(environment.getContext(), runtime.id);
        File directHome = new File(directory, "home");
        Files.createDirectories(new File(directHome, ".steam").toPath());
        for (String name : new String[]{".local", ".config", ".cache"})
            link(new File(directHome, name), new File(home, name));
        File steam = new File(home, ".local/share/Steam");
        for (String name : new String[]{"steam.pid", "steam.token", "steam.pipe", "registry.vdf"})
            link(new File(directHome, ".steam/" + name), new File(home, ".steam/" + name));
        link(new File(directHome, ".steam/steam"), steam);
        link(new File(directHome, ".steam/root"), steam);
        link(new File(directHome, ".steam/sdk64"), new File(steam, "linux64"));
        File legacy = new File(directory, "legacy");
        Files.createDirectories(legacy.toPath());
        for (String relative : game.legacyLibraries) {
            String name = relative.substring(relative.lastIndexOf('/') + 1);
            File source = new File(steam, "steamapps/common/SteamLinuxRuntime/steam-runtime/" + relative);
            if (!source.isFile()) throw new IOException("Missing scout library: " + name);
            link(new File(legacy, name), source);
        }
        if (LinuxBionicSession.enabled(container) && !LinuxEmulator.FEX.equals(LinuxEmulator.choice(container))) {
            List<String> guestEnv = new ArrayList<>();
            guestEnv.add("BOX64_DYNAREC=1");
            guestEnv.add("BOX64_DYNAREC_STRONGMEM=1");
            env.forEach((key, value) -> guestEnv.add(key + "=" + value));
            String libraries = game.libraryPath(gameRoot) + ":" + legacy + ":" + steam + "/linux64";
            String custom = env.get("LD_LIBRARY_PATH");
            if (custom != null && !custom.isEmpty()) libraries += ":" + custom.replace("/root/", home + "/");
            guestEnv.add("BOX64_LD_LIBRARY_PATH=" + libraries);
            String preloads = env.get("BOX64_LD_PRELOAD");
            guestEnv.add("BOX64_LD_PRELOAD=" + (preloads == null || preloads.isEmpty() ? "" : preloads + ":")
                    + asset("libskysysv_x64.so").getPath());
            List<String> explicit = new ArrayList<>();
            for (String key : new String[]{"SteamAppId", "SteamGameId", "STEAM_COMPAT_APP_ID"})
                explicit.add(key + "=" + game.appId);
            explicit.add("BL_SYSV_DIR=" + new File(environment.getContext().getFilesDir(),
                    "linux-session/shm/wnsysv").getPath());
            List<String> bionicCommand = new ArrayList<>(Arrays.asList(
                    "bannerlator-run", executable.getPath(), "box64", Integer.toString(explicit.size()), "0"));
            bionicCommand.addAll(explicit);
            bionicCommand.addAll(command.subList(1, command.size()));
            LinuxSession.Launch bionic = LinuxBionicSession.build(environment.getContext(),
                    environment.getImageFs(), container, bionicCommand.toArray(new String[0]), guestEnv, directHome);
            if (bionic == null) throw new IOException(game.folder + " requires the installed Android Box64 bionic runtime");
            List<String> argv = new ArrayList<>(Arrays.asList(bionic.argv));
            String cores = container.getCPUList();
            if (cores != null && !cores.isEmpty()) {
                int mask = com.winlator.cmod.core.ProcessHelper.getAffinityMask(cores);
                if (mask == 0) throw new IOException("Bionic CPU affinity selects no cores");
                argv.addAll(0, Arrays.asList("/system/bin/taskset", Integer.toHexString(mask)));
            }
            return new LinuxSession.Launch(argv.toArray(new String[0]), bionic.hostEnv,
                    bionic.workingDir, bionic.executionMode);
        }
        List<String> preloads = new ArrayList<>();
        // A guest FEX run without proot sees the Android host's /tmp and /dev/shm, not the Steam client's: its crash handler and
        // its IPC with the client live there. The shim maps the two to the directories the client has bound (see libskypathmap).
        if (LinuxBionicSession.enabled(container)) {
            preloads.add(asset("libskypathmap_x64.so").getPath());
            env.put("SKY_PATHMAP_TMP", new File(rt, "tmp").getPath());
            if (game.steamIpc) env.put("SKY_PATHMAP_SHM", LinuxSession.sessionDirectory(environment.getContext(), "shm").getPath());
        }
        for (String name : new String[]{"libskyplatform_x64.so", "libskysysv_x64.so",
                "libskysockets_x64.so", "libskyshim_x64.so", "libcefnosb_x64.so"})
            preloads.add(asset(name).getPath());
        JSONObject config = new JSONObject(new String(Files.readAllBytes(new File(home,
                ".skynet/steam-emulator/" + game.appId + ".fex.json").toPath()), StandardCharsets.UTF_8));
        if (LinuxBionicSession.enabled(container)) {
            String libraries = game.libraryPath(gameRoot) + ":" + legacy + ":" + steam + "/linux64";
            String custom = env.get("LD_LIBRARY_PATH");
            if (custom != null && !custom.isEmpty()) libraries += ":" + custom.replace("/root/", home + "/");
            return LinuxFexBionicSession.build(environment.getContext(), environment.getImageFs(), container,
                    emulator, rootfs, home, directHome, executable, command.subList(1, command.size()),
                    env, libraries, preloads, config, game.appId);
        }
        config.getJSONObject("Config").put("Env", "LD_PRELOAD=" + String.join(":", preloads));
        File configFile = new File(directory, "Config.json");
        Files.write(configFile.toPath(), config.toString().getBytes(StandardCharsets.UTF_8));
        env.put("HOME", directHome.getPath());
        env.put("TMPDIR", new File(rt, "tmp").getPath());
        env.put("XDG_DATA_HOME", new File(home, ".local/share").getPath());
        env.put("XDG_CONFIG_HOME", new File(home, ".config").getPath());
        env.put("XDG_CACHE_HOME", new File(home, ".cache").getPath());
        env.put("FEX_APP_CONFIG", configFile.getPath());
        env.put("FEX_ROOTFS", rootfs.dir.getPath());
        env.put("FEX_THUNKCONFIG", new File(home, ".skynet/steam-emulator/" + game.appId + ".thunks64.json").getPath());
        env.put("FEX_SERVER_SOCKET_PATH", new File(directory, "fex.socket").getPath());
        env.put("FEX_NEEDSSECCOMP", "1");
        env.put("SteamAppId", game.appId);
        env.put("SteamGameId", game.appId);
        env.put("STEAM_COMPAT_APP_ID", game.appId);
        env.put("BL_SYSV_DIR", new File(environment.getContext().getFilesDir(), "linux-session/shm/wnsysv").getPath());
        env.put("SKY_DIRECT_TMP", new File(rt, "tmp").getPath());
        env.put("SKY_DIRECT_X11", new File(environment.getImageFs().getRootDir(), "usr/tmp/.X11-unix").getPath());
        env.put("SKY_DIRECT_HOME", home.getPath());
        String libraries = game.libraryPath(gameRoot) + ":" + legacy + ":" + steam + "/linux64";
        String custom = env.get("LD_LIBRARY_PATH");
        if (custom != null && !custom.isEmpty()) libraries += ":" + custom.replace("/root/", home + "/");
        env.put("LD_LIBRARY_PATH", libraries);
        env.remove("LD_PRELOAD");
        List<String> directBindings = new ArrayList<>(bindings);
        directBindings.addAll(Arrays.asList("-b", new File(rt, "tmp") + ":/tmp"));
        List<String> args = new ArrayList<>();
        args.add(LinuxEmulator.binary(emulator).getPath());
        args.add(executable.getPath());
        args.addAll(command.subList(1, command.size()));
        List<String> pairs = new ArrayList<>();
        env.forEach((key, value) -> pairs.add(key + "=" + value));
        LinuxSession.Launch launch = LinuxSession.noProotLaunch(environment.getContext(), rt, home,
                directBindings, pairs, args.toArray(new String[0]));
        if (launch == null) throw new IOException("Direct FEX native loader is unavailable");
        List<String> argv = new ArrayList<>(Arrays.asList(launch.argv));
        for (int index = 0; index < argv.size(); ++index) {
            if (argv.get(index).startsWith("LD_PRELOAD="))
                argv.set(index, "LD_PRELOAD=" + asset("libskyaccept.so") + ":" + argv.get(index).substring(11));
        }
        String cores = container.getCPUList();
        if (cores != null && !cores.isEmpty()) {
            int mask = com.winlator.cmod.core.ProcessHelper.getAffinityMask(cores);
            if (mask == 0) throw new IOException("Direct FEX CPU affinity selects no cores");
            argv.addAll(0, Arrays.asList("/system/bin/taskset", Integer.toHexString(mask)));
        }
        return new LinuxSession.Launch(argv.toArray(new String[0]), launch.hostEnv, executable.getParentFile(), "direct_fex");
    }

    private File asset(String name) throws IOException {
        File file = LinuxSession.stageAsset(environment.getContext(), "linux-shim", name);
        if (file == null) throw new IOException("Cannot stage direct FEX platform library " + name);
        return file;
    }

    private static void link(File path, File target) throws IOException {
        if (Files.isSymbolicLink(path.toPath())) {
            if (Files.readSymbolicLink(path.toPath()).equals(target.toPath())) return;
            Files.delete(path.toPath());
        } else if (Files.exists(path.toPath())) {
            throw new IOException("Direct FEX metadata link collides with a real file: " + path);
        }
        Files.createSymbolicLink(path.toPath(), target.toPath());
    }

    public synchronized void setSuspended(boolean value) {
        suspended = value;
        if (game != null) game.setSuspended(value);
        if (bionicServer != null) bionicServer.setSuspended(value);
    }

    public int getPid() {
        ContainerProcess current = game;
        return current == null ? -1 : current.getPid();
    }

    @Override public synchronized void close() {
        stopped = true;
        if (game != null) game.requestStop();
        if (bionicServer != null) bionicServer.requestStop();
        try {
            server.close();
        } catch (IOException error) {
            Log.w(TAG, "Cannot close direct FEX launch socket", error);
        }
        try {
            if (connection != null) connection.close();
        } catch (IOException error) {
            Log.w(TAG, "Cannot close Steam direct FEX connection", error);
        }
    }
}
