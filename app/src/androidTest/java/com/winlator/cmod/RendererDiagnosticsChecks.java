package com.winlator.cmod;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Surface;

import com.winlator.cmod.telemetry.GameSessionRecorder;
import com.winlator.cmod.widget.VulkanXServerView;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

/** Checks the real serializer and JNI renderer without starting a game or changing user settings. */
final class RendererDiagnosticsChecks {
    private final Instrumentation driver;
    private final StringBuilder report = new StringBuilder();

    RendererDiagnosticsChecks(Instrumentation driver) { this.driver = driver; }

    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        report.append("PASS ").append(message).append('\n');
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        Method method = VulkanXServerView.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    String run() throws Exception {
        serialization();
        renderer();
        return report.toString();
    }

    private void serialization() throws Exception {
        Class<?> eventType = Class.forName("com.winlator.cmod.telemetry.Telemetry$Event");
        Constructor<?> constructor = eventType.getDeclaredConstructor(String.class, String.class, String.class, Object[].class);
        constructor.setAccessible(true);
        Object event = constructor.newInstance("skynet.error", "launch", null, new Object[] {
                "skynet.renderer.diagnostics", "stage=allocate_image_memory vk_result=-2 " + "d".repeat(3000),
                "skynet.linux.logs", "open /tmp/private/game.log by person@example.test\n" + "l".repeat(7000),
                "skynet.linux.setup", "native vulkan " + "s".repeat(1100),
                "skynet.linux.seconds_running", 40,
                "error.stack_trace", "f".repeat(9000),
                "error.message", "m".repeat(400),
        });
        Method json = Class.forName("com.winlator.cmod.telemetry.Uploader").getDeclaredMethod(
                "json", eventType, JSONObject.class, SimpleDateFormat.class);
        json.setAccessible(true);
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        time.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject doc = new JSONObject((String) json.invoke(null, event, new JSONObject(), time));
        check(doc.getString("skynet.renderer.diagnostics").length() == 2048
                && doc.getString("skynet.renderer.diagnostics").contains("vk_result=-2"),
                "real event serializer retains native stage/result beyond 300 characters");
        check(doc.getString("skynet.linux.logs").length() == 6000
                && doc.getString("skynet.linux.setup").length() == 1000
                && doc.getString("error.stack_trace").length() == 8192
                && doc.getString("error.message").length() == 300,
                "real event serializer applies all diagnostic limits");
        check(doc.getString("skynet.linux.logs").startsWith("open <path> by <email>\n"),
                "real event serializer scrubs paths and email addresses");
        check(!doc.has("event.outcome") && doc.getInt("skynet.linux.seconds_running") == 40,
                "renderer snapshot is neutral and preserves numeric fields");
    }

    private void renderer() throws Exception {
        Context context = driver.getTargetContext();
        HandlerThread consumer = new HandlerThread("RendererDiagnosticsImages");
        consumer.start();
        ImageReader reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_CPU_READ_OFTEN);
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) { /* Release each presented image. */ }
        }, new Handler(consumer.getLooper()));
        VulkanXServerView[] view = new VulkanXServerView[1];
        XServer server = new XServer(new ScreenInfo(64, 64), "vortek", null);
        try {
            VulkanXServerView.loadNativeLibrary();
            driver.runOnMainSync(() -> view[0] = new VulkanXServerView(context, server));
            long handle = (Long) method("nativeInit", Surface.class, int.class, int.class,
                    String.class, String.class, String.class, boolean.class).invoke(view[0], reader.getSurface(),
                    64, 64, null, null, context.getApplicationInfo().nativeLibraryDir, false);
            check(handle != 0, "real Vulkan renderer initializes on an offscreen Android surface");
            field(VulkanXServerView.class, "nativeHandle").setLong(view[0], handle);
            field(VulkanXServerView.class, "importFailureReported").setBoolean(view[0], true);
            Method snapshot = method("nativeRendererDiagnostics", long.class);
            String initial = (String) snapshot.invoke(view[0], handle);
            check(initial.contains("device=") && initial.contains("external_fd=") && initial.contains("dma_buf=")
                    && initial.contains("stage=not_started"), "native snapshot contains GPU identity and import capabilities");
            Window root = server.windowManager.rootWindow;
            long contentId = System.identityHashCode(root.getContent());
            method("nativeInitRootWindow", long.class, long.class, long.class, int.class, int.class)
                    .invoke(view[0], handle, (long) root.id, contentId, 64, 64);
            // The compositor intentionally excludes root content; a mapped child is the game surface.
            long gameId = (long) root.id + 1;
            method("nativeCreateWindow", long.class, long.class, long.class, long.class,
                    int.class, int.class, int.class, int.class)
                    .invoke(view[0], handle, gameId, (long) root.id, contentId, 0, 0, 64, 64);
            method("nativeMapWindow", long.class, long.class).invoke(view[0], handle, gameId);
            Field frames = field(GameSessionRecorder.class, "framesSeen");
            int before = frames.getInt(null);
            Drawable pixmap = root.getContent();
            view[0].setSessionWindowId(root.id);
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY)) {
                // Zero stride is rejected before touching the Vulkan driver, regardless of fd support.
                pixmap.dmaBufFd = fd.getFd();
                pixmap.dmaBufStride = 0;
                view[0].onUpdateWindowContentDirect(root, pixmap);
                check(frames.getInt(null) == before, "rejected DMA frame does not increase the game/HUD frame count");
                String failure = (String) snapshot.invoke(view[0], handle);
                check(failure.contains("stage=invalid_buffer") && failure.contains("size=64x64 stride=0")
                        && failure.contains("updates=1 accepted=0 failed=1"),
                        "JNI reports failed import stage, geometry and counters");
                report.append("NATIVE ").append(failure).append('\n');
            } finally {
                pixmap.dmaBufFd = -1;
            }
            view[0].onUpdateWindowContentDirect(root, pixmap);
            check(frames.getInt(null) == before + 1, "AHB frames continue through the existing presentation path");
            long deadline = SystemClock.elapsedRealtime() + 5000;
            String presented;
            do {
                presented = (String) snapshot.invoke(view[0], handle);
                if (counter(presented, "presents") > 0 && counter(presented, "draws") > 0) break;
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < deadline);
            check(counter(presented, "submits") > 0 && counter(presented, "presents") > 0 && counter(presented, "draws") > 0,
                    "native diagnostics observe successful GPU submission and presentation with content: " + presented);
            report.append("NATIVE ").append(presented).append('\n');
        } finally {
            if (view[0] != null) {
                long handle = field(VulkanXServerView.class, "nativeHandle").getLong(view[0]);
                if (handle != 0) method("nativeDetachSurface", long.class).invoke(view[0], handle);
                view[0].forceCleanup();
                ((ExecutorService) field(VulkanXServerView.class, "eventExecutor").get(view[0])).shutdownNow();
            }
            reader.close();
            consumer.quitSafely();
            consumer.join(2000);
            server.cursorLocker.cancel();
            com.winlator.cmod.xserver.extensions.XInput2Extension xi = server.getExtension(
                    com.winlator.cmod.xserver.extensions.XInput2Extension.MAJOR_OPCODE);
            if (xi != null) xi.touch.close();
        }
    }

    private static long counter(String text, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^| )" + key + "=(\\d+)(?: |$)").matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }
}
