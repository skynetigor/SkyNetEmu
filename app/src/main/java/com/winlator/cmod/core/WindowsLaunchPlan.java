package com.winlator.cmod.core;

import android.content.Context;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Revalidates the selected executable; a runtime's ARM64EC name is not a guest architecture. */
public final class WindowsLaunchPlan {
    public final WindowsExecutable.Architecture architecture;
    public final File executable;
    public final boolean nativeArm64;
    public final String nativeExecutablePath;
    public final String nativeWorkingDirectory;
    private final String inlineArguments;

    private WindowsLaunchPlan(WindowsExecutable.Architecture architecture, File executable, String inlineArguments,
                              String nativeExecutablePath, String nativeWorkingDirectory) {
        this.architecture = architecture;
        this.executable = executable;
        this.inlineArguments = inlineArguments;
        nativeArm64 = architecture == WindowsExecutable.Architecture.ARM64;
        this.nativeExecutablePath = nativeExecutablePath;
        this.nativeWorkingDirectory = nativeWorkingDirectory;
    }

    public static WindowsLaunchPlan resolve(ImageFs imageFs, Container container, Shortcut shortcut, WineInfo wine) {
        if (shortcut == null) {
            if (container != null && WineInfo.NATIVE_ARM64_CONTAINER_VALUE.equals(
                    container.getExtra(WineInfo.NATIVE_ARM64_CONTAINER_KEY))) {
                if (wine == null || !wine.supportsNativeArm64())
                    throw new IllegalArgumentException("This Windows ARM64 environment needs a native ARM64 Wine/Proton runtime. Choose a compatible runtime in the container settings.");
                File fileManager = new File(container.getRootDir(), ".wine/drive_c/windows/system32/winefile.exe");
                WindowsExecutable.Architecture architecture = WindowsExecutable.detect(fileManager);
                if (architecture != WindowsExecutable.Architecture.ARM64 && architecture != WindowsExecutable.Architecture.ARM64X)
                    throw new IllegalArgumentException("The native ARM64 file manager is missing or incompatible. Create a new Windows ARM64 environment or launch a game shortcut.");
                return new WindowsLaunchPlan(WindowsExecutable.Architecture.ARM64, fileManager, "C:\\",
                        reverseHostPath(imageFs, container, fileManager), "C:\\");
            }
            return new WindowsLaunchPlan(WindowsExecutable.Architecture.UNKNOWN, null, "", null, null);
        }
        String path = shortcut.path.trim();
        String selected = GameFolder.executablePath(path);
        String arguments = "";
        if (selected != null && path.startsWith("\"")) {
            int end = path.indexOf('"', 1);
            arguments = path.substring(end + 1).trim();
        } else if (selected != null) {
            arguments = path.substring(selected.length()).trim();
        }
        String host = GameFolder.unixExePath(imageFs, container, path);
        File file = host == null ? null : new File(host);
        WindowsExecutable.Architecture architecture = file == null
                ? WindowsExecutable.Architecture.UNKNOWN : WindowsExecutable.detect(file);
        // Cached ARM64 is useful for a missing-file error, never as authority to execute a replaced file.
        if (architecture == WindowsExecutable.Architecture.UNKNOWN
                && "arm64".equals(shortcut.getExtra(WindowsExecutable.SHORTCUT_ARCHITECTURE_KEY)))
            throw new IllegalArgumentException("The ARM64 executable is missing or its PE header cannot be read. Select the game executable again.");
        boolean nativeArm64 = architecture == WindowsExecutable.Architecture.ARM64;
        if (nativeArm64 && (wine == null || !wine.supportsNativeArm64()))
            throw new IllegalArgumentException("This Windows ARM64 game needs a native ARM64 Wine/Proton runtime. Add it using the Windows ARM64 preset.");
        String nativePath = nativeArm64 ? reverseHostPath(imageFs, container, file) : null;
        String workingDirectory = null;
        if (nativeArm64) {
            File directory = file.getParentFile();
            // BeamNG locates game data from the installation root; match the working native launch from that folder.
            if (file.getName().equalsIgnoreCase("BeamNG.drive.arm64.exe") && directory != null
                    && directory.getName().equalsIgnoreCase("BinArm64") && directory.getParentFile() != null)
                directory = directory.getParentFile();
            workingDirectory = reverseHostPath(imageFs, container, directory);
        }
        return new WindowsLaunchPlan(architecture, file, arguments, nativePath, workingDirectory);
    }

