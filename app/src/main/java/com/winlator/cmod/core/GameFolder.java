package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.Locale;

/**
 * Where a game's files are: the exe a shortcut starts and the folder of the game around it, as paths on the device.
 *
 * A shortcut keeps its exe either as a unix path ("/storage/emulated/0/Download/Games/X/Bin64/X.exe") or as a Windows path
 * ("D:\Games\X\Bin64\X.exe", the drive letter naming one of the container's drives). The game's folder is the exe's folder,
 * or the folder above it when the exe sits in a binaries folder (Bin64, x64, ...).
 */
public final class GameFolder {
    private static final String BINARIES = "(?i)bin|bin32|bin64|binarm64|binaries|win64|x64|x86_64|arm64|game";

    /** The exe as a path on the device. */
    public final File exe;
    /** The folder that holds the exe. */
    public final File exeDir;
    /** The game's folder: {@link #exeDir}, or its parent when {@link #exeDir} is a binaries folder. */
    public final File folder;

    private GameFolder(File exe, File exeDir, File folder) {
        this.exe = exe;
        this.exeDir = exeDir;
        this.folder = folder;
    }

    /**
     * @param shortcutPath what the shortcut stores (Shortcut.path): the exe, possibly in quotes and followed by arguments
     * @return null when the exe cannot be worked out
     */
    public static GameFolder of(ImageFs imageFs, Container container, String shortcutPath) {
        String unix = unixExePath(imageFs, container, shortcutPath);
        if (unix == null) return null;
        File exe = new File(unix);
        File exeDir = exe.getParentFile();
        if (exeDir == null || exeDir.getPath().length() < 2) return null;
        File parent = exeDir.getParentFile();
        File folder = parent != null && parent.getPath().length() > 1 && exeDir.getName().matches(BINARIES) ? parent : exeDir;
        return new GameFolder(exe, exeDir, folder);
    }

    /** The folders the file-system cache works on: the exe's folder and, for a binaries folder, the game's folder above it. */
    public static String cacheRoots(ImageFs imageFs, Container container, String shortcutPath) {
        GameFolder game = of(imageFs, container, shortcutPath);
        if (game == null) return "";
        return game.folder.equals(game.exeDir) ? game.exeDir.getPath() : game.exeDir.getPath() + ":" + game.folder.getPath();
    }

    /** The exe of a shortcut as a unix path, or null. */
    public static String unixExePath(ImageFs imageFs, Container container, String shortcutPath) {
        String p = executablePath(shortcutPath);
        if (p == null) return null;
        if (p.length() > 2 && p.charAt(1) == ':' && (p.charAt(2) == '\\' || p.charAt(2) == '/')) {
            // A Windows path: the drive letter says where it is on the device.
            String letter = p.substring(0, 1).toLowerCase(Locale.ENGLISH);
            String rest = p.substring(2).replace('\\', '/');
            String base = null;
            if (letter.equals("z")) base = imageFs.getRootDir().getPath();
            else if (letter.equals("c") && container != null) base = new File(container.getRootDir(), ".wine/drive_c").getPath();
            else if (container != null) {
                for (String[] drive : container.drivesIterator())
                    if (drive[0].equalsIgnoreCase(letter)) base = drive[1];
            }
            return base == null ? null : base + rest;
        }
        return p.startsWith("/") ? p : null;
    }

    /** The selected program before inline arguments; a quoted path may contain ".exe" in a directory name. */
    public static String executablePath(String shortcutPath) {
        if (shortcutPath == null) return null;
        String path = shortcutPath.trim();
        if (path.startsWith("\"")) {
            int end = path.indexOf('"', 1);
            return end > 0 ? path.substring(1, end) : null;
        }
        String lower = path.toLowerCase(Locale.ENGLISH);
        int offset = 0;
        while (offset < lower.length()) {
            int exe = lower.indexOf(".exe", offset);
            if (exe < 0) break;
            int end = exe + 4;
            if (end == path.length() || path.charAt(end) == ' ' || path.charAt(end) == '\t') return path.substring(0, end);
            offset = end;
        }
        return path;
    }

    /** True when the folder is inside the app's own storage. */
    public static boolean isInternal(Context context, File file) {
        String files = context.getFilesDir().getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.equals(files) || path.startsWith(files + "/");
    }

    /** The place on internal storage that games are copied to: seen in Wine as Z:\home\games. */
    public static File gamesRoot(ImageFs imageFs) {
        return new File(imageFs.getRootDir(), "home/games");
    }

    /** {bytes, files} of everything under the folder (symbolic links are counted as files of no size). */
    public static long[] measure(File folder, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        long bytes = 0, files = 0;
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(folder);
        while (!stack.isEmpty()) {
            if (cancelled != null && cancelled.get()) break;
            File dir = stack.pop();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    files++;
                } else if (child.isDirectory()) {
                    stack.push(child);
                } else {
                    files++;
                    bytes += child.length();
                }
            }
        }
        return new long[]{bytes, files};
    }
}
