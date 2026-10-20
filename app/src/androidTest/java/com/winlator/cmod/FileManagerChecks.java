package com.winlator.cmod;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.ShortcutWriter;
import com.winlator.cmod.core.WineInfo;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Uses only uniquely named scratch folders; exercises the real browser paste actions. */
public final class FileManagerChecks {
    private static Object call(FileManagerFragment fragment, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = FileManagerFragment.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(fragment, args);
    }
    private static void check(boolean value, String message, StringBuilder report) {
        if (!value) throw new AssertionError(message);
        report.append("PASS ").append(message).append('\n');
    }
    private static void progress(Instrumentation test, String message) {
        Bundle status = new Bundle();
        status.putString("stream", "FileManagerChecks: " + message + "\n");
        test.sendStatus(0, status);
    }
    /** Compose and animated browser chrome need not make the entire main queue idle. */
    private static void main(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try { action.run(); } catch (Throwable failure) { error.set(failure); }
            finally { done.countDown(); }
        });
        if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Main-thread action did not complete within 10 seconds");
        if (error.get() instanceof Exception) throw (Exception) error.get();
        if (error.get() != null) throw new AssertionError("Main-thread action failed", error.get());
    }
    private static void awaitMain(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        while (System.nanoTime() < deadline) {
            main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }
    private static boolean pasteDialogClosed(FileManagerFragment fragment) {
        try {
            java.lang.reflect.Field field = FileManagerFragment.class.getDeclaredField("progressDialog");
            field.setAccessible(true);
            android.app.AlertDialog dialog = (android.app.AlertDialog) field.get(fragment);
            return dialog == null || !dialog.isShowing();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void remove(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
    private static File executable(File directory, String name, int machine) throws Exception {
        byte[] bytes = new byte[512];
        bytes[0] = 'M'; bytes[1] = 'Z'; bytes[60] = (byte)128;
        bytes[128] = 'P'; bytes[129] = 'E';
        bytes[132] = (byte)machine; bytes[133] = (byte)(machine >>> 8);
        bytes[148] = (byte)240; bytes[152] = 0x0b; bytes[153] = 2;
        File file = new File(directory, name);
        Files.write(file.toPath(), bytes);
        return file;
    }
    public static String run(Instrumentation test) throws Exception {
        StringBuilder report = new StringBuilder();
        File internal = new File(test.getTargetContext().getCacheDir(), "browser-" + UUID.randomUUID());
        File shared = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "browser-" + UUID.randomUUID());
        MainActivity activity = null;
        try {
            check(internal.mkdirs() && shared.mkdirs(), "scratch folders on app and shared storage", report);
            Container container = new ContainerManager(test.getTargetContext()).getContainers().get(0);
            progress(test, "opening container browser");
            Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
            try {
                test.getTargetContext().startActivity(new Intent(test.getTargetContext(), MainActivity.class)
                        .putExtra(MainActivity.EXTRA_BROWSE_CONTAINER, container.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                activity = (MainActivity) test.waitForMonitorWithTimeout(monitor, 10000);
            } finally { test.removeMonitor(monitor); }
            check(activity != null, "browser activity starts within 10 seconds", report);
            MainActivity owner = activity;
            AtomicReference<FileManagerFragment> browser = new AtomicReference<>();
            awaitMain("container file browser", () -> {
                androidx.fragment.app.Fragment candidate = owner.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer);
                if (!(candidate instanceof FileManagerFragment) || !candidate.isAdded() || candidate.getView() == null) return false;
                browser.set((FileManagerFragment) candidate);
                return true;
            });
            FileManagerFragment fragment = browser.get();
            check(fragment != null, "container Files opens the app browser", report);
            progress(test, "browser ready");
            for (int mode = 0; mode < 3; mode++) {
                boolean cut = mode != 0;
                File from = mode == 2 ? internal : shared;
                File to = mode == 2 ? shared : internal;
                File source = new File(from, "sample-" + mode);
                byte[] bytes = mode == 1 ? new byte[0] : new byte[]{1, 2, 3, 4};
                Files.write(source.toPath(), bytes);
                AtomicReference<Exception> error = new AtomicReference<>();
                main(() -> {
                    try {
                        call(fragment, "copyToClipboard", new Class[]{File.class, boolean.class}, source, cut);
                        call(fragment, "openDrive", new Class[]{File.class, File.class}, to, to);
                        call(fragment, "startPasteOperation", new Class[]{});
                    } catch (Exception e) { error.set(e); }
                });
                if (error.get() != null) throw error.get();
                File destination = new File(to, source.getName());
                long deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline && (!destination.isFile() || (cut && source.exists()))) Thread.sleep(50);
                awaitMain("paste progress dialog to close", () -> pasteDialogClosed(fragment));
                check(destination.isFile() && java.util.Arrays.equals(bytes, Files.readAllBytes(destination.toPath()))
                        && (cut ? !source.exists() : source.exists()),
                        mode == 0 ? "copy shared storage into app storage" : mode == 1 ? "move empty file into app storage" : "move app file to shared storage", report);
                progress(test, "paste check " + mode + " complete");
            }
            File target = new File(internal, "link-target");
            check(target.mkdir(), "create symlink target", report);
            File keep = new File(target, "keep");
            Files.write(keep.toPath(), new byte[]{7});
            File link = new File(internal, "link");
            Files.createSymbolicLink(link.toPath(), target.toPath());
            call(fragment, "deleteRecursive", new Class[]{File.class}, link);
            check(!Files.isSymbolicLink(link.toPath()) && keep.isFile(), "deleting directory link preserves its target", report);
            call(fragment, "deleteRecursive", new Class[]{File.class}, keep);
            check(!keep.exists(), "delete a regular file", report);

            File x64 = executable(internal, "ordinary-" + UUID.randomUUID() + ".exe", 0x8664);
            AtomicReference<Object> routed = new AtomicReference<>();
            main(() -> {
                try { routed.set(call(fragment, "routeNativeAddGame", new Class[]{File.class}, x64)); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            check(Boolean.FALSE.equals(routed.get()), "x64 file browser actions retain their existing route", report);
            File nativeExe = executable(internal, "misleading-x64-" + UUID.randomUUID() + ".exe", 0xaa64);
            File wouldCreate = new File(container.getDesktopDir(), ShortcutWriter.smartName(nativeExe) + ".desktop");
            progress(test, "checking native Add Game route");
            main(() -> {
                try { call(fragment, "createShortcutDirectly", new Class[]{File.class, Container.class}, nativeExe, container); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            AtomicReference<androidx.fragment.app.Fragment> destination = new AtomicReference<>();
            awaitMain("native Add Game screen", () -> {
                destination.set(owner.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer));
                return destination.get() instanceof com.winlator.cmod.ui.addgame.AddGameFragment;
            });
            check(destination.get() instanceof com.winlator.cmod.ui.addgame.AddGameFragment && !wouldCreate.exists(),
                    "ARM64 browser shortcut creation opens native Add Game without writing into the current container", report);

            Container legacy = new ContainerManager(test.getTargetContext()).getWineContainers().stream()
                    .filter(c -> !WineInfo.NATIVE_ARM64_CONTAINER_VALUE.equals(c.getExtra(WineInfo.NATIVE_ARM64_CONTAINER_KEY)))
                    .findFirst().orElse(null);
            if (legacy != null) {
                AtomicReference<FileManagerFragment> secondBrowser = new AtomicReference<>();
                progress(test, "checking native Run/Open route");
                main(() -> {
                    FileManagerFragment next = FileManagerFragment.forContainer(legacy.id);
                    owner.getSupportFragmentManager().beginTransaction().replace(R.id.FLFragmentContainer, next).commitNow();
                    secondBrowser.set(next);
                });
                main(() -> {
                    try { call(secondBrowser.get(), "runFileDirectly", new Class[]{File.class, Container.class}, nativeExe, legacy); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                awaitMain("native Add Game screen after Run/Open", () -> {
                    destination.set(owner.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer));
                    return destination.get() instanceof com.winlator.cmod.ui.addgame.AddGameFragment;
                });
                check(destination.get() instanceof com.winlator.cmod.ui.addgame.AddGameFragment,
                        "ARM64 Run/Open in an incompatible container opens native Add Game instead of launching", report);
            }
        } finally {
            try {
                if (activity != null) { MainActivity owner = activity; main(owner::finish); }
            } finally {
                remove(shared.toPath());
                remove(internal.toPath());
            }
        }
        return report.toString();
    }
}
