package com.winlator.cmod.linux;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.HashMap;
import java.util.List;

/**
 * A Linux x86-64 program run the way Wine is: by a Box64 built for Android (bionic), started straight from the app, with no proot and no glibc
 * runtime. The program's own x86-64 libraries come from the x86-64 root filesystem (and the Box64 package's), Box64 maps the C library calls
 * of the program onto Android's (the build in assets/linux/box64-bionic knows glibc's names: __errno_location, the ctype tables, ...), and the
 * display, the audio, the GPU (the Wine wrapper ICD on an adrenotools driver) and SysV shared memory are the ones of the imagefs.
 *
 * Experimental: for a program started from a shortcut (not the Steam client); see docs/linux-bionic.md.
 */
public final class LinuxBionicSession {
    private static final String TAG = "LinuxBionicSession";

    /** Container extra: "1" runs the container's x86-64 programs through the Android Box64. */
    public static final String EXTRA = "linuxBionic";

    private LinuxBionicSession() {}

    public static boolean enabled(Container container) {
        // An explicit proot choice takes precedence over an old experimental bionic setting.
        return "1".equals(container.getExtra(EXTRA))
                && !LinuxSession.prootEnabled(container.getExtra(LinuxSession.EXTRA_NO_PROOT));
    }

    /**
     * @param guestCommand the command {@link LinuxSession#directCommand} made: ..., "bannerlator-run", program, emulator, N, M, N variables, M wrapper words, arguments
     * @param guestEnv     what the glibc session would have given the program (KEY=value)
     * @return null when this session cannot run the command (it then runs the usual way)
     */
    static LinuxSession.Launch build(Context context, ImageFs imageFs, Container container, String[] guestCommand, List<String> guestEnv, File home) {
        int at = -1;
        for (int i = 0; i < guestCommand.length; i++) if ("bannerlator-run".equals(guestCommand[i])) at = i;
        if (at < 0 || at + 4 >= guestCommand.length) return null;
        String program = guestCommand[at + 1];
        int nenv, nwrap;
        try {
            nenv = Integer.parseInt(guestCommand[at + 3]);
            nwrap = Integer.parseInt(guestCommand[at + 4]);
        }
        catch (NumberFormatException e) {
            return null;
        }
        int first = at + 5;
        if (first + nenv + nwrap > guestCommand.length) return null;
        File hostProgram = LinuxSession.hostFile(context, container, program);
        if (hostProgram == null || LinuxSession.elfMachine(hostProgram) != 62) return null;

        File box64 = LinuxSession.stageAsset(context, "linux-bionic", "box64-bionic");
        if (box64 == null) return null;
        File parent = LinuxSession.stageAsset(context, "linux-bionic", "bionic-launch-parent");
        if (parent == null) {
            LinuxFail.w(TAG, "Android Box64 launch parent is unavailable");
            return null;
        }
        String homePath = home.getPath();
        EnvVars env = nativeEnvironment(context, imageFs, container, home);

        // What the glibc session decided for the program: Box64's settings, the shims it preloads, the game's own variables.
        String libs = "";
        for (String entry : guestEnv) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String key = entry.substring(0, eq);
            String value = entry.substring(eq + 1).replace("/root", homePath);
            if (key.equals("BOX64_LD_LIBRARY_PATH")) libs = value;
            else if (key.equals("BOX64_LD_PRELOAD")) {
                // libskyshim_x64 (gamepad, execveat, robust lists) is built for the glibc Box64 and brings the Android one down; the others are plain.
                StringBuilder kept = new StringBuilder();
                for (String lib : value.split(":")) {
                    if (lib.isEmpty() || lib.endsWith("libskyshim_x64.so")) continue;
                    if (kept.length() > 0) kept.append(':');
                    kept.append(lib);
                }
                env.put(key, kept.toString());
            }
            else if (key.startsWith("BOX64_") || key.startsWith("SKY_") && !key.startsWith("SKY_FEX") && !key.equals("SKY_X86_EMULATOR") && !key.equals("SKY_X86_32_EMULATOR")
                    && !key.equals("SKY_ROOT") && !key.equals("SKY_PATHMAP") && !key.equals("SKY_LIBPATH") && !key.equals("SKY_LOADER") && !key.equals("SKY_SELF")
                    || key.equals("TZ") || key.startsWith("SDL_") || key.startsWith("GDK_") || key.startsWith("QT_QPA")
                    || key.startsWith("TU_") || key.startsWith("MESA_")) {
                env.put(key, value);
            }
        }
        java.util.Map<String,String> memoryEnv=new java.util.LinkedHashMap<>();
        for (String pair:env.toStringArray()) {
            int eq=pair.indexOf('=');if(eq>0)memoryEnv.put(pair.substring(0,eq),pair.substring(eq+1));
        }
        LinuxGpuMemory.prepare(context,container,memoryEnv,true,true);
        for (String key:new String[]{"VK_INSTANCE_LAYERS","VK_LAYER_PATH","VK_ADD_LAYER_PATH"})
            if (!memoryEnv.containsKey(key)) env.remove(key);
        for (java.util.Map.Entry<String,String> pair:memoryEnv.entrySet()) env.put(pair.getKey(),pair.getValue());
        LinuxRootfs.Installed x86 = LinuxRootfs.resolve(context, container);
        if (x86 == null) {
            java.util.List<LinuxRootfs.Installed> all = LinuxRootfs.list(context);
            if (!all.isEmpty()) x86 = all.get(0);
        }
        // An embedded Chromium's gtk and epoxy look for OpenGL: the root filesystem's Mesa (a software renderer on LLVM) cannot work on Android, so a library saying
        // "no OpenGL" (tools/linux-shim/skynogl.c) stands in under these names, ahead of the root filesystem's.
        File noGl = new File(new File(context.getFilesDir(), "linux-bionic"), "nogl");
        File noGlLib = LinuxSession.stageAsset(context, "linux-bionic", "libskynogl_x64.so");
        if (noGlLib != null) {
            noGl.mkdirs();
            for (String name : new String[]{"libGL.so.1", "libGLX.so.0", "libGLX.so.1", "libEGL.so.1", "libGLESv2.so.2", "libGLdispatch.so.0", "libOpenGL.so.0", "libGLX_indirect.so.0"}) {
                File link = new File(noGl, name);
                if (link.exists()) continue;
                try {
                    android.system.Os.symlink(noGlLib.getPath(), link.getPath());
                }
                catch (android.system.ErrnoException e) {
                    LinuxFail.w(TAG, "Cannot link " + link + ": " + e.getMessage());
                }
            }
        }
        StringBuilder libPath = new StringBuilder(noGl.getPath());
        if (!libs.isEmpty()) libPath.append(':').append(libs);
        if (x86 != null) libPath.append(libPath.length() > 0 ? ":" : "").append(filteredLibs(context, new File(x86.dir, "usr/lib/x86_64-linux-gnu")).getPath());
        File programDir = hostProgram.getParentFile();
        libPath.append(libPath.length() > 0 ? ":" : "").append(programDir.getPath());
        env.put("BOX64_LD_LIBRARY_PATH", libPath.toString());
        env.put("BOX64_ALLOWMISSINGLIBS", "1");
        env.put("BOX64_NOBANNER", "1");
        // The translated code of the glibc Box64's runs is not this one's: each has a folder of its own.
        File dynaCache = new File(new File(context.getFilesDir(), "linux-bionic"), "dynacache");
        dynaCache.mkdirs();
        env.put("BOX64_DYNACACHE_FOLDER", dynaCache.getPath());
        // Android has none of these (Box64 would take the names for native libraries and leave an x86-64 libgtk without its glib): the ones of the root filesystem are run.
        env.put("BOX64_EMULATED_LIBS", "libglib-2.0.so.0:libgobject-2.0.so.0:libgio-2.0.so.0:libgmodule-2.0.so.0:libgthread-2.0.so.0:libpango-1.0.so.0:libpangocairo-1.0.so.0"
                + ":libpangoft2-1.0.so.0:libcairo.so.2:libcairo-gobject.so.2:libgdk_pixbuf-2.0.so.0:libgtk-3.so.0:libgdk-3.so.0:libatk-1.0.so.0:libatk-bridge-2.0.so.0:libpixman-1.so.0:libxcb-render.so.0");
        // Android has no /dev/shm: shm_open (Box64's own) uses this folder.
        File shm = LinuxSession.sessionDirectory(context, "shm");
        env.put("BOX64_SHM_DIR", shm.getPath());

