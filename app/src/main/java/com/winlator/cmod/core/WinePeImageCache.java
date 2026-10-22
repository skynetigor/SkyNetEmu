package com.winlator.cmod.core;

import android.content.Context;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.nio.file.Files;

/** Shared backing for native Wine's unaligned, read-only PE image sections. */
public final class WinePeImageCache {
    public static final String ENABLED = "SKY_PE_IMAGE_CACHE";
    public static final String DIRECTORY = "SKY_PE_IMAGE_CACHE_DIR";
    private WinePeImageCache() {}

    public static void prepare(Context context, EnvVars env, boolean nativeArm64) {
        configure(env, nativeArm64,
                new File(context.getApplicationInfo().nativeLibraryDir, "libwine_pe_cache.so"),
                new File(context.getCacheDir(), "wine-pe-images-v1"));
    }

    static void configure(EnvVars env, boolean nativeArm64, File library, File directory) {
        if (!nativeArm64 || (env.has(ENABLED) && !"1".equals(env.get(ENABLED)))) return;
        try {
            if (!library.isFile() || Files.isSymbolicLink(directory.toPath())
                    || (!directory.isDirectory() && !directory.mkdirs())) return;
            Os.chmod(directory.getAbsolutePath(), 0700);
            String path = library.getAbsolutePath();
            String preload = env.get("LD_PRELOAD");
            boolean present = false;
            for (String item : preload.split("[\\s:]+")) if (path.equals(item)) present = true;
            env.put("LD_PRELOAD", present ? preload : preload.isEmpty() ? path : preload + ":" + path);
            env.put(DIRECTORY, directory.getAbsolutePath());
            env.put(ENABLED, "1");
        } catch (Exception e) {
            // Optional optimization: a cache/permission problem must never prevent a game launch.
            Log.w("WinePeImageCache", "Shared PE backing unavailable", e);
        }
    }
}
