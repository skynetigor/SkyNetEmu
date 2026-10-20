import android.content.Context;
import android.os.Environment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ShortcutWriter;
import com.winlator.cmod.core.ExecutableKind;
import com.winlator.cmod.core.WindowsExecutable;
import com.winlator.cmod.core.WindowsExecutable.Architecture;
import com.winlator.cmod.core.WindowsLaunchPlan;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WindowsExecutableTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte)value;
        bytes[offset + 1] = (byte)(value >>> 8);
    }

    private static void put32(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte)(value >>> (i * 8));
    }

    private static byte[] pe(Architecture architecture) {
        byte[] bytes = new byte[512];
        bytes[0] = 'M'; bytes[1] = 'Z';
        put32(bytes, 60, 128);
        bytes[128] = 'P'; bytes[129] = 'E';
        put16(bytes, 132, architecture.machine);
        put16(bytes, 148, architecture == Architecture.X86 ? 224 : 240);
        put16(bytes, 152, architecture == Architecture.X86 ? 0x10b : 0x20b);
        return bytes;
    }

    private static File write(File work, String name, byte[] bytes) throws Exception {
        File file = new File(work, name);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static void rejected(File work, String name, byte[] bytes) throws Exception {
        check(WindowsExecutable.detect(write(work, name, bytes)) == Architecture.UNKNOWN, name + " rejected");
    }

    private static Map<String, String> shortcut(Context context, Container container, File exe, String name,
                                               Map<String, String> extras, String... args) throws Exception {
        File file = ShortcutWriter.write(context, exe, container, name, extras, Arrays.asList(args));
        check(file != null, "Shortcut created: " + name);
        Map<String, String> result = new LinkedHashMap<>();
        boolean extra = false;
        for (String line : Files.readAllLines(file.toPath())) {
            if (line.equals("[Extra Data]")) { extra = true; continue; }
            if (!extra || !line.contains("=")) continue;
            int index = line.indexOf('=');
            check(!result.containsKey(line.substring(0, index)), "No duplicate key: " + line);
            result.put(line.substring(0, index), line.substring(index + 1));
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        File work = new File(args[0]);
        work.mkdirs();
        check(WindowsExecutable.detect(null) == Architecture.UNKNOWN, "Null file");
        check(WindowsExecutable.detect(work) == Architecture.UNKNOWN, "Directory");
        check(WindowsExecutable.detect(new File(work, "missing.exe")) == Architecture.UNKNOWN, "Missing file");
        for (Architecture architecture : Architecture.values()) {
            if (architecture == Architecture.UNKNOWN) continue;
            File file = write(work, architecture.id + "/Misleading.arm64.exe", pe(architecture));
            check(WindowsExecutable.detect(file) == architecture, "PE machine selects " + architecture.id);
            check(ExecutableKind.windowsArchitecture(file) == architecture, "Selected executable architecture " + architecture.id);
            check(ExecutableKind.of(file) == ExecutableKind.WINDOWS, "Windows enum remains stable");
            check(WindowsExecutable.fromId(" " + architecture.id.toUpperCase() + " ") == architecture, "Metadata ID round trip");
        }
        check(WindowsExecutable.fromId(null) == Architecture.UNKNOWN, "Null metadata");
        check(WindowsExecutable.fromId("aarch64") == Architecture.UNKNOWN, "Unknown metadata cannot select native route");
        check(Architecture.ARM64.isNativeArm64() && !Architecture.ARM64EC.isNativeArm64()
                && !Architecture.ARM64X.isNativeArm64(), "Hybrid entry points do not select plain ARM64 route");
        rejected(work, "short.exe", new byte[63]);
        byte[] bad = pe(Architecture.ARM64); bad[0] = 'X'; rejected(work, "bad-dos.exe", bad);
        bad = pe(Architecture.ARM64); bad[129] = 'X'; rejected(work, "bad-pe.exe", bad);
        bad = pe(Architecture.ARM64); bad[130] = 'X'; rejected(work, "bad-pe-zero.exe", bad);
        bad = pe(Architecture.ARM64); put32(bad, 60, -1); rejected(work, "unsigned-offset.exe", bad);
        bad = pe(Architecture.ARM64); put32(bad, 60, 32); rejected(work, "inside-dos.exe", bad);
        bad = pe(Architecture.ARM64); put32(bad, 60, 500); rejected(work, "past-eof.exe", bad);
        bad = pe(Architecture.ARM64); put16(bad, 148, 2); rejected(work, "short-optional.exe", bad);
        bad = pe(Architecture.ARM64); put16(bad, 148, 65535); rejected(work, "truncated-optional.exe", bad);
        bad = pe(Architecture.ARM64); put16(bad, 152, 0x10b); rejected(work, "wrong-optional-magic.exe", bad);
        bad = pe(Architecture.ARM64); put16(bad, 132, 0x9999); rejected(work, "unsupported-machine.exe", bad);
        File sparse = write(work, "huge-header.exe", pe(Architecture.ARM64));
        try (RandomAccessFile file = new RandomAccessFile(sparse, "rw")) {
            file.setLength(2 * 1024 * 1024);
            file.seek(60); file.write(new byte[]{1, 0, 16, 0}); // e_lfanew = 1 MiB + 1.
            file.seek(1024 * 1024 + 1); file.write(pe(Architecture.ARM64), 128, 264);
        }
        check(WindowsExecutable.detect(sparse) == Architecture.UNKNOWN, "DOS offset bound applies even to a long file");

        Context context = new Context(work);
        Environment.root = new File(work, "storage");
        Container container = new Container(new File(work, "imagefs/home/xuser-7"));
        String key = WindowsExecutable.SHORTCUT_ARCHITECTURE_KEY;
        File x64 = write(work, "x64/Game.exe", pe(Architecture.X86_64));
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put(key, "arm64"); extras.put("custom", "value"); extras.put("ignored", null);
        Map<String, String> saved = shortcut(context, container, x64, "x64", extras, "-windowed", "42");
        check(saved.get(key).equals("x86_64"), "Header overrides counterfeit architecture metadata");
        check(saved.get("execArgs").equals("-windowed 42") && saved.get("custom").equals("value")
                && !saved.containsKey("ignored"), "Ordinary shortcut extras and args preserved");
        check(extras.get(key).equals("arm64") && extras.containsKey("ignored"), "Caller extras unchanged");
        saved = shortcut(context, container, write(work, "x86/Game.exe", pe(Architecture.X86)), "x86", null);
        check(saved.get(key).equals("x86"), "x86 shortcut metadata");
        saved = shortcut(context, container, write(work, "Game.exe", new byte[64]), "unknown", extras);
        check(!saved.containsKey(key), "Unrecognized executable cannot inherit native metadata");
        File msi = write(work, "Installer.msi", pe(Architecture.ARM64));
        check(ExecutableKind.windowsArchitecture(msi) == Architecture.UNKNOWN, "MSI keeps generic Wine behavior");
        saved = shortcut(context, container, msi, "msi", extras);
        check(!saved.containsKey(key), "Installer cannot inherit native metadata");

        File armGame = write(work, "arm/BeamNG.drive.arm64.exe", pe(Architecture.ARM64));
        saved = shortcut(context, container, armGame, "BeamNG ARM64", null);
        check(saved.get(key).equals("arm64"), "ARM64 shortcut metadata");
        check(saved.get("execArgs").equals("-gfx vk -nosteam") && saved.get("gameProfile").equals("beamng-drive"), "BeamNG native Vulkan defaults");
        check(saved.get("envVars").equals("WINEESYNC=0 WINEFSYNC=0 WINEDLLOVERRIDES=browseui="), "BeamNG native startup defaults");
        Map<String, String> explicit = new LinkedHashMap<>();
        explicit.put("gameProfile", "none");
        explicit.put("envVars", "WINEESYNC=1 WINEFSYNC=1 WINEDLLOVERRIDES=custom=n,b CUSTOM=kept");
        saved = shortcut(context, container, armGame, "BeamNG custom", explicit, "-gfx", "vk", "-level", "gridmap_v2");
        check(saved.get("gameProfile").equals("none") && saved.get("execArgs").endsWith("gridmap_v2"), "Explicit profile and arguments preserved");
        check(saved.get("envVars").equals(explicit.get("envVars")), "Explicit BeamNG environment overrides defaults");
        saved = shortcut(context, container, write(work, "x64/BeamNG.drive.arm64.exe", pe(Architecture.X86_64)), "Misnamed", null);
        check(!saved.containsKey("envVars") && !saved.containsKey("execArgs"), "Filename alone cannot enable native defaults");
        saved = shortcut(context, container, write(work, "arm/OtherGame.exe", pe(Architecture.ARM64)), "Other ARM64", null);
        check(!saved.containsKey("envVars") && !saved.containsKey("execArgs"), "BeamNG defaults stay game-specific");
        File otherArm = write(work, "arm/Arguments.exe", pe(Architecture.ARM64));
        String[] nativeArgs = {"-userpath", "C:\\user data\\", "", "a\"b", "plain", "C:\\plain\\"};
        saved = shortcut(context, container, otherArm, "ARM64 structured arguments", null, nativeArgs);
        check(WindowsLaunchPlan.parseArguments(saved.get("execArgs")).equals(Arrays.asList(nativeArgs)),
                "ARM64 shortcut serializes structured arguments without losing spaces, empty values, quotes or backslashes");
        check(saved.get("execArgs").startsWith("-userpath ") && saved.get("execArgs").contains(" plain "),
                "Native structured switch arguments remain unquoted");
        Map<String, String> rawArgs = new LinkedHashMap<>();
        rawArgs.put("execArgs", "-userpath \"C:\\Keep raw path\\\"    -custom=$PATH");
        saved = shortcut(context, container, otherArm, "ARM64 raw arguments", rawArgs, nativeArgs);
        check(saved.get("execArgs").equals(rawArgs.get("execArgs")), "Explicit raw native execArgs remains verbatim");
        saved = shortcut(context, container, x64, "x64 legacy structured arguments", null, "-file", "two words");
        check(saved.get("execArgs").equals("-file two words"), "Legacy translated argument serialization remains unchanged");
        System.out.println("PASS " + checks + " PE architecture, malformed header, shortcut metadata and BeamNG native defaults checks");
    }
}
