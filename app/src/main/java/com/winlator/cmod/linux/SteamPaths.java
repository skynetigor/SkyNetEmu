package com.winlator.cmod.linux;

import java.io.File;

/** Where Steam's files are in a Linux container's home (a folder given as a parameter, so that host tests need no Android). */
public final class SteamPaths {
    /** Guest path of the home folder: what the container sees as ~. */
    public static final String GUEST_HOME = "/root";

    private final File home;

    public SteamPaths(File linuxHome) {
        this.home = linuxHome;
    }

    public File home() {
        return home;
    }

    public File steamRoot() {
        return new File(home, ".local/share/Steam");
    }

    public File appInfo() {
        return new File(steamRoot(), "appcache/appinfo.vdf");
    }

    public File packageInfo() {
        return new File(steamRoot(), "appcache/packageinfo.vdf");
    }

    public File consoleLog() {
        return new File(steamRoot(), "logs/console_log.txt");
    }

    /** The folders where {@code download_depot} may leave app_&lt;id&gt;/depot_&lt;id&gt;: the client's own root and the runtime's one under it. */
    public File[] contentRoots() {
        return new File[]{new File(steamRoot(), "steamapps/content"), new File(steamRoot(), "steamrtarm64/steamapps/content")};
    }

    /** Folder of the downloaded Linux versions; each one is a folder named like the shortcut. */
    public File versionsRoot() {
        return new File(home, "games-versions");
    }

    /**
     * Where a version is put together: inside the container's home for a Linux game, on shared storage for a Windows one (a Wine container
     * reaches the phone's storage through a drive letter, not the Linux container's private home).
     */
    public File versionsRoot(String platform) {
        return "windows".equals(platform) ? new File("/storage/emulated/0/Winlator/Games") : versionsRoot();
    }

    public File jobsDir() {
        return new File(home, ".skynet/downloads");
    }

    /** A guest path ("/root/...", as Steam prints it) as a path on the host; null when it is not under the guest home. */
    public File hostFile(String guestPath) {
        if (guestPath == null) return null;
        String path = guestPath.trim();
        if (path.equals(GUEST_HOME)) return home;
        if (!path.startsWith(GUEST_HOME + "/")) return null;
        return new File(home, path.substring(GUEST_HOME.length() + 1));
    }

    public String guestPath(File host) {
        String base = home.getAbsolutePath();
        String path = host.getAbsolutePath();
        if (path.equals(base)) return GUEST_HOME;
        return path.startsWith(base + "/") ? GUEST_HOME + path.substring(base.length()) : path;
    }
}
