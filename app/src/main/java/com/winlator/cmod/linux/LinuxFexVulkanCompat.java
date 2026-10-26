package com.winlator.cmod.linux;

import android.content.Context;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/** Select a corrected host thunk for the exact FEX 2610 package, without modifying installed components. */
final class LinuxFexVulkanCompat {
    private static final String HOST_SHA = "7db5b88d3c897f756d553b2f7c37fb3d8990170bef5932dbef81e032ff5e3e10";
    private static final String GUEST_SHA = "6fe7217b2443243aae4d74ff6b5578b3cea030f967b27ee43414ef7757b469eb";
    private static final ConcurrentHashMap<String,Boolean> checked = new ConcurrentHashMap<>();
    private LinuxFexVulkanCompat() {}

    static File hostLibraries(Context context, LinuxEmulator.Installed emulator) throws IOException {
        File original = new File(emulator.dir, "lib/fex-emu/HostThunks");
        File host = new File(original, "libvulkan-host.so");
        File guest = new File(emulator.dir, "share/fex-emu/GuestThunks/libvulkan-guest.so");
        String key = host.getAbsolutePath()+":"+host.length()+":"+host.lastModified()+":"+guest.length()+":"+guest.lastModified();
        Boolean supported = checked.get(key);
        if (supported == null) {
            supported = HOST_SHA.equals(sha256(host)) && GUEST_SHA.equals(sha256(guest));
            checked.put(key, supported);
        }
        if (!supported) return original;
        File fixed = LinuxSession.stageAsset(context, "linux-fex-compat", "libfex_vulkan_host_2610.so");
        if (fixed == null) throw new IOException("Cannot stage FEX Vulkan callback fix");
        File overlay = new File(context.getFilesDir(), "linux-fex-compat/"+emulator.id.replaceAll("[^A-Za-z0-9._-]", "_")+"/HostThunks");
        if (!overlay.isDirectory() && !overlay.mkdirs()) throw new IOException("Cannot create "+overlay);
        File[] files = original.listFiles();
        if (files == null) throw new IOException("Cannot list "+original);
        for (File file : files) {
            File target = file.getName().equals("libvulkan-host.so") ? fixed : file;
            link(new File(overlay,file.getName()),target);
        }
        // FEX appends _32 when a child starts a 32-bit program. Keep its existing thunks intact.
        File original32 = new File(emulator.dir,"lib/fex-emu/HostThunks_32");
        if (original32.isDirectory()) link(new File(overlay.getParentFile(),"HostThunks_32"),original32);
        Log.i("LinuxFexVulkanCompat","Using corrected Vulkan callback routing for "+emulator.id);
        return overlay;
    }

    private static void link(File destination, File target) throws IOException {
        try {
            try { if (Os.readlink(destination.getPath()).equals(target.getAbsolutePath())) return; }
            catch (android.system.ErrnoException ignored) {}
            File temporary = new File(destination.getParentFile(),destination.getName()+".tmp");
            temporary.delete();
            Os.symlink(target.getAbsolutePath(),temporary.getPath());
            Os.rename(temporary.getPath(),destination.getPath());
        } catch (android.system.ErrnoException e) { throw new IOException("Cannot link "+destination,e); }
    }

    private static String sha256(File file) throws IOException {
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768]; int count;
            while ((count=in.read(buffer))!=-1) digest.update(buffer,0,count);
            StringBuilder hex = new StringBuilder();
            for (byte b:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
