package com.winlator.cmod;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Environment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    private static void remove(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
    public static String run(Instrumentation test) throws Exception {
        StringBuilder report = new StringBuilder();
        File internal = new File(test.getTargetContext().getCacheDir(), "browser-" + UUID.randomUUID());
        File shared = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "browser-" + UUID.randomUUID());
        MainActivity activity = null;
        try {
            check(internal.mkdirs() && shared.mkdirs(), "scratch folders on app and shared storage", report);
            Container container = new ContainerManager(test.getTargetContext()).getContainers().get(0);
            activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class)
                    .putExtra(MainActivity.EXTRA_BROWSE_CONTAINER, container.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            test.waitForIdleSync();
            MainActivity owner = activity;
            AtomicReference<FileManagerFragment> browser = new AtomicReference<>();
            test.runOnMainSync(() -> browser.set((FileManagerFragment) owner.getSupportFragmentManager().findFragmentById(R.id.FLFragmentContainer)));
            FileManagerFragment fragment = browser.get();
            check(fragment != null, "container Files opens the app browser", report);
            for (int mode = 0; mode < 3; mode++) {
                boolean cut = mode != 0;
                File from = mode == 2 ? internal : shared;
                File to = mode == 2 ? shared : internal;
                File source = new File(from, "sample-" + mode);
                byte[] bytes = mode == 1 ? new byte[0] : new byte[]{1, 2, 3, 4};
                Files.write(source.toPath(), bytes);
                AtomicReference<Exception> error = new AtomicReference<>();
                test.runOnMainSync(() -> {
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
                test.waitForIdleSync();
                check(destination.isFile() && java.util.Arrays.equals(bytes, Files.readAllBytes(destination.toPath()))
                        && (cut ? !source.exists() : source.exists()),
                        mode == 0 ? "copy shared storage into app storage" : mode == 1 ? "move empty file into app storage" : "move app file to shared storage", report);
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
        } finally {
            if (activity != null) { MainActivity owner = activity; test.runOnMainSync(owner::finish); test.waitForIdleSync(); }
            remove(shared.toPath());
            remove(internal.toPath());
        }
        return report.toString();
    }
}
