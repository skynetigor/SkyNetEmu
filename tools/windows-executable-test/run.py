#!/usr/bin/env python3
"""Host PE detection, shortcut metadata and launch-plan checks. Requires JDK 17; no Android SDK or device."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/com/winlator/cmod"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else Path("/usr/bin")

stubs = {
    "android/content/Context.java": """package android.content;
        public class Context {
            private final java.io.File root;
            public Context(java.io.File root) { this.root = root; }
            public java.io.File getFilesDir() { return root; }
        }""",
    "android/os/Environment.java": """package android.os;
        public class Environment {
            public static java.io.File root;
            public static java.io.File getExternalStorageDirectory() { return root; }
        }""",
    "androidx/annotation/NonNull.java": """package androidx.annotation; public @interface NonNull {}""",
    "com/winlator/cmod/container/Container.java": """package com.winlator.cmod.container;
        import java.io.File; import java.util.*;
        public class Container {
            public final int id = 7;
            private final File root;
            public final List<String[]> drives = new ArrayList<>();
            public final Map<String, String> extras = new LinkedHashMap<>();
            public Container(File root) { this.root = root; }
            public File getRootDir() { return root; }
            public File getDesktopDir() { return new File(root, ".wine/drive_c/users/xuser/Desktop"); }
            public File getIconsDir(int size) { return new File(root, "icons/" + size); }
            public List<String[]> drivesIterator() { return drives; }
            public String getExtra(String key) { return extras.getOrDefault(key, ""); }
            public void setDrives(String drives) {}
            public void saveData() {}
        }""",
    "com/winlator/cmod/container/Shortcut.java": """package com.winlator.cmod.container;
        import java.util.*;
        public class Shortcut {
            public final Container container;
            public final String path;
            public final Map<String, String> extras = new LinkedHashMap<>();
            public Shortcut(Container container, String path) { this.container = container; this.path = path; }
            public String getExtra(String key) { return extras.getOrDefault(key, ""); }
        }""",
    "com/winlator/cmod/core/ExeIconExtractor.java": """package com.winlator.cmod.core;
        public class ExeIconExtractor {
            public static boolean extractIcon(java.io.File exe, java.io.File icon) { return false; }
        }""",
    "com/winlator/cmod/core/FileUtils.java": """package com.winlator.cmod.core;
        import java.io.*; import java.nio.file.*;
        public class FileUtils {
            public static boolean writeString(File file, String value) {
                try { Files.writeString(file.toPath(), value); return true; } catch (IOException e) { return false; }
            }
            public static void copy(File a, File b) { throw new AssertionError("Unexpected icon copy"); }
            public static void copy(android.content.Context context, String asset, File destination) {
                throw new AssertionError("Unexpected asset copy");
            }
        }""",
    "com/winlator/cmod/core/WineInfo.java": """package com.winlator.cmod.core;
        public class WineInfo {
            public static final String NATIVE_ARM64_CONTAINER_KEY = "windowsArchitecture";
            public static final String NATIVE_ARM64_CONTAINER_VALUE = "arm64";
            private final boolean nativeArm64;
            public WineInfo(boolean nativeArm64) { this.nativeArm64 = nativeArm64; }
            public boolean supportsNativeArm64() { return nativeArm64; }
        }""",
    "com/winlator/cmod/core/WineUtils.java": """package com.winlator.cmod.core;
        public class WineUtils {
            public static void createDosdevicesSymlinks(com.winlator.cmod.container.Container c) {}
        }""",
    "com/winlator/cmod/xenvironment/ImageFs.java": """package com.winlator.cmod.xenvironment;
        public class ImageFs {
            public static final String USER = "xuser";
            private final java.io.File root;
            public ImageFs(java.io.File root) { this.root = root; }
            public java.io.File getRootDir() { return root; }
        }""",
    "com/winlator/cmod/linux/LinuxSession.java": """package com.winlator.cmod.linux;
        public class LinuxSession { public static int elfMachine(java.io.File file) { return 0; } }""",
}

with tempfile.TemporaryDirectory(prefix="windows-executable-test-") as work:
    work = Path(work)
    sources = []
    for path, text in stubs.items():
        target = work / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        sources.append(str(target))
    sources += [str(SOURCE / path) for path in (
        "core/WindowsExecutable.java", "core/ExecutableKind.java", "core/EnvVars.java", "container/ShortcutWriter.java",
        "core/WindowsLaunchPlan.java", "core/GameFolder.java")]
    sources.append(str(Path(__file__).with_name("WindowsExecutableTest.java")))
    sources.append(str(Path(__file__).with_name("WindowsLaunchPlanTest.java")))
    subprocess.run([str(JAVA / "javac"), "-d", str(work), *sources], check=True)
    subprocess.run([str(JAVA / "java"), "-cp", str(work), "WindowsExecutableTest", str(work / "games")], check=True)
    subprocess.run([str(JAVA / "java"), "-cp", str(work), "WindowsLaunchPlanTest", str(work / "launch-games")], check=True)
