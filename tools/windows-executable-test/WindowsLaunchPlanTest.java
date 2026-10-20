import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.GameFolder;
import com.winlator.cmod.core.WindowsExecutable;
import com.winlator.cmod.core.WindowsExecutable.Architecture;
import com.winlator.cmod.core.WindowsLaunchPlan;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Uses real PE detection, DOS path resolution, argument parsing and launch-plan construction. */
public final class WindowsLaunchPlanTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void arguments(String command, String... expected) {
        List<String> actual = WindowsLaunchPlan.parseArguments(command);
        check(actual.equals(Arrays.asList(expected)), "Arguments " + command + ": expected " + Arrays.toString(expected) + ", got " + actual);
    }

    private static void serializes(String expected, String... arguments) {
        String command = WindowsLaunchPlan.serializeArguments(Arrays.asList(arguments));
        check(command.equals(expected), "Structured arguments serialize as expected: " + command);
        check(WindowsLaunchPlan.parseArguments(command).equals(Arrays.asList(arguments)), "Structured arguments round trip: " + command);
    }

    private static File pe(File file, Architecture architecture) throws Exception {
        byte[] bytes = new byte[512];
        bytes[0] = 'M'; bytes[1] = 'Z'; bytes[60] = (byte)128;
        bytes[128] = 'P'; bytes[129] = 'E';
        bytes[132] = (byte)architecture.machine;
        bytes[133] = (byte)(architecture.machine >>> 8);
        bytes[148] = (byte)(architecture == Architecture.X86 ? 224 : 240);
        bytes[152] = 0x0b; bytes[153] = (byte)(architecture == Architecture.X86 ? 1 : 2);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static WindowsLaunchPlan resolve(ImageFs fs, Container container, File file, boolean runtime) {
        return WindowsLaunchPlan.resolve(fs, container, new Shortcut(container, "\"" + file + "\""), new WineInfo(runtime));
    }

    private static void rejected(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        arguments(null);
        arguments("");
        arguments("  \t ");
        arguments("\"\"", "");
        arguments("one \"\" two", "one", "", "two");
        arguments("-userpath \"C:\\BeamNG User\\current\" -level gridmap_v2", "-userpath", "C:\\BeamNG User\\current", "-level", "gridmap_v2");
        arguments("prefix\"with spaces\"suffix", "prefixwith spacessuffix");
        arguments("C:\\folder\\ next", "C:\\folder\\", "next");
        arguments("C:\\folder\\\tsecond", "C:\\folder\\", "second");
        arguments("'single quotes' $HOME %PATH% ; &&", "'single", "quotes'", "$HOME", "%PATH%", ";", "&&");
        arguments("\"a b c\" d e", "a b c", "d", "e");
        // Microsoft CRT examples: https://learn.microsoft.com/en-us/cpp/c-language/parsing-c-command-line-arguments
        arguments("\"ab\\\"c\" \"\\\\\" d", "ab\"c", "\\", "d");
        arguments("a\\\\\\b d\"e f\"g h", "a\\\\\\b", "de fg", "h");
        arguments("a\\\\\\\"b c d", "a\\\"b", "c", "d");
        arguments("a\\\\\\\\\"b c\" d e", "a\\\\b c", "d", "e");
        arguments("a\"b\"\" c d", "ab\" c d");
        arguments("\"a\"\"b\" tail", "a\"b", "tail");
        arguments("\"unfinished argument", "unfinished argument");
        arguments("line\nbreak", "line\nbreak");
        check(WindowsLaunchPlan.serializeArguments(null).isEmpty(), "Null argument list is empty");
        serializes("");
        serializes("\"\"", "");
        serializes("plain \"path with spaces\"", "plain", "path with spaces");
        serializes("C:\\dir\\", "C:\\dir\\");
        serializes("\"a\\\"b\"", "a\"b");
        serializes("\"slash\\\\\\\"quote\"", "slash\\\"quote");
        serializes("\"space slash \\\\\"", "space slash \\");
        serializes("\"a\tb\"", "a\tb");
        serializes("-gfx vk -nosteam -userpath C:/beamng-arm64-user -level gridmap_v2",
                "-gfx", "vk", "-nosteam", "-userpath", "C:/beamng-arm64-user", "-level", "gridmap_v2");

        File work = new File(args[0]);
        ImageFs fs = new ImageFs(new File(work, "imagefs"));
        Container container = new Container(new File(work, "imagefs/home/xuser-7"));
        File game = pe(new File(fs.getRootDir(), "home/games/ARM Game with spaces/BinArm64/Game.arm64.exe"), Architecture.ARM64);
        Shortcut shortcut = new Shortcut(container, "\"" + game + "\" -inline \"two words\"");
        shortcut.extras.put("execArgs", "-userpath \"C:\\user path\\current\" \"\"");
        WindowsLaunchPlan nativePlan = WindowsLaunchPlan.resolve(fs, container, shortcut, new WineInfo(true));
        check(nativePlan.nativeArm64 && nativePlan.executable.equals(game), "Legacy ARM64 shortcut discovered from header");
        check(nativePlan.architecture == Architecture.ARM64, "Native game architecture");
        String[] argv = nativePlan.wineArguments("1280x720", shortcut);
        check(Arrays.equals(argv, new String[]{"wine", "explorer", "/desktop=shell,1280x720", "C:\\windows\\system32\\winhandler-arm64.exe",
                "/dir", "Z:\\home\\games\\ARM Game with spaces\\BinArm64", "Z:\\home\\games\\ARM Game with spaces\\BinArm64\\Game.arm64.exe",
                "-inline", "two words", "-userpath", "C:\\user path\\current", ""}), "Native argv preserves selected DOS path and arguments");
        check(new File(GameFolder.unixExePath(fs, container, nativePlan.nativeExecutablePath)).getCanonicalFile().equals(game.getCanonicalFile()),
                "Reverse-mapped DOS path reaches exactly the verified executable");
        check(Arrays.stream(argv).noneMatch(arg -> arg.equalsIgnoreCase("box64") || arg.toLowerCase().contains("fex")), "Native argv has no translator");
        EnvVars env = new EnvVars("HODLL=libwow64fex.dll WINE_NO_DUPLICATE_EXPLORER=1 WINEESYNC=1 CUSTOM=kept");
        nativePlan.applyEnvironment(env);
        check(!env.has("HODLL") && !env.has("WINE_NO_DUPLICATE_EXPLORER") && env.get("CUSTOM").equals("kept"), "Native environment removes translator/explorer overrides");
        check(env.get("WINEESYNC").equals("1") && !env.has("WINEDLLOVERRIDES"), "Generic native games preserve game environment");
        rejected(() -> resolve(fs, container, game, false), "Native game rejects incompatible Wine");

        File cGame = pe(new File(container.getRootDir(), ".wine/drive_c/Program Files/ARM game/Game.exe"), Architecture.ARM64);
        WindowsLaunchPlan cPlan = WindowsLaunchPlan.resolve(fs, container, new Shortcut(container, "\"C:\\Program Files\\ARM game\\Game.exe\""), new WineInfo(true));
        check(cPlan.nativeArm64 && cPlan.executable.equals(cGame), "C drive path resolves to selected prefix");
        check(cPlan.nativeExecutablePath.equals("C:\\Program Files\\ARM game\\Game.exe")
                && cPlan.nativeWorkingDirectory.equals("C:\\Program Files\\ARM game"), "Prefix executable and working directory use C drive");
        File cRootGame = pe(new File(container.getRootDir(), ".wine/drive_c/Game.exe"), Architecture.ARM64);
        check(resolve(fs, container, cRootGame, true).nativeWorkingDirectory.equals("C:\\"), "Executable in C root retains root backslash");
        File dRoot = new File(work, "shared games");
        File dGame = pe(new File(dRoot, "ARM Game/Game.exe"), Architecture.ARM64);
        container.drives.add(new String[]{"D", dRoot.getPath()});
        WindowsLaunchPlan dPlan = WindowsLaunchPlan.resolve(fs, container, new Shortcut(container, "\"D:\\ARM Game\\Game.exe\""), new WineInfo(true));
        check(dPlan.nativeArm64 && dPlan.executable.equals(dGame), "Mapped storage drive resolves to real ARM executable");
        check(dPlan.nativeExecutablePath.equals("D:\\ARM Game\\Game.exe"), "External executable uses its mapped DOS drive");
        container.drives.add(new String[]{"e:", new File(dRoot, "ARM Game").getPath()});
        check(WindowsLaunchPlan.reverseHostPath(fs, container, dGame).equals("E:\\Game.exe"), "Longest matching drive mapping wins");
        File zGame = pe(new File(fs.getRootDir(), "home/games/ARM/Game.exe"), Architecture.ARM64);
        WindowsLaunchPlan zPlan = WindowsLaunchPlan.resolve(fs, container, new Shortcut(container, "Z:\\home\\games\\ARM\\Game.exe"), new WineInfo(true));
        check(zPlan.nativeArm64 && zPlan.executable.equals(zGame), "Z drive is imagefs root");
        check(zPlan.nativeExecutablePath.equals("Z:\\home\\games\\ARM\\Game.exe"), "Native Z path is relative to imagefs rather than Android root");

        File prefix = new File(work, "linked-prefix");
        File linkedGame = pe(new File(prefix, "drive_c/Linked Game/Game.exe"), Architecture.ARM64);
        Container linkedContainer = new Container(new File(work, "linked-container"));
        linkedContainer.getRootDir().mkdirs();
        Files.createSymbolicLink(new File(linkedContainer.getRootDir(), ".wine").toPath(), prefix.toPath());
        check(WindowsLaunchPlan.reverseHostPath(fs, linkedContainer, linkedGame).equals("C:\\Linked Game\\Game.exe"), "Prefix symlink resolves to C drive");
        File realExternal = new File(work, "external-real");
        File linkedExternalGame = pe(new File(realExternal, "Game with spaces/Game.exe"), Architecture.ARM64);
        File alias = new File(work, "external-alias");
        Files.createSymbolicLink(alias.toPath(), realExternal.toPath());
        container.drives.add(new String[]{"F", alias.getPath()});
        check(WindowsLaunchPlan.reverseHostPath(fs, container, linkedExternalGame).equals("F:\\Game with spaces\\Game.exe"), "Mapped storage symlink resolves to the same executable");
        File unmapped = pe(new File(work, "unmapped/Game.exe"), Architecture.ARM64);
        rejected(() -> resolve(fs, container, unmapped, true), "Unmapped native executable gives an actionable error");
        File sibling = pe(new File(work, "imagefs-sibling/Game.exe"), Architecture.ARM64);
        rejected(() -> resolve(fs, container, sibling, true), "Imagefs path prefix alone cannot create an invalid Z mapping");

        File dottedDirectory = pe(new File(fs.getRootDir(), "home/games/game.exe data/BinArm64/Real Game.exe"), Architecture.ARM64);
        Shortcut dottedShortcut = new Shortcut(container, "\"" + dottedDirectory + "\" -config \"another.exe\"");
        WindowsLaunchPlan dottedPlan = WindowsLaunchPlan.resolve(fs, container, dottedShortcut, new WineInfo(true));
        check(dottedPlan.nativeArm64 && dottedPlan.executable.equals(dottedDirectory), "Quoted executable path preserves .exe directory names");
        List<String> dottedArgv = Arrays.asList(dottedPlan.wineArguments("1280x720", dottedShortcut));
        check(dottedArgv.subList(dottedArgv.size() - 2, dottedArgv.size()).equals(Arrays.asList("-config", "another.exe")), "Quoted .exe arguments remain arguments");
        check(GameFolder.of(fs, container, dottedShortcut.path).folder.equals(dottedDirectory.getParentFile().getParentFile()), "Game root resolves correctly through .exe directory");
        File suffixDirectory = pe(new File(fs.getRootDir(), "home/games/game.exe-data/Real Game.EXE"), Architecture.ARM64);
        Shortcut suffixShortcut = new Shortcut(container, suffixDirectory + " -file another.exe");
        WindowsLaunchPlan suffixPlan = WindowsLaunchPlan.resolve(fs, container, suffixShortcut, new WineInfo(true));
        check(suffixPlan.nativeArm64 && suffixPlan.executable.equals(suffixDirectory), "Unquoted .exe directory suffix is not mistaken for entry point");
        check(GameFolder.executablePath("\"unfinished path.exe") == null, "Unmatched executable quote is unresolved");
        check(GameFolder.executablePath("D:\\Game.EXE\t-tab").equals("D:\\Game.EXE"), "Tab separates executable arguments");

        for (Architecture architecture : new Architecture[]{Architecture.X86, Architecture.X86_64, Architecture.ARM64EC, Architecture.ARM64X}) {
            File translated = pe(new File(work, architecture.id + "/Game.exe"), architecture);
            WindowsLaunchPlan plan = resolve(fs, container, translated, false);
            check(plan.architecture == architecture && !plan.nativeArm64, architecture.id + " keeps translated launch path");
            EnvVars legacyEnv = new EnvVars("HODLL=wowbox64.dll WINE_NO_DUPLICATE_EXPLORER=1 WINEESYNC=1 CUSTOM=value");
            String original = legacyEnv.toString(); plan.applyEnvironment(legacyEnv);
            check(original.equals(legacyEnv.toString()), architecture.id + " keeps existing environment unchanged");
        }
        Shortcut cached = new Shortcut(container, "\"" + game + "\"");
        cached.extras.put(WindowsExecutable.SHORTCUT_ARCHITECTURE_KEY, "arm64");
        pe(game, Architecture.X86_64);
        WindowsLaunchPlan replaced = WindowsLaunchPlan.resolve(fs, container, cached, new WineInfo(false));
        check(!replaced.nativeArm64 && replaced.architecture == Architecture.X86_64, "Replaced executable revalidated; cache cannot force native route");
        Files.write(game.toPath(), new byte[]{1, 2, 3});
        rejected(() -> WindowsLaunchPlan.resolve(fs, container, cached, new WineInfo(true)), "Corrupt cached native game gives actionable error");
        Files.delete(game.toPath());
        rejected(() -> WindowsLaunchPlan.resolve(fs, container, cached, new WineInfo(true)), "Missing cached native game gives actionable error");
        cached.extras.put(WindowsExecutable.SHORTCUT_ARCHITECTURE_KEY, "x86_64");
        check(!WindowsLaunchPlan.resolve(fs, container, cached, new WineInfo(false)).nativeArm64, "Missing x64 game keeps legacy handling");
        check(!WindowsLaunchPlan.resolve(fs, container, null, new WineInfo(false)).nativeArm64, "Container desktop launch keeps legacy handling");
        container.extras.put(WineInfo.NATIVE_ARM64_CONTAINER_KEY, WineInfo.NATIVE_ARM64_CONTAINER_VALUE);
        rejected(() -> WindowsLaunchPlan.resolve(fs, container, null, new WineInfo(false)), "Native desktop rejects incompatible Wine");
        rejected(() -> WindowsLaunchPlan.resolve(fs, container, null, new WineInfo(true)), "Missing native file manager gives actionable error");
        File fileManager = pe(new File(container.getRootDir(), ".wine/drive_c/windows/system32/winefile.exe"), Architecture.X86_64);
        rejected(() -> WindowsLaunchPlan.resolve(fs, container, null, new WineInfo(true)), "Native desktop rejects translated file manager");
        pe(fileManager, Architecture.ARM64);
        WindowsLaunchPlan desktop = WindowsLaunchPlan.resolve(fs, container, null, new WineInfo(true));
        check(desktop.nativeArm64 && desktop.executable.equals(fileManager), "Native Enter selects ARM64 Wine file manager");
        check(Arrays.equals(desktop.wineArguments("1280x720", null), new String[]{"wine", "explorer", "/desktop=shell,1280x720",
                "C:\\windows\\system32\\winhandler-arm64.exe", "/dir", "C:\\", "C:\\windows\\system32\\winefile.exe", "C:\\"}),
                "Native Enter retains virtual desktop and native helper without a translator");
        EnvVars desktopEnv = new EnvVars("HODLL=libwow64fex.dll WINE_NO_DUPLICATE_EXPLORER=1");
        desktop.applyEnvironment(desktopEnv);
        check(!desktopEnv.has("HODLL") && !desktopEnv.has("WINE_NO_DUPLICATE_EXPLORER") && !desktop.isBeamNG(),
                "Native desktop removes translation overrides without BeamNG defaults");
        container.extras.remove(WineInfo.NATIVE_ARM64_CONTAINER_KEY);

        File beam = pe(new File(fs.getRootDir(), "home/games/BeamNG/BinArm64/BeamNG.drive.arm64.exe"), Architecture.ARM64);
        Shortcut beamShortcut = new Shortcut(container, "\"" + beam + "\"");
        WindowsLaunchPlan beamPlan = WindowsLaunchPlan.resolve(fs, container, beamShortcut, new WineInfo(true));
        List<String> beamArgv = Arrays.asList(beamPlan.wineArguments("1280x720", beamShortcut));
        check(beamPlan.nativeWorkingDirectory.equals("Z:\\home\\games\\BeamNG")
                && beamArgv.get(5).equals("Z:\\home\\games\\BeamNG"), "Native BeamNG starts from installation root above BinArm64");
        check(beamPlan.nativeExecutablePath.equals("Z:\\home\\games\\BeamNG\\BinArm64\\BeamNG.drive.arm64.exe"), "BeamNG root working directory preserves exact selected ARM64 executable");
        check(beamArgv.subList(beamArgv.size() - 3, beamArgv.size()).equals(Arrays.asList("-gfx", "vk", "-nosteam")), "Legacy native BeamNG gains Vulkan defaults");
        EnvVars beamEnv = new EnvVars("WINEDLLOVERRIDES=custom=n,b"); beamPlan.applyEnvironment(beamEnv);
        check(beamEnv.get("WINEDLLOVERRIDES").equals("custom=n,b;browseui=") && beamEnv.get("WINEESYNC").equals("0")
                && beamEnv.get("WINEFSYNC").equals("0"), "Legacy BeamNG startup workaround is game-specific");
        EnvVars explicit = new EnvVars("WINEDLLOVERRIDES=browseui=n,b WINEESYNC=1 WINEFSYNC=1");
        beamPlan.applyEnvironment(explicit);
        check(explicit.get("WINEDLLOVERRIDES").equals("browseui=n,b") && explicit.get("WINEESYNC").equals("1")
                && explicit.get("WINEFSYNC").equals("1"), "Explicit BeamNG environment remains authoritative");
        EnvVars grouped = new EnvVars("WINEDLLOVERRIDES=other,browseui=n,b");
        beamPlan.applyEnvironment(grouped);
        check(grouped.get("WINEDLLOVERRIDES").equals("other,browseui=n,b"), "Grouped explicit DLL override remains authoritative");
        File otherNative = pe(new File(beam.getParentFile(), "OtherGame.exe"), Architecture.ARM64);
        check(resolve(fs, container, otherNative, true).nativeWorkingDirectory.equals("Z:\\home\\games\\BeamNG\\BinArm64"), "Other native games retain executable directory");
        File relocatedBeam = pe(new File(fs.getRootDir(), "home/games/BeamNG/OtherBin/BeamNG.drive.arm64.exe"), Architecture.ARM64);
        check(resolve(fs, container, relocatedBeam, true).nativeWorkingDirectory.equals("Z:\\home\\games\\BeamNG\\OtherBin"), "BeamNG working directory exception requires BinArm64 parent");
        container.drives.add(new String[]{"B", beam.getParent()});
        WindowsLaunchPlan narrowMappedBeam = WindowsLaunchPlan.resolve(fs, container, beamShortcut, new WineInfo(true));
        check(narrowMappedBeam.nativeExecutablePath.equals("B:\\BeamNG.drive.arm64.exe")
                && narrowMappedBeam.nativeWorkingDirectory.equals("Z:\\home\\games\\BeamNG"), "BeamNG root maps independently when binary directory is a drive root");
        System.out.println("PASS " + checks + " Windows argument parsing, PE revalidation and native/translated launch-plan checks");
    }
}
