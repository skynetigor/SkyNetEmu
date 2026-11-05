package com.winlator.cmod.linux;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.gameprofile.GameProfile;
import com.winlator.cmod.gameprofile.GameProfiles;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * A game profile's {@code userData}: the game is started with an argument (BeamNG's {@code -userpath <dir>}) that moves its user data to a
 * directory on the app's internal storage, in which the folders the profile lists are links to folders of the game's own folder (see
 * {@link UserDataLinks}). The settings and mods of a game folder are then kept in that folder, also when it is on shared storage, and the
 * rest (a browser's locks and caches) stays internal. Every user gets this; it is not a setting.
 */
final class LinuxUserData {
    private static final String TAG = "LinuxUserData";

    private LinuxUserData() {}

    /**
     * Makes the directory and adds the argument and the directory to the launch options, unless the profile has no user data, the game's folder
     * is not known, or the options (the user's own launch options) already carry the argument. When the links cannot be made the game keeps
     * its default user folder.
     */
    static void apply(Container container, GameProfiles.Effective profile, LinuxLaunchOptions options) {
        GameProfile.UserData data = profile == null ? null : profile.userData;
        if (data == null || profile.rootHost == null || options.args.contains(data.arg)) return;
        boolean proot = LinuxSession.prootEnabled(container.getExtra(LinuxSession.EXTRA_NO_PROOT));
        // Without PRoot the program sees the host's paths; under PRoot the container's.
        String targetBase = proot ? profile.rootGuest : profile.rootHost.getPath();
        if (targetBase == null) return;
        File userPath = userPath(container, profile);
        try {
            List<String> notes = UserDataLinks.make(userPath, profile.rootHost, targetBase, data.links);
            for (String note : notes) LinuxFail.w(TAG, note);
        } catch (IOException e) {
            LinuxFail.w(TAG, "Cannot make the user-data links of " + profile.profile.id + "; the game keeps its default user folder", e);
            return;
        }
        options.args.add(data.arg);
        options.args.add(proot ? LinuxProgramInstaller.guestPath(container, userPath) : userPath.getPath());
    }

    /** The user-data directory of this game folder: in the container's home, one per game and folder. */
    static File userPath(Container container, GameProfiles.Effective profile) {
        File root = profile.rootHost;
        String name = root.getName().replaceAll("[^A-Za-z0-9._-]+", "_");
        return new File(container.getLinuxHomeDir(), ".local/share/SkyNetEmu/userdata/" + profile.profile.id + "/" + name + "-" + hash(root.getAbsolutePath()));
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