    /** Maps the verified host executable into Wine's actual drives; Z: is the imagefs root, not Android's root. */
    public static String reverseHostPath(ImageFs imageFs, Container container, File file) {
        if (file == null) throw new IllegalArgumentException("The Windows ARM64 executable path is missing.");
        try {
            String host = file.getCanonicalPath();
            List<String[]> mappings = new ArrayList<>();
            if (container != null) {
                mappings.add(new String[]{"C", new File(container.getRootDir(), ".wine/drive_c").getCanonicalPath()});
                for (String[] drive : container.drivesIterator()) {
                    if (drive == null || drive.length < 2 || drive[0] == null || drive[1] == null || drive[1].isEmpty()) continue;
                    String letter = drive[0].replace(":", "").trim().toUpperCase(Locale.ROOT);
                    if (letter.length() != 1 || letter.charAt(0) < 'A' || letter.charAt(0) > 'Z'
                            || letter.equals("C") || letter.equals("Z")) continue;
                    mappings.add(new String[]{letter, new File(drive[1]).getCanonicalPath()});
                }
            }
            if (imageFs != null) mappings.add(new String[]{"Z", imageFs.getRootDir().getCanonicalPath()});
            String selected = null;
            int longest = -1;
            for (String[] mapping : mappings) {
                String base = mapping[1];
                if (!host.equals(base) && !host.startsWith(base.endsWith("/") ? base : base + "/")) continue;
                if (base.length() <= longest) continue;
                String relative = host.substring(base.length());
                if (relative.startsWith("/")) relative = relative.substring(1);
                selected = mapping[0] + ":\\" + relative.replace('/', '\\');
                longest = base.length();
            }
            if (selected != null) return selected;
        } catch (IOException error) {
            throw new IllegalArgumentException("The Windows ARM64 executable path could not be resolved. Select the executable again.", error);
        }
        throw new IllegalArgumentException("This Windows ARM64 executable is outside the container's mapped drives. Add its folder to the container's drives, then launch it again.");
    }

    public void installHelper(Context context, Container container) throws IOException {
        if (!nativeArm64) return;
        File helper = new File(container.getRootDir(), ".wine/drive_c/windows/system32/winhandler-arm64.exe");
        helper.getParentFile().mkdirs();
        FileUtils.copy(context, "windows/arm64/winhandler.exe", helper);
        if (WindowsExecutable.detect(helper) != WindowsExecutable.Architecture.ARM64)
            throw new IOException("The native ARM64 input helper could not be installed.");
    }

    /** Arguments are passed directly to ProcessBuilder, including paths with spaces. */
    public String[] wineArguments(String desktopSize, Shortcut shortcut) {
        if (!nativeArm64) throw new IllegalStateException("Native arguments requested for a translated game");
        List<String> args = new ArrayList<>();
        args.add("wine"); args.add("explorer"); args.add("/desktop=shell," + desktopSize);
        args.add("C:\\windows\\system32\\winhandler-arm64.exe");
        args.add("/dir"); args.add(nativeWorkingDirectory); args.add(nativeExecutablePath);
        args.addAll(parseArguments(inlineArguments));
        if (shortcut != null) args.addAll(parseArguments(shortcut.getExtra("execArgs")));
        if (isBeamNG() && !args.contains("-gfx")) { args.add("-gfx"); args.add("vk"); }
        if (isBeamNG() && !args.contains("-nosteam")) args.add("-nosteam");
        return args.toArray(new String[0]);
    }

