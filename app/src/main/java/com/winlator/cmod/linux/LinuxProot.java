package com.winlator.cmod.linux;

import android.content.Context;
import com.winlator.cmod.container.Container;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** Explicitly selected, matched tracer/loader pairs; the runtime remains the default. */
public final class LinuxProot {
    public static final String EXTRA = "linuxProotMode";

    private LinuxProot() {}

    public static String mode(Container container) {
        String value = container.getExtra(EXTRA);
        if (value.isEmpty() || "runtime".equals(value)) return "runtime";
        if ("baseline".equals(value) || "filters".equals(value) || "fastpath".equals(value)) return value;
        throw new IllegalStateException("Unsupported experimental PRoot mode: " + value);
    }

    public static synchronized File hostDir(Context context, Container container, File runtimeHost) {
        String mode = mode(container);
        if ("runtime".equals(mode) || LinuxBionicSession.enabled(container))
            return runtimeHost;
        File directory = new File(context.getFilesDir(), "linux-proot/" + mode);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Cannot create experimental PRoot directory");
        try {
            String asset = "linux/proot/" + mode + "/";
            String manifest;
            try (InputStream input = context.getAssets().open(asset + "SHA256SUMS")) {
                manifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            String[] artifacts = "fastpath".equals(mode)
                    ? new String[]{"proot", "loader", "libtalloc.so.2", "libskyfastpath.so"}
                    : new String[]{"proot", "loader", "libtalloc.so.2"};
            for (String name : artifacts) {
                String expected = null;
                for (String line : manifest.split("\n")) {
                    if (line.length() == 66 + name.length() && line.endsWith("  " + name))
                        expected = line.substring(0, 64);
                }
                if (expected == null || !expected.matches("[0-9a-f]{64}"))
                    throw new IOException("Missing checksum for " + name);
                File target = new File(directory, name);
                if (!target.isFile() || !expected.equals(hash(target))) {
                    File temporary = new File(directory, name + ".tmp");
                    try {
                        try (InputStream input = context.getAssets().open(asset + name)) {
                            Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (!expected.equals(hash(temporary))) throw new IOException("PRoot checksum mismatch: " + name);
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        Files.deleteIfExists(temporary.toPath());
                    }
                }
                if (!target.setExecutable(true, true)) throw new IOException("Cannot make PRoot artifact executable: " + name);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare experimental PRoot " + mode, e);
        }
        android.util.Log.i("LinuxProot", "Selected matched experimental PRoot pair: " + mode);
        return directory;
    }

    static String bindMap(java.util.List<String> arguments, File root) {
        StringBuilder table = new StringBuilder("/\t").append(root.getPath()).append('\n');
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (!"-b".equals(arguments.get(i))) continue;
            String specification = arguments.get(++i);
            int colon = specification.indexOf(':');
            String host = colon < 0 ? specification : specification.substring(0, colon);
            String guest = colon < 0 ? specification : specification.substring(colon + 1);
            if (!host.startsWith("/") || !guest.startsWith("/") || host.contains("\n")
                    || guest.contains("\n") || host.contains("\t") || guest.contains("\t")
                    || guest.contains(":") || host.contains(":"))
                throw new IllegalStateException("Unsupported fast-path bind specification: " + specification);
            table.append(guest).append('\t').append(host).append('\n');
        }
        return table.toString();
    }

    private static String hash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
