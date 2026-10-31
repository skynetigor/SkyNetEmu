package com.winlator.cmod.linux;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Keeps executable mappings off Android's noexec shared storage without copying game data. */
public final class LinuxExecutableStaging {
    private static final Map<String, Integer> active = new HashMap<>();

    private LinuxExecutableStaging() {}

    public static boolean isBeamNG(File executable) {
        return executable != null && "BeamNG.drive.x64".equals(executable.getName())
                && executable.getParentFile() != null && "BinLinux".equals(executable.getParentFile().getName());
    }

    public static synchronized String retain(File executable, File stagingRoot) throws IOException {
        String key = referenceKey(executable, stagingRoot);
        active.put(key, active.getOrDefault(key, 0) + 1);
        return key;
    }

    public static synchronized void release(String key) {
        Integer count = active.get(key);
        if (count == null) throw new IllegalStateException("Staging lease is not active: " + key);
        if (count == 1) active.remove(key);
        else active.put(key, count - 1);
    }

    /** Scans every Linux desktop before deleting any cache; unreadable references fail closed. */
    public static synchronized void prune(File stagingRoot, File containerHomes) throws IOException {
        if (!Files.exists(stagingRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(stagingRoot.toPath()))
            throw new IOException("Staging root is a symbolic link: " + stagingRoot);
        Set<String> retained = new HashSet<>(active.keySet());
        for (File home : list(containerHomes)) {
            File desktop = new File(home, "linux-home/Desktop");
            if (Files.notExists(desktop.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            for (File shortcut : list(desktop)) {
                if (!shortcut.getName().endsWith(".desktop")) continue;
                String section = "";
                for (String line : Files.readAllLines(shortcut.toPath(), StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.startsWith("[")) section = line;
                    else if ("[Desktop Entry]".equals(section) && line.startsWith("Exec=")) {
                        retainReference(retained, resolveReference(home, line.substring(5)), stagingRoot);
                    } else if ("[Extra Data]".equals(section) && line.startsWith("gameRoot=")) {
                        retainReference(retained, new File(resolveReference(home, line.substring(9)),
                                "BinLinux/BeamNG.drive.x64"), stagingRoot);
                    }
                }
            }
        }
        for (File entry : list(stagingRoot)) {
            if (!entry.getName().matches("[0-9a-f]{64}") || retained.contains(entry.getName())) continue;
            // Only our flat BinLinux layout is disposable; never recurse into unknown trees or links.
            if (Files.isSymbolicLink(entry.toPath())) throw new IOException("Linked staging directory: " + entry);
            File[] children = list(entry);
            for (File child : children) {
                if (!"BinLinux".equals(child.getName()) || Files.isSymbolicLink(child.toPath()))
                    throw new IOException("Unexpected staging entry: " + child);
                for (File file : list(child)) {
                    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Unexpected staged file: " + file);
                }
            }
            for (File child : children) {
                for (File file : list(child)) Files.delete(file.toPath());
                Files.delete(child.toPath());
            }
            Files.delete(entry.toPath());
        }
    }

    private static File resolveReference(File home, String path) {
        return path.startsWith("/root/") ? new File(home, "linux-home/" + path.substring(6)) : new File(path);
    }

    private static void retainReference(Set<String> retained, File executable, File stagingRoot) throws IOException {
        if (!isBeamNG(executable)) return;
        retained.add(referenceKey(executable, stagingRoot));
    }

    private static String referenceKey(File executable, File stagingRoot) throws IOException {
        File source = executable.getParentFile().getCanonicalFile();
        File cache = source.getParentFile();
        if (cache != null && cache.getName().matches("[0-9a-f]{64}")
                && stagingRoot.getCanonicalFile().equals(cache.getParentFile())) return cache.getName();
        return directoryKey(source);
    }

    private static File[] list(File directory) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Cannot read directory for staging cleanup: " + directory);
        return entries;
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
