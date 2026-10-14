package com.winlator.cmod.linux;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.winlator.cmod.container.Container;

import java.io.File;
import java.util.List;

/**
 * The XFCE desktop of a Linux container: what it needs on disk and the command that runs it. A shortcut's session never
 * uses it (a game runs bare, see {@link LinuxSession}); a container started without a shortcut opens this.
 */
public final class LinuxDesktop {
    /** Container extra: "0" keeps the container from opening a desktop (it asks for a program as before); on when absent. */
    public static final String EXTRA_DESKTOP = "linuxDesktop";
    /** Programs the session starts; all of them must be in the runtime for the desktop to count as installed. */
    private static final String[] REQUIRED = {"usr/bin/xfwm4", "usr/bin/xfce4-panel", "usr/bin/xfdesktop", "usr/bin/xfsettingsd", "usr/bin/dbus-daemon"};
    public static final String LOG = "/storage/emulated/0/Winlator/logs/linux-desktop.log";

    private LinuxDesktop() {}

    public static boolean enabled(Container container) {
        return container != null && container.isLinux() && !"0".equals(container.getExtra(EXTRA_DESKTOP));
    }

    public static boolean isInstalled(Context context, LinuxRuntime.Installed runtime) {
        if (runtime == null) return false;
        File root = LinuxRuntime.rootDir(context, runtime.id);
        for (String path : REQUIRED) if (!new File(root, path).isFile()) return false;
        return true;
    }

    /**
     * Starts what makes a desktop and stays until the panel is closed: a session bus, the settings daemon, the window manager
     * without compositing, the panel and the desktop. The script is the session's first process, so the session ends with it.
     */
    /** Container extra: the desktop's screen size, "native" or WIDTHxHEIGHT; the screen's own size when absent. */
    public static final String EXTRA_SCREEN = "linuxDesktopScreen";

    /** The size a desktop at this width of screen is drawn at: twice, on a screen as dense as a phone's. */
    public static int scaleFor(int screenWidth) {
        return screenWidth >= 1800 ? 2 : 1;
    }

    /** The file the app creates in the guest's /tmp when a text field was tapped: the on-screen keyboard shows itself and removes it. */
    public static final String OSK_SHOW_FILE = "skynet-osk-show";

    /** Prefix of what is generated for the desktop's launchers, so the next start can tell it from what the user made. */
    private static final String GENERATED = "skynet-";

