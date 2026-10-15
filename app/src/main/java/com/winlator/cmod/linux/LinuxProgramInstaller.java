package com.winlator.cmod.linux;

import android.os.Environment;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.ShortcutWriter;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;

import java.io.File;
import java.util.Map;

/**
 * Puts a Linux program the user picked from storage into a Linux container: where it can stay, how it is copied in when it
 * cannot, and the shortcut. The same rules as the container's own "Programs" screen.
 */
public final class LinuxProgramInstaller {
    /** What of the picked program's surroundings goes into the container. */
    public enum CopyMode {
        /** The program's folder. */
        FOLDER,
        /** The folder above the program's, with everything beside it. */
        WHOLE_GAME,
        /** The program's folder only; the rest of the game stays on storage and is bound in beside it. */
        PROGRAM_ONLY
    }

    private LinuxProgramInstaller() {}

    /**
     * False only for an x86-64 program that Box64 reads where it lies (Box64 reads a program instead of mapping it) on the
     * internal storage that every container binds. FEX maps the program and its libraries as executable, shared storage
     * refuses that, scripts start programs of their own, and other volumes are not bound: all of those are copied in.
     */
    public static boolean needsCopy(Container container, File file) {
        if (LinuxSession.elfMachine(file) != 62) return true;
        if (!LinuxEmulator.BOX64.equals(LinuxEmulator.choice(container))) return true;
        return !file.getPath().startsWith(Environment.getExternalStorageDirectory().getPath() + "/");
    }

    /** Why {@link #needsCopy} says yes, in a sentence for the person adding the program (the reason depends on what the program is). */
    public static String copyReason(Container container, File file) {
        if (!file.getPath().startsWith(Environment.getExternalStorageDirectory().getPath() + "/")) {
            return "This file is on a storage that the container does not see, so it is copied in.";
        }
        switch (LinuxSession.elfMachine(file)) {
            case 3:
                return "A 32-bit program is run by FEX, even in a Box64 container (Box64 cannot run it), and FEX needs the program and its "
                        + "libraries as executable files, which shared storage does not allow.";
            case 62:
                return "This container runs x86-64 programs through FEX, which needs the program and its libraries as executable files, "
                        + "which shared storage does not allow. (Box64 reads a program where it lies, so a Box64 container does not copy it.)";
            case 183:
                return "An ARM64 program is started as it is, and shared storage does not allow starting programs from it.";
            default:
                return "A script starts programs of its own from its folder, and shared storage does not allow that.";
        }
    }

    /** The folder above the program's folder, when the game may keep its data there; null when it cannot be told. */
    public static File gameFolder(File file) {
        File folder = file.getParentFile();
        if (folder == null) return null;
        File game = folder.getParentFile();
        if (game == null || game.getParentFile() == null || game.getName().isEmpty()) return null;
        return game;
    }

    /** The size of a file or of everything under a folder. */
    public static long size(File file) {
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children == null) return 0;
        for (File child : children) total += size(child);
        return total;
    }

    /**
     * Copies the program into the container's programs/ folder. Blocking (a game is tens of GB): call it off the main thread.
     * @return the program's path as the container sees it, or null when the copy failed (nothing is left behind)
     */
    public static String copy(Container container, File file, CopyMode mode) {
        File folder = file.getParentFile();
        if (folder == null) return null;
        File game = mode == CopyMode.FOLDER ? null : gameFolder(file);
        if (game == null) mode = CopyMode.FOLDER;
        File programs = new File(container.getLinuxHomeDir(), "programs");
        programs.mkdirs();
        Callback<File> executable = copied -> FileUtils.chmod(copied, 505 /* octal 0771 */);
        File target = freeFolder(programs, mode == CopyMode.FOLDER ? folder.getName() : game.getName());
        boolean ok = false;
        try {
            switch (mode) {
                case FOLDER:
                    ok = FileUtils.copy(folder, target, executable);
                    break;
                case WHOLE_GAME:
                    ok = FileUtils.copy(game, target, executable);
                    break;
                default:
                    target.mkdirs();
                    ok = FileUtils.copy(folder, new File(target, folder.getName()), executable);
                    if (ok) writeBinds(game, folder, target);
            }
        }
        catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            FileUtils.delete(target);
            return null;
        }
        String base = "/root/programs/" + target.getName() + "/";
        return mode == CopyMode.FOLDER ? base + file.getName() : base + folder.getName() + "/" + file.getName();
    }

    /** The rest of the game stays on storage: its places in the copy must exist, and .binds says what is bound to each. */
    private static void writeBinds(File game, File programFolder, File target) throws java.io.IOException {
        StringBuilder binds = new StringBuilder();
        File[] entries = game.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.getName().equals(programFolder.getName())) continue;
                File place = new File(target, entry.getName());
                if (entry.isDirectory()) place.mkdirs();
                else place.createNewFile();
                binds.append(entry.getPath()).append('\t').append("/root/programs/").append(target.getName()).append('/')
                        .append(entry.getName()).append('\n');
            }
        }
        FileUtils.writeString(new File(target, ".binds"), binds.toString());
    }

    private static File freeFolder(File parent, String name) {
        File candidate = new File(parent, name);
        int n = 2;
        while (candidate.exists()) candidate = new File(parent, name + "-" + n++);
        return candidate;
    }

    /** Writes the shortcut of a program (a path as the container sees it) and returns the .desktop file. */
    public static File addShortcut(Container container, String name, String guestPath, Map<String, String> extras) {
        return ContainerManager.addLinuxShortcut(container, ShortcutWriter.freeName(container, name), guestPath, extras);
    }
}
