#!/usr/bin/env python3
"""Host-only game-version regressions. Requires JDK 17 and ANDROID_HOME (or ANDROID_JAR). No device access."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/com/winlator/cmod"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else Path("/usr/bin")
sdk = os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", ""))
android = os.environ.get("ANDROID_JAR") or str(Path(sdk) / "platforms/android-35/android.jar")
if not Path(android).is_file():
    raise SystemExit("Set ANDROID_HOME or ANDROID_JAR to an installed Android SDK (compile-only API stubs).")

# Minimal fixtures for Android/container boundaries. The real profile model, matcher and Wine path resolver run below.
stubs = {
    "android/content/Context.java": """package android.content;
        public class Context { public java.io.File getFilesDir() { return new java.io.File("/unused"); } }""",
    "android/util/Log.java": """package android.util;
        public class Log {
            public static int w(String tag, String message, Throwable e) { return 0; }
            public static int w(String tag, String message) { return 0; }
        }""",
    "com/winlator/cmod/container/Container.java": """package com.winlator.cmod.container;
        import java.io.File; import java.util.List;
        public class Container {
            public final File root; public final boolean linux; public String drive;
            public Container(File root, boolean linux) { this.root = root; this.linux = linux; }
            public boolean isLinux() { return linux; }
            public File getRootDir() { return root; }
            public File getLinuxHomeDir() { return new File(root, "linux-home"); }
            public List<String[]> drivesIterator() { return java.util.Collections.singletonList(new String[]{"D", drive}); }
        }""",
    "com/winlator/cmod/container/Shortcut.java": """package com.winlator.cmod.container;
        import java.util.HashMap; import java.util.Map;
        public class Shortcut {
            public final Container container; public final String path;
            public final Map<String, String> extras = new HashMap<>();
            public Shortcut(Container c, String p) { container = c; path = p; }
            public String getExtra(String key) { return extras.getOrDefault(key, ""); }
            public boolean isSteamGame() { return path.startsWith("steam-game:"); }
        }""",
    "com/winlator/cmod/gameprofile/GameProfiles.java": """package com.winlator.cmod.gameprofile;
        public class GameProfiles { public static final String NONE="none", EXTRA_PROFILE="gameProfile", EXTRA_ROOT="gameRoot"; }""",
    "com/winlator/cmod/gameprofile/GameProfileCatalog.java": """package com.winlator.cmod.gameprofile;
        import android.content.Context; import java.util.*;
        public class GameProfileCatalog {
            public static final Map<String, GameProfile> profiles = new LinkedHashMap<>();
            public static class IndexEntry {
                public String id, steamAppId;
                IndexEntry(GameProfile p) { id = p.id; steamAppId = p.steamAppId; }
            }
            public static List<IndexEntry> index(Context c, boolean net) {
                List<IndexEntry> entries = new ArrayList<>();
                for (GameProfile p : profiles.values()) entries.add(new IndexEntry(p));
                return entries;
            }
            public static GameProfile game(Context c, String id, boolean net) { return profiles.get(id); }
        }""",
    "com/winlator/cmod/linux/LinuxSession.java": """package com.winlator.cmod.linux;
        import android.content.Context; import java.io.File; import com.winlator.cmod.container.Container;
        public class LinuxSession {
            public static String steamAppId(String p) { return p.substring("steam-game:".length()); }
            public static File hostFile(Context c, Container container, String p) {
                return p.startsWith("/root/") ? new File(container.getLinuxHomeDir(), p.substring(6)) : new File(p);
            }
        }""",
    "com/winlator/cmod/linux/LinuxSteamGames.java": """package com.winlator.cmod.linux;
        import java.io.File; import java.util.*; import com.winlator.cmod.container.Container;
        public class LinuxSteamGames {
            public static final Map<String, Game> games = new HashMap<>();
            public static class Game {
                public String appId = "42"; public long buildId; public File root;
                public Game(long build, File root) { buildId = build; this.root = root; }
            }
            public static Map<String, Game> installed(Container c) { return new HashMap<>(games); }
            public static File installFolder(Container c, Game game) { return game.root; }
        }""",
    "com/winlator/cmod/xenvironment/ImageFs.java": """package com.winlator.cmod.xenvironment;
        import java.io.File; import android.content.Context;
        public class ImageFs {
            public static File root;
            public static ImageFs find(Context c) { return new ImageFs(); }
            public File getRootDir() { return root; }
        }""",
}
with tempfile.TemporaryDirectory(prefix="game-version-test-") as work:
    work = Path(work)
    sources = []
    for path, text in stubs.items():
        target = work / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        sources.append(str(target))
    sources += [str(SOURCE / path) for path in (
        "gameprofile/GameProfile.java", "gameprofile/GameMatcher.java", "gameprofile/GameVersionMatcher.java",
        "gameprofile/ShortcutGameVersions.java", "core/Versions.java", "core/GameFolder.java")]
    sources.append(str(Path(__file__).with_name("GameVersionsTest.java")))
    subprocess.run([str(JAVA / "javac"), "-cp", android, "-d", str(work), *sources], check=True)
    subprocess.run([str(JAVA / "java"), "-cp", os.pathsep.join((str(work), android)),
                    "com.winlator.cmod.gameprofile.GameVersionsTest", str(work / "games")], check=True)
