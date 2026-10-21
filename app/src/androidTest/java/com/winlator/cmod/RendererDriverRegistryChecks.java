package com.winlator.cmod;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.contents.RendererDriverCatalog;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.linux.LinuxShortcutDefaults;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.XServer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Registry decisions, download integrity and actual Android Vulkan loader/capability checks. */
public final class RendererDriverRegistryChecks {
    private final StringBuilder report = new StringBuilder();
    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(report + "FAIL " + message);
        report.append("PASS ").append(message).append('\n');
    }
    private static Method method(String name, Class<?>... parameters) throws Exception {
        Method method = VulkanXServerView.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true); return method;
    }
    private static void main(Runnable run) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> { try { run.run(); } finally { done.countDown(); } });
        if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("main thread did not complete the renderer setup");
    }
    public static String run(Instrumentation test) throws Exception {
        return new RendererDriverRegistryChecks().execute(test);
    }
    private String execute(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        String json;
        try (java.io.InputStream in = context.getAssets().open("registry/renderer-drivers.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<RendererDriverCatalog.Entry> entries = RendererDriverCatalog.parse(json);
        check(!entries.isEmpty(), "bundled Android renderer mapping parses");
        RendererDriverCatalog.Entry entry = entries.get(0);
        check(entry.matches("Adreno (TM) 830", 37, true) && "Turnip Gen8 V30".equals(entry.id),
                "SM-S931B Adreno 830 defaults to registry Turnip Gen8 V30");
        check(!entry.matches("Adreno (TM) 840", 37, true), "Adreno 830 default does not replace the tested 840 driver");
        check(entries.stream().anyMatch(candidate -> candidate.matches("Adreno (TM) 840", 37, true)
                && "DD-Turnip v0.1.1".equals(candidate.id)), "tested Adreno 840 retains DD-Turnip v0.1.1");
        RendererDriverCatalog.Entry poco = entries.stream().filter(candidate -> candidate.matches("Adreno (TM) 735", 34, true)).findFirst().orElse(null);
        check(poco != null && "Turnip v26.2.0-R8".equals(poco.id), "POCO F6 API 34 Auto selects the Android A7xx driver");
        check(!poco.matches("Adreno (TM) 735v2", 34, true), "POCO mapping does not assume support for other GPU variants");
        String linuxJson;
        try (java.io.InputStream in = context.getAssets().open("registry/linux.json")) {
            linuxJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        check("turnip-mesa-26.2.2-linux-aarch64".equals(com.winlator.cmod.steam.SteamContainer.driverForGpu(linuxJson, "Adreno (TM) 735", "original")),
                "Adreno 735 Linux default uses glibc Mesa 26.2.2, not the A8xx driver");
        for (String gpu : new String[]{"Adreno (TM) 735v2", "Adreno (TM) 840", "Mali-G715"})
            check("original".equals(com.winlator.cmod.steam.SteamContainer.driverForGpu(linuxJson, gpu, "original")),
                    "Linux GPU mapping preserves the default for " + gpu);
        String absent = new JSONObject(linuxJson).put("drivers", new JSONArray()).toString();
        check("original".equals(com.winlator.cmod.steam.SteamContainer.driverForGpu(absent, "Adreno (TM) 735", "original")),
                "Linux GPU map cannot select a package absent from its catalog");
        check(!entry.matches("Adreno (TM) 830v2", 37, true), "GPU variants require an explicit mapping");
        check(!entry.matches("Adreno (TM) 730", 37, true) && !entry.matches("Mali-G715", 37, true), "unlisted GPUs are never matched as the same family");
        check(!entry.matches("Adreno (TM) 830", 28, true) && !entry.matches("Adreno (TM) 830", 99, true), "Android version bounds are enforced");
        JSONObject object = new JSONObject(json).getJSONArray("drivers").getJSONObject(0);
        String valid = object.toString();
        String ordered = new JSONObject().put("schema", 1).put("drivers", new JSONArray()
                .put(new JSONObject(valid).put("priority", 1)).put(new JSONObject(valid).put("priority", 500))).toString();
        check(RendererDriverCatalog.parse(ordered).get(0).priority == 500, "registry priority orders candidates independently of installation order");
        object.put("dmaBufImport", false);
        RendererDriverCatalog.Entry noDma = RendererDriverCatalog.parse(new JSONObject().put("schema", 1).put("drivers", new JSONArray().put(object)).toString()).get(0);
        check(!noDma.matches("Adreno (TM) 830", 37, true) && noDma.matches("Adreno (TM) 830", 37, false), "native Linux output requires DMA-BUF capability in the mapping");
        for (String key : new String[]{"sha256", "platform", "url", "id"}) {
            JSONObject bad = new JSONObject(valid).put(key, key.equals("url") ? "https://[bad" : key.equals("id") ? "../driver" : "invalid");
            String candidates = new JSONObject().put("schema", 1).put("drivers", new JSONArray().put(bad).put(new JSONObject(valid))).toString();
            check(RendererDriverCatalog.parse(candidates).size() == 1, "invalid " + key + " is skipped without losing a later valid entry");
        }
        check("system".equals(RendererDriverCatalog.resolve(null, "system", true, null)), "explicit System never invokes automatic selection or downloads");
        check("Manual driver".equals(RendererDriverCatalog.resolve(null, "Manual driver", true, null)), "explicit custom renderer selection stays unchanged");
        JSONObject profile = new JSONObject().put("type", "linux").put("rendererDriverId", "auto");
        ContainerManager containers = new ContainerManager(context);
        check("auto".equals(LinuxShortcutDefaults.fromData(context, containers, profile).get("rendererDriverId")), "Linux profile renderer Auto is carried into per-shortcut settings");
        profile.remove("rendererDriverId");
        check(!LinuxShortcutDefaults.fromData(context, containers, profile).containsKey("rendererDriverId"), "old profiles without a renderer preserve the shared environment's manual selection");
        profile.put("extraData", new JSONObject().put("linuxVulkanPresent", "native"));
        check("auto".equals(LinuxShortcutDefaults.fromData(context, containers, profile).get("rendererDriverId")), "older native-output profiles acquire Auto when newly imported");
        profile.put("rendererDriverId", "system");
        check("system".equals(LinuxShortcutDefaults.fromData(context, containers, profile).get("rendererDriverId")), "an explicit profile System choice is preserved even for native output");

        AdrenotoolsManager drivers = new AdrenotoolsManager(context);
        java.util.Set<String> before = new java.util.HashSet<>(drivers.enumarateInstalledDrivers());
        String fixtureId = "__renderer_hash_" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("meta.json"));
            zip.write(new JSONObject().put("name", fixtureId).put("libraryName", "fixture.so").toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry(); zip.putNextEntry(new java.util.zip.ZipEntry("fixture.so")); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray();
        File archiveFile = new File(context.getCacheDir(), fixtureId + ".zip");
        java.nio.file.Files.write(archiveFile.toPath(), archive);
        Method importer = RemoteDriverCatalog.class.getDeclaredMethod("importArchive", Context.class, File.class, String.class, String.class);
        importer.setAccessible(true);
        try {
            String url = "https://example.invalid/renderer-test.zip";
            String result = (String) importer.invoke(null, context, archiveFile, url, "0".repeat(64));
            check(result.isEmpty() && before.equals(new java.util.HashSet<>(drivers.enumarateInstalledDrivers())), "checksum mismatch rejects a valid driver archive before installation");
            StringBuilder hash = new StringBuilder();
            for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(archive)) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            result = (String) importer.invoke(null, context, archiveFile, url, hash.toString());
            check(fixtureId.equals(result) && new File(drivers.getDriverPath(fixtureId), "fixture.so").isFile(), "matching checksum imports the verified archive successfully");
        } finally {
            archiveFile.delete();
            com.winlator.cmod.core.FileUtils.delete(new File(drivers.getDriverPath(fixtureId)));
        }
        check(before.equals(new java.util.HashSet<>(drivers.enumarateInstalledDrivers())), "download test restores the installed driver set");
        String selected = RendererDriverCatalog.resolve(context, "auto", true, ignored -> {});
        check(!"auto".equals(selected) && !"system".equals(selected), "real phone Auto resolves an installed Android renderer instead of System");
        VulkanXServerView.loadNativeLibrary();
        VulkanXServerView[] view = new VulkanXServerView[1];
        main(() -> view[0] = new VulkanXServerView(context, new XServer(new ScreenInfo(64, 64), "vortek", null)));
        Method init = method("nativeInit", Surface.class, int.class, int.class, String.class, String.class, String.class,
                boolean.class, boolean.class, boolean.class, boolean.class);
        long handle = 0;
        try (ImageReader reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2)) {
            handle = (Long) init.invoke(view[0], reader.getSurface(), 64, 64,
                    drivers.getDriverPath(selected), drivers.getLibraryName(selected), context.getApplicationInfo().nativeLibraryDir,
                    false, true, true, false);
            check(handle != 0, "selected Android driver loads and passes the actual native DMA-BUF capability gate");
            String snapshot = (String) method("nativeRendererDiagnostics", long.class).invoke(view[0], handle);
            check(snapshot.contains("external_fd=1") && snapshot.contains("dma_buf=1"), "live renderer confirms external-memory FD and DMA-BUF support");
            report.append("AUTO_RENDERER_SELECTED=").append(selected).append('\n');
            report.append(snapshot).append('\n');
            try {
                init.invoke(view[0], reader.getSurface(), 64, 64, new File(context.getCacheDir(), "missing-auto-driver").getPath() + "/",
                        "missing.so", context.getApplicationInfo().nativeLibraryDir, false, false, true, false);
                throw new AssertionError("automatic driver silently fell back to System");
            } catch (InvocationTargetException error) {
                check(error.getCause() instanceof IllegalStateException && error.getCause().getMessage().contains("could not be loaded"),
                        "Auto rejects a failed driver load even when DMA-BUF import is not requested");
            }
        } finally {
            if (handle != 0) method("nativeDestroy", long.class).invoke(view[0], handle);
            Field executor = VulkanXServerView.class.getDeclaredField("eventExecutor"); executor.setAccessible(true);
            ((ExecutorService) executor.get(view[0])).shutdownNow();
        }
        return report.toString();
    }
}
