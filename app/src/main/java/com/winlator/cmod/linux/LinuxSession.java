package com.winlator.cmod.linux;

import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.gameprofile.GameProfiles;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the proot command line and environment that run a program inside a Linux runtime:
 * a glibc aarch64 rootfs, entered with the runtime's own proot, whose windows are drawn by the
 * app's X server.
 */
public final class LinuxSession {
    public static final class Launch {
        public final String[] argv;
        public final String[] hostEnv;
        public final File workingDir;
        public final String executionMode;

        Launch(String[] argv, String[] hostEnv, File workingDir, String executionMode) {
            this.argv = argv;
            this.hostEnv = hostEnv;
            this.workingDir = workingDir;
            this.executionMode = executionMode;
        }
    }

    private static final String TAG = "LinuxSession";
    /** Container extra: "sw" (default) draws Vulkan frames with a CPU copy, "native" leaves the driver's own presentation. */
    public static final String EXTRA_VULKAN_PRESENT = "linuxVulkanPresent";
    /** Container extra: "software" (default, llvmpipe) or "zink" (OpenGL on the Turnip Vulkan driver). */
    public static final String EXTRA_GL_DRIVER = "linuxGlDriver";
    /** "0" when a finger should not act where it touches (the touchpad way is used); on when absent. */
    public static final String EXTRA_TOUCHSCREEN = "linuxTouchscreen";
    /** On a Steam game's shortcut: "0" starts the whole Steam client; absent, the client starts without its window. */
    public static final String EXTRA_STEAM_MINIMAL = "steamMinimal";
    /** Container extra: "0" keeps the Steam client's web helper drawing on the CPU; otherwise it draws on the GPU (steam-taskset.sh). */
    public static final String EXTRA_STEAM_GPU = "linuxSteamGpu";
    /** "0" hides the button that opens the on-screen keyboard; shown when absent. */
    public static final String EXTRA_KEYBOARD_BUTTON = "linuxKeyboardButton";
    /** "0" stops the keyboard opening by itself when a text field is tapped; on when absent. */
    public static final String EXTRA_AUTO_KEYBOARD = "linuxAutoKeyboard";
    private static final String HOST_DIR = "opt/android-host";
    private static final String PRELOAD = "/usr/local/lib/libblsession.so";
    private static final String FAKE_PROC_DIR = "etc/bannerlator/proc";
    private static final String EMPTY_DIR = "etc/bannerlator/empty";
    private static final String[] FAKE_PROC_FILES = {
            "stat", "version", "loadavg", "uptime", "vmstat", "bus/pci/devices"
    };

    private LinuxSession() {}

    /**
     * The X display number the guest uses. libxcb tries the abstract socket "@/tmp/.X11-unix/X<n>" before the file, and
     * abstract sockets are shared by every app on the device: another app's X server on display 0 (Bannerlator, for
     * one) answers first and refuses us, and libxcb does not fall back. A number of our own avoids that clash.
     */
    public static int displayNumber() {
        return 100 + Process.myUid() % 800;
    }

