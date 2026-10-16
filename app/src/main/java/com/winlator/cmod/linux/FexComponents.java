package com.winlator.cmod.linux;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * What FEX needs to run a program: a FEX build and an x86-64 root filesystem (it runs the program inside that userland).
 * Both are downloaded from the catalog, so "FEX works" means both are installed.
 */
public final class FexComponents {
    private FexComponents() {}

    public static boolean fexInstalled(Context context) {
        return !LinuxEmulator.list(context, LinuxEmulator.FEX).isEmpty();
    }

    public static boolean rootfsInstalled(Context context) {
        return !LinuxRootfs.list(context).isEmpty();
    }

    /** Everything FEX needs is installed (cheap: looks at the disk only). */
    public static boolean complete(Context context) {
        return fexInstalled(context) && rootfsInstalled(context);
    }

    /**
     * The packages still to be downloaded for FEX to work: empty when everything is installed, null when the catalog cannot be
     * read. Blocking (reads the catalog): call it off the main thread.
     * @param preferredFex the id of the FEX build to take, or the first one of the catalog when it has none by this id
     * @param preferredRootfs likewise for the root filesystem
     */
    public static List<LinuxRuntimeCatalog.Entry> missing(Context context, String preferredFex, String preferredRootfs) {
        List<LinuxRuntimeCatalog.Entry> result = new ArrayList<>();
        if (!fexInstalled(context)) {
            List<LinuxRuntimeCatalog.Entry> builds = LinuxRuntimeCatalog.fetchEmulators();
            LinuxRuntimeCatalog.Entry entry = pick(only(builds, LinuxEmulator.FEX), preferredFex);
            if (entry == null) return null;
            result.add(entry);
        }
        if (!rootfsInstalled(context)) {
            LinuxRuntimeCatalog.Entry entry = pick(LinuxRuntimeCatalog.fetchRootfs(), preferredRootfs);
            if (entry == null) return null;
            result.add(entry);
        }
        return result;
    }

    /** The FEX build whose 32-bit thunks ship in the app (assets/linux/fex-thunks32-<version>). */
    private static final String THUNKS32_VERSION = "2610";

    /**
     * Gives a FEX build the 32-bit OpenGL thunks that a 32-bit game needs to draw on the GPU (without them it falls back to the
     * root filesystem's software OpenGL). The package of FEX {@value #THUNKS32_VERSION} that the catalog serves has none; the
     * app carries them, built from the same FEX release (tools/linux-fex/package-fex.sh), and puts the files that are missing
     * in place. Does nothing for another FEX version (the thunks must come from the same build), and for a build that has them
     * (a package that includes them is left as it is). Cheap when there is nothing to do: call it before every start.
     */
    public static void ensureThunks32(Context context, LinuxEmulator.Installed fex) {
        if (fex == null || !LinuxEmulator.FEX.equals(fex.emulator) || !THUNKS32_VERSION.equals(fex.version)) return;
        try {
            copyMissing(context.getAssets(), "linux/fex-thunks32-" + THUNKS32_VERSION, fex.dir);
        }
        catch (java.io.IOException e) {
            android.util.Log.w("FexComponents", "Cannot add the 32-bit thunks to " + fex.id, e);
        }
    }

    private static void copyMissing(android.content.res.AssetManager assets, String assetPath, java.io.File target) throws java.io.IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            // A file (an asset folder lists its children, a file lists none).
            if (target.isFile()) return;
            java.io.File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            java.io.File temp = new java.io.File(target.getPath() + ".tmp");
            try (java.io.InputStream in = assets.open(assetPath); java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                in.transferTo(out);
            }
            com.winlator.cmod.core.FileUtils.chmod(temp, 0755);
            if (!temp.renameTo(target)) throw new java.io.IOException("cannot create " + target);
            return;
        }
        for (String child : children) copyMissing(assets, assetPath + "/" + child, new java.io.File(target, child));
    }

    private static List<LinuxRuntimeCatalog.Entry> only(List<LinuxRuntimeCatalog.Entry> entries, String emulator) {
        List<LinuxRuntimeCatalog.Entry> result = new ArrayList<>();
        if (entries == null) return result;
        for (LinuxRuntimeCatalog.Entry entry : entries) if (emulator.equals(entry.emulator)) result.add(entry);
        return result;
    }

    private static LinuxRuntimeCatalog.Entry pick(List<LinuxRuntimeCatalog.Entry> entries, String preferred) {
        if (entries == null || entries.isEmpty()) return null;
        for (LinuxRuntimeCatalog.Entry entry : entries) if (entry.id.equals(preferred)) return entry;
        return entries.get(0);
    }
}
