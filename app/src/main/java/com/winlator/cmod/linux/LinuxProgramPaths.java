package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;

/** Resolves program locations across Android storage aliases and container home paths. */
public final class LinuxProgramPaths {
    private LinuxProgramPaths() {}

    /** Guest path, or null if the file resolves outside this home (including symlink escapes). */
    public static String inHome(File home, File file) {
        String relative = relativeTo(home, file);
        return relative == null ? null : relative.isEmpty() ? "/root" : "/root/" + relative;
    }

    public static boolean isWithin(File directory, File file) {
        return relativeTo(directory, file) != null;
    }

    private static String relativeTo(File directory, File file) {
        try {
            String root = directory.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(root)) return "";
            if (path.startsWith(root + File.separator)) return path.substring(root.length() + 1);
        } catch (IOException ignored) {
            // An unresolved location must go through the normal import checks.
        }
        return null;
    }
}
