package com.winlator.cmod.gameprofile;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.GameFolder;
import com.winlator.cmod.linux.LinuxSession;
import com.winlator.cmod.linux.LinuxSteamGames;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A read-only library refresh. Profiles and manifests are shared within this pass, never between app opens. */
public final class ShortcutGameVersions {
    private final Context context;
    private final List<GameProfileCatalog.IndexEntry> index;
    private final Map<String, GameProfile> profiles = new HashMap<>();
    private final Map<Container, Map<String, LinuxSteamGames.Game>> steamGames = new HashMap<>();

    public ShortcutGameVersions(Context context) {
        this.context = context;
        index = GameProfileCatalog.index(context, false);
    }

    /** Empty means no version was detected. Does not change the shortcut's selected profile/version or settings. */
    public String label(Shortcut shortcut) {
        Container container = shortcut.container;
        if (container == null || GameProfiles.NONE.equals(shortcut.getExtra(GameProfiles.EXTRA_PROFILE))) return "";
        String id = shortcut.getExtra(GameProfiles.EXTRA_PROFILE);
        LinuxSteamGames.Game steam = null;
        if (shortcut.isSteamGame()) {
            steam = steamGames.computeIfAbsent(container, LinuxSteamGames::installed)
                    .get(LinuxSession.steamAppId(shortcut.path));
            if (steam == null) return "";
            if (id.isEmpty()) {
                for (GameProfileCatalog.IndexEntry entry : index) {
                    if (entry.steamAppId.equals(steam.appId)) { id = entry.id; break; }
                }
            }
        }

        GameProfile.Platform platform = container.isLinux() ? GameProfile.Platform.LINUX : GameProfile.Platform.WINDOWS;
        String executable = null;
        if (steam == null) {
            if (container.isLinux()) {
                File host = shortcut.path.startsWith("/") ? LinuxSession.hostFile(context, container, shortcut.path) : null;
                if (host != null) executable = host.getPath();
            } else {
                executable = GameFolder.unixExePath(ImageFs.find(context), container, shortcut.path);
            }
        }

        GameProfile profile = profile(id);
        // Older shortcuts may not have a profile extra. Recognise only the executable paths declared by a profile.
        if (id.isEmpty() && executable != null) {
            for (GameProfileCatalog.IndexEntry entry : index) {
                GameProfile candidate = profile(entry.id);
                if (candidate != null && folderOf(candidate, platform, executable) != null) { profile = candidate; break; }
            }
        }
        if (profile == null) return "";

        File root;
        if (steam != null) {
            root = LinuxSteamGames.installFolder(container, steam);
        } else {
            String ownRoot = shortcut.getExtra(GameProfiles.EXTRA_ROOT);
            root = container.isLinux() && ownRoot.startsWith("/") ? LinuxSession.hostFile(context, container, ownRoot)
                    : folderOf(profile, platform, executable);
        }
        return GameVersionMatcher.installedLabel(profile, root, steam == null ? -1 : steam.buildId);
    }

    private GameProfile profile(String id) {
        if (id.isEmpty()) return null;
        if (!profiles.containsKey(id)) profiles.put(id, GameProfileCatalog.game(context, id, false));
        return profiles.get(id);
    }

    private static File folderOf(GameProfile profile, GameProfile.Platform platform, String executable) {
        if (executable == null) return null;
        String path = executable.replace('\\', '/');
        return GameMatcher.folderOf(profile.executables(platform), path, path.toLowerCase(Locale.ROOT));
    }
}
