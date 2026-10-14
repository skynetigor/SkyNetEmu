package com.winlator.cmod.gameprofile;

import android.content.Context;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Recognises a game from the exe the user picked: an exe whose path ends with one of a game's {@code windows.executables}
 * ("Bin64/BeamNG.drive.x64.exe", compared without regard to case) is that game, the rest of the path is its folder, and the
 * folder says which version it is ({@link GameVersionMatcher}).
 *
 * Blocks (the game files may be fetched): call it off the main thread.
 */
public final class GameMatcher {
    public static final class Result {
        public final GameProfile game;
        /** The recognised version, or the game's default; null when the game lists no default and none was recognised. */
        public final GameProfile.Version version;
        /** How the version was recognised, or "default". */
        public final String how;
        public final File folder;
        public final GameProfile.Platform platform;

        Result(GameProfile game, GameProfile.Version version, String how, File folder, GameProfile.Platform platform) {
            this.platform = platform;
            this.game = game;
            this.version = version;
            this.how = how;
            this.folder = folder;
        }
    }

    private GameMatcher() {}

    /** @return null when no game of the registry has this exe */
    public static Result match(Context context, String exePath, boolean allowNetwork) {
        return match(context, exePath, GameProfile.Platform.WINDOWS, allowNetwork);
    }

    /** @return null when no game of the registry has this program on the platform */
    public static Result match(Context context, String exePath, GameProfile.Platform platform, boolean allowNetwork) {
        if (exePath == null) return null;
        String path = exePath.replace('\\', '/');
        String lower = path.toLowerCase(Locale.ROOT);
        for (GameProfileCatalog.IndexEntry entry : GameProfileCatalog.index(context, allowNetwork)) {
            GameProfile game = GameProfileCatalog.game(context, entry.id, allowNetwork);
            if (game == null) continue;
            File folder = folderOf(game.executables(platform), path, lower);
            if (folder == null) continue;
            GameVersionMatcher.Match version = GameVersionMatcher.detect(game, folder, -1);
            return new Result(game, version == null ? null : version.version, version == null ? "" : version.how, folder, platform);
        }
        return null;
    }

    /** The folder of the game when the path ends with one of the executables, otherwise null. */
    static File folderOf(List<String> executables, String path, String lowerPath) {
        for (String exe : executables) {
            String suffix = "/" + exe.toLowerCase(Locale.ROOT);
            if (lowerPath.endsWith(suffix)) return new File(path.substring(0, path.length() - suffix.length()));
        }
        return null;
    }
}
