package com.winlator.cmod.core;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.linux.UserDataLinks;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Applies a game profile's {@code userData} to a Windows launch. The directory is inside the Wine prefix, so the game
 * receives a DOS {@code -userpath}; settings and mods stay linked into the game folder, the same way a Linux launch does.
 */
public final class WindowsProfileLaunch {
    private WindowsProfileLaunch() {}

    /**
     * The profile's user-data argument and its DOS directory, or an empty list when the profile has none, the game folder
     * is unknown, or the command already carries the argument. A failure leaves the game on its default user folder.
     */
    public static List<String> userDataArguments(ImageFs imageFs, Container container, List<String> existing,
                                                 String profileId, File gameRoot, String argument, Map<String, String> links)
            throws IOException {
        if (argument == null || argument.isEmpty() || gameRoot == null || links == null || existing.contains(argument))
            return List.of();
        File userPath = userPath(container, profileId, gameRoot);
        UserDataLinks.make(userPath, gameRoot, gameRoot.getPath(), links);
        try {
            return List.of(argument, WindowsLaunchPlan.reverseHostPath(imageFs, container, userPath));
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static File userPath(Container container, String profileId, File gameRoot) {
        String name = gameRoot.getName().replaceAll("[^A-Za-z0-9._-]+", "_");
        String id = profileId == null || profileId.isEmpty() ? "game" : profileId;
        return new File(container.getRootDir(), ".wine/drive_c/users/" + ImageFs.USER + "/AppData/SkyNetEmu/userdata/"
                + id + "/" + name + "-" + hash(gameRoot.getAbsolutePath()));
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
