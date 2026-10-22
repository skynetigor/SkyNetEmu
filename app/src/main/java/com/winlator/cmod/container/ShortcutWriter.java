package com.winlator.cmod.container;

import android.content.Context;
import android.os.Environment;

import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.ExecutableKind;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WindowsExecutable;
import com.winlator.cmod.core.WindowsLaunchPlan;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Makes the .desktop file of a game in a container's Desktop folder, and what goes with it: the drive of an SD card the exe is on,
 * the icon taken out of the exe, a name for the game. The file manager's "Add this game" and the add-game flow both use it.
 */
public final class ShortcutWriter {
    private ShortcutWriter() {}

    /** The name to show for a game: the exe's name, or the folder's when the exe is called "game", "launcher", "x64" ... */
    public static String smartName(File file) {
        String filename = cleanName(file.getName());
        String lowerName = filename.toLowerCase(Locale.ENGLISH);
        List<String> genericNames = Arrays.asList(
                "game", "launcher", "setup", "installer", "start", "run",
                "speed", "update", "patch", "loader", "client", "app", "main", "boot", "play",
                "application", "shipping", "x64", "x86", "win64", "win32", "binaries");
        boolean generic = lowerName.contains("mod") || lowerName.contains("fix") || lowerName.contains("crack") || lowerName.contains("patch")
                || filename.length() < 4;
        if (!generic) {
            for (String name : genericNames) {
                if (lowerName.equals(name) || lowerName.startsWith(name + " ")) {
                    generic = true;
                    break;
                }
            }
        }
        if (generic) {
            File parent = file.getParentFile();
            if (parent != null) {
                String parentName = cleanName(parent.getName());
                List<String> genericFolders = Arrays.asList("bin", "bin32", "bin64", "system", "release", "retail", "win64");
                if (genericFolders.contains(parentName.toLowerCase(Locale.ENGLISH))) {
                    File grandParent = parent.getParentFile();
                    if (grandParent != null) return cleanName(grandParent.getName());
                }
                return parentName;
            }
        }
        return filename;
    }

    private static String cleanName(String filename) {
        String name = filename;
        int pos = name.lastIndexOf('.');
        if (pos > 0) name = name.substring(0, pos);
        name = name.replace("_", " ").replace(".", " ").replace("-", " ");
        name = name.replaceAll("(?i)\\b(v\\d+|repack|setup|installer|portable|goty|edition)\\b", "");
        name = name.replaceAll("[^a-zA-Z0-9 ]", "");
        name = name.replaceAll("\\s+", " ").trim();
        return name;
    }

    /** {@code base}, or "base (2)", "base (3)" ... when the container already has a shortcut by that name. */
    public static String freeName(Container container, String base) {
        File dir = container.getDesktopDir();
        if (!new File(dir, base + ".desktop").exists()) return base;
        int n = 2;
        while (new File(dir, base + " (" + n + ").desktop").exists()) n++;
        return base + " (" + n + ")";
    }

