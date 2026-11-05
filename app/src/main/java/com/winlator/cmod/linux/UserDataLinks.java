package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Makes a game's user-data directory: a real directory (on storage that can hold symbolic links) in which some folders are symbolic
 * links to folders of the game's own folder. The links are what keeps a game's settings and mods with the game even when the game
 * folder is on shared storage, which cannot hold the symlinks some programs create (a browser's profile lock) but is followed
 * through when the link itself is elsewhere. No Android classes, so that it is tested on the host.
 */
final class UserDataLinks {
    private UserDataLinks() {}

    /**
     * @param userPath   the user-data directory, made if it is not there
     * @param gameRoot   the game's folder as this process sees it; the targets are made in it if they are not there
     * @param targetBase the game's folder as the program sees it (the same path, or another one under PRoot); what the links name
     * @param links      link name (relative to {@code userPath}) to target (relative to the game's folder)
     * @return what was left alone, one sentence each: a link name that is already a real folder or file is not touched
     */
    static List<String> make(File userPath, File gameRoot, String targetBase, Map<String, String> links) throws IOException {
        List<String> notes = new ArrayList<>();
        Path base = userPath.toPath();
        Files.createDirectories(base);
        for (Map.Entry<String, String> entry : links.entrySet()) {
            Path link = base.resolve(entry.getKey()).normalize();
            if (!link.startsWith(base) || link.equals(base)) throw new IOException("A link outside the user-data directory: " + entry.getKey());
            Files.createDirectories(new File(gameRoot, entry.getValue()).toPath());
            Path target = new File(targetBase, entry.getValue()).toPath();
            Files.createDirectories(link.getParent());
            if (Files.isSymbolicLink(link)) {
                if (Files.readSymbolicLink(link).equals(target)) continue;
                // One of ours that points somewhere else: the game folder moved.
                Files.delete(link);
            } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                notes.add(link + " is not a link (the game made it before the links existed): it stays where it is, apart from the game folder.");
                continue;
            }
            Files.createSymbolicLink(link, target);
        }
        return notes;
    }
}
