package com.winlator.cmod.linux;

import com.winlator.cmod.container.Container;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * BeamNG's Lua VMs (the game's and every vehicle's) run LuaJIT, which keeps generating new x86 code. FEX translates all of it,
 * again and again, so one thread spends its time compiling and the game runs at about 15 FPS (about 60 with the JIT off).
 * Under FEX the launcher therefore installs a small game extension that turns the Lua JIT off, and starts it with
 * {@code -onLevelLoad_ext}. Set {@code SKY_BEAMNG_LUAJIT=1} in the shortcut's variables to keep the JIT.
 */
final class LinuxBeamNgLuaJit {
    static final String EXTENSION = "skynetfexjit";
    static final String KEEP_JIT_VARIABLE = "SKY_BEAMNG_LUAJIT";
    private static final String ARGUMENT = "-onLevelLoad_ext";
    private static final String SOURCE = String.join("\n",
            "-- Written by SkyNetEmu: the Lua JIT stays off under FEX (see LinuxBeamNgLuaJit).",
            "local M = {}",
            "",
            "local function vehicleJitOff(vid)",
            "  local vehicle = be:getObjectByID(vid)",
            "  if vehicle then vehicle:queueLuaCommand(\"pcall(function() jit.off() jit.flush() end)\") end",
            "end",
            "",
            "function M.onExtensionLoaded()",
            "  setExtensionUnloadMode(\"" + EXTENSION + "\", \"manual\")",
            "  pcall(function() jit.off() end)",
            "  log(\"I\", \"" + EXTENSION + "\", \"Lua JIT turned off (FEX)\")",
            "end",
            "",
            "M.onVehicleSpawned = vehicleJitOff",
            "M.onVehicleReplaced = vehicleJitOff",
            "M.onVehicleResetted = vehicleJitOff",
            "M.onVehicleSwitched = function(_, vid) if vid then vehicleJitOff(vid) end end",
            "",
            "return M",
            "");

    private LinuxBeamNgLuaJit() {}

    /** The directory of {@code -userpath <dir>} in the arguments (a relative one is taken from the program's folder, where the launcher starts it), or null. */
    private static File userPath(File program, List<String> args) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (!"-userpath".equals(args.get(i))) continue;
            File dir = new File(args.get(i + 1));
            return dir.isAbsolute() || program == null ? dir : new File(program.getParentFile(), dir.getPath());
        }
        return null;
    }

    /** The host file of a path the program is given: under PRoot a container path ("/root/...") is in the container's home. */
    private static File hostPath(Container container, File path) {
        String p = path.getPath();
        return p.startsWith("/root/") ? new File(container.getLinuxHomeDir(), p.substring("/root/".length())) : path;
    }

    /** The arguments to start the game with: unchanged unless this is BeamNG under FEX with nothing of the user's in the way. */
    static List<String> arguments(Container container, File program, boolean runsOnFex, LinuxLaunchOptions options) {
        if (!runsOnFex || !LinuxProgramPaths.isBeamNG(program) || "1".equals(options.env.get(KEEP_JIT_VARIABLE))
                || options.args.contains(ARGUMENT)) return options.args;
        try {
            // The game's user folder: the container's default one, or the one -userpath gives it ("<path>/current").
            File userPath = userPath(program, options.args);
            File directory = userPath != null ? new File(hostPath(container, userPath), "current/lua/ge/extensions")
                    : new File(container.getLinuxHomeDir(), ".local/share/BeamNG/BeamNG.drive/current/lua/ge/extensions");
            File file = new File(directory, EXTENSION + ".lua");
            byte[] bytes = SOURCE.getBytes(StandardCharsets.UTF_8);
            if (!file.isFile() || !java.util.Arrays.equals(Files.readAllBytes(file.toPath()), bytes)) {
                Files.createDirectories(directory.toPath());
                Files.write(file.toPath(), bytes);
            }
        } catch (IOException e) {
            LinuxFail.w("LinuxBeamNgLuaJit", "Cannot install the Lua JIT extension; BeamNG starts with its JIT on", e);
            return options.args;
        }
        List<String> arguments = new java.util.ArrayList<>(options.args);
        arguments.add(ARGUMENT);
        arguments.add(EXTENSION);
        return arguments;
    }
}