    /**
     * Makes the container's shortcuts launchable from the desktop. A shortcut's file only names a guest program, which a double-click
     * could not start (an x86-64 one needs the emulator, a script needs bash, the launch options and the game's profile apply). Each
     * shortcut gets a script that runs the very command its own session would, a launcher for it on the desktop and in the
     * applications menu, and {@code skynet-run} runs any program the same way (Thunar's "Open With"). Everything is made again at
     * every start, so it follows the shortcuts; the shortcut files themselves are not touched. Steam's shortcuts are left out.
     */
    public static void prepareLaunchers(Context context, Container container) {
        File home = container.getLinuxHomeDir();
        File skynet = new File(home, ".skynet");
        File scripts = new File(skynet, "launch");
        File icons = new File(skynet, "desktop");
        File apps = new File(home, ".local/share/applications");
        File bin = new File(skynet, "bin");
        for (File dir : new File[]{scripts, icons, apps, bin, new File(home, ".config")}) dir.mkdirs();
        for (File dir : new File[]{scripts, icons, apps}) {
            File[] old = dir.listFiles();
            if (old == null) continue;
            for (File file : old) if (dir != apps || file.getName().startsWith(GENERATED)) file.delete();
        }

        LinuxEmulator.Installed emulator = LinuxEmulator.resolve(context, container);
        File emulatorFile = new File(skynet, "emulator");
        if (emulator != null && !LinuxEmulator.NONE.equals(LinuxEmulator.choice(container))) {
            com.winlator.cmod.core.FileUtils.writeString(emulatorFile, LinuxEmulator.binary(emulator).getPath() + "\n");
        }
        else emulatorFile.delete();

        File run = new File(bin, "skynet-run");
        com.winlator.cmod.core.FileUtils.writeString(run, "#!/bin/bash\n"
                + "# skynet-run PROGRAM [ARGS]: runs a program of this container as its shortcuts do (x86-64 ones through the emulator).\n"
                + "prog=\"$1\"; shift\n"
                + "[ -f \"$prog\" ] || { echo \"skynet-run: no program $prog\" >&2; exit 2; }\n"
                + "chmod +x \"$prog\" 2>/dev/null; cd \"$(dirname \"$prog\")\" || exit 1\n"
                + "case \"$prog\" in *.sh) exec bash \"$prog\" \"$@\";; esac\n"
                + "machine=$(od -An -tu2 -j18 -N2 \"$prog\" 2>/dev/null | tr -d ' ')\n"
                + "if [ \"$machine\" = 62 ] && [ -s \"$HOME/.skynet/emulator\" ]; then exec \"$(cat \"$HOME/.skynet/emulator\")\" \"$prog\" \"$@\"; fi\n"
                + "exec \"$prog\" \"$@\"\n");
        com.winlator.cmod.core.FileUtils.chmod(run, 493 /* octal 0755 */);
        com.winlator.cmod.core.FileUtils.writeString(new File(apps, GENERATED + "run.desktop"), "[Desktop Entry]\nType=Application\n"
                + "Name=Run with SkyNet\nExec=skynet-run %f\nNoDisplay=true\nTerminal=false\n"
                + "MimeType=application/x-executable;application/x-pie-executable;application/x-sharedlib;application/x-shellscript;\n");

        File[] shortcuts = container.getDesktopDir().listFiles((dir, name) -> name.endsWith(".desktop"));
        if (shortcuts != null) {
            for (File file : shortcuts) {
                com.winlator.cmod.container.Shortcut shortcut;
                try {
                    shortcut = new com.winlator.cmod.container.Shortcut(container, file);
                }
                catch (RuntimeException e) {
                    continue; // a file that is not a shortcut
                }
                if (shortcut.path == null || shortcut.path.isEmpty() || LinuxSession.isSteam(shortcut.path) || LinuxSession.isSteamGame(shortcut.path)) continue;
                String slug = file.getName().substring(0, file.getName().length() - ".desktop".length()).replaceAll("[^A-Za-z0-9._-]", "_");
                StringBuilder script = new StringBuilder("#!/bin/bash\nmkdir -p \"$HOME/.skynet\"\nexec >>\"$HOME/.skynet/launch.log\" 2>&1\n"
                        + "echo \"== $(date) " + slug + "\"\nexec");
                for (String word : LinuxSession.programCommand(context, container, shortcut)) script.append(" '").append(word.replace("'", "'\\''")).append('\'');
                File launch = new File(scripts, slug + ".sh");
                com.winlator.cmod.core.FileUtils.writeString(launch, script.append('\n').toString());
                com.winlator.cmod.core.FileUtils.chmod(launch, 493);
                String entry = "[Desktop Entry]\nType=Application\nVersion=1.0\nName=" + shortcut.name + "\nExec=/root/.skynet/launch/" + slug
                        + ".sh\nTerminal=false\nIcon=applications-games\nCategories=Game;\nStartupNotify=false\n";
                File onDesktop = new File(icons, slug + ".desktop");
                com.winlator.cmod.core.FileUtils.writeString(onDesktop, entry);
                com.winlator.cmod.core.FileUtils.chmod(onDesktop, 493); // xfdesktop only launches a trusted (executable) launcher
                com.winlator.cmod.core.FileUtils.writeString(new File(apps, GENERATED + slug + ".desktop"), entry);
            }
        }
        // The desktop's icons are these launchers; the shortcut files stay where the app keeps them.
        com.winlator.cmod.core.FileUtils.writeString(new File(home, ".config/user-dirs.dirs"), "XDG_DESKTOP_DIR=\"$HOME/.skynet/desktop\"\n");
    }

