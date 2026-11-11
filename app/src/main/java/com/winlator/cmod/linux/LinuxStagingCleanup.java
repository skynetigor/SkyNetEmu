package com.winlator.cmod.linux;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Older versions copied BeamNG's ELF files from shared storage into files/linux-executable-staging because FEX mapped them as executable,
 * which Android's noexec shared storage refuses. FEX no longer asks for that mapping and the game runs from its own folder, so the
 * copies (about 400 MB each) are only disk space; this removes them. Links are removed, never followed.
 */
public final class LinuxStagingCleanup {
    private static final String FOLDER = "linux-executable-staging";

    private LinuxStagingCleanup() {}

    public static void schedule(Context context) {
        File root = new File(context.getApplicationContext().getFilesDir(), FOLDER);
        if (!root.exists()) return;
        Thread worker = new Thread(() -> {
            try {
                reclaim(root);
            } catch (IOException error) {
                Log.w("LinuxStagingCleanup", "Cannot remove the old executable copies", error);
            }
        }, "staging-reclaim");
        worker.setDaemon(true);
        worker.start();
    }

    static void reclaim(File root) throws IOException {
        Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
