package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/** Reads PE architecture without loading an executable or guessing from its filename. */
public final class WindowsExecutable {
    public static final String SHORTCUT_ARCHITECTURE_KEY = "windowsExecutableArchitecture";
    // A damaged DOS header must not turn shortcut discovery into an unbounded header scan.
    private static final long MAX_PE_HEADER_OFFSET = 1024 * 1024;

    private WindowsExecutable() {}

    public enum Architecture {
        X86("x86", 0x014c),
        X86_64("x86_64", 0x8664),
        ARM64("arm64", 0xaa64),
        ARM64EC("arm64ec", 0xa641),
        ARM64X("arm64x", 0xa64e),
        UNKNOWN("unknown", 0);

        public final String id;
        public final int machine;

        Architecture(String id, int machine) {
            this.id = id;
            this.machine = machine;
        }

        /** Only a plain ARM64 entry point selects the native game launch route. */
        public boolean isNativeArm64() { return this == ARM64; }
    }

    public static Architecture fromId(String id) {
        if (id == null) return Architecture.UNKNOWN;
        String normalized = id.trim().toLowerCase(Locale.ENGLISH);
        for (Architecture architecture : Architecture.values()) {
            if (architecture.id.equals(normalized)) return architecture;
        }
        return Architecture.UNKNOWN;
    }

    /**
     * Reads the DOS header and 26 bytes at e_lfanew, including the PE signature, machine and optional-header magic.
     * This also accepts DLLs so runtime capabilities can be checked using their actual installed files. ARM64X images
     * whose final PE machine is ARM64 remain ARM64 here; distinguishing their extra views requires load-config metadata.
     */
    public static Architecture detect(File file) {
        if (file == null || !file.isFile()) return Architecture.UNKNOWN;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            if (length < 64) return Architecture.UNKNOWN;
            byte[] dos = new byte[64];
            input.readFully(dos);
            if (dos[0] != 'M' || dos[1] != 'Z') return Architecture.UNKNOWN;
            long offset = uint32(dos, 60);
            if (offset < dos.length || offset > MAX_PE_HEADER_OFFSET || offset > length - 26) return Architecture.UNKNOWN;
            input.seek(offset);
            byte[] pe = new byte[26];
            input.readFully(pe);
            if (pe[0] != 'P' || pe[1] != 'E' || pe[2] != 0 || pe[3] != 0) return Architecture.UNKNOWN;
            int optionalSize = uint16(pe, 20);
            int magic = uint16(pe, 24);
            if ((magic != 0x10b && magic != 0x20b) || optionalSize < (magic == 0x10b ? 96 : 112)
                    || offset + 24L + optionalSize > length) return Architecture.UNKNOWN;
            int machine = uint16(pe, 4);
            for (Architecture architecture : Architecture.values()) {
                if (architecture == Architecture.UNKNOWN || architecture.machine != machine) continue;
                boolean pe32 = architecture == Architecture.X86;
                return pe32 == (magic == 0x10b) ? architecture : Architecture.UNKNOWN;
            }
        }
        catch (IOException | SecurityException ignored) {}
        return Architecture.UNKNOWN;
    }

    private static int uint16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long uint32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }
}
