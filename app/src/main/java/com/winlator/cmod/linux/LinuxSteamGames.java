package com.winlator.cmod.linux;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.gameprofile.GameProfileCatalog;
import com.winlator.cmod.gameprofile.GameProfiles;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The games installed in the Steam client of a Linux container, shown as shortcuts of the container: one for each game
 * Steam has installed (its steamapps/appmanifest_*.acf), started through the client ({@link LinuxSession#STEAM_GAME_PREFIX}).
 * They only mirror Steam: a game uninstalled there loses its shortcut here at the next sync, and the user cannot remove
 * one from Winlator. What the user sets on one (resolution, scaling, upscaler, frame generation) is kept in its file.
 */
public final class LinuxSteamGames {
    private static final String TAG = "LinuxSteamGames";
    private static final Pattern PAIR = Pattern.compile("\"([^\"]+)\"\\s+\"([^\"]*)\"");
    /** Steam's own tools and runtimes, which have manifests like games do. */
    private static final Set<String> TOOL_APPS = new HashSet<>(java.util.Arrays.asList(
            "228980",   // Steamworks Common Redistributables
            "1070560",  // Steam Linux Runtime 1.0 (scout)
            "1391110",  // Steam Linux Runtime 2.0 (soldier)
            "1628350",  // Steam Linux Runtime 3.0 (sniper)
            "1493710",  // Proton Experimental
            "3127680"   // FEX (the x86 emulator the arm64 client downloads)
    ));

    private LinuxSteamGames() {}

    public static final class Game {
        public final String appId;
        public final String name;
        /** The folder under steamapps/common the game is installed in; empty when the manifest has none. */
        public final String installDir;
        /** Steam's build id of the installed version (it only grows); -1 when the manifest has none. */
        public final long buildId;
        /** The library the game is in, as the host sees it: the container's own Steam folder or the shared library. */
        public final File library;
        /** Where that library is for the guest (the shared library is at the same path on both sides). */
        public final String libraryGuest;

        Game(String appId, String name, String installDir, long buildId, File library, String libraryGuest) {
            this.appId = appId;
            this.name = name;
            this.installDir = installDir;
            this.buildId = buildId;
            this.library = library;
            this.libraryGuest = libraryGuest;
        }

        /** The folder steamapps/common of the game's library, as the guest sees it, with a closing slash. */
        public String commonGuest() {
            return libraryGuest + "/steamapps/common/";
        }
    }

    /** Legacy shared library, retained for discovering games installed by older app versions. */
    public static File sharedLibrary(Container container) {
        return new File(container.getRootDir().getParentFile(), "steam-library");
    }

    private static final String OWN_LIBRARY_GUEST = "/root/.local/share/Steam";

    public static File steamRoot(Container container) {
        return new File(container.getLinuxHomeDir(), ".local/share/Steam");
    }

    /** The games Steam has installed, by app id. */
    public static Map<String, Game> installed(Container container) {
        Map<String, Game> games = new HashMap<>();
        // The container's own library first: a game that is in both stays the one Steam installed there.
        readLibrary(games, steamRoot(container), OWN_LIBRARY_GUEST);
        File shared = sharedLibrary(container);
        readLibrary(games, shared, shared.getPath());
        return games;
    }

    private static void readLibrary(Map<String, Game> games, File library, String libraryGuest) {
        File[] manifests = new File(library, "steamapps").listFiles((dir, name) ->
                name.startsWith("appmanifest_") && name.endsWith(".acf"));
        if (manifests == null) return;
        for (File manifest : manifests) {
            String text = FileUtils.readString(manifest);
            if (text == null) continue;
            String appId = null, name = null, installDir = "";
            long buildId = -1;
            int flags = 0;
            Matcher matcher = PAIR.matcher(text);
            while (matcher.find()) {
                switch (matcher.group(1).toLowerCase()) {
                    case "appid": if (appId == null) appId = matcher.group(2); break;
                    case "name": if (name == null) name = matcher.group(2); break;
                    case "installdir": if (installDir.isEmpty()) installDir = matcher.group(2).trim(); break;
                    case "buildid":
                        try { if (buildId < 0) buildId = Long.parseLong(matcher.group(2).trim()); } catch (NumberFormatException ignored) {}
                        break;
                    case "stateflags":
                        try { flags = Integer.parseInt(matcher.group(2)); } catch (NumberFormatException ignored) {}
                        break;
                }
            }
            // StateFlags bit 4: fully installed. A game being downloaded or updated for the first time is not a shortcut yet.
            if (appId == null || name == null || name.trim().isEmpty() || !appId.matches("\\d+")) continue;
            if ((flags & 4) == 0 || TOOL_APPS.contains(appId)) continue;
            String lower = name.toLowerCase();
            if (lower.equals("fex") || lower.equals("fex-emu") || lower.startsWith("proton") || lower.startsWith("steam linux runtime") || lower.startsWith("steamworks")) continue;
            if (!games.containsKey(appId)) games.put(appId, new Game(appId, name.trim(), installDir, buildId, library, libraryGuest));
        }
    }

    /** Stops registering the extra library; remove its old entry only when no content would be hidden. */
    public static void removeEmptySharedLibrary(Container container) {
        File shared = sharedLibrary(container);
        File list = new File(steamRoot(container), "steamapps/libraryfolders.vdf");
        if (!list.isFile()) return;
        if (!emptyLibraryDirectory(shared)) {
            Log.i(TAG, "Keeping the legacy Steam library because it contains content or cannot be inspected");
            return;
        }
        String text = FileUtils.readString(list);
        if (text == null) {
            Log.w(TAG, "Cannot read Steam library registrations");
            return;
        }
        try {
            String updated = withoutSharedLibrary(text, shared.getPath());
            if (!text.equals(updated)) FileUtils.writeString(list, updated);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Cannot remove the empty legacy Steam library from malformed registrations", e);
        }
    }

    private static boolean emptyLibraryDirectory(File directory) {
        if (java.nio.file.Files.isSymbolicLink(directory.toPath())) return false;
        if (!directory.exists()) return true;
        File[] children = directory.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) return false;
            if (child.isDirectory()) {
                if (!emptyLibraryDirectory(child)) return false;
            } else if (!child.getName().equals("libraryfolder.vdf")
                    || !child.getParentFile().equals(directory)) return false;
        }
        return true;
    }

    static String withoutSharedLibrary(String text, String path) {
        Matcher tokens = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|[{}]|//[^\\r\\n]*").matcher(text);
        int depth = 0, entryStart = -1;
        boolean matches = false;
        String previous = "";
        StringBuilder result = new StringBuilder();
        int copied = 0;
        while (tokens.find()) {
            String token = tokens.group();
            if (token.startsWith("//")) continue;
            if (token.equals("{")) {
                depth++;
                if (depth == 2) matches = false;
            } else if (token.equals("}")) {
                if (depth == 2 && matches && entryStart >= copied) {
                    result.append(text, copied, entryStart);
                    copied = tokens.end();
                }
                if (--depth < 0) throw new IllegalArgumentException("Unbalanced Steam library registrations");
            } else {
                if (depth == 1) entryStart = tokens.start();
                if (depth == 2 && previous.equals("\"path\"") && token.equals("\"" + path + "\"")) matches = true;
            }
            previous = token;
        }
        if (depth != 0) throw new IllegalArgumentException("Unbalanced Steam library registrations");
        return result.append(text, copied, text.length()).toString();
    }

    public static int count(Container container) {
        return installed(container).size();
    }

    /** One installed game, or null when Steam has none by this app id. */
    public static Game game(Container container, String appId) {
        return installed(container).get(appId);
    }

    /** The folder a game is installed in on the host (null when the manifest names none). */
    public static File installFolder(Container container, Game game) {
        if (game == null || game.installDir.isEmpty() || game.installDir.contains("/") || game.installDir.contains("..")) return null;
        return new File(game.library, "steamapps/common/" + game.installDir);
    }

    /**
     * Makes the container's Steam game shortcuts match what Steam has installed: a shortcut is added for each game that has
     * none and removed when its game is gone. A shortcut that is already there is left as it is (its settings are kept).
     */
    public static void sync(Context context, Container container) {
        if (container == null || !container.isLinux()) return;
        File desktop = container.getDesktopDir();
        File[] files = desktop.listFiles((dir, name) -> name.endsWith(".desktop"));
        Map<String, Game> games = installed(container);
        Set<String> present = new HashSet<>();
        Set<String> takenNames = new HashSet<>();
        if (files != null) {
            for (File file : files) {
                takenNames.add(file.getName());
                String exec = execOf(file);
                if (exec == null || !exec.startsWith(LinuxSession.STEAM_GAME_PREFIX)) continue;
                String appId = exec.substring(LinuxSession.STEAM_GAME_PREFIX.length());
                if (games.containsKey(appId)) present.add(appId);
                else if (file.delete()) Log.d(TAG, "Removed the shortcut of the uninstalled game " + appId);
            }
        }
        for (Game game : games.values()) {
            if (present.contains(game.appId)) {
                applyDefaults(context, container, game, ContainerManager.linuxShortcutFile(container, game.name));
                continue;
            }
            String name = game.name;
            if (takenNames.contains(ContainerManager.linuxShortcutFile(container, name).getName())) name = game.name + " (" + game.appId + ")";
            File file = ContainerManager.addLinuxShortcut(context, container, name, LinuxSession.STEAM_GAME_PREFIX + game.appId);
            takenNames.add(file.getName());
            applyDefaults(context, container, game, file);
        }
    }

    private static String execOf(File file) {
        for (String line : FileUtils.readLines(file)) {
            if (line.startsWith("Exec=")) return line.substring(5).trim();
        }
        return null;
    }

    /**
     * What a shortcut gets once, the first time it is looked at: Steam's art for the game (kept in its library cache), and the
     * game's registry profile when the registry has one for the app (the shortcut then follows the profile's launch defaults;
     * the user can take it off in the shortcut's settings, which writes "none" so it is not added again).
     */
    private static void applyDefaults(Context context, Container container, Game game, File shortcutFile) {
        if (!shortcutFile.isFile()) return;
        try {
            Shortcut shortcut = new Shortcut(container, shortcutFile);
            boolean changed = false;
            if (shortcut.getExtra("customCoverArtPath").isEmpty()) {
                File art = findArt(new File(steamRoot(container), "appcache/librarycache/" + game.appId));
                if (art != null) {
                    shortcut.putExtra("customCoverArtPath", art.getPath());
                    changed = true;
                }
            }
            if (shortcut.getExtra(GameProfiles.EXTRA_PROFILE).isEmpty()) {
                GameProfileCatalog.IndexEntry entry = GameProfileCatalog.bySteamAppId(context, game.appId);
                if (entry != null) {
                    shortcut.putExtra(GameProfiles.EXTRA_PROFILE, entry.id);
                    changed = true;
                }
            }
            if (changed) {
                shortcut.saveData();
                // The profile was found only now: what it recommends is written into the shortcut, as at creation.
                LinuxShortcutDefaults.applyProfile(context, container, shortcut);
            }
        }
        catch (RuntimeException e) {
            LinuxFail.w(TAG, "Cannot set up the shortcut of " + game.name, e);
        }
    }

    private static File findArt(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return null;
        for (String wanted : new String[]{"library_600x900.jpg", "library_capsule.jpg", "header.jpg", "library_header.jpg"}) {
            File found = find(entries, wanted, 3);
            if (found != null) return found;
        }
        return null;
    }

    private static File find(File[] entries, String name, int depth) {
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().equals(name)) return entry;
        }
        if (depth == 0) return null;
        for (File entry : entries) {
            File[] children = entry.isDirectory() ? entry.listFiles() : null;
            if (children == null) continue;
            File found = find(children, name, depth - 1);
            if (found != null) return found;
        }
        return null;
    }
}
