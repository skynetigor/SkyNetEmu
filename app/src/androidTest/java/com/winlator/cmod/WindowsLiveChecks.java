package com.winlator.cmod;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WindowsExecutable;
import com.winlator.cmod.core.WindowsLaunchPlan;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.ProcessInfo;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.Pointer;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Actual Windows GUI/input/helper ABI checks, using tools/winhandler/probe.c on each guest architecture. */
public final class WindowsLiveChecks {
    private final StringBuilder report = new StringBuilder();

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        report.append("PASS ").append(message).append('\n');
    }

    private static boolean until(long timeoutMs, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if (condition.getAsBoolean()) return true;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static String log(File file) {
        String text = FileUtils.readString(file);
        return text == null ? "" : text;
    }

    private static int clickEvents(File file, String architecture) {
        int count = 0;
        String prefix = architecture + " click ";
        for (String line : log(file).split("\n")) if (line.startsWith(prefix)) count++;
        return count;
    }

    public static String run(Instrumentation test, Bundle options) throws Exception {
        WindowsLiveChecks checks = new WindowsLiveChecks();
        try {
            return checks.execute(test, options);
        } catch (Throwable error) {
            throw new AssertionError(checks.report + "FAIL " + android.util.Log.getStackTraceString(error), error);
        }
    }

    private static XEnvironment captureEnvironment(XServerDisplayActivity activity) throws ReflectiveOperationException {
        XEnvironment environment = (XEnvironment) field(activity, "environment");
        if (environment != null) {
            GuestProgramLauncherComponent launcher = environment.getComponent(GuestProgramLauncherComponent.class);
            if (launcher != null) launcher.setTerminationCallback(null);
        }
        return environment;
    }

    private static Set<String> liveNewWineProcesses(Set<String> previous) {
        Set<String> result = new HashSet<>();
        for (String pid : ProcessHelper.listRunningWineProcesses()) {
            if (previous.contains(pid)) continue;
            String stat = log(new File("/proc/" + pid + "/stat"));
            int nameEnd = stat.lastIndexOf(')');
            if (nameEnd >= 0 && !stat.substring(nameEnd + 1).trim().startsWith("Z")) result.add(pid);
        }
        return result;
    }

    private static Window findProbeWindow(Window window, String title) {
        if (window.attributes.isMapped() && window.getWidth() > 30 && window.getHeight() > 30
                && window.getName().contains(title)) return window;
        for (Window child : window.getChildren()) {
            Window found = findProbeWindow(child, title);
            if (found != null) return found;
        }
        return null;
    }

    private static int[] probeClickPosition(XServer server, String architecture, boolean normalized) {
        int x = 312, y = 110;
        try (XLock ignored = server.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window = findProbeWindow(server.windowManager.rootWindow, "SkyNet " + architecture + " launch probe");
            if (window != null) {
                x = window.getRootX() + Math.min(120, window.getWidth() / 2);
                y = window.getRootY() + Math.min(90, window.getHeight() / 2);
            }
            int width = Math.max(1, server.screenInfo.width - 1);
            int height = Math.max(1, server.screenInfo.height - 1);
            if (!normalized) return new int[]{Math.max(0, Math.min(x, width)), Math.max(0, Math.min(y, height))};
            return new int[]{(int) (Math.max(0, Math.min(x, width)) * 65535L / width),
                    (int) (Math.max(0, Math.min(y, height)) * 65535L / height)};
        }
    }

    private static void dispatchKey(Instrumentation test, XServerDisplayActivity activity, int action, int keyCode, int metaState) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        test.runOnMainSync(() -> activity.dispatchKeyEvent(event));
    }

    private String execute(Instrumentation test, Bundle options) throws Exception {
        Context context = test.getTargetContext();
        int containerId = Integer.parseInt(options.getString("windowsLiveContainerId", "0"));
        Container container = new ContainerManager(context).getContainerById(containerId);
        check(container != null && !container.isLinux(), "selected Windows test container exists");
        File shortcutFile = new File(options.getString("windowsLiveShortcut", ""));
        check(shortcutFile.isFile(), "selected test shortcut exists");
        Shortcut shortcut = new Shortcut(container, shortcutFile);
        ContentsManager contents = new ContentsManager(context);
        contents.syncContents();
        WineInfo wine = WineInfo.fromIdentifier(context, contents, container.getWineVersion());
        WindowsLaunchPlan plan = WindowsLaunchPlan.resolve(com.winlator.cmod.xenvironment.ImageFs.find(context), container, shortcut, wine);
        check(plan.executable != null && plan.executable.getName().startsWith("windows-probe-"), "live test only launches the Windows input fixture");
        String architecture = plan.architecture == WindowsExecutable.Architecture.ARM64 ? "ARM64"
                : plan.architecture == WindowsExecutable.Architecture.X86_64 ? "x64" : "x86";
        File logFile = new File(options.getString("windowsLiveLog", ""));
        check(!logFile.getPath().isEmpty(), "fixture log path is provided");
        if (logFile.exists()) check(logFile.delete(), "previous fixture log removed");
        Set<String> existingWineProcesses = new HashSet<>(ProcessHelper.listRunningWineProcesses());

        XServerDisplayActivity activity = null;
        XEnvironment environment = null;
        Throwable failure = null;
        try {
            Intent launch = new Intent(context, XServerDisplayActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("container_id", containerId)
                    .putExtra("shortcut_path", shortcutFile.getAbsolutePath());
            activity = (XServerDisplayActivity) test.startActivitySync(launch);
            environment = captureEnvironment(activity);
            XServerDisplayActivity active = activity;
            check(until(60_000, () -> {
                try {
                    captureEnvironment(active);
                    WinHandler handler = active.getWinHandler();
                    return handler != null && (boolean) field(handler, "initReceived");
                } catch (ReflectiveOperationException ignored) { return false; }
            }), architecture + " helper completes the Java UDP handshake");
            environment = captureEnvironment(activity);
            // The production exit path restarts the Android process. Keep this instrumentation alive
            // long enough to inspect normal guest exit and drain the same environment components.
            check(until(45_000, () -> log(logFile).contains(architecture + " ready 0")), architecture + " GUI creates and paints a real window");
            WinHandler handler = activity.getWinHandler();

            List<ProcessInfo> processes = new CopyOnWriteArrayList<>();
            CountDownLatch listed = new CountDownLatch(1);
            handler.setOnGetProcessInfoListener((index, count, process) -> {
                if (process != null) processes.add(process);
                if (count == 0 || index >= count - 1) listed.countDown();
            });
            handler.listProcesses();
            check(listed.await(10, TimeUnit.SECONDS), architecture + " helper returns the process list packet sequence");
            handler.setOnGetProcessInfoListener(null);
            String executableName = plan.executable.getName();
            ProcessInfo probe = processes.stream().filter(process -> executableName.equalsIgnoreCase(process.name)).findFirst().orElse(null);
            check(probe != null && probe.pid > 0 && probe.memoryUsage > 0, architecture + " process packet fields decode correctly");
            check(probe.wow64Process == (plan.architecture == WindowsExecutable.Architecture.X86), architecture + " process reports the expected WoW64 bitness");

            handler.bringToFront(executableName);
            int[] click = probeClickPosition(activity.getXServer(), architecture, plan.nativeArm64);
            String mouseRoute = plan.nativeArm64 ? "native WinHandler" : "touchpad X11 pointer";
            String keyboardRoute = plan.nativeArm64 ? "native WinHandler" : "Android keyboard dispatch";
            if (plan.nativeArm64) {
                handler.mouseEvent(MouseEventFlags.MOVE | MouseEventFlags.ABSOLUTE, click[0], click[1], 0);
            } else {
                test.runOnMainSync(() -> active.getXServer().injectPointerMove(click[0], click[1]));
            }
            SystemClock.sleep(150);
            if (plan.nativeArm64) {
                handler.mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                handler.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
            } else {
                test.runOnMainSync(() -> {
                    active.getXServer().injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    active.getXServer().injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                });
            }
            check(until(5_000, () -> log(logFile).contains(architecture + " click ")), architecture + " probe receives an activating click through " + mouseRoute);
            check(until(10_000, () -> {
                if (log(logFile).contains(architecture + " key 65")) return true;
                if (plan.nativeArm64) {
                    handler.keyboardEvent((byte) 0x41, 0);
                    handler.keyboardEvent((byte) 0x41, 2);
                } else {
                    dispatchKey(test, active, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0);
                    dispatchKey(test, active, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0);
                }
                SystemClock.sleep(150);
                return false;
            }), architecture + " focused probe receives A through " + keyboardRoute);
            if (plan.nativeArm64) {
                // The direct helper checks above verify the native UDP ABI. Also exercise the
                // regular Android keyboard and touchpad/X11 routes used by the application.
                int previousClicks = clickEvents(logFile, architecture);
                int[] pointer = probeClickPosition(activity.getXServer(), architecture, false);
                test.runOnMainSync(() -> {
                    active.getXServer().injectPointerMove(pointer[0], pointer[1]);
                    active.getXServer().injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    active.getXServer().injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                });
                check(until(5_000, () -> clickEvents(logFile, architecture) > previousClicks),
                        architecture + " probe receives another click through touchpad X11 pointer");
                check(until(10_000, () -> {
                    if (log(logFile).contains(architecture + " key 66")) return true;
                    dispatchKey(test, active, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0);
                    dispatchKey(test, active, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, 0);
                    SystemClock.sleep(150);
                    return false;
                }), architecture + " focused probe receives B through Android keyboard dispatch");
                report.append("WINDOWS_LIVE_NATIVE_PRODUCTION_INPUT=Android keyboard dispatch + touchpad X11 pointer\n");
            }
            if (plan.nativeArm64) {
                handler.keyboardEvent((byte) 0x12, 0);
                handler.keyboardEvent((byte) 0x73, 0);
                handler.keyboardEvent((byte) 0x73, 2);
                handler.keyboardEvent((byte) 0x12, 2);
            } else {
                int alt = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
                dispatchKey(test, active, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, alt);
                dispatchKey(test, active, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F4, alt);
                dispatchKey(test, active, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F4, alt);
                dispatchKey(test, active, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0);
            }
            check(until(10_000, () -> log(logFile).contains(architecture + " close 0") && log(logFile).contains(architecture + " exit 0")),
                    architecture + " Alt-F4 through " + keyboardRoute + " closes the guest normally");
            report.append("WINDOWS_LIVE_ARCHITECTURE=").append(architecture).append('\n');
            report.append("WINDOWS_LIVE_INPUT_ROUTE=").append(keyboardRoute).append('\n');
            report.append(log(logFile));
        } catch (Throwable error) {
            failure = error;
            report.append("Probe log at failure:\n").append(log(logFile));
        }
        try {
            XServerDisplayActivity active = activity;
            if (environment == null && activity != null) environment = captureEnvironment(activity);
            XEnvironment runningEnvironment = environment;
            if (active != null) {
                test.runOnMainSync(() -> {
                    if (runningEnvironment != null) runningEnvironment.stopEnvironmentComponents();
                    if (active.getWinHandler() != null) active.getWinHandler().stop();
                    try {
                        Object requestHandler = field(active, "wineRequestHandler");
                        if (requestHandler != null) requestHandler.getClass().getMethod("stop").invoke(requestHandler);
                    } catch (ReflectiveOperationException ignored) {}
                    active.finish();
                });
            }
            ProcessHelper.terminateAllWineProcesses();
            if (!until(2_000, () -> liveNewWineProcesses(existingWineProcesses).isEmpty())) {
                for (String pid : liveNewWineProcesses(existingWineProcesses)) ProcessHelper.killProcess(Integer.parseInt(pid));
            }
            check(until(5_000, () -> liveNewWineProcesses(existingWineProcesses).isEmpty()), "new Wine processes terminate after the live test");
        } catch (Throwable cleanupError) {
            if (failure != null) {
                failure.addSuppressed(cleanupError);
                report.append("Cleanup also failed: ").append(cleanupError).append('\n');
            } else failure = cleanupError;
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new RuntimeException(failure);
        return report.toString();
    }
}