        // A game with an embedded Chromium looks for icudtl.dat and its .pak files in the game's folder, one level above the program's, where
        // the depot has none (the glibc session has libskyshim_x64 open them from the program's folder): links stand in for them.
        File gameDir = programDir.getParentFile();
        if (gameDir != null && new File(programDir, "libcef.so").isFile()) {
            for (String name : new String[]{"icudtl.dat", "resources.pak", "chrome_100_percent.pak", "chrome_200_percent.pak", "snapshot_blob.bin", "v8_context_snapshot.bin"}) {
                File target = new File(programDir, name);
                File link = new File(gameDir, name);
                if (!target.isFile() || link.exists()) continue;
                try {
                    // relative, so the link also holds under proot and in the glibc sessions that share the game folder
                    android.system.Os.symlink(programDir.getName() + "/" + name, link.getPath());
                }
                catch (android.system.ErrnoException e) {
                    LinuxFail.w(TAG, "Cannot link " + link + ": " + e.getMessage());
                }
            }
        }

        // Debugging: KEY=value lines of Winlator/linux-bionic.env (BOX64_LOG, BOX64_ROLLING_LOG, ...) are added to the environment.
        File extra = new File(Environment.getExternalStorageDirectory(), "Winlator/linux-bionic.env");
        String extraText = extra.isFile() ? FileUtils.readString(extra) : null;
        if (extraText != null) {
            for (String line : extraText.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }

        // The profile and shortcut's explicit variables override runtime defaults and diagnostic environment files.
        for (int i = 0; i < nenv; i++) {
            String entry = guestCommand[first + i];
            int eq = entry.indexOf('=');
            if (eq > 0) env.put(entry.substring(0, eq), entry.substring(eq + 1).replace("/root", homePath));
        }

        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        // Debugging: SKY_BIONIC_PIPE (Winlator/linux-bionic.env) is a command the program's output is piped through (grep -B for a trace).
        String pipe = env.get("SKY_BIONIC_PIPE");
        if (pipe != null && !pipe.isEmpty()) {
            argv.add("/system/bin/sh");
            argv.add("-c");
            argv.add("\"$@\" 2>&1 | " + pipe + "; echo \"piped\" >&2");
            argv.add("sh");
        }
        argv.add(parent.getPath());
        argv.add(box64.getPath());
        argv.add(hostProgram.getPath());
        for (int i = first + nenv + nwrap; i < guestCommand.length; i++) argv.add(guestCommand[i].replace("/root", homePath));
        Log.i(TAG, "Session on the Android Box64: " + hostProgram);
        // The whole command and environment, to run it by hand (the log lines of ProcessHelper are cut short).
        StringBuilder dump = new StringBuilder();
        for (String word : argv) dump.append("ARG ").append(word).append('\n');
        for (String entry : env.toStringArray()) dump.append("ENV ").append(entry).append('\n');
        FileUtils.writeString(new File(Environment.getExternalStorageDirectory(), "Winlator/logs/linux-bionic-launch.txt"), dump.toString());
        return new LinuxSession.Launch(argv.toArray(new String[0]), env.toStringArray(), programDir, "bionic");
    }