    public boolean isBeamNG() {
        return nativeArm64 && executable != null && executable.getName().equalsIgnoreCase("BeamNG.drive.arm64.exe");
    }

    public void applyEnvironment(EnvVars env) {
        if (!nativeArm64) return;
        env.remove("HODLL");
        env.remove("WINE_NO_DUPLICATE_EXPLORER");
        if (isBeamNG()) {
            String overrides = env.get("WINEDLLOVERRIDES");
            boolean browseuiConfigured = false;
            for (String clause : overrides.split(";")) {
                int equals = clause.indexOf('=');
                if (equals < 0) continue;
                for (String dll : clause.substring(0, equals).split(","))
                    if (dll.trim().equalsIgnoreCase("browseui")) browseuiConfigured = true;
            }
            if (!browseuiConfigured)
                env.put("WINEDLLOVERRIDES", overrides.isEmpty() ? "browseui=" : overrides + ";browseui=");
            if (!env.has("WINEESYNC")) env.put("WINEESYNC", "0");
            if (!env.has("WINEFSYNC")) env.put("WINEFSYNC", "0");
        }
    }

    /** Serializes structured arguments; raw user-entered execArgs must be left verbatim. */
    public static String serializeArguments(List<String> arguments) {
        if (arguments == null) return "";
        StringBuilder command = new StringBuilder();
        for (String argument : arguments) {
            if (argument == null) throw new IllegalArgumentException("Launch arguments cannot contain null.");
            if (command.length() > 0) command.append(' ');
            boolean quoted = argument.isEmpty();
            for (int i = 0; i < argument.length() && !quoted; i++) {
                char ch = argument.charAt(i);
                quoted = ch == '"' || ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '\f' || ch == 11;
            }
            // Games which parse their raw command line can require unquoted switches.
            if (!quoted) { command.append(argument); continue; }
            command.append('"');
            int slashes = 0;
            for (int i = 0; i < argument.length(); i++) {
                char ch = argument.charAt(i);
                if (ch == '\\') { slashes++; continue; }
                int escapedSlashes = ch == '"' ? slashes * 2 + 1 : slashes;
                for (int j = 0; j < escapedSlashes; j++) command.append('\\');
                command.append(ch);
                slashes = 0;
            }
            for (int j = 0; j < slashes * 2; j++) command.append('\\');
            command.append('"');
        }
        return command.toString();
    }

    /** Windows command-line quoting, without a shell or environment expansion. */
    public static List<String> parseArguments(String command) {
        List<String> result = new ArrayList<>();
        if (command == null) return result;
        int index = 0;
        while (index < command.length()) {
            while (index < command.length() && (command.charAt(index) == ' ' || command.charAt(index) == '\t')) index++;
            if (index == command.length()) break;
            StringBuilder arg = new StringBuilder();
            boolean quoted = false;
            while (index < command.length()) {
                char ch = command.charAt(index);
                if (!quoted && (ch == ' ' || ch == '\t')) break;
                int slashes = 0;
                while (index < command.length() && command.charAt(index) == '\\') { slashes++; index++; }
                if (index < command.length() && command.charAt(index) == '"') {
                    for (int i = 0; i < slashes / 2; i++) arg.append('\\');
                    if ((slashes & 1) != 0) {
                        arg.append('"');
                        index++;
                    } else if (quoted && index + 1 < command.length() && command.charAt(index + 1) == '"') {
                        arg.append('"');
                        index += 2;
                    } else {
                        quoted = !quoted;
                        index++;
                    }
                } else {
                    for (int i = 0; i < slashes; i++) arg.append('\\');
                    if (!quoted && index < command.length() && (command.charAt(index) == ' ' || command.charAt(index) == '\t')) break;
                    if (index < command.length()) arg.append(command.charAt(index++));
                }
            }
            result.add(arg.toString());
        }
        return result;
    }
}
