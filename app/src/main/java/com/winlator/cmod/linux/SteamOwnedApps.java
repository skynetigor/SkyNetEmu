package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The apps the signed-in account owns, from the licenses in Steam's {@code appcache/packageinfo.vdf}. */
public final class SteamOwnedApps {
    private static final long MAGIC_BASE = 0x06565500L;

    private SteamOwnedApps() {
    }

    public static Set<Long> read(File file) throws IOException {
        return read(Files.readAllBytes(file.toPath()));
    }

    public static Set<Long> read(byte[] data) throws IOException {
        if (data.length < 8) throw new IOException("packageinfo.vdf is too short");
        ByteBuffer buffer = SteamVdf.little(data, 0, data.length);
        long magic = buffer.getInt() & 0xffffffffL;
        int version = (int) (magic & 0xff);
        if ((magic & 0xffffff00L) != MAGIC_BASE || version < 39) throw new IOException("Unsupported packageinfo.vdf version " + Long.toHexString(magic));
        buffer.getInt();
        // Version 39 has no access token per package.
        int header = 4 + 20 + 4 + (version >= 40 ? 8 : 0);
        Set<Long> owned = new HashSet<>();
        while (buffer.remaining() >= 4) {
            long packageId = buffer.getInt() & 0xffffffffL;
            if (packageId == 0xffffffffL) break;
            if (buffer.remaining() < header - 4) throw new IOException("Truncated package " + packageId);
            buffer.position(buffer.position() + header - 4);
            Map<String, Object> root = SteamVdf.readMap(buffer, null);
            Map<String, Object> node = SteamVdf.map(root, Long.toString(packageId));
            Map<String, Object> apps = SteamVdf.map(node, "appids");
            if (apps == null) continue;
            for (Object value : apps.values()) {
                if (value instanceof Number) owned.add(((Number) value).longValue());
            }
        }
        return owned;
    }
}