    /**
     * The root filesystem's x86-64 libraries without the ones that cannot work on Android: OpenGL, Mesa's drivers and LLVM (a software renderer a game's own
     * window never needs). A library that is not there counts as missing (BOX64_ALLOWMISSINGLIBS). A folder of links to the rest.
     */
    private static File filteredLibs(Context context, File libDir) {
        File out = new File(new File(context.getFilesDir(), "linux-bionic"), "x86libs");
        File[] files = libDir.listFiles();
        if (files == null) return libDir;
        String[] skip = {"libGL", "libEGL", "libOpenGL", "libgallium", "libLLVM", "libdrm", "libvulkan", "libglapi"};
        File marker = new File(out, ".done-" + files.length + "-" + libDir.lastModified() + "-" + String.join(",", skip).hashCode());
        if (marker.exists()) return out;
        FileUtils.delete(out);
        out.mkdirs();
        for (File file : files) {
            String name = file.getName();
            boolean excluded = false;
            for (String prefix : skip) if (name.startsWith(prefix)) { excluded = true; break; }
            if (excluded) continue;
            try {
                android.system.Os.symlink(file.getPath(), new File(out, name).getPath());
            }
            catch (android.system.ErrnoException ignored) {}
        }
        try {
            marker.createNewFile();
        }
        catch (java.io.IOException ignored) {}
        return out;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    static EnvVars nativeEnvironment(Context context, ImageFs imageFs, Container container, File home) {
        ensureGraphicsRuntime(context, imageFs);
        String root = imageFs.getRootDir().getPath();
        EnvVars env = new EnvVars();
        env.put("HOME", home.getPath());
        env.put("USER", "xuser");
        env.put("LANG", "C.UTF-8");
        env.put("TMPDIR", root + "/usr/tmp");
        env.put("XDG_RUNTIME_DIR", root + "/usr/tmp");
        env.put("XDG_DATA_DIRS", root + "/usr/share");
        env.put("XDG_CONFIG_DIRS", root + "/usr/etc/xdg");
        env.put("PATH", root + "/usr/bin:/system/bin");
        env.put("DISPLAY", ":" + LinuxSession.displayNumber());
        env.put("LD_LIBRARY_PATH", imageFs.getLibDir().getPath() + ":/system/lib64");
        env.put("FONTCONFIG_PATH", root + "/usr/etc/fonts");
        env.put("ALSA_CONFIG_PATH", root + "/usr/share/alsa/alsa.conf:" + root + "/usr/etc/alsa/conf.d/android_aserver.conf");
        env.put("ALSA_PLUGIN_DIR", root + "/usr/lib/alsa-lib");
        env.put("SSL_CERT_FILE", root + "/usr/etc/tls/cert.pem");
        env.put("SSL_CERT_DIR", root + "/usr/etc/tls/certs");
        env.put("PULSE_SERVER", root + UnixSocketConfig.PULSE_SERVER_PATH);
        env.put("ANDROID_SYSVSHM_SERVER", root + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        File sysvshm = new File(imageFs.getLibDir(), "libandroid-sysvshm.so");
        if (sysvshm.exists()) env.put("LD_PRELOAD", sysvshm.getPath());
        File icd = writeIcd(context, imageFs);
        if (icd != null) env.put("VK_ICD_FILENAMES", icd.getPath());
        env.put("VK_LAYER_PATH", root + "/usr/share/vulkan/implicit_layer.d:" + root + "/usr/share/vulkan/explicit_layer.d");
        env.put("WRAPPER_LAYER_PATH", root + "/usr/lib");
        env.put("WRAPPER_CACHE_PATH", root + "/usr/var/cache");
        HashMap<String, String> graphics = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
        String driverId = graphics.get("version");
        AdrenotoolsManager drivers = new AdrenotoolsManager(context);
        if (driverId != null && !driverId.isEmpty() && !"System".equals(driverId)) drivers.setDriverById(env, imageFs, driverId);
        env.put("WRAPPER_VK_VERSION", "1.3.289");
        env.put("WRAPPER_RESOURCE_TYPE", valueOr(graphics.get("resourceType"), "auto"));
        env.put("WRAPPER_DISABLE_PRESENT_WAIT", valueOr(graphics.get("disablePresentWait"), "0"));
        env.put("WRAPPER_EMULATE_BCN", "1");
        env.put("MESA_VK_WSI_PRESENT_MODE", valueOr(graphics.get("presentMode"), "mailbox"));
        return env;
    }

    /** The wrapper library and layers Wine's first boot installs into the imagefs (the Linux sessions never needed them). */
    private static void ensureGraphicsRuntime(Context context, ImageFs imageFs) {
        File root = imageFs.getRootDir();
        if (new File(imageFs.getLibDir(), "libvulkan_wrapper.so").exists()) return;
        Log.i(TAG, "Installing the graphics runtime into the imagefs");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/wrapper.tzst", root);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "layers.tzst", root);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "graphics_driver/extra_libs.tzst", root);
    }

    /** The wrapper's ICD file names the library by an absolute path of the app that built it; this one names this app's. */
    private static File writeIcd(Context context, ImageFs imageFs) {
        File wrapper = new File(imageFs.getLibDir(), "libvulkan_wrapper.so");
        if (!wrapper.exists()) return null;
        File out = new File(new File(context.getFilesDir(), "linux-bionic"), "wrapper_icd.json");
        String json = "{\n  \"ICD\": {\n    \"api_version\": \"1.3.289\",\n    \"library_path\": \"" + wrapper.getPath() + "\"\n  },\n  \"file_format_version\": \"1.0.0\"\n}\n";
        out.getParentFile().mkdirs();
        return FileUtils.writeString(out, json) ? out : null;
    }
}
