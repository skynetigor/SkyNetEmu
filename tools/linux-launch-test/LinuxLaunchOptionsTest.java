import com.winlator.cmod.linux.LinuxLaunchOptions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** No Android runtime needed: exercise the options shared by direct, bionic and Steam launches. */
public final class LinuxLaunchOptionsTest {
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        LinuxLaunchOptions own = LinuxLaunchOptions.parse(
                "FEX_TSOENABLED=0 EMPTY=old QUOTED='hello world' taskset -c 4-7 %command% -windowed 'two words'",
                "FEX_TSOENABLED=1 EMPTY= CUSTOM=a=b LITERAL=$HOME\\path'quote FEX_TSOENABLED=2\nFEX_TSOENABLED=1");
        LinuxLaunchOptions merged = LinuxLaunchOptions.merged(
                Map.of("FEX_TSOENABLED", "profile", "PROFILE_ONLY", "kept", "EMPTY", "profile"),
                List.of("-profile"), own);
        equal("1", merged.env.get("FEX_TSOENABLED"));
        equal("", merged.env.get("EMPTY"));
        equal("a=b", merged.env.get("CUSTOM"));
        equal("$HOME\\path'quote", merged.env.get("LITERAL"));
        equal("hello world", merged.env.get("QUOTED"));
        equal("kept", merged.env.get("PROFILE_ONLY"));
        equal(List.of("taskset", "-c", "4-7"), merged.wrapper);
        equal(List.of("-profile", "-windowed", "two words"), merged.args);
        equal(List.of("-windowed", "two words"), own.args); // merging must not mutate the shortcut

        equal("0", LinuxLaunchOptions.parse("FEX_TSOENABLED=0 %command%", "").env.get("FEX_TSOENABLED"));
        equal("1", LinuxLaunchOptions.parse(null, "FEX_TSOENABLED=1").env.get("FEX_TSOENABLED"));
        equal(Map.of(), LinuxLaunchOptions.parse(null, null).env);
        equal(Map.of("GOOD", "value"), LinuxLaunchOptions.parse("", "missing =bad 1BAD=x BAD-NAME=x BAD;NAME=x GOOD=value").env);
        LinuxLaunchOptions legacy = LinuxLaunchOptions.parse("NAME='space here' -width 1280");
        equal("space here", legacy.env.get("NAME"));
        equal(List.of("-width", "1280"), legacy.args);
        System.out.println("Launch option precedence and parsing passed");
    }
}
