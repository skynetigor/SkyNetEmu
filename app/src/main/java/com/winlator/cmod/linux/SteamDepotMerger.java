package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Puts downloaded depot folders together into one game folder, and measures them. */
public final class SteamDepotMerger {
    public static final String PART_MARKER = ".skynet-part";

    private SteamDepotMerger() {
    }

    /**
     * Moves every file of {@code depot} into {@code target} (same file system, so a rename). A file that is already there is replaced:
     * later depots win, as Steam's own install does. The depot folder is removed afterwards.
     */
    public static void moveInto(File depot, File target) throws IOException {
        Path from = depot.toPath(), to = target.toPath();
        Files.createDirectories(to);
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destination = to.resolve(from.relativize(file).toString());
                try {
                    Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                catch (IOException e) {
                    // A different file system: copy, then remove.
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** The bytes in the files below a folder (0 when it does not exist yet). */
    public static long size(File folder) {
        if (!folder.exists()) return 0;
        final long[] total = {0};
        try {
            Files.walkFileTree(folder.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e) {
            // A folder that changes under the walk is measured as far as it got.
        }
        return total[0];
    }

    public static void deleteRecursively(File file) {
        if (!file.exists()) return;
        try {
            Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e) {
            // What could not be removed stays; the caller reports a failed cleanup only by the folder still being there.
        }
    }

    /** A folder name made of a game and a version: no path separators or characters a file system or the shell dislikes. */
    public static String folderName(String game, String version, String platformSuffix) {
        String name = (game + " " + version + (platformSuffix.isEmpty() ? "" : " (" + platformSuffix + ")"));
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (name.startsWith(".")) name = name.substring(1).trim();
        return name.isEmpty() ? "Game" : name;
    }

    /** {@code name}, or "name (2)", "name (3)"... when a folder of that name exists in {@code root}. */
    public static String freeFolderName(File root, String name) {
        if (!new File(root, name).exists()) return name;
        for (int i = 2; ; i++) {
            String candidate = name + " (" + i + ")";
            if (!new File(root, candidate).exists()) return candidate;
        }
    }
}