    /** The ELF header's machine type of a file: 62 is x86-64, 3 is 32-bit x86, 183 is AArch64; -1 if not an ELF file. */
    public static int elfMachine(File file) {
        if (file == null || !file.isFile()) return -1;
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] header = new byte[20];
            if (in.read(header) < 20) return -1;
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') return -1;
            return (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        }
        catch (java.io.IOException e) {
            return -1;
        }
    }

    /** Where a program, given as the guest sees its path, lives on the host (null if it cannot be told). */
    public static File hostFile(Context context, Container container, String guestPath) {
        if (guestPath.startsWith("/root/")) return new File(container.getLinuxHomeDir(), guestPath.substring(6));
        if (guestPath.startsWith("/storage/") || guestPath.startsWith("/data/")) return new File(guestPath);
        LinuxRuntime.Installed runtime = LinuxRuntime.resolve(context, container);
        return runtime == null ? null : new File(LinuxRuntime.rootDir(context, runtime.id), guestPath.substring(1));
    }

    /** A command that only prints why the program cannot start, so the failure dialog explains it. */
    private static String[] refusal(String message) {
        return new String[]{"/bin/sh", "-c", "echo \"$1\" >&2; exit 127", "bannerlator-run", message};
    }

    /**
     * A program started from a shortcut. Scripts run through bash and ARM64 programs run natively; an x86-64 program
     * is started through the container's emulator, or refused with a message when there is none to use.
     */
    public static String[] programCommand(Context context, Container container, String path) {
        return programCommand(context, container, path, null);
    }

    /**
     * A shortcut's program, with its launch options (see {@link LinuxLaunchOptions}) laid over what the game's registry profile
     * says (see {@link GameProfiles}). The Steam client always opens in Big Picture mode. A Steam game whose profile says
     * "without Steam" runs its own executable straight away; otherwise it is started through the client.
     */
    public static String[] programCommand(Context context, Container container, com.winlator.cmod.container.Shortcut shortcut) {
        String path = shortcut.path;
        if (isSteam(path)) {
            LinuxLaunchOptions options = LinuxLaunchOptions.parse(shortcut.getExtra(LinuxLaunchOptions.EXTRA),
                    shortcut.getExtra(LinuxLaunchOptions.EXTRA_ENV_VARS));
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/env");
            for (Map.Entry<String, String> e : options.env.entrySet()) command.add(e.getKey() + "=" + e.getValue());
            command.addAll(options.wrapper);
            command.addAll(java.util.Arrays.asList(steamCommand("", false, true)));
            command.addAll(options.args);
            return command.toArray(new String[0]);
        }
        GameProfiles.Effective profile = GameProfiles.resolve(context, container, shortcut);
        LinuxLaunchOptions own = LinuxLaunchOptions.parse(shortcut.getExtra(LinuxLaunchOptions.EXTRA),
                shortcut.getExtra(LinuxLaunchOptions.EXTRA_ENV_VARS));
        LinuxLaunchOptions options = profile == null ? own : LinuxLaunchOptions.merged(profile.env, profile.programArgs(), own);
        if (isSteamGame(path)) {
            String appId = steamAppId(path);
            if (!appId.matches("\\d+")) return refusal("Not a Steam game: " + path);
            if (profile != null && profile.runsWithoutSteam()) {
                File program = hostFile(context, container, profile.executableGuest);
                if (program == null || !program.isFile()) {
                    return refusal("The game's program " + profile.executableGuest + " is not there. Turn off \"Launch without Steam\" "
                            + "in the shortcut's settings to start the game through Steam.");
                }
                return directCommand(context, container, profile.executableGuest, options);
            }
            // A Steam game starts the client without its window unless the shortcut asks for the whole client.
            writeSteamLaunchOptions(container, appId, options);
            return steamCommand(appId, !"0".equals(shortcut.getExtra(EXTRA_STEAM_MINIMAL)), false);
        }
        return directCommand(context, container, path, options);
    }

    public static String[] programCommand(Context context, Container container, String path, String launchOptions) {
        if (isSteam(path)) return steamCommand("", false, true);
        if (isSteamGame(path)) {
            String appId = steamAppId(path);
            if (!appId.matches("\\d+")) return refusal("Not a Steam game: " + path);
            return steamCommand(appId, true, false);
        }
        return directCommand(context, container, path, LinuxLaunchOptions.parse(launchOptions));
    }

    /** A program of the guest, run with these launch options through the container's emulator when it is an x86-64 one. */
    private static String[] directCommand(Context context, Container container, String path, LinuxLaunchOptions options) {
        String emulatorPath = "";
        boolean runsOnFex = false;
        int machine = elfMachine(hostFile(context, container, path));
        if (machine == 62) {
            String choice = LinuxEmulator.choice(container);
            LinuxEmulator.Installed emulator = LinuxEmulator.resolve(context, container);
            if (choice.equals(LinuxEmulator.NONE)) {
                return refusal("This is an x86-64 program. Choose an x86 emulator (Box64 or FEX) in the container's settings.");
            }
            if (emulator == null) {
                return refusal("The selected x86 emulator is not installed. Install it in Components, Linux Emulator.");
            }
            if (emulator.emulator.equals(LinuxEmulator.FEX) && LinuxRootfs.resolve(context, container) == null) {
                return refusal(LinuxFex.NEEDS_ROOTFS);
            }
            emulatorPath = LinuxEmulator.binary(emulator).getPath();
            runsOnFex = emulator.emulator.equals(LinuxEmulator.FEX);
        }
        else if (machine == 3) {
            // Box64 cannot run a 32-bit program; FEX can, with a root filesystem that has the 32-bit libraries.
            LinuxEmulator.Installed fex = LinuxEmulator.resolve(context, container);
            if (fex == null || !fex.emulator.equals(LinuxEmulator.FEX)) {
                java.util.List<LinuxEmulator.Installed> builds = LinuxEmulator.list(context, LinuxEmulator.FEX);
                fex = builds.isEmpty() ? null : builds.get(0);
            }
            if (fex == null) {
                return refusal("This is a 32-bit x86 program. Install FEX and an x86-64 root filesystem (Components, Linux Emulator) to run it.");
            }
            if (LinuxRootfs.resolve(context, container) == null && LinuxRootfs.list(context).isEmpty()) return refusal(LinuxFex.NEEDS_ROOTFS);
            emulatorPath = LinuxEmulator.binary(fex).getPath();
            runsOnFex = true;
        }
        File program = hostFile(context, container, path);
        if (runsOnFex && program != null
                && program.getPath().startsWith(Environment.getExternalStorageDirectory().getPath() + "/")) {
            // FEX maps a program and its libraries as executable, which shared storage (mounted noexec) refuses.
            return refusal((machine == 3 ? "This 32-bit program is run by FEX (Box64 cannot run it), and " : "") + "FEX cannot run a program from shared storage. Copy the program into the container (add it with \"Program only\" "
                    + "or \"Whole game\"), or choose Box64 for this shortcut.");
        }
        // $1 the program, $2 the emulator (empty for a native program), $3 and $4 how many variables and wrapper words
        // follow, then those, then the program's arguments. The variables go through env so they reach the emulator and,
        // through it, the program; the wrapper (taskset, ...) starts the emulator.
        List<String> command = new ArrayList<>(java.util.Arrays.asList(
                "/bin/bash", "-c",
                "chmod +x \"$1\" 2>/dev/null; cd \"$(dirname \"$1\")\" || exit 1; "
                        + "prog=\"$1\"; emu=\"$2\"; nenv=\"$3\"; nwrap=\"$4\"; shift 4; "
                        + "envs=(\"${@:1:$nenv}\"); shift \"$nenv\"; wrap=(\"${@:1:$nwrap}\"); shift \"$nwrap\"; "
                        // A 32-bit program on FEX with the OpenGL thunk: its thunk selection and Mesa's version and vendor (as the launcher
                        // does for a game Steam starts).
                        + "if [ -n \"$emu\" ] && [ -n \"$SKY_FEX_THUNKCONFIG32\" ] && [ -f \"$SKY_FEX_GL32_SCRIPT\" ] && "
                        + "[ \"$(od -An -tx1 -j4 -N1 \"$prog\" 2>/dev/null | tr -d ' \\n')\" = 01 ]; then "
                        + "export FEX_THUNKCONFIG=\"$SKY_FEX_THUNKCONFIG32\"; . \"$SKY_FEX_GL32_SCRIPT\"; sky_gl32_mesa_env; fi; "
                        + "case \"$prog\" in *.sh) exec env \"${envs[@]}\" \"${wrap[@]}\" bash \"$prog\" \"$@\";; "
                        + "*) if [ -n \"$emu\" ]; then exec env \"${envs[@]}\" \"${wrap[@]}\" \"$emu\" \"$prog\" \"$@\"; "
                        + "else exec env \"${envs[@]}\" \"${wrap[@]}\" \"$prog\" \"$@\"; fi;; esac",
                "bannerlator-run", path, emulatorPath,
                String.valueOf(options.env.size()), String.valueOf(options.wrapper.size())));
        for (Map.Entry<String, String> entry : options.env.entrySet()) command.add(entry.getKey() + "=" + entry.getValue());
        command.addAll(options.wrapper);
        command.addAll(options.args);
        return command.toArray(new String[0]);
    }

    /** The program of a container's Steam shortcut: not a file, the runtime's Steam client. */
    public static final String STEAM_PROGRAM = "steam://client";

    public static boolean isSteam(String path) {
        return STEAM_PROGRAM.equals(path);
    }

    /** The program of a shortcut for a game installed in the container's Steam: started through the client. */
    public static final String STEAM_GAME_PREFIX = "steam://rungameid/";
    /** The word after the script of a Steam session's command: what tells {@link #build} that the session runs the Steam client. */
    private static final String STEAM_MARKER = "bannerlator-steam";

    public static boolean isSteamGame(String path) {
        return path != null && path.startsWith(STEAM_GAME_PREFIX) && path.length() > STEAM_GAME_PREFIX.length();
    }

    public static String steamAppId(String path) {
        return path.substring(STEAM_GAME_PREFIX.length());
    }

    /**
     * Runs the runtime's native arm64 Steam client on the container's own display (no gamescope). The first run
     * downloads the client through the app-staged installer; its output goes to Winlator/logs/linux-steam.log
     * unless SKY_STEAM_LOG_DIR selects another destination. Files a killed session left behind are cleared first.
     */
    private static String[] steamCommand(String appId, boolean minimal, boolean bigPicture) {
        return new String[]{"/bin/bash", "-c",
                "export XDG_DATA_HOME=\"${XDG_DATA_HOME:-$HOME/.local/share}\"; steam_root=\"$XDG_DATA_HOME/Steam\"; "
                        + "log_dir=\"${SKY_STEAM_LOG_DIR:-/storage/emulated/0/Winlator/logs}\"; "
                        + "mkdir -p \"$log_dir\" || exit 1; exec >\"$log_dir/linux-steam.log\" 2>&1; "
                        + "echo \"== steam start $(date) uid=$(id -u) home=$HOME\"; "
                        // The runtime ships gawk (what awk links to) without the execute bit, and the installer needs awk.
                        + "chmod 755 /usr/bin/gawk; "
                        + "sky_stage() { printf '== SKY_STEAM stage=%s epoch_s=%s\\n' \"$1\" \"$(date -u +%s)\"; }; "
                        + "sky_stage installer_start; "
                        + "if [ -z \"$SKY_STEAM_INSTALLER\" ] || [ ! -f \"$SKY_STEAM_INSTALLER\" ]; then "
                        + "echo 'SkyNetEmu Steam installer is missing' >&2; exit 1; fi; "
                        + "/bin/bash \"$SKY_STEAM_INSTALLER\" || exit 1; sky_stage installer_complete; "
                        // ~/.steam/sdk64 is where an x86-64 game looks for steamclient.so, and the installer points it at the arm64
                        // client (which Box64 cannot load): the client's x86-64 copy is in linux64. sdkarm64 keeps the arm64 one.
                        + "if [ -f \"$steam_root/linux64/steamclient.so\" ]; then mkdir -p \"$HOME/.steam\"; "
                        + "ln -sfn \"$steam_root/linux64\" \"$HOME/.steam/sdk64\"; fi; "
                        + "/bin/bash \"$SKY_STEAM_COMPAT_INSTALLER\" || exit 1; "
                        // The client's own logs are in the container's private home: keep a copy on shared storage.
                        + "( while sleep 10; do "
                        + "if ! /bin/bash \"$SKY_STEAM_LOG_EXPORT\" \"$steam_root/logs\" \"$log_dir/steam-logs\"; then "
                        + "echo 'SkyNetEmu: Steam diagnostic log export failed' >&2; fi; done ) & "
                        + "rm -f \"$steam_root/.crash\" \"$steam_root/steam.pid\" \"$steam_root/config/htmlcache/\"Singleton*; "
                        // The client starts its web helper through taskset: the stand-in (first in PATH) has the helper draw on the GPU.
                        + "if [ -n \"$SKY_STEAM_TASKSET\" ] && [ -f \"$SKY_STEAM_TASKSET\" ]; then mkdir -p \"$HOME/.skynet/bin\"; "
                        + "if ! cmp -s \"$SKY_STEAM_TASKSET\" \"$HOME/.skynet/bin/taskset\"; then "
                        + "cp -f \"$SKY_STEAM_TASKSET\" \"$HOME/.skynet/bin/taskset\" || exit 1; fi; "
                        + "chmod 755 \"$HOME/.skynet/bin/taskset\"; "
                        + "export PATH=\"$HOME/.skynet/bin:$PATH\"; fi; "
                        // The client works around a bug of old libXrandr by looking its functions up behind its own; with RandR 1.3 offered
                        // it asks for XRRGetOutputInfo, finds nothing (the library is not loaded yet) and crashes. Loading it first fixes that.
                        // The client's own OpenGL stays in software: Zink would break it (its web helper draws through Vulkan, see
                        // steam-taskset.sh). The games it starts get Zink back from the launcher (SKY_GAME_GL).
                        + "if [ \"$GALLIUM_DRIVER\" = zink ]; then export SKY_GAME_GL=zink LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe; "
                        + "unset MESA_LOADER_DRIVER_OVERRIDE; fi; "
                        + "[ -f /usr/lib/libXrandr.so.2 ] && export LD_PRELOAD=\"/usr/lib/libXrandr.so.2${LD_PRELOAD:+:$LD_PRELOAD}\"; "
                        + "export STEAM_RUNTIME=1 SDL_VIDEO_X11_DGAMOUSE=0 STEAM_LAUNCH_WRAPPER_AUDIO_NAMESPACE=0 "
                        + "LD_LIBRARY_PATH=\"$steam_root/steamrtarm64:$steam_root/steamrtarm64/panorama\"; "
                        // The client exits with 42 after updating itself and wants a fresh start; an update that cannot
                        // install asks again every time, so the restarts are capped.
                        // $1: the game to start once the client is up (empty: just the client).
                        + "game=\"$1\"; minimal=\"$2\"; extra=\"\"; "
                        // Big Picture (the Steam Deck interface) fills the screen; the desktop interface is a window of about
                        // 1280x800 that does not fit a smaller screen.
                        + "[ \"$3\" = 1 ] && [ -z \"$game\" ] && extra=\"-gamepadui\"; "
                        // To start a game the client needs no window and no web helper (the Chromium process that draws its
                        // interface and takes most of its memory), but it cannot sign in without them: only when a login is
                        // remembered does the game start that way.
                        + "if [ -n \"$game\" ] && [ \"$minimal\" = 1 ] && grep -qs '\"RememberPassword\"[[:space:]]*\"1\"' "
                        + "\"$steam_root/config/loginusers.vdf\"; then extra=\"$extra -silent -no-browser\"; fi; "
                        + "echo \"== steam launch: game=$game minimal=$minimal flags=$extra\"; "
                        + "for try in 1 2 3 4; do sky_stage client_attempt_$try; \"$steam_root/steamrtarm64/steam\" -clientbeta publicbeta -no-cef-sandbox "
                        + "-cef-disable-gpu -cef-disable-gpu-compositing -cef-ozone-platform=x11 $extra ${game:+-applaunch $game} \"${@:4}\"; code=$?; "
                        + "sky_stage client_exit_$code; [ \"$code\" = 42 ] || break; done; exit $code",
                STEAM_MARKER, appId, minimal ? "1" : "0", bigPicture ? "1" : "0"};
    }

    /**
     * A game's data folders left on shared storage. Shared storage cannot run programs, but a game only reads its data,
     * so only its program folder is copied and the rest is bound in beside it (a bind, unlike a symbolic link, is not
     * seen by the game as one). Each line of programs/<game>/.binds is "host path, tab, guest path".
     */
    private static void bindDataFolders(List<String> argv, File home) {
        File[] games = new File(home, "programs").listFiles(File::isDirectory);
        if (games == null) return;
        for (File game : games) {
            File list = new File(game, ".binds");
            if (!list.isFile()) continue;
            for (String line : FileUtils.readLines(list)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                File host = new File(line.substring(0, tab));
                if (host.exists()) bind(argv, host.getPath(), line.substring(tab + 1));
                else LinuxFail.w(TAG, "The game's data is missing: " + host);
            }
        }
    }

    public static Launch build(Context context, ImageFs imageFs, Container container,
                               LinuxRuntime.Installed runtime, String[] guestCommand) {
        if (java.util.Arrays.asList(guestCommand).contains(STEAM_MARKER)) LinuxSteamGames.registerSharedLibrary(container);
        File rt = LinuxRuntime.rootDir(context, runtime.id);
        File hostDir = new File(rt, HOST_DIR);
        File proot = new File(hostDir, "proot");
        File loader = new File(hostDir, "loader");
        FileUtils.chmod(proot, 0755);
        FileUtils.chmod(loader, 0755);

        int uid = Process.myUid();
        File cacheDir = context.getCacheDir();
        File shmDir = new File(cacheDir, "shm");
        File home = container.getLinuxHomeDir();
        File imageFsRoot = imageFs.getRootDir();

        FileUtils.delete(shmDir);
        shmDir.mkdirs();
        home.mkdirs();
        new File(rt, "tmp/.X11-unix").mkdirs();
        // The X server listens on X0 in this directory; the guest reaches it as X<displayNumber>.
        File xLink = new File(imageFsRoot, "usr/tmp/.X11-unix/X" + displayNumber());
        xLink.delete();
        try {
            android.system.Os.symlink("X0", xLink.getPath());
        }
        catch (android.system.ErrnoException e) {
            LinuxFail.w(TAG, "Cannot link the X display", e);
        }
        writeAccounts(rt, uid);
        writePreload(context, rt);

        List<String> argv = new ArrayList<>();
        argv.add(proot.getPath());
        argv.add("--kill-on-exit");
        argv.add("-i");
        argv.add(uid + ":" + uid);
        argv.add("-r");
        argv.add(rt.getPath());
        argv.add("-w");
        argv.add("/root");
        bind(argv, "/dev");
        bind(argv, "/proc");
        bind(argv, "/sys");
        // Android denies desktop battery probes with EACCES; expose no guest power supplies instead.
        File powerSupplies = new File(cacheDir, "linux-power-supply");
        if (!powerSupplies.isDirectory() && !powerSupplies.mkdirs())
            throw new IllegalStateException("Cannot create guest power supply directory");
        bind(argv, powerSupplies.getPath(), "/sys/class/power_supply");
        bind(argv, "/dev/urandom", "/dev/random");
        bind(argv, "/proc/self/fd", "/dev/fd");
        for (int fd = 0; fd < 3; fd++) {
            bind(argv, "/proc/self/fd/" + fd, "/dev/" + new String[]{"stdin", "stdout", "stderr"}[fd]);
        }
        bind(argv, shmDir.getPath(), "/dev/shm");
        // Android's own /dev/input is unreadable by apps, and SDL finds a pad by listing that directory: the app's
        // gamepad files stand in for it. libskyshim turns them from plain files into evdev devices (see guestEnv).
        File inputDir = inputDir(context);
        inputDir.mkdirs();
        try {
            // SDL lists the pads once, as it starts: a node that appears later is only found if the program
            // watches for one, so the first slot is always there, for the on-screen pad if for nothing else.
            new File(inputDir, "event0").createNewFile();
        }
        catch (java.io.IOException e) {
            LinuxFail.w(TAG, "Cannot create the gamepad file", e);
        }
        bind(argv, inputDir.getPath(), "/dev/input");
        bindIfExists(argv, new File(rt, EMPTY_DIR), "/sys/fs/selinux");
        // Chromium (the Steam client's web helper, a game's CEF) reads the inotify limits and logs an error that it does not
        // survive for long when they are missing; Android hides /proc/sys/fs from an app.
        String[][] inotify = {
                {"sys/fs/inotify/max_user_watches", "65536\n"},
                {"sys/fs/inotify/max_user_instances", "1024\n"},
                {"sys/fs/inotify/max_queued_events", "16384\n"},
                // libpci (Chromium's GPU process, a Feral game) exits the process when it cannot open this file, which Android
                // allows to nobody; with no device in it the scan finds no GPU on the bus and carries on.
                {"bus/pci/devices", ""}};
        java.util.List<String> fakeNames = new ArrayList<>(java.util.Arrays.asList(FAKE_PROC_FILES));
        for (String[] entry : inotify) {
            File fake = new File(rt, FAKE_PROC_DIR + "/" + entry[0]);
            if (!fake.exists()) {
                fake.getParentFile().mkdirs();
                FileUtils.writeString(fake, entry[1]);
            }
            if (!fakeNames.contains(entry[0])) fakeNames.add(entry[0]);
        }
        for (String name : fakeNames) {
            File fake = new File(rt, FAKE_PROC_DIR + "/" + name);
            if (fake.exists() && !new File("/proc/" + name).canRead()) bind(argv, fake.getPath(), "/proc/" + name);
        }

        // The app's own directories at their own paths: the audio socket lives under the image fs.
        bind(argv, context.getFilesDir().getPath());
        bind(argv, cacheDir.getPath());
        bind(argv, imageFsRoot.getPath());
        bind(argv, "/storage/emulated/0");
        // FEX looks a path up in the x86-64 root filesystem first, and that has /proc, /sys and /dev as empty folders: a path
        // given whole (/proc/self/task) falls through to the phone's, but one opened as a directory and then looked into
        // (Chromium opens "/proc" and stats "self/task/" in it; its sandbox check dies on that) finds nothing. The real
        // ones are put over the root filesystem's, as a chroot would have them mounted.
        LinuxEmulator.Installed fexEmulator = LinuxEmulator.resolve(context, container);
        if ((fexEmulator != null && fexEmulator.emulator.equals(LinuxEmulator.FEX))
                || java.util.Arrays.asList(guestCommand).contains(STEAM_MARKER)) {
            LinuxRootfs.Installed x86Root = LinuxRootfs.resolve(context, container);
            if (x86Root != null) {
                String root = x86Root.dir.getPath();
                bind(argv, "/proc", root + "/proc");
                bind(argv, "/sys", root + "/sys");
                bind(argv, powerSupplies.getPath(), root + "/sys/class/power_supply");
                bind(argv, "/dev", root + "/dev");
                bind(argv, shmDir.getPath(), root + "/dev/shm");
                bind(argv, inputDir.getPath(), root + "/dev/input");
            }
        }
        bind(argv, home.getPath(), "/root");
        bindDataFolders(argv, home);
        bind(argv, new File(imageFsRoot, "usr/tmp/.X11-unix").getPath(), "/tmp/.X11-unix");

        argv.add("/usr/bin/env");
        argv.add("-i");
        if (java.util.Arrays.asList(guestCommand).contains(STEAM_MARKER))
            prepareSteamGames(context, rt, imageFsRoot, container);
        String programGuestPath = programGuestPath(guestCommand);
        List<String> guestEnv = guestEnv(context, rt, imageFsRoot, container,
                programGuestPath == null ? null : hostFile(context, container, programGuestPath), programGuestPath,
                !java.util.Arrays.asList(guestCommand).contains(STEAM_MARKER));
        argv.addAll(guestEnv);
        writeSteamEmulatorEnv(home, guestCommand, guestEnv);
        for (String part : guestCommand) argv.add(part);

        if (LinuxBionicSession.enabled(container) && !java.util.Arrays.asList(guestCommand).contains(STEAM_MARKER)) {
            Launch bionic = LinuxBionicSession.build(context, imageFs, container, guestCommand, guestEnv, home);
            if (bionic != null) return bionic;
        }
        if (noProot(container) && !runsOnFex(context, container, programGuestPath) && !java.util.Arrays.asList(guestCommand).contains(STEAM_MARKER)) {
            Launch direct = noProotLaunch(context, rt, home, argv, guestEnv, guestCommand);
            if (direct != null) return direct;
        }

        EnvVars host = new EnvVars();
        host.put("PROOT_LOADER", loader.getPath());
        host.put("PROOT_TMP_DIR", cacheDir.getPath());
        // Android's linker does not search an executable's own directory, and proot needs libtalloc from there.
        host.put("LD_LIBRARY_PATH", hostDir.getPath());

        return new Launch(argv.toArray(new String[0]), host.toStringArray(), rt, "proot");
    }

    /** Legacy container extra: only "1" disables proot; absent values use proot. */
    public static final String EXTRA_NO_PROOT = "linuxNoProot";

    /**
     * Whether the program is run by FEX: the container's emulator is FEX, or the program is a 32-bit x86 one (Box64 cannot run it, FEX does). FEX makes its
     * system calls itself, without the libc functions libskypath hooks, so the paths the guest names (/tmp/.X11-unix, the home folder) are not translated: the
     * game cannot find the X server. Such a session stays under proot until libskypath covers FEX.
     */
    /**
     * The Steam client (what starts with STEAM_MARKER) stays under proot too: its links (~/.steam/steam and the others) point at guest paths
     * (/root/.local/share/Steam), which proot translates and the kernel cannot follow, and its start script makes /storage/emulated/0 with
     * mkdir -p, which the phone answers "permission denied" where proot answers "exists".
     */
    private static boolean runsOnFex(Context context, Container container, String programGuestPath) {
        if (LinuxEmulator.FEX.equals(LinuxEmulator.choice(container))) return true;
        return programGuestPath != null && elfMachine(hostFile(context, container, programGuestPath)) == 3;
    }

    /** Proot is enabled by default. Existing explicit direct-execution choices remain valid. */
    public static boolean prootEnabled(String noProotValue) {
        return !"1".equals(noProotValue);
    }

    private static boolean noProot(Container container) {
        if (prootEnabled(container.getExtra(EXTRA_NO_PROOT))) return false;
        return !new File(Environment.getExternalStorageDirectory(), "Winlator/linux-proot").exists();
    }

    /**
     * The session without proot. proot stops the guest at every system call that names a path and translates it from outside, which
     * costs about 100 us for a stat or an open (the call itself: about 1 us). Here the first program is started through the runtime's
     * own loader and libskypath (tools/linux-shim/skypath.c) does the same translation inside the processes, in their libc, from the
     * table of what proot's -b options bound over the runtime. Programs started later (exec, spawn) go through the loader the same way.
     *
     * @param proot the arguments the session would have given proot: its -b options are the table
     * @return null when libskypath cannot be had (the session then runs under proot)
     */
    private static Launch noProotLaunch(Context context, File rt, File home, List<String> proot, List<String> guestEnv, String[] guestCommand) {
        File skypath = stageAsset(context, "libskypath.so");
        if (skypath == null || guestCommand.length == 0) return null;
        if (!new File(rt, "usr/lib/ld-linux-aarch64.so.1").exists()) return null;
        String libpath = new File(rt, "usr/lib").getPath();
        String sysfree = sigsysFreeLibc(context, rt);
        if (sysfree == null) return null;
        File ldso = new File(sysfree, "ld-linux-aarch64.so.1");
        libpath = sysfree + ":" + libpath + ":" + new File(rt, "usr/lib/pulseaudio").getPath(); // libpulse finds libpulsecommon through an absolute RUNPATH
        // The loader's --library-path replaces $LD_LIBRARY_PATH: the guest's own folders come first, then the runtime's.
        for (String entry : guestEnv) {
            if (entry.startsWith("LD_LIBRARY_PATH=") && entry.length() > 16) libpath = entry.substring(16) + ":" + libpath;
        }

        List<String[]> binds = new ArrayList<>();
        StringBuilder table = new StringBuilder();
        for (int i = 0; i + 1 < proot.size(); i++) {
            if (!"-b".equals(proot.get(i))) continue;
            String spec = proot.get(i + 1);
            int colon = spec.indexOf(':');
            String hostPath = colon < 0 ? spec : spec.substring(0, colon);
            String guestPath = colon < 0 ? spec : spec.substring(colon + 1);
            binds.add(new String[]{guestPath, hostPath});
            if (table.length() > 0) table.append('|');
            table.append(guestPath).append('=').append(hostPath);
        }

        String program = hostOf(guestCommand[0], binds, rt);
        EnvVars host = new EnvVars();
        for (String entry : guestEnv) {
            int eq = entry.indexOf('=');
            if (eq > 0) host.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        host.put("SKY_ROOT", rt.getPath());
        host.put("SKY_PATHMAP", table.toString());
        host.put("SKY_LIBPATH", libpath);
        host.put("SKY_LOADER", ldso.getPath());
        host.put("SKY_SELF", program);
        // What /etc/ld.so.preload does under proot: that file is looked for on the phone's own file system here.
        StringBuilder preload = new StringBuilder(skypath.getPath());
        File session = new File(rt, PRELOAD.substring(1));
        if (session.exists()) preload.append(':').append(session.getPath());
        File shim = stageShim(context);
        if (shim != null) preload.append(':').append(shim.getPath());
        host.put("LD_PRELOAD", preload.toString());

        // The app's own environment (Android's) is merged into whatever the process is given: env -i clears it, as proot's did.
        List<String> argv = new ArrayList<>();
        argv.add("/system/bin/env");
        argv.add("-i");
        argv.addAll(java.util.Arrays.asList(host.toStringArray()));
        argv.add(ldso.getPath());
        argv.add("--library-path");
        argv.add(libpath);
        argv.add("--argv0");
        argv.add(guestCommand[0]);
        argv.add(program);
        for (int i = 1; i < guestCommand.length; i++) argv.add(guestCommand[i]);
        Log.i(TAG, "Session without proot: " + program);
        return new Launch(argv.toArray(new String[0]), new String[0], home, "direct");
    }

    /**
     * Android's seccomp filter for apps kills a process that calls a system call it does not allow with SIGSYS; proot absorbs that signal. Without proot,
     * libskypath answers such calls itself (a SIGSYS handler), but the loader and libc make two of them before any library is loaded (set_robust_list and
     * rseq, for the main thread): a copy of both has those calls replaced by "return 0" and "return ENOSYS" (robust futex lists only matter when a thread dies
     * holding a lock; glibc runs without rseq when the kernel has none). The copies are made once per runtime.
     *
     * @return the folder holding the patched libc.so.6, or null when it cannot be made
     */
    private static String sigsysFreeLibc(Context context, File rt) {
        try {
            File dir = new File(context.getFilesDir(), "linux-shim/libc-v2-" + rt.getName());
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            // The loader makes the call too, for the main thread, before libc is even loaded.
            for (String name : new String[]{"libc.so.6", "ld-linux-aarch64.so.1"}) {
                File src = new File(rt, "usr/lib/" + name).getCanonicalFile();
                File out = new File(dir, name);
                if (out.isFile() && out.length() == src.length() && out.lastModified() >= src.lastModified()) continue;
                byte[] d = java.nio.file.Files.readAllBytes(src.toPath());
                java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int patched = 0;
                for (int off = 0; off + 4 * 12 <= d.length; off += 4) {
                    int result;
                    if (b.getInt(off) == 0xD2800C68) result = 0xD2800000; // mov x8, #99 (set_robust_list): mov x0, #0
                    else if (b.getInt(off) == 0xD28024A8) result = 0x928004A0; // mov x8, #293 (rseq): mov x0, #-ENOSYS, which glibc takes as "no rseq here"
                    else continue;
                    for (int k = 1; k <= 10; k++) {
                        if (b.getInt(off + 4 * k) == 0xD4000001) { // svc #0
                            b.putInt(off + 4 * k, result);
                            patched++;
                            break;
                        }
                    }
                }
                if (patched == 0) return null;
                File tmp = new File(dir, name + ".tmp");
                java.nio.file.Files.write(tmp.toPath(), d);
                tmp.setExecutable(true, false);
                if (!tmp.renameTo(out)) return null;
            }
            return dir.getPath();
        }
        catch (java.io.IOException e) {
            LinuxFail.w(TAG, "Cannot make a libc without set_robust_list: " + e);
            return null;
        }
    }

    /** Where a guest path is on the phone: a bound place, or under the runtime. */
    private static String hostOf(String guest, List<String[]> binds, File rt) {
        String best = null;
        int bestLength = -1;
        for (String[] bind : binds) {
            String g = bind[0];
            if ((guest.equals(g) || guest.startsWith(g.endsWith("/") ? g : g + "/")) && g.length() > bestLength) {
                best = bind[1] + guest.substring(g.length());
                bestLength = g.length();
            }
        }
        return best != null ? best : rt.getPath() + guest;
    }

    private static String shellWord(String word) {
        return "'" + word.replace("'", "'\\''") + "'";
    }

    /**
     * The launch options of a shortcut of a Steam game (the ones in the shortcut's settings, written like Steam's), in
     * ~/.skynet/steam-emulator/&lt;appId&gt;.launch for the registered compatibility tool: the variables are
     * exported, the wrapper goes before the game's command and the arguments after it (Steam's own launch options for the game, set in
     * Steam, are already in that command).
     */
    private static void writeSteamLaunchOptions(Container container, String appId, LinuxLaunchOptions options) {
        StringBuilder text = new StringBuilder("unset sky_wrap sky_args\n");
        for (Map.Entry<String, String> entry : options.env.entrySet()) {
            text.append("export ").append(entry.getKey()).append('=').append(shellWord(entry.getValue())).append('\n');
        }
        text.append("sky_wrap=(");
        for (String word : options.wrapper) text.append(shellWord(word)).append(' ');
        text.append(")\nsky_args=(");
        for (String word : options.args) text.append(shellWord(word)).append(' ');
        text.append(")\n");
        File dir = new File(container.getLinuxHomeDir(), ".skynet/steam-emulator");
        dir.mkdirs();
        if (!FileUtils.writeString(new File(dir, appId + ".launch"), text.toString()))
            throw new IllegalStateException("Cannot prepare Steam launch options for " + appId);
    }

    /** The variables that make up the x86 emulator of a session: the launcher of a Steam game applies the ones of its own game. */
    /** The script with the environment of a 32-bit game on the OpenGL thunk (skynet-gl32.sh), for the launcher and the shortcut's wrapper. */
    private static void putGl32Script(Context context, Map<String, String> env) {
        File script = stageAsset(context, "skynet-gl32.sh");
        if (script != null) env.put("SKY_FEX_GL32_SCRIPT", script.getPath());
    }

    private static boolean isEmulatorVariable(String name) {
        return name.startsWith("BOX64_") || name.startsWith("FEX_") || name.startsWith("SKY_X86_") || name.startsWith("SKY_FEX_")
                || name.startsWith("SKY_GPU_MEMORY_")
                || name.equals("SKY_GAME_SETUP_ERROR")
                || name.equals("VK_DRIVER_FILES") || name.equals("VK_ICD_FILENAMES")
                || name.equals("GALLIUM_DRIVER") || name.equals("MESA_LOADER_DRIVER_OVERRIDE")
                || name.equals("LIBGL_ALWAYS_SOFTWARE") || name.equals("LD_LIBRARY_PATH")
                || name.equals("SDL_TOUCH_MOUSE_EVENTS") || name.equals("SDL_MOUSE_TOUCH_EVENTS")
                || name.equals("SKY_LAUNCHER") || name.equals("SKY_MIMALLOC_STUB") || name.equals("TU_DEBUG");
    }

    /**
     * A Steam game is started by the Steam client, which may already be running (another session of the container started it) with the
     * environment of that session. So a shortcut of a Steam game leaves the emulator's variables for its game in
     * ~/.skynet/steam-emulator/&lt;appId&gt;.env, and the selected SkyNetEmu compatibility tool applies them. This is
     * what lets each Steam game have its own emulator (see {@link LinuxEmulatorSetup}).
     */
    private static void writeSteamEmulatorEnv(File home, String[] guestCommand, List<String> guestEnv) {
        String appId = null;
        for (int i = 0; i + 1 < guestCommand.length; i++) {
            if (STEAM_MARKER.equals(guestCommand[i])) appId = guestCommand[i + 1];
        }
        if (appId == null || !appId.matches("\\d+")) return;
        StringBuilder text = new StringBuilder("unset LIBGL_ALWAYS_SOFTWARE MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER VK_DRIVER_FILES VK_ICD_FILENAMES\n");
        for (String entry : guestEnv) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || !isEmulatorVariable(entry.substring(0, eq))) continue;
            text.append("export ").append(entry, 0, eq).append("='").append(entry.substring(eq + 1).replace("'", "'\\''")).append("'\n");
        }
        File dir = new File(home, ".skynet/steam-emulator");
        dir.mkdirs();
        File fexConfig = new File(home, ".config/fex-emu/Config.json");
        if (fexConfig.isFile()) {
            try {
                org.json.JSONObject config = new org.json.JSONObject(FileUtils.readString(fexConfig));
                org.json.JSONObject thunkConfig = new org.json.JSONObject();
                if (config.has("ThunksDB")) thunkConfig.put("ThunksDB", config.getJSONObject("ThunksDB"));
                if (!FileUtils.writeString(new File(dir, appId + ".thunks64.json"), thunkConfig.toString()))
                    throw new IllegalStateException("Cannot prepare Steam 64-bit FEX thunks for " + appId);
                // Architecture-specific thunk selection belongs to FEX_THUNKCONFIG, not the app override.
                config.remove("ThunksDB");
                if (!FileUtils.writeString(new File(dir, appId + ".fex.json"), config.toString()))
                    throw new IllegalStateException("Cannot prepare Steam FEX configuration for " + appId);
            } catch (org.json.JSONException e) {
                throw new IllegalStateException("Invalid Steam FEX configuration for " + appId, e);
            }
            text.append("export FEX_APP_CONFIG='/root/.skynet/steam-emulator/").append(appId).append(".fex.json'\n");
            text.append("export SKY_FEX_THUNKCONFIG64='/root/.skynet/steam-emulator/").append(appId).append(".thunks64.json'\n");
        }
        File thunks = new File(home, ".config/fex-emu/ThunkConfig32.json");
        if (thunks.isFile()) {
            if (!FileUtils.copy(thunks, new File(dir, appId + ".thunks.json")))
                throw new IllegalStateException("Cannot prepare Steam FEX thunks for " + appId);
            text.append("export SKY_FEX_THUNKCONFIG32='/root/.skynet/steam-emulator/").append(appId).append(".thunks.json'\n");
        }
        if (!FileUtils.writeString(new File(dir, appId + ".env"), text.toString()))
            throw new IllegalStateException("Cannot prepare Steam emulator for " + appId);
    }

    private static void prepareSteamGames(Context context, File rt, File imageFsRoot, Container container) {
        LinuxSteamGames.sync(context, container);
        File[] shortcuts = container.getDesktopDir().listFiles((dir, name) -> name.endsWith(".desktop"));
        if (shortcuts == null) return;
        for (File file : shortcuts) {
            com.winlator.cmod.container.Shortcut shortcut = new com.winlator.cmod.container.Shortcut(container, file);
            if (!isSteamGame(shortcut.path)) continue;
            String appId = steamAppId(shortcut.path);
            if (!appId.matches("\\d+")) continue;
            GameProfiles.Effective profile = GameProfiles.resolve(context, container, shortcut);
            LinuxLaunchOptions own = LinuxLaunchOptions.parse(shortcut.getExtra(LinuxLaunchOptions.EXTRA),
                    shortcut.getExtra(LinuxLaunchOptions.EXTRA_ENV_VARS));
            LinuxLaunchOptions options = profile == null ? own : LinuxLaunchOptions.merged(profile.env, profile.programArgs(), own);
            writeSteamLaunchOptions(container, appId, options);
            Container gameContainer = LinuxEmulatorSetup.of(container, shortcut);
            if (!gameContainer.getLinuxRuntime().equals(container.getLinuxRuntime())) {
                writeSteamEmulatorEnv(container.getLinuxHomeDir(), new String[]{STEAM_MARKER, appId},
                        java.util.Collections.singletonList("SKY_GAME_SETUP_ERROR=This game needs a different Linux runtime; start it from its SkyNetEmu shortcut."));
                continue;
            }
            LinuxRootfs.Installed sessionRootfs = LinuxRootfs.resolve(context, container);
            LinuxRootfs.Installed gameRootfs = LinuxRootfs.resolve(context, gameContainer);
            if (gameRootfs != null && (sessionRootfs == null || !gameRootfs.dir.equals(sessionRootfs.dir))) {
                writeSteamEmulatorEnv(container.getLinuxHomeDir(), new String[]{STEAM_MARKER, appId},
                        java.util.Collections.singletonList("SKY_GAME_SETUP_ERROR=This game needs a different FEX root filesystem; start it from its SkyNetEmu shortcut."));
                continue;
            }
            List<String> env = guestEnv(context, rt, imageFsRoot, gameContainer, null, null, false);
            writeSteamEmulatorEnv(container.getLinuxHomeDir(), new String[]{STEAM_MARKER, appId}, env);
        }
    }

    /** The program a session starts, as the guest sees its path (the argument after "bannerlator-run"); null if none. */
    private static String programGuestPath(String[] guestCommand) {
        for (int i = 0; i + 1 < guestCommand.length; i++) {
            if ("bannerlator-run".equals(guestCommand[i])) return guestCommand[i + 1];
        }
        return null;
    }

    private static List<String> guestEnv(Context context, File rt, File imageFsRoot, Container container, File program, String programGuestPath, boolean directProgram) {
        File programFolder = program != null ? program.getParentFile() : null;
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("TZ", TimeZone.getDefault().getID());
        env.put("DISPLAY", ":" + displayNumber());
        env.put("XDG_RUNTIME_DIR", "/tmp");
        // Toolkits and SDL talk to the app's X server.
        env.put("GDK_BACKEND", "x11");
        env.put("QT_QPA_PLATFORM", "xcb");
        env.put("SDL_VIDEODRIVER", "x11");
        // MIT-SHM needs the app's bionic SysV shim; a glibc client sends images through the socket instead.
        env.put("QT_X11_NO_MITSHM", "1");
        env.put("_X11_NO_MITSHM", "1");
        if ("zink".equals(container.getExtra(EXTRA_GL_DRIVER))) {
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            env.put("GALLIUM_DRIVER", "zink");
        }
        else {
            env.put("LIBGL_ALWAYS_SOFTWARE", "1");
            env.put("GALLIUM_DRIVER", "llvmpipe");
        }
        env.put("PULSE_SERVER", "unix:" + new File(imageFsRoot, UnixSocketConfig.PULSE_SERVER_PATH).getPath());
        // The Steam client's web helper draws on the GPU through this taskset stand-in unless the container turns that off.
        File steamTaskset = stageAsset(context, "steam-taskset.sh");
        if (steamTaskset != null) env.put("SKY_STEAM_TASKSET", steamTaskset.getPath());
        if (!directProgram) {
            File installer = stageAsset(context, "steam-install.sh");
            File logExport = stageAsset(context, "steam-log-export.sh");
            File compatInstaller = stageAsset(context, "steam-compat-install.sh");
            if (installer == null || logExport == null || compatInstaller == null)
                throw new IllegalStateException("Cannot prepare Steam startup scripts");
            env.put("SKY_STEAM_INSTALLER", installer.getPath());
            env.put("SKY_STEAM_LOG_EXPORT", logExport.getPath());
            env.put("SKY_STEAM_COMPAT_INSTALLER", compatInstaller.getPath());
        }
        env.put("SKY_STEAM_GPU", "0".equals(container.getExtra(EXTRA_STEAM_GPU)) ? "0" : "1");

        // Turnip: an imported Linux driver, or the one the runtime ships.
        String driverId = container.getExtra(LinuxDriverManager.EXTRA_DRIVER);
        if (!driverId.isEmpty() && LinuxDriverManager.isInstalled(context, driverId)) {
            String icd = LinuxDriverManager.icdFile(context, driverId).getPath();
            env.put("VK_DRIVER_FILES", icd);
            env.put("VK_ICD_FILENAMES", icd);
        }
        else {
            if (!driverId.isEmpty()) LinuxFail.w(TAG, "Linux driver " + driverId + " is not installed; using the runtime's");
            if (new File(rt, "usr/share/vulkan/icd.d/freedreno_icd.json").exists()) {
                env.put("VK_ICD_FILENAMES", "/usr/share/vulkan/icd.d/freedreno_icd.json");
            }
        }

        // Gamepad: libskyshim answers the evdev ioctls on the files in this folder (see inputDir), which build()
        // binds over /dev/input. SDL lists a pad by reading that directory, so the bind is what makes it visible;
        // on its own the shim only answers a path a program already knows.
        env.put("SKY_INPUT_DIR", inputDir(context).getPath());
        // SDL before 2.0.14 (Feral's ports ship 2.0.10) has no SDL_JOYSTICK_DISABLE_UDEV and finds pads only through
        // udev, which reports none here; it does read this list first. A slot without a file yet is skipped.
        env.put("SDL_JOYSTICK_DEVICE", "/dev/input/event0:/dev/input/event1:/dev/input/event2:/dev/input/event3");
        // Turnip's tiled rendering (GMEM) switched off: sysmem renders straight into memory. A game started by Steam gets it from
        // ~/.skynet/steam-emulator/<appId>.env like the emulator's variables.
        if ("1".equals(container.getExtra(LinuxEmulatorSetup.EXTRA_TU_SYSMEM))) {
            String tuDebug = env.get("TU_DEBUG");
            env.put("TU_DEBUG", tuDebug == null || tuDebug.isEmpty() ? "sysmem" : tuDebug + ",sysmem");
        }
        // No udev runs in the session, so SDL must scan /dev/input itself instead of waiting on a monitor.
        env.put("SDL_JOYSTICK_DISABLE_UDEV", "1");
        env.put("SDL_HIDAPI_JOYSTICK_DISABLE_UDEV", "1");
        // SDL drops joystick events while its window lacks keyboard focus, and without a window manager the
        // core FocusIn never reaches SDL (see docs/linux-input-findings.md). Let pad events through regardless.
        env.put("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1");
        // Keep native touch and mouse independent, including when the sidebar changes mode in a running app.
        // Explicit shortcut variables are applied after these defaults.
        env.put("SDL_TOUCH_MOUSE_EVENTS", "0");
        env.put("SDL_MOUSE_TOUCH_EVENTS", "0");

        // The X server cannot take dma-bufs yet: with this on, Vulkan copies finished frames on the CPU.
        if (!"native".equals(container.getExtra(EXTRA_VULKAN_PRESENT))) env.put("MESA_VK_WSI_DEBUG", "sw");

        // Frame generation (Lossless Scaling through lsfg-vk): a Vulkan layer the guest's loader picks up.
        com.winlator.cmod.lsfg.LsfgManager.prepareLinuxLaunch(context, container, env);

        LinuxEmulator.Installed emulator = LinuxEmulator.resolve(context, container);
        if (emulator != null && emulator.emulator.equals(LinuxEmulator.BOX64)) {
            installDebugBox64(context, emulator);
            // Patched Box64 can share read-only ELF segments that its normal loader
            // would copy into every process. Older components ignore this variable.
            // Keep backing private to the app; failure leaves Box64's loader intact.
            File elfCache = new File(context.getCacheDir(), "elf-images-v1");
            try {
                if (!java.nio.file.Files.isSymbolicLink(elfCache.toPath())
                        && (elfCache.isDirectory() || elfCache.mkdirs())) {
                    android.system.Os.chmod(elfCache.getPath(), 0700);
                    env.put("SKY_ELF_IMAGE_CACHE_DIR", elfCache.getPath());
                }
            } catch (Exception e) {
                Log.w(TAG, "Shared ELF backing unavailable", e);
            }
            // The libraries Box64 does not wrap come from the package; the program's own folder is searched too.
            env.put("BOX64_LD_LIBRARY_PATH", new File(emulator.dir, "lib/box64-x86_64-linux-gnu").getPath());
            env.put("BOX64_NOBANNER", "1");
            // The Steam client is an arm64 program and starts a game as it is, and there is no binfmt_misc to hand an
            // x86-64 program to Box64 (proot's own -q does not work in this build). libskyshim's exec and spawn functions
            // do it: any program that starts an x86-64 one gets it run through this emulator.
            env.put("SKY_X86_EMULATOR", LinuxEmulator.binary(emulator).getPath());
            // Box64 runs 64-bit programs only. A 32-bit one (Tomb Raider, 2013) goes to FEX when one is installed with a root
            // filesystem that has the 32-bit libraries; libskyshim picks the emulator by the program's ELF class.
            java.util.List<LinuxEmulator.Installed> fexBuilds = LinuxEmulator.list(context, LinuxEmulator.FEX);
            java.util.List<LinuxRootfs.Installed> rootfsList = LinuxRootfs.list(context);
            if (!fexBuilds.isEmpty() && !rootfsList.isEmpty()) {
                FexComponents.ensureThunks32(context, fexBuilds.get(0));
                env.put("SKY_X86_32_EMULATOR", LinuxEmulator.binary(fexBuilds.get(0)).getPath());
                // FEX reads its root filesystem and thunks from the configuration LinuxFex writes (FEX_ROOTFS stays for the
                // programs run by hand). A 32-bit game's OpenGL goes through the thunk to the runtime's Mesa; the launcher gives
                // it Zink and the thunk selection (SKY_FEX_THUNKCONFIG32).
                env.put("FEX_ROOTFS", rootfsList.get(0).dir.getPath());
                env.put("FEX_SILENTLOG", "1");
                LinuxFex.applyPreset(context, container, env);
                String thunks32 = LinuxFex.prepareFor32Bit(context, container, fexBuilds.get(0), rootfsList.get(0));
                if (thunks32 != null) {
                    env.put("SKY_FEX_THUNKCONFIG32", thunks32);
                    if (LinuxFex.gl32Enabled(container, fexBuilds.get(0))) { env.put("SKY_FEX_GL32", "1"); env.put("SKY_FEX_GL_VENDOR", LinuxFex.glVendor(container)); putGl32Script(context, env); }
                }
            }
            // Steam starts a game through Valve's FEX tool, which needs what proot cannot give; this stands in for it.
            File launcher = stageAsset(context, "skynet-launcher.sh");
            if (launcher != null) env.put("SKY_LAUNCHER", launcher.getPath());
            // The launcher preloads this stub for a game that ships mimalloc (see below), as a program started from a shortcut
            // gets it here: the Steam client's session does not know the game when it starts.
            File mimallocStub = stageAsset(context, "libmimalloc.so.3");
            if (mimallocStub != null) env.put("SKY_MIMALLOC_STUB", mimallocStub.getPath());
            // An emulated program is an x86-64 process, so the session's aarch64 libraries are never loaded into it:
            // the shims it needs are preloaded again, built for that architecture.
            StringBuilder preload = new StringBuilder();
            for (String name : new String[]{
                    "libskyshim_x64.so",   // the gamepad's evdev ioctls and the uevent socket
                    "libcefnosb_x64.so"}) { // an embedded Chromium (CEF) aborts on the setuid sandbox check
                File shim = stageAsset(context, name);
                if (shim == null) continue;
                if (preload.length() > 0) preload.append(':');
                preload.append(shim.getPath());
            }
            // A game that ships mimalloc (BeamNG 0.39) has it take over malloc and free for the whole process, which Box64
            // cannot follow: a block mimalloc allocated reaches glibc's free and the game dies a second after it starts.
            // A library with its mi_* functions on plain malloc, preloaded under the soname the game asks for, takes the
            // place of the game's own (tools/linux-shim/mimalloc_compat.c).
            if (programFolder != null && new File(programFolder, "libmimalloc.so.3").isFile()) {
                File stub = stageAsset(context, "libmimalloc.so.3");
                if (stub != null) {
                    if (preload.length() > 0) preload.append(':');
                    preload.append(stub.getPath());
                }
            }
            if (preload.length() > 0) env.put("BOX64_LD_PRELOAD", preload.toString());
            // A game with an embedded Chromium looks for icudtl.dat and the .pak files in its game folder, one level above
            // the program's, where its depot has none: the shim opens them from the program's folder when that fails.
            if (programFolder != null && new File(programFolder, "libcef.so").isFile()
                    && !new File(programFolder.getParentFile(), "icudtl.dat").exists()) {
                env.put("SKY_RESOURCE_DIR", programGuestPath.substring(0, programGuestPath.lastIndexOf('/')));
            }
            env.put("BOX64_DYNAREC", "1");
            // Box64 prints its native and emulated backtraces only for an unhandled crash or abort; launch telemetry keeps them.
            env.put("BOX64_SHOWBT", "1");
            // The Box64 log switch in Settings (on by default in a debug build) reaches Linux sessions too.
            if (androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean("enable_box64_logs", com.winlator.cmod.core.AppUtils.isDebugBuild(context))) {
                env.put("BOX64_LOG", "1");
            }
            String preset = container.getExtra(LinuxEmulator.EXTRA_PRESET);
            if (preset.isEmpty()) preset = com.winlator.cmod.box64.Box64Preset.COMPATIBILITY;
            for (String pair : com.winlator.cmod.box64.Box64PresetManager.getEnvVars("box64", context, preset).toStringArray()) {
                int index = pair.indexOf('=');
                if (index > 0) env.put(pair.substring(0, index), pair.substring(index + 1));
            }
        }

        else if (emulator != null && emulator.emulator.equals(LinuxEmulator.FEX)) {
            // FEX runs the program in an x86-64 root filesystem of its own, so nothing of Box64's applies. A missing
            // root filesystem is reported when the program is started (programCommand).
            FexComponents.ensureThunks32(context, emulator);
            String problem = LinuxFex.prepare(context, container, emulator, env);
            if (problem != null) LinuxFail.w(TAG, problem);
            else {
                // The Steam client starts a game through Valve's FEX tool, which needs what proot cannot give (see the Box64
                // branch above): the stand-in runs the game's command, and libskyshim hands an x86 program to FEX.
                String fexBinary = LinuxEmulator.binary(emulator).getPath();
                // The launcher looks in the root filesystem for the libraries a 32-bit game's runtime would add (see skynet-launcher.sh).
                LinuxRootfs.Installed rootfs = LinuxRootfs.resolve(context, container);
                if (rootfs != null) env.put("FEX_ROOTFS", rootfs.dir.getPath());
                env.put("SKY_X86_EMULATOR", fexBinary);
                env.put("SKY_X86_32_EMULATOR", fexBinary);
                File launcher = stageAsset(context, "skynet-launcher.sh");
                if (launcher != null) env.put("SKY_LAUNCHER", launcher.getPath());
                String thunks32 = LinuxFex.writeThunkConfig32(container, emulator);
                if (thunks32 != null) {
                    env.put("SKY_FEX_THUNKCONFIG32", thunks32);
                    if (LinuxFex.gl32Enabled(container, emulator)) { env.put("SKY_FEX_GL32", "1"); env.put("SKY_FEX_GL_VENDOR", LinuxFex.glVendor(container)); putGl32Script(context, env); }
                }
            }
        }

        // Native libraries the emulated program's wrapped libraries need that the runtime does not ship: the
        // emulator package's lib/host and the container's own hostlibs folder.
        java.util.List<String> extraLibs = new ArrayList<>();
        File containerLibs = new File(container.getLinuxHomeDir(), "hostlibs");
        if (containerLibs.isDirectory()) extraLibs.add("/root/hostlibs");
        // libXss (X screensaver): Box64 wraps it, the runtime does not ship it, and a game with an embedded
        // Chromium (CEF) needs it to start. Arch Linux ARM's libxss 1.2.5, taken from the package unchanged.
        // (Box64 only: FEX's guest uses the x86-64 libXss of its own root filesystem.)
        File xss = emulator != null && emulator.emulator.equals(LinuxEmulator.FEX) ? null : stageAsset(context, "libXss.so.1");
        if (xss != null) extraLibs.add(xss.getParent());
        if (emulator != null && new File(emulator.dir, "lib/host").isDirectory()) {
            extraLibs.add(new File(emulator.dir, "lib/host").getPath());
        }
        if (!extraLibs.isEmpty()) env.put("LD_LIBRARY_PATH", String.join(":", extraLibs));

        env.putAll(userEnv(container));
        env.putAll(debugEnv(context));
        // Bionic reconstructs the environment with its own loader ABI in LinuxBionicSession.build.
        LinuxGpuMemory.prepare(context,container,env,false,directProgram && !LinuxBionicSession.enabled(container),program==null ? 0 : elfMachine(program));
        if (emulator != null && LinuxEmulator.BOX64.equals(emulator.emulator) && !LinuxBionicSession.enabled(container))
            FramePipelineDiagnostics.prepare(context, env, com.winlator.cmod.lsfg.LsfgManager.isArmed(context,container) && com.winlator.cmod.lsfg.LsfgManager.getMultiplier(container)>=2);

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) result.add(entry.getKey() + "=" + entry.getValue());
        return result;
    }

    /**
     * Debug builds only: a Box64 binary at Winlator/box64-override on shared storage replaces the installed build's, so
     * a patched Box64 can be tried from a computer without publishing it. Shared storage cannot run programs, hence the
     * copy; it is made once per file (by size and time).
     */
    private static void installDebugBox64(Context context, LinuxEmulator.Installed emulator) {
        if (!com.winlator.cmod.core.AppUtils.isDebugBuild(context)) return;
        File extras = new File(android.os.Environment.getExternalStorageDirectory(), "Winlator/box64-override-files");
        File[] extraFiles = extras.listFiles();
        if (extraFiles != null) {
            for (File extra : extraFiles) {
                File copy = new File(LinuxEmulator.binary(emulator).getParentFile(), extra.getName());
                if (extra.isFile() && (!copy.isFile() || copy.length() != extra.length())) FileUtils.copy(extra, copy);
            }
        }
        File source = new File(android.os.Environment.getExternalStorageDirectory(), "Winlator/box64-override");
        File target = LinuxEmulator.binary(emulator);
        if (!source.isFile()) return;
        File stamp = new File(target.getParentFile(), ".override-stamp");
        String id = source.length() + ":" + source.lastModified();
        if (stamp.isFile() && id.equals(FileUtils.readString(stamp))) return;
        if (FileUtils.copy(source, target)) {
            FileUtils.chmod(target, 0755);
            FileUtils.writeString(stamp, id);
            Log.i(TAG, "Box64 replaced by " + source + " (" + id + ")");
        }
    }

    /**
     * Debug builds only: Winlator/linux-env.txt on shared storage (one KEY=VALUE per line) adds variables to every Linux
     * session, so an emulator setting can be tried from a computer without opening the container's settings each time.
     */
    private static Map<String, String> debugEnv(Context context) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!com.winlator.cmod.core.AppUtils.isDebugBuild(context)) return result;
        File file = new File(android.os.Environment.getExternalStorageDirectory(), "Winlator/linux-env.txt");
        if (!file.isFile()) return result;
        for (String line : FileUtils.readLines(file)) {
            line = line.trim();
            int index = line.indexOf('=');
            if (line.startsWith("#") || index <= 0) continue;
            result.put(line.substring(0, index), line.substring(index + 1));
        }
        return result;
    }

    /** The container's own variables ("A=1 B=2"). The untouched Wine default means "none", as for older Linux containers. */
    private static Map<String, String> userEnv(Container container) {
        Map<String, String> result = new LinkedHashMap<>();
        String value = container.getEnvVars();
        if (value == null || value.trim().isEmpty() || value.equals(Container.DEFAULT_ENV_VARS)) return result;
        for (String token : value.trim().split("\\s+")) {
            int index = token.indexOf('=');
            if (index > 0) result.put(token.substring(0, index), token.substring(index + 1));
        }
        return result;
    }

    /** X access control and libc look the user up by uid, which is the app's, so "root" gets that uid. */
    private static void writeAccounts(File rt, int uid) {
        FileUtils.writeString(new File(rt, "etc/passwd"),
                "root:x:" + uid + ":" + uid + ":root:/root:/bin/bash\n"
                        + "nobody:x:65534:65534:nobody:/:/usr/bin/nologin\n");
        FileUtils.writeString(new File(rt, "etc/group"),
                "root:x:" + uid + ":\nnobody:x:65534:\n");
    }

    /** The runtime's shim library (SysV IPC and other things Android withholds) is loaded into every process. */
    private static void writePreload(Context context, File rt) {
        File preload = new File(rt, "etc/ld.so.preload");
        StringBuilder libraries = new StringBuilder();
        if (new File(rt, PRELOAD.substring(1)).exists()) libraries.append(PRELOAD).append('\n');
        File shim = stageShim(context);
        if (shim != null) libraries.append(shim.getPath()).append('\n');
        if (libraries.length() > 0) FileUtils.writeString(preload, libraries.toString());
        else preload.delete();
    }

    /**
     * Copies our own preload library (tools/linux-shim: answers the uevent socket that Android refuses, which SDL needs
     * to start) out of the APK. files/ is bound into the session at its own path, so the guest can load it from there.
     */
    private static File stageShim(Context context) {
        return stageAsset(context, "libskyshim.so");
    }

    private static File stageAsset(Context context, String name) {
        return stageAsset(context, "linux-shim", name);
    }

    /** Copies one of the app's own Linux binaries out of the APK into files/{@code dirName}. */
    static File stageAsset(Context context, String dirName, String name) {
        File dir = new File(context.getFilesDir(), dirName);
        File shim = new File(dir, name);
        try (java.io.InputStream in = context.getAssets().open("linux/" + name)) {
            byte[] data = in.readAllBytes();
            // The same size is not the same file (a rebuilt binary can come out as long as the last one): the bytes are compared.
            if (!shim.isFile() || shim.length() != data.length || !java.util.Arrays.equals(java.nio.file.Files.readAllBytes(shim.toPath()), data)) {
                dir.mkdirs();
                File temp = new File(dir, name + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                    out.write(data);
                }
                FileUtils.chmod(temp, 0755);
                if (!temp.renameTo(shim)) return null;
            }
            return shim;
        }
        catch (java.io.IOException e) {
            LinuxFail.w(TAG, "Cannot stage " + name, e);
            return null;
        }
    }

    /** Where the app writes the controllers' evdev events; bound over /dev/input in the session. */
    public static File inputDir(Context context) {
        return new File(context.getFilesDir(), "imagefs/dev/input");
    }

    private static void bind(List<String> argv, String path) {
        bind(argv, path, path);
    }

    private static void bind(List<String> argv, String host, String guest) {
        argv.add("-b");
        argv.add(host.equals(guest) ? host : host + ":" + guest);
    }

    private static void bindIfExists(List<String> argv, File host, String guest) {
        if (host.exists()) bind(argv, host.getPath(), guest);
    }
}
