package com.winlator.cmod.gameprofile;

import android.content.Context;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.linux.LinuxSteamGames;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Host regression tests with real profile detectors/path resolution and small Android boundary fixtures. */
public final class GameVersionsTest {
    private static final Context CONTEXT = new Context();

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected '" + expected + "', got '" + actual + "'");
    }

    private static GameProfile.Version version(String id, boolean fallback, GameProfile.Match match) {
        return new GameProfile.Version(id, id + ".x", "untested", "", "", fallback, match,
                new GameProfile.Settings(), new GameProfile.Settings(), new GameProfile.Settings(),
                Collections.emptyList(), null, Collections.emptyMap());
    }

    private static GameProfile profile(String id, List<GameProfile.Version> versions) throws Exception {
        Constructor<?> constructor = GameProfile.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (GameProfile) constructor.newInstance(id, "Test game", "42", "Test",
                Arrays.asList("Bin64/Game.exe"), Arrays.asList("BinLinux/Game"),
                new GameProfile.Settings(), new GameProfile.Settings(), new GameProfile.Settings(), versions);
    }

    private static void register(GameProfile profile) {
        GameProfileCatalog.profiles.clear();
        GameProfileCatalog.profiles.put(profile.id, profile);
    }

    private static void writeVersion(File root, String value) throws Exception {
        root.mkdirs();
        // Read the tail, not the large data before the version (as with BeamNG's integrity.json).
        Files.writeString(new File(root, "integrity.json").toPath(), " ".repeat(70000) + "\"version\":\"" + value + "\"");
    }

    public static void main(String[] args) throws Exception {
        File work = new File(args[0]);
        ImageFs.root = work;
        GameProfile.VersionFile vf = new GameProfile.VersionFile("integrity.json", 0, 256,
                Pattern.compile("\"version\":\\s*\"([0-9.]+)\""), "0.39", "0.40");
        GameProfile.Match fileMatch = new GameProfile.Match(null, null, false, Collections.emptyList(), vf);
        GameProfile profile = profile("test", Arrays.asList(version("0.39", true, fileMatch)));
        register(profile);

        Container linux = new Container(new File(work, "linux"), true);
        File game = new File(linux.getLinuxHomeDir(), "Games/Test");
        Shortcut nativeGame = new Shortcut(linux, "/root/Games/Test/BinLinux/Game");
        nativeGame.extras.put("gameProfile", "test");
        nativeGame.extras.put("gameProfileVersion", "0.39"); // Compatibility choice must not become the installed badge.
        nativeGame.extras.put("gameRoot", "/root/Games/Test");
        writeVersion(game, "0.39.4.0");
        equal("0.39.4.0", new ShortcutGameVersions(CONTEXT).label(nativeGame));
        writeVersion(game, "0.40.0.0");
        equal("0.40.0.0", new ShortcutGameVersions(CONTEXT).label(nativeGame));
        equal("0.39", nativeGame.getExtra("gameProfileVersion"));
        if (GameVersionMatcher.detect(profile, game, -1).version != profile.defaultVersion())
            throw new AssertionError("Launch fallback behavior changed");
        Files.delete(new File(game, "integrity.json").toPath());
        equal("", new ShortcutGameVersions(CONTEXT).label(nativeGame));
        writeVersion(game, "0.40.0.1");
        nativeGame.extras.remove("gameRoot");
        equal("0.40.0.1", new ShortcutGameVersions(CONTEXT).label(nativeGame));
        nativeGame.extras.remove("gameProfile");
        equal("0.40.0.1", new ShortcutGameVersions(CONTEXT).label(nativeGame));
        nativeGame.extras.put("gameProfile", "none");
        equal("", new ShortcutGameVersions(CONTEXT).label(nativeGame));
        nativeGame.extras.put("gameProfile", "unavailable");
        equal("", new ShortcutGameVersions(CONTEXT).label(nativeGame));

        Container wine = new Container(new File(work, "wine"), false);
        wine.drive = new File(work, "Shared games").getPath();
        File wineGame = new File(wine.drive, "Test");
        writeVersion(wineGame, "0.34.2.0");
        Shortcut windowsGame = new Shortcut(wine, "\"D:\\Test\\Bin64\\Game.exe\" -windowed");
        windowsGame.extras.put("gameProfile", "test");
        equal("0.34.2.0", new ShortcutGameVersions(CONTEXT).label(windowsGame));
        windowsGame.extras.clear();
        equal("0.34.2.0", new ShortcutGameVersions(CONTEXT).label(windowsGame));
        equal("", new ShortcutGameVersions(CONTEXT).label(new Shortcut(wine, "unknown-command")));

        GameProfile builds = profile("builds", Arrays.asList(
                version("1", true, new GameProfile.Match(100L, 200L, true, Collections.emptyList(), null)),
                version("2", false, new GameProfile.Match(200L, 300L, true, Collections.emptyList(), null))));
        register(builds);
        Shortcut steam = new Shortcut(linux, "steam-game:42");
        steam.extras.put("gameProfileVersion", "1");
        LinuxSteamGames.games.put("42", new LinuxSteamGames.Game(150, game));
        equal("1.x", new ShortcutGameVersions(CONTEXT).label(steam));
        LinuxSteamGames.games.put("42", new LinuxSteamGames.Game(220, game));
        equal("2.x", new ShortcutGameVersions(CONTEXT).label(steam));
        LinuxSteamGames.games.put("42", new LinuxSteamGames.Game(350, game));
        equal("", new ShortcutGameVersions(CONTEXT).label(steam));
        LinuxSteamGames.games.clear();
        equal("", new ShortcutGameVersions(CONTEXT).label(steam));

        File signature = new File(game, "signature.bin");
        Files.write(signature.toPath(), new byte[]{1, 2, 3});
        String hash = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
        GameProfile signed = profile("signed", Arrays.asList(version("3", true,
                new GameProfile.Match(null, null, false,
                        Arrays.asList(new GameProfile.FileCheck("signature.bin", 3, hash)), null))));
        equal("3.x", GameVersionMatcher.installedLabel(signed, game, -1));
        Files.write(signature.toPath(), new byte[]{1, 2, 4}); // Same size, different content.
        equal("", GameVersionMatcher.installedLabel(signed, game, -1));
        equal("", GameVersionMatcher.installedLabel(signed, null, -1));
        System.out.println("PASS: installed version refresh, Steam updates, Linux/Wine roots, profile inference, unknown/defaults, signatures");
    }
}
