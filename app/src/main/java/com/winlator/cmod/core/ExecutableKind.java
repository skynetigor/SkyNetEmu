package com.winlator.cmod.core;

import com.winlator.cmod.linux.LinuxSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * What a file the user picked to add as a game runs on: Wine (.exe, .msi, .bat) or a Linux container (an ELF program or a
 * shell script). Only files with no extension or a Linux-looking one are opened to read their header, so a folder of game
 * data is listed without reading every file.
 */
public enum ExecutableKind {
    WINDOWS,
    LINUX_X64,
    LINUX_ARM64,
    LINUX_SCRIPT,
    /** A 32-bit x86 Linux program: it runs on FEX, with an x86-64 root filesystem that has the 32-bit libraries (Box64 cannot). */
    LINUX_X86,
    NONE;

    public boolean isLinux() {
        return this == LINUX_X64 || this == LINUX_ARM64 || this == LINUX_SCRIPT || this == LINUX_X86;
    }

    /** Architecture of a selected Windows program. Installers and scripts keep the ordinary Wine path. */
    public static WindowsExecutable.Architecture windowsArchitecture(File file) {
        if (file == null || !file.getName().toLowerCase(Locale.ENGLISH).endsWith(".exe"))
            return WindowsExecutable.Architecture.UNKNOWN;
        return WindowsExecutable.detect(file);
    }

    public static ExecutableKind of(File file) {
        if (file == null || !file.isFile()) return NONE;
        String name = file.getName().toLowerCase(Locale.ENGLISH);
        if (name.endsWith(".exe") || name.endsWith(".msi") || name.endsWith(".bat")) return WINDOWS;
        if (name.endsWith(".sh")) return LINUX_SCRIPT;
        if (!mayBeLinux(name)) return NONE;
        switch (LinuxSession.elfMachine(file)) {
            case 62: return LINUX_X64;
            case 183: return LINUX_ARM64;
            case 3: return LINUX_X86;
            default: return startsWithShebang(file) ? LINUX_SCRIPT : NONE;
        }
    }

    /** No extension, or one that Linux games give their programs ("game.x86_64", "run.bin", "tool.AppImage"). */
    private static boolean mayBeLinux(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot <= 0) return true;
        String ext = lowerName.substring(dot + 1);
        return ext.equals("x86_64") || ext.equals("x86") || ext.equals("x64") || ext.equals("bin") || ext.equals("appimage")
                || ext.equals("arm64") || ext.equals("aarch64");
    }

    private static boolean startsWithShebang(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return in.read() == '#' && in.read() == '!';
        }
        catch (IOException e) {
            return false;
        }
    }
}
