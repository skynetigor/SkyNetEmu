package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** Keeps executable mappings off Android's noexec shared storage without copying game data. */
public final class LinuxExecutableStaging {
    private LinuxExecutableStaging() {}

    public static boolean isBeamNG(File executable) {
        return executable != null && "BeamNG.drive.x64".equals(executable.getName())
                && executable.getParentFile() != null && "BinLinux".equals(executable.getParentFile().getName());
    }

    public static synchronized File stageBeamNG(File executable, File stagingRoot) throws IOException {
        if (!isBeamNG(executable)) throw new IOException("Unsupported shared-storage executable: " + executable);
        File source = executable.getParentFile().getCanonicalFile();
        File target = new File(new File(stagingRoot, directoryKey(source)), "BinLinux");
        Files.createDirectories(target.toPath());
        File[] entries = source.listFiles();
        if (entries == null) throw new IOException("Cannot read BeamNG executable directory: " + source);
        Set<String> retained = new HashSet<>();
        for (File entry : entries) {
            if (!entry.isFile()) continue;
            if (!entry.getCanonicalFile().getParentFile().equals(source))
                throw new IOException("BeamNG executable directory contains an escaping link: " + entry);
            if (!isElf(entry)) continue;
            retained.add(entry.getName());
            File copy = new File(target, entry.getName());
            if (!copy.isFile() || copy.length() != entry.length() || copy.lastModified() != entry.lastModified()) {
                File temporary = File.createTempFile("stage-", ".tmp", target);
                try {
                    long size = entry.length(), modified = entry.lastModified();
                    Files.copy(entry.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    if (temporary.length() != size || entry.length() != size || entry.lastModified() != modified)
                        throw new IOException("BeamNG file changed while staging: " + entry);
                    if (!temporary.setLastModified(modified))
                        throw new IOException("Cannot preserve BeamNG staging timestamp: " + temporary);
                    Files.move(temporary.toPath(), copy.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary.toPath());
                }
            }
            if (!copy.setExecutable(true, true))
                throw new IOException("Cannot make staged BeamNG file executable: " + copy);
        }
        if (!retained.contains(executable.getName()))
            throw new IOException("BeamNG executable is not an ELF file: " + executable);
        File[] old = target.listFiles();
        if (old == null) throw new IOException("Cannot read BeamNG staging directory: " + target);
        for (File entry : old) {
            if (entry.isFile() && !retained.contains(entry.getName())) Files.delete(entry.toPath());
        }
        return new File(target, executable.getName());
    }

    private static boolean isElf(File file) throws IOException {
        try (InputStream input = Files.newInputStream(file.toPath())) {
            return input.read() == 0x7f && input.read() == 'E' && input.read() == 'L' && input.read() == 'F';
        }
    }

    private static String directoryKey(File source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getPath().getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte value : hash) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return key.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
