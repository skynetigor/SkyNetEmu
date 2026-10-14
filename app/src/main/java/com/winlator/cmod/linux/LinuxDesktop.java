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
    public static String[] command() {
        return new String[]{"/bin/bash", "-c",
                "mkdir -p /storage/emulated/0/Winlator/logs; exec >" + LOG + " 2>&1; "
                        + "echo \"== desktop start $(date) DISPLAY=$DISPLAY uid=$(id -u)\"; "
                        + "export XDG_RUNTIME_DIR=/tmp XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP=XFCE "
                        + "XDG_CONFIG_DIRS=/etc/xdg XDG_DATA_DIRS=/usr/local/share:/usr/share "
                        + "XDG_CONFIG_HOME=$HOME/.config XDG_CACHE_HOME=$HOME/.cache XDG_DATA_HOME=$HOME/.local/share "
                        + "GTK_THEME=Adwaita; "
                        + "mkdir -p \"$XDG_CONFIG_HOME\" \"$XDG_CACHE_HOME\" \"$XDG_DATA_HOME\"; "
                        // gdk-pixbuf 2.44 loads images through glycin, which wants bubblewrap and cannot get it under proot. glycin runs
                        // its decoders without a sandbox when bwrap says it may not make a namespace: this stand-in says so.
                        + "mkdir -p \"$HOME/.skynet/bin\"; "
                        + "printf '#!/bin/sh\\necho \"bwrap: setting up uid map: Permission denied\" >&2\\nexit 1\\n' > \"$HOME/.skynet/bin/bwrap\"; "
                        + "chmod 755 \"$HOME/.skynet/bin/bwrap\"; export PATH=\"$HOME/.skynet/bin:$PATH\"; "
                        + "[ -s /etc/machine-id ] || dbus-uuidgen > /etc/machine-id; "
                        + "rm -f /tmp/skynet-session-bus; "
                        + "dbus-daemon --session --nofork --address=unix:path=/tmp/skynet-session-bus & "
                        + "export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/skynet-session-bus; sleep 1; "
                        // A file ~/.desktop-components.sh replaces the list below (for trying components from a computer).
                        + "if [ -f \"$HOME/.desktop-components.sh\" ]; then . \"$HOME/.desktop-components.sh\"; else "
                        + "xfsettingsd & sleep 1; "
                        + "xfwm4 --compositor=off & sleep 2; "
                        + "xfce4-panel & panel=$!; sleep 1; "
                        + "xfdesktop & "
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
