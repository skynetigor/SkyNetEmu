package com.winlator.cmod.linux;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A statically translated Linux program (an AArch64 executable that links the translated x86-64 program and its libraries with a runtime built for
 * Android's C library) started straight from the app: no proot, no glibc runtime and no emulator process. The environment is the one of the
 * Android Box64 and FEX sessions (the imagefs's X server socket, PulseAudio, the Wine wrapper ICD over an adrenotools driver); the program reads
 * the game's folder on shared storage itself.
 *
 * For any shortcut whose program is such an executable (an AArch64 ELF whose interpreter is Android's linker; {@link #EXTRA} = "1" is no longer needed) (tools/binary-translation-poc/build-bionic.sh).
 * Shortcut variables used here and not passed on: SKY_LIFTED_CWD, the folder to start in (the game's BinLinux folder).
 */
public final class LinuxLiftedBionicSession {
    private static final String TAG = "LinuxLiftedBionic";

    /** Container extra: "1" starts the shortcut's AArch64 program on Android's C library, without proot. */
    public static final String EXTRA = "linuxLiftedBionic";

    private LinuxLiftedBionicSession() {}

    /** Set when a program was started by this session: the UI overlay and the on-screen gamepad belong to such a session. */
    private static volatile boolean active;

    public static boolean enabled(Container container) {
        return active || "1".equals(container.getExtra(EXTRA));
    }

    /**
     * @param guestCommand the program (as the guest sees its path) and its arguments
     * @param guestEnv     what the glibc session would have given the program (KEY=value): the shortcut's variables are in it
     * @return null when this session cannot run the command (it then runs the usual way)
     */
    static LinuxSession.Launch build(Context context, ImageFs imageFs, Container container, String[] guestCommand, List<String> guestEnv, File home) {
        active = false;
        int at = -1;
        for (int i = 0; i < guestCommand.length; i++) if ("bannerlator-run".equals(guestCommand[i])) at = i;
        if (at < 0 || at + 4 >= guestCommand.length) return null;
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
        File program = LinuxSession.hostFile(context, container, guestCommand[at + 1]);
        if (program == null || !program.isFile()) {
            Log.w(TAG, "The translated program is missing: " + guestCommand[at + 1]);
            return null;
        }
        if (LinuxSession.elfMachine(program) != 183) {
            Log.d(TAG, "Not an AArch64 program: " + program);
            return null;
        }
        String interpreter = LinuxSession.elfInterpreter(program);
        if (interpreter == null || !interpreter.startsWith("/system/bin/linker")) {   // a glibc AArch64 program needs the runtime's loader: the usual session
            Log.i(TAG, "Not built for Android's C library (" + interpreter + "): " + program);
            return null;
        }
        File parent = LinuxSession.stageAsset(context, "linux-bionic", "bionic-launch-parent");
        if (parent == null) {
            Log.w(TAG, "The launch parent is unavailable");
            return null;
        }
        String homePath = home.getPath();
        EnvVars env = LinuxBionicSession.nativeEnvironment(context, imageFs, container, home);
        // The imagefs's OpenSSL 3 would shadow the platform's libcrypto/libssl for the host Vulkan loader's vendor libraries (no GPU found).
        try {
            File linuxBionic = new File(context.getFilesDir(), "linux-bionic");
            env.put("LD_LIBRARY_PATH", LinuxFexBionicSession.platformTlsDirectory(linuxBionic).getPath() + ":" + env.get("LD_LIBRARY_PATH"));
        }
        catch (java.io.IOException e) {
            Log.w(TAG, "Cannot link the platform's libcrypto: " + e.getMessage());
        }
        File workingDir = program.getParentFile();
        for (String entry : guestEnv) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String key = entry.substring(0, eq);
            String value = entry.substring(eq + 1).replace("/root", homePath);
            if (key.equals("SKY_LIFTED_CWD")) { workingDir = new File(value); continue; }
            if (key.startsWith("SKY_") && !key.equals("SKY_ROOT") && !key.equals("SKY_PATHMAP") && !key.equals("SKY_LIBPATH") && !key.equals("SKY_LOADER") && !key.equals("SKY_SELF")
                    && !key.startsWith("SKY_FEX") && !key.equals("SKY_X86_EMULATOR") && !key.equals("SKY_X86_32_EMULATOR")
                    || key.equals("TZ") || key.startsWith("SDL_") || key.startsWith("TU_") || key.startsWith("MESA_") || key.startsWith("WRAPPER_") || key.startsWith("FEX_NOT")) {
                env.put(key, value);
            }
        }
        for (int i = 0; i < nenv; i++) {
            String entry = guestCommand[first + i];
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String key = entry.substring(0, eq), value = entry.substring(eq + 1).replace("/root", homePath);
            if (key.equals("SKY_LIFTED_CWD")) workingDir = new File(value);
            else env.put(key, value);
        }
        Map<String, String> memoryEnv = new LinkedHashMap<>();
        for (String pair : env.toStringArray()) {
            int eq = pair.indexOf('=');
            if (eq > 0) memoryEnv.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        LinuxGpuMemory.prepare(context, container, memoryEnv, true, true);
        for (String key : new String[]{"VK_INSTANCE_LAYERS", "VK_LAYER_PATH", "VK_ADD_LAYER_PATH"})
            if (!memoryEnv.containsKey(key)) env.remove(key);
        for (Map.Entry<String, String> pair : memoryEnv.entrySet()) env.put(pair.getKey(), pair.getValue());
        // Debugging: KEY=value lines of Winlator/linux-bionic.env on shared storage are added to the environment.
        File extra = new File(Environment.getExternalStorageDirectory(), "Winlator/linux-bionic.env");
        String extraText = extra.isFile() ? FileUtils.readString(extra) : null;
        if (extraText != null) {
            for (String line : extraText.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        // Gamepads: the program is an aarch64 bionic executable, so the shim (built for bionic) is preloaded here; it hands the app's controller files out
        // as evdev devices and SDL, which lists pads only through SDL_JOYSTICK_DEVICE without udev, opens them.
        File shim = LinuxSession.stageAsset(context, "linux-bionic", "libskyshim_bionic.so");
        if (shim != null) {
            String preload = env.get("LD_PRELOAD");
            env.put("LD_PRELOAD", preload == null || preload.isEmpty() ? shim.getPath() : shim.getPath() + ":" + preload);
            env.put("SKY_INPUT_DIR", LinuxSession.inputDir(context).getPath());
            env.put("SKY_INPUT_PROTOCOL", com.winlator.cmod.inputcontrols.NativeInputTransport.isEnabled() ? "2" : "1");
            env.put("SDL_JOYSTICK_DEVICE", "/dev/input/event0:/dev/input/event1:/dev/input/event2:/dev/input/event3");
            env.put("SDL_JOYSTICK_DISABLE_UDEV", "1");
            env.put("SDL_HIDAPI_JOYSTICK_DISABLE_UDEV", "1");
            env.put("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1");
        }
        env.put("SKY_SHM_DIR", LinuxSession.sessionDirectory(context, "shm").getPath());
        env.put("SKY_X11_DIR", new File(imageFs.getRootDir(), "usr/tmp/.X11-unix").getPath());   // libxcb connects to /tmp/.X11-unix: the runtime maps it here
        if (env.get("SKY_EXE_PATH") == null) env.put("SKY_EXE_PATH", program.getPath());

        List<String> argv = new ArrayList<>();
        argv.add(parent.getPath());
        argv.add(program.getPath());
        for (int i = first + nenv + nwrap; i < guestCommand.length; i++) argv.add(guestCommand[i].replace("/root", homePath));
        Log.i(TAG, "Session on Android's C library: " + program + " in " + workingDir);
        StringBuilder dump = new StringBuilder();
        for (String word : argv) dump.append("ARG ").append(word).append('\n');
        for (String entry : env.toStringArray()) dump.append("ENV ").append(entry).append('\n');
        FileUtils.writeString(new File(Environment.getExternalStorageDirectory(), "Winlator/logs/linux-lifted-bionic-launch.txt"), dump.toString());
        active = true;
        return new LinuxSession.Launch(argv.toArray(new String[0]), env.toStringArray(), workingDir, "bionic");
    }
}