    public static String[] command(Context context, int scale) {
        File osk = LinuxSession.stageAsset(context, "linux-shim", "skynet-osk.py");
        return new String[]{"/bin/bash", "-c",
                "mkdir -p /storage/emulated/0/Winlator/logs; exec >" + LOG + " 2>&1; "
                        + "echo \"== desktop start $(date) DISPLAY=$DISPLAY uid=$(id -u)\"; "
                        + "export XDG_RUNTIME_DIR=/tmp XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP=XFCE "
                        + "XDG_CONFIG_DIRS=/etc/xdg XDG_DATA_DIRS=/usr/local/share:/usr/share "
                        + "XDG_CONFIG_HOME=$HOME/.config XDG_CACHE_HOME=$HOME/.cache XDG_DATA_HOME=$HOME/.local/share "
                        // The screen is the display's own size, so a desktop is drawn at 2x on it (windows, text and the panel).
                        + "GTK_THEME=Adwaita GDK_SCALE=" + scale + "; "
                        + "mkdir -p \"$XDG_CONFIG_HOME\" \"$XDG_CACHE_HOME\" \"$XDG_DATA_HOME\"; "
                        // gdk-pixbuf 2.44 loads images through glycin, which wants bubblewrap and cannot get it under proot. glycin runs
                        // its decoders without a sandbox when bwrap says it may not make a namespace: this stand-in says so.
                        + "mkdir -p \"$HOME/.skynet/bin\"; "
                        + "printf '#!/bin/sh\\necho \"bwrap: setting up uid map: Permission denied\" >&2\\nexit 1\\n' > \"$HOME/.skynet/bin/bwrap\"; "
                        + "chmod 755 \"$HOME/.skynet/bin/bwrap\"; export PATH=\"$HOME/.skynet/bin:$PATH\"; "
                        // "Log Out" of the panel asks the session manager, which this desktop has none of: the panel ending ends the desktop.
                        + "printf '#!/bin/sh\\npkill -x xfce4-panel\\n' > \"$HOME/.skynet/bin/xfce4-session-logout\"; "
                        + "chmod 755 \"$HOME/.skynet/bin/xfce4-session-logout\"; "
                        + "[ -s /etc/machine-id ] || dbus-uuidgen > /etc/machine-id; "
                        + "rm -f /tmp/skynet-session-bus; "
                        + "dbus-daemon --session --nofork --address=unix:path=/tmp/skynet-session-bus & "
                        + "export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/skynet-session-bus; sleep 1; "
                        // A file ~/.desktop-components.sh replaces the list below (for trying components from a computer).
                        + "if [ -f \"$HOME/.desktop-components.sh\" ]; then . \"$HOME/.desktop-components.sh\"; else "
                        + "xfsettingsd & sleep 1; "
                        // The window frames come in a 2x set for a dense screen.
                        + (scale > 1 ? "xfconf-query -c xfwm4 -p /general/theme -n -t string -s Default-xhdpi; " : "")
                        + "xfwm4 --compositor=off & sleep 2; "
                        + "xfce4-panel & panel=$!; sleep 1; "
                        + "xfdesktop & "
                        // The desktop opens launchers and folders through the file manager service, which has to be running.
                        + "thunar --daemon & "
                        // The on-screen keyboard of the desktop: the Android one is not used here.
                        + (osk != null ? "rm -f /tmp/" + OSK_SHOW_FILE + "; python3 \"" + osk.getPath() + "\" > /tmp/skynet-osk.log 2>&1 & " : "")
                        + "wait $panel; fi; echo \"== desktop ended $?\"",
                "bannerlator-desktop"};
    }

    /**
     * Asks to install the desktop, downloads it with a progress window, and calls {@code onInstalled}; {@code onDeclined} when
     * the user says no or it cannot be installed (the reason is shown).
     */
    public static void offerInstall(Activity activity, Runnable onInstalled, Runnable onDeclined) {
        new AlertDialog.Builder(activity)
                .setTitle("Install the desktop?")
                .setMessage("The XFCE desktop is not in this Linux runtime yet. It is a small download (about 6 MB) and is added to the runtime.")
                .setPositiveButton("Install", (dialog, which) -> install(activity, onInstalled, onDeclined))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> onDeclined.run())
                .setOnCancelListener(dialog -> onDeclined.run())
                .show();
    }

    private static void install(Activity activity, Runnable onInstalled, Runnable onDeclined) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView status = new TextView(activity);
        status.setText("Looking for the desktop…");
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        layout.addView(status);
        layout.addView(bar);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Installing the desktop").setView(layout).setCancelable(false).show();
        new Thread(() -> {
            String error;
            List<LinuxRuntimeCatalog.Entry> desktops = localBundle(activity);
            if (desktops == null) desktops = LinuxRuntimeCatalog.fetchDesktops();
            if (desktops == null || desktops.isEmpty()) {
                error = desktops == null ? "The catalog cannot be read. Check the connection." : "No desktop is published yet.";
            }
            else {
                error = LinuxRuntimeInstaller.install(activity, desktops.get(0), (phase, percent) -> activity.runOnUiThread(() -> {
                    status.setText((phase == LinuxRuntimeInstaller.Phase.DOWNLOAD ? "Downloading " : phase == LinuxRuntimeInstaller.Phase.VERIFY
                            ? "Checking " : "Unpacking ") + percent + "%");
                    bar.setProgress(percent);
                }));
            }
            String result = error;
            activity.runOnUiThread(() -> {
                dialog.dismiss();
                if (result == null) onInstalled.run();
                else {
                    Toast.makeText(activity, result, Toast.LENGTH_LONG).show();
                    onDeclined.run();
                }
            });
        }).start();
    }

    /**
     * Debug builds only: a bundle at Winlator/desktop-bundle.tar.zst on shared storage is installed instead of the catalog's, so one
     * made by tools/xfce-bundle/build.sh can be tried from a computer before it is published.
     */
    private static List<LinuxRuntimeCatalog.Entry> localBundle(Context context) {
        if (!com.winlator.cmod.core.AppUtils.isDebugBuild(context)) return null;
        File file = new File(android.os.Environment.getExternalStorageDirectory(), "Winlator/desktop-bundle.tar.zst");
        if (!file.isFile()) return null;
        List<LinuxRuntimeCatalog.Entry> list = new java.util.ArrayList<>();
        list.add(new LinuxRuntimeCatalog.Entry("xfce-local", "XFCE (local file)", "local", file.toURI().toString(), "", file.length(),
                "prerelease", LinuxPackages.KIND_DESKTOP, null));
        return list;
    }
}