    /**
     * Writes the shortcut.
     * @param extras keys of the shortcut's [Extra Data] (nothing is written for a null value)
     * @param args the exe's arguments, stored as the shortcut's execArgs; may be null
     * @return the .desktop file, or null when it could not be written
     */
    public static File write(Context context, File exe, Container container, String displayName, Map<String, String> extras, List<String> args) {
        try {
            WindowsExecutable.Architecture architecture = ExecutableKind.windowsArchitecture(exe);
            Map<String, String> shortcutExtras = new LinkedHashMap<>();
            if (args != null && !args.isEmpty()) shortcutExtras.put("execArgs",
                    architecture == WindowsExecutable.Architecture.ARM64
                            ? WindowsLaunchPlan.serializeArguments(args) : String.join(" ", args));
            if (extras != null) shortcutExtras.putAll(extras);
            shortcutExtras.put(Shortcut.EXTRA_LIBRARY_ORIGIN, Shortcut.ORIGIN_MANUAL);
            if (architecture == WindowsExecutable.Architecture.ARM64
                    && exe.getName().equalsIgnoreCase("BeamNG.drive.arm64.exe")) {
                String execArgs = shortcutExtras.get("execArgs");
                String profile = shortcutExtras.get("gameProfile");
                if (execArgs == null || execArgs.isEmpty()) shortcutExtras.put("execArgs", "-gfx vk -nosteam");
                if (profile == null || profile.isEmpty()) shortcutExtras.put("gameProfile", "beamng-drive");
                EnvVars env = new EnvVars("WINEESYNC=0 WINEFSYNC=0 WINEDLLOVERRIDES=browseui=");
                env.putAll(shortcutExtras.get("envVars"));
                shortcutExtras.put("envVars", env.toString());
            }
            File dir = container.getDesktopDir();
            if (!dir.exists() && !dir.mkdirs()) return null;
            File desktop = new File(dir, displayName + ".desktop");
            StringBuilder text = new StringBuilder();
            text.append("[Desktop Entry]\n");
            text.append("Name=").append(displayName).append('\n');
            text.append("Exec=env WINEPREFIX=\"").append(wineHome(context, container)).append("/.wine\" wine \"").append(exe.getAbsolutePath()).append("\"\n");
            text.append("Type=Application\n");
            text.append("Icon=").append(displayName).append('\n');
            text.append("container_id:").append(container.id).append('\n');
            StringBuilder extra = new StringBuilder();
            for (Map.Entry<String, String> entry : shortcutExtras.entrySet()) {
                // Detection owns this cache: profile/import extras cannot mark an x64 executable as native ARM64.
                if (WindowsExecutable.SHORTCUT_ARCHITECTURE_KEY.equals(entry.getKey())) continue;
                if (entry.getValue() != null) extra.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            if (architecture != WindowsExecutable.Architecture.UNKNOWN) {
                extra.append(WindowsExecutable.SHORTCUT_ARCHITECTURE_KEY).append('=').append(architecture.id).append('\n');
            }
            if (extra.length() > 0) text.append("\n[Extra Data]\n").append(extra);
            if (!FileUtils.writeString(desktop, text.toString())) return null;
            saveIcon(exe, container, displayName);
            return desktop;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static void saveIcon(File exe, Container container, String displayName) {
        File iconDir = container.getIconsDir(64);
        if (!iconDir.exists()) iconDir.mkdirs();
        File iconFile = new File(iconDir, displayName + ".png");
        boolean extracted = ExeIconExtractor.extractIcon(exe, iconFile);
        File iconsDir = new File(Environment.getExternalStorageDirectory(), "Winlator/icons");
        if (!iconsDir.exists()) iconsDir.mkdirs();
        File userIcon = new File(iconsDir, displayName + ".png");
        if (extracted && !userIcon.exists()) {
            try {
                FileUtils.copy(iconFile, userIcon);
            }
            catch (RuntimeException ignored) {}
        }
        File cover = new File(new File(Environment.getExternalStorageDirectory(), "Winlator/covers"), displayName + ".png");
        if (cover.exists()) cover.delete();
    }

    /** The container's home as Wine sees it ("/home/xuser-3"). */
    public static String wineHome(Context context, Container container) {
        String imagefs = normalize(new File(context.getFilesDir(), "imagefs").getAbsolutePath());
        String root = normalize(container.getRootDir().getAbsolutePath());
        return root.startsWith(imagefs) ? root.substring(imagefs.length()) : "/home/" + ImageFs.USER;
    }

    /**
     * Makes sure the exe can be reached from the container: an exe on an SD card or USB drive needs a drive letter for it.
     * @return false when no letter is left
     */
    public static boolean ensureExternalStorageMapped(Container container, File file) {
        File volumeRoot = removableStorageRoot(file);
        if (volumeRoot == null) return true;

        String filePath = normalize(file.getAbsolutePath());
        String volumePath = normalize(volumeRoot.getAbsolutePath());
        String volumeName = volumeRoot.getName();
        boolean[] used = new boolean[26];

        for (String[] drive : container.drivesIterator()) {
            if (drive == null || drive.length < 2 || drive[0] == null || drive[1] == null) continue;
            String letter = drive[0].replace(":", "").trim().toUpperCase(Locale.ENGLISH);
            if (letter.length() == 1) {
                int index = letter.charAt(0) - 'A';
                if (index >= 0 && index < used.length) used[index] = true;
            }

            String mappedPath = normalize(drive[1]);
            if (!mappedPath.isEmpty() && (filePath.equals(mappedPath) || filePath.startsWith(mappedPath + File.separator))) {
                WineUtils.createDosdevicesSymlinks(container);
                return true;
            }

            String legacyRoot = normalize("/mnt/media_rw/" + volumeName);
            if (mappedPath.equals(legacyRoot) || mappedPath.startsWith(legacyRoot + File.separator)) {
                setDriveMapping(container, letter, volumePath);
                WineUtils.createDosdevicesSymlinks(container);
                return true;
            }
        }

        char driveLetter = 0;
        for (char candidate = 'E'; candidate <= 'Y'; candidate++) {
            if (!used[candidate - 'A']) {
                driveLetter = candidate;
                break;
            }
        }
        if (driveLetter == 0) return false;
        setDriveMapping(container, String.valueOf(driveLetter), volumePath);
        WineUtils.createDosdevicesSymlinks(container);
        return true;
    }

    private static File removableStorageRoot(File file) {
        if (file == null) return null;
        String path = normalize(file.getAbsolutePath());
        if (!path.startsWith("/storage/")) return null;
        String rest = path.substring("/storage/".length());
        int slash = rest.indexOf('/');
        String volume = slash >= 0 ? rest.substring(0, slash) : rest;
        if (volume.isEmpty() || volume.equals("emulated") || volume.equals("self")) return null;
        File root = new File("/storage/" + volume);
        return root.exists() && root.isDirectory() ? root : null;
    }

    private static void setDriveMapping(Container container, String letter, String path) {
        StringBuilder rebuilt = new StringBuilder();
        for (String[] drive : container.drivesIterator()) {
            if (drive == null || drive.length < 2 || drive[0] == null || drive[1] == null) continue;
            String existingLetter = drive[0].replace(":", "").trim().toUpperCase(Locale.ENGLISH);
            if (existingLetter.equals(letter)) continue;
            rebuilt.append(existingLetter).append(':').append(drive[1]);
        }
        rebuilt.append(letter).append(':').append(path);
        container.setDrives(rebuilt.toString());
        container.saveData();
    }

    private static String normalize(String path) {
        if (path == null) return "";
        try {
            return new File(path).getCanonicalPath();
        }
        catch (IOException e) {
            return new File(path).getAbsolutePath();
        }
    }
}
