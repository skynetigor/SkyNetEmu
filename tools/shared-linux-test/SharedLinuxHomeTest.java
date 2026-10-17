import com.winlator.cmod.linux.SharedLinuxHome;
import java.nio.file.*;
import java.util.Comparator;

public class SharedLinuxHomeTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("shared-linux-test");
        try {
            Path old = Files.createDirectories(work.resolve("old")), shared = Files.createDirectories(work.resolve("shared"));
            Files.createDirectories(old.resolve(".config/game"));
            Files.createDirectories(shared.resolve(".config/game"));
            Files.writeString(old.resolve(".config/game/preferences"), "old settings");
            Files.writeString(shared.resolve(".config/game/preferences"), "Steam settings");
            Files.writeString(old.resolve(".config/game/save"), "user progress");
            Files.createDirectories(old.resolve("programs/game/bin"));
            Files.writeString(old.resolve("programs/game/bin/start"), "program");
            Files.createDirectories(old.resolve("Desktop"));
            Files.writeString(old.resolve("Desktop/old.desktop"), "old container id");
            Files.createSymbolicLink(old.resolve("broken-link"), old.resolve("missing"));
            SharedLinuxHome.merge(old.toFile(), shared.toFile());
            check(Files.readString(shared.resolve(".config/game/preferences")).equals("Steam settings"), "do not overwrite shared preferences");
            check(Files.readString(old.resolve(".config/game/preferences")).equals("old settings"), "retain conflicting original preferences");
            check(Files.readString(shared.resolve(".config/game/save")).equals("user progress"), "preserve existing saves");
            check(Files.readString(shared.resolve("programs/game/bin/start")).equals("program"), "program readable without copying game data");
            check(!Files.exists(shared.resolve("Desktop/old.desktop")), "shortcuts require separate migration");
            check(Files.isSymbolicLink(shared.resolve("broken-link")), "preserve broken symbolic links");
            SharedLinuxHome.merge(old.toFile(), shared.toFile());
            check(Files.readString(shared.resolve(".config/game/save")).equals("user progress"), "repeat migration is safe");
            System.out.println("PASS shared Linux home: saves, conflicts, program paths, symlinks and repeat migration");
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
