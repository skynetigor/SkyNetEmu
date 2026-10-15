package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.ExecutableKind;

/**
 * Whether a Linux container can run a program of a kind, and if not, why in a sentence. The same conditions {@link LinuxSession} refuses a
 * program for when it is started, asked earlier: when a game is added, for the containers to choose from.
 */
public final class LinuxProgramSupport {
    private LinuxProgramSupport() {}

    /** null when the container can run a program of this kind; otherwise what is missing. */
    public static String refusal(Context context, Container container, ExecutableKind kind) {
        if (container == null || !container.isLinux()) return "it is not a Linux container";
        switch (kind) {
            case LINUX_X64: {
                String choice = LinuxEmulator.choice(container);
                if (LinuxEmulator.NONE.equals(choice)) return "it has no x86 emulator (Box64 or FEX)";
                LinuxEmulator.Installed emulator = LinuxEmulator.resolve(context, container);
                if (emulator == null) return "its x86 emulator is not installed";
                if (LinuxEmulator.FEX.equals(emulator.emulator) && LinuxRootfs.resolve(context, container) == null) return LinuxFex.NEEDS_ROOTFS;
                return null;
            }
            case LINUX_X86: {
                // Box64 cannot run a 32-bit program: FEX does, the container's or any installed build, with a root filesystem.
                LinuxEmulator.Installed emulator = LinuxEmulator.resolve(context, container);
                boolean fex = emulator != null && LinuxEmulator.FEX.equals(emulator.emulator) || !LinuxEmulator.list(context, LinuxEmulator.FEX).isEmpty();
                if (!fex) return "FEX is not installed (Components, Linux Emulator)";
                if (LinuxRootfs.resolve(context, container) == null && LinuxRootfs.list(context).isEmpty()) return LinuxFex.NEEDS_ROOTFS;
                return null;
            }
            default:
                // An ARM64 program or a script needs no emulator.
                return null;
        }
    }
}
