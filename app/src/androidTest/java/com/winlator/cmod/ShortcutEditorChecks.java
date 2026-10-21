package com.winlator.cmod;

import android.app.Instrumentation;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Exercises the actual editor state with native and translated Windows containers; user files are not edited. */
public final class ShortcutEditorChecks {
    private static Object call(Object state, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = state.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(state, args);
    }
    public static String run(Instrumentation test) throws Exception {
        StringBuilder report = new StringBuilder();
        Class<?> type = Class.forName("com.winlator.cmod.ui.shortcut.ShortcutEditorStateV2");
        Constructor<?> constructor = type.getDeclaredConstructor(Shortcut.class);
        constructor.setAccessible(true);
        boolean nativeChecked = false, translatedChecked = false;
        for (Container container : new ContainerManager(test.getTargetContext()).getWineContainers()) {
            boolean nativeArm64 = WineInfo.NATIVE_ARM64_CONTAINER_VALUE.equals(container.getExtra(WineInfo.NATIVE_ARM64_CONTAINER_KEY));
            String configBefore = FileUtils.readString(container.getConfigFile());
            String emulatorBefore = container.getEmulator();
            File file = File.createTempFile("shortcut-editor-", ".desktop", test.getTargetContext().getCacheDir());
            try {
                if (nativeArm64) container.setEmulator(null); // The real native profile deliberately omits this field.
                FileUtils.writeString(file, "[Desktop Entry]\nName=Editor regression\nExec=wine C:\\fixture.exe\n");
                Shortcut shortcut = new Shortcut(container, file);
                Object state = constructor.newInstance(shortcut);
                String emulator = (String) call(state, "getEmulator", new Class<?>[0]);
                if (nativeArm64 && !emulator.isEmpty()) throw new AssertionError("Native editor must not select an x86 emulator");
                if (!nativeArm64 && emulator.isEmpty()) throw new AssertionError("Translated Windows editor lost its emulator");
                String args = "-gfx vk -nosteam -level gridmap_v2";
                call(state, "extra", new Class<?>[]{String.class, String.class}, "execArgs", args);
                Shortcut reloaded = new Shortcut(container, file);
                Object reopened = constructor.newInstance(reloaded);
                if (!args.equals(call(reopened, "getExecArgs", new Class<?>[0]))) throw new AssertionError("Editor setting did not survive reopening");
                if (!shortcut.getExtra("emulator").isEmpty() || !reloaded.getExtra("emulator").isEmpty())
                    throw new AssertionError("Opening/editing unrelated settings must not add an emulator override");
                if (!configBefore.equals(FileUtils.readString(container.getConfigFile()))) throw new AssertionError("Editor changed container configuration");
                report.append("PASS container ").append(container.id).append(nativeArm64 ? " native ARM64" : " translated Windows")
                        .append(": editor opens, settings persist on reopen, emulator and container preserved\n");
                nativeChecked |= nativeArm64;
                translatedChecked |= !nativeArm64;
            } finally {
                container.setEmulator(emulatorBefore);
                file.delete();
            }
        }
        if (!nativeChecked || !translatedChecked) throw new AssertionError("Both native and translated Windows fixtures are required");
        return report.toString();
    }
}
