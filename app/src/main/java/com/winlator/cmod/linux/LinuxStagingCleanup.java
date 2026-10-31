package com.winlator.cmod.linux;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reclaims only app-owned executable copies, never the source game or its saves. */
public final class LinuxStagingCleanup {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinuxStagingCleanup() {}

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            try {
                LinuxExecutableStaging.prune(new File(app.getFilesDir(), "linux-executable-staging"),
                        new File(ImageFs.find(app).getRootDir(), "home"));
            } catch (IOException error) {
                Log.w("LinuxStagingCleanup", "Cannot reclaim unused executable copies", error);
            }
        });
    }
}
