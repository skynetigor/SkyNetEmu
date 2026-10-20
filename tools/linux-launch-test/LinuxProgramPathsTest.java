import com.winlator.cmod.linux.LinuxProgramPaths;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LinuxProgramPathsTest {
    private static void check(String expected, Path home, Path file) {
        String actual = LinuxProgramPaths.inHome(home.toFile(), file.toFile());
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(file + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        Path home = Files.createDirectories(work.resolve("container/linux-home"));
        Path game = Files.createDirectories(home.resolve(".local/share/BeamNG 0.34/BinLinux"));
        Path exe = Files.createFile(game.resolve("BeamNG.drive.x64"));
        String guest = "/root/.local/share/BeamNG 0.34/BinLinux/BeamNG.drive.x64";
        check(guest, home, exe);
        check(guest, home, game.resolve("../BinLinux/BeamNG.drive.x64"));

        // Android's /data/data and /data/user/0 aliases must identify the same home.
        Path alias = Files.createSymbolicLink(work.resolve("home-alias"), home);
        check(guest, home, alias.resolve(home.relativize(exe)));
        check(guest, alias, exe);
        Path internalLink = Files.createSymbolicLink(home.resolve("game-link"), game);
        check(guest, home, internalLink.resolve("BeamNG.drive.x64"));

        Path shared = Files.createDirectories(work.resolve("shared"));
        Path externalExe = Files.createFile(shared.resolve("game.x64"));
        check(null, home, externalExe);
        Path escape = Files.createSymbolicLink(home.resolve("shared-link"), shared);
        check(null, home, escape.resolve("game.x64"));
        check(null, home, home.resolve("../outside.x64"));
        check(null, home, work.resolve("container/linux-home-other/game.x64"));
        check(null, home, work.resolve("other-container/linux-home/game.x64"));
        System.out.println("PASS Linux home paths, host aliases, internal links and outside-home boundaries");
    }
}
