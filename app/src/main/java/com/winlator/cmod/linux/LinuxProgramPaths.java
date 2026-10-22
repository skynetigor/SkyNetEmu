package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;

/** Translates files already stored in a container's bound Linux home. */
public final class LinuxProgramPaths {
    private LinuxProgramPaths() {}

    /** Guest path, or null if the file resolves outside this home (including symlink escapes). */
    public static String inHome(File home, File file) {
        try {
            String root = home.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(root)) return "/root";
            if (path.startsWith(root + File.separator)) return "/root/" + path.substring(root.length() + 1);
        } catch (IOException ignored) {
            // An unresolved location must go through the normal import checks.
        }
        return null;
    }
}
