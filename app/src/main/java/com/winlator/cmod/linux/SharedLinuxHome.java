package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** Non-destructive consolidation: old homes remain on disk; missing shared files are linked to them. */
public final class SharedLinuxHome {
    private SharedLinuxHome() {}

    public static void merge(File source, File target) throws IOException {
        File[] children = source.listFiles();
        if (children == null) throw new IOException("Cannot read " + source);
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create " + target);
        for (File child : children) {
            // Shortcuts need their settings snapshotted and paths translated separately. Never recurse into migration links.
            if (child.getName().equals("Desktop") || child.getName().equals("legacy-homes")) continue;
            File destination = new File(target, child.getName());
            if (!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(destination.toPath(), child.toPath().toAbsolutePath());
            } else if (child.isDirectory() && !Files.isSymbolicLink(child.toPath()) && destination.isDirectory()) {
                merge(child, destination);
            }
            // Conflicting files stay in the original home, accessible through .skynet/legacy-homes/<id>.
        }
    }
}
