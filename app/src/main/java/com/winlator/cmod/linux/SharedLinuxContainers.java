package com.winlator.cmod.linux;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.steam.SteamContainer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Comparator;

/** One Linux home for Steam and all Linux programs. Old data/configurations are retained for recovery. */
public final class SharedLinuxContainers {
    public static final String REDIRECT = ".linux-shared-id";
    private SharedLinuxContainers() {}

    public static Container find(ContainerManager manager) {
        Container first = null;
        for (Container c : manager.getContainers()) {
            if (!c.isLinux()) continue;
            if (SteamContainer.MANAGED_STEAM.equals(c.getExtra(SteamContainer.EXTRA_MANAGED))) return c;
            if (first == null || c.id < first.id) first = c;
        }
        return first;
    }

    public static void consolidate(Context context, ContainerManager manager) {
        Container shared = find(manager);
        if (shared == null) return;
        long linuxCount = manager.getContainers().stream().filter(Container::isLinux).count();
        if (linuxCount == 1 && "1".equals(shared.getExtra("linuxSharedVersion"))) return;
        ArrayList<Container> old = new ArrayList<>(manager.getContainers());
        old.sort(Comparator.comparingInt(c -> c.id));
        // Snapshot before changing any defaults or home: every existing shortcut keeps its previous configuration.
        for (Container c : old) if (c.isLinux()) snapshot(context, c);
        shared.setName("Linux");
        shared.putExtra(SteamContainer.EXTRA_MANAGED, SteamContainer.MANAGED_STEAM);
        shared.saveData();
        for (Container source : old) {
            if (!source.isLinux() || source.id == shared.id) continue;
            try {
                File links = new File(shared.getLinuxHomeDir(), ".skynet/legacy-homes");
                if (!links.isDirectory() && !links.mkdirs()) throw new IOException("Cannot create migration folder");
                File link = new File(links, String.valueOf(source.id));
                if (!Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
                    Files.createSymbolicLink(link.toPath(), source.getLinuxHomeDir().toPath());
                SharedLinuxHome.merge(source.getLinuxHomeDir(), shared.getLinuxHomeDir());
                for (int size : new int[]{16, 32, 48, 64}) {
                    File icons = source.getIconsDir(size);
                    if (icons.isDirectory()) SharedLinuxHome.merge(icons, shared.getIconsDir(size));
                }
                File[] shortcuts = source.getDesktopDir().listFiles((dir, name) -> name.endsWith(".desktop"));
                if (shortcuts != null) for (File file : shortcuts) {
                    Shortcut shortcut = new Shortcut(source, file);
                    // The shared container owns one Steam shortcut. Preserve all other shortcuts, including name collisions.
                    if (LinuxSession.STEAM_PROGRAM.equals(shortcut.path)) continue;
                    File originalFile = new File(file.getPath() + ".before-shared-linux");
                    String text = FileUtils.readString(originalFile.isFile() ? originalFile : file);
                    String original = text;
                    text = text.replace("container_id:" + source.id + "\n", "container_id:" + shared.id + "\n");
                    text = text.replace("/root/", "/root/.skynet/legacy-homes/" + source.id + "/");
                    File destination = new File(shared.getDesktopDir(), file.getName());
                    if (destination.exists() && !text.equals(FileUtils.readString(destination)))
                        destination = new File(shared.getDesktopDir(), shortcut.name + " (" + source.id + ").desktop");
                    shared.getDesktopDir().mkdirs();
                    Files.write(destination.toPath(), text.getBytes(StandardCharsets.UTF_8));
                    // Pinned Android shortcuts may still refer to this original file.
                    if (!new File(file.getPath() + ".before-shared-linux").exists())
                        Files.write(new File(file.getPath() + ".before-shared-linux").toPath(), original.getBytes(StandardCharsets.UTF_8));
                    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
                }
                Files.write(new File(source.getRootDir(), REDIRECT).toPath(), String.valueOf(shared.id).getBytes(StandardCharsets.UTF_8));
                File backup = new File(source.getRootDir(), ".container.before-shared-linux");
                if (!source.getConfigFile().renameTo(backup)) throw new IOException("Cannot archive " + source.getConfigFile());
                manager.getContainers().remove(source);
                Log.i("SharedLinuxContainers", "Migrated Linux container " + source.id + " into " + shared.id);
            } catch (IOException | RuntimeException e) {
                // Leave the source registered if consolidation failed: never hide inaccessible games/data.
                Log.e("SharedLinuxContainers", "Cannot consolidate container " + source.id, e);
            }
        }
        if (manager.getContainers().stream().filter(Container::isLinux).count() == 1) {
            shared.putExtra("linuxSharedVersion", "1");
            shared.saveData();
        }
    }

    private static void snapshot(Context context, Container container) {
        File[] files = container.getDesktopDir().listFiles((dir, name) -> name.endsWith(".desktop"));
        if (files == null) return;
        for (File file : files) LinuxShortcutDefaults.snapshot(context, container, new Shortcut(container, file));
    }
}
