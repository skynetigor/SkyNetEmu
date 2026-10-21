package com.winlator.cmod.telemetry;

import android.app.Instrumentation;
import android.content.Context;
import com.winlator.cmod.core.ProcessHelper;
import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Live child failures/output isolation and the stale setup/session regression, without launching a game. */
public final class LinuxLaunchDiagnosticsChecks {
    private final StringBuilder report = new StringBuilder();
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(report + "FAIL " + message); report.append("PASS ").append(message).append('\n'); }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    public static String run(Instrumentation test) throws Exception { return new LinuxLaunchDiagnosticsChecks().execute(test.getTargetContext()); }
    private String execute(Context context) throws Exception {
        Field enabled = field(Telemetry.class, "enabled"); boolean previous = enabled.getBoolean(null);
        int root = -1;
        try {
            enabled.setBoolean(null, true);
            AtomicReference<String> mode = new AtomicReference<>("executionMode=not_built");
            LinuxDiagnostics.start(context, null, () -> "old-startup", mode::get, () -> "old-process", () -> "old-exit");
            mode.set("executionMode=proot");
            Supplier<String> currentSetup = (Supplier<String>)field(LinuxDiagnostics.class, "setup").get(null);
            check(currentSetup.get().equals("executionMode=proot"), "description observes mode after session build");
            Supplier<String> old = LinuxDiagnostics.evidenceCollector();
            LinuxDiagnostics.start(context, null, () -> "new-startup", () -> "executionMode=direct", () -> "new-process", () -> "new-exit");
            check(old.get().contains("old-startup") && !old.get().contains("new-startup"), "queued exit collector stays attached to its original session");
            check(LinuxDiagnostics.collect().contains("new-startup"), "new diagnostics use new session evidence");
            LinuxLaunchOutput noisy = new LinuxLaunchOutput();
            noisy.append("first startup"); noisy.append("fatal SIGSYS syscall=434");
            for (int i = 0; i < 1000; i++) noisy.append("loading " + i + " " + "x".repeat(100));
            LinuxDiagnostics.start(context, null, noisy::snapshot, mode::get,
                    () -> "guest_seen=true guest_alive=0 " + "p".repeat(2000),
                    () -> "signal=31 seccomp_syscall=434 " + "c".repeat(2500));
            String evidence = LinuxDiagnostics.collect();
            check(evidence.length() <= 6000 && evidence.contains("first startup") && evidence.contains("loading 999")
                    && evidence.contains("seccomp_syscall=434"), "section budgets preserve process, crash, startup and recent evidence");
            org.json.JSONObject clean = TelemetrySchema.load(context).clean(new org.json.JSONObject()
                    .put("event.dataset", "skynet.error").put("event.action", "linux_program_exit").put("skynet.linux.logs", evidence));
            check(clean.getString("skynet.linux.logs").contains("seccomp_syscall=434") && clean.getString("skynet.linux.logs").contains("loading 999"),
                    "structured crash and recent output survive real telemetry schema cleaning");
            Supplier<String> exitEvidence = LinuxDiagnostics.evidenceCollector();
            LinuxDiagnostics.stop();
            check(exitEvidence.get().contains("seccomp_syscall=434"), "queued exit evidence survives watchdog shutdown");
            check(((Supplier<String>)field(LinuxDiagnostics.class, "setup").get(null)).get().isEmpty(), "stop releases launcher suppliers");

            LinuxLaunchOutput output = new LinuxLaunchOutput();
            CountDownLatch ready = new CountDownLatch(1), guestDone = new CountDownLatch(1), finished = new CountDownLatch(1);
            AtomicInteger status = new AtomicInteger(-999);
            long since = System.currentTimeMillis();
            root = ProcessHelper.exec(new String[]{"/system/bin/sh", "-c", "/system/bin/sleep 2 & child=$!; echo ready; wait $child; echo guest_finished; read hold"},
                    new String[0], context.getCacheDir(), code -> { status.set(code); finished.countDown(); }, line -> {
                        output.append(line); if (line.equals("ready")) ready.countDown(); if (line.equals("guest_finished")) guestDone.countDown();
                    });
            check(root > 0 && ready.await(5, TimeUnit.SECONDS), "live helper and guest start");
            LinuxProcessTracker tracker = new LinuxProcessTracker(new File("/proc"), android.os.Process.myUid(), "sleep");
            String before = tracker.snapshot(root);
            check(before.contains("guest_alive=1"), "actual guest is observed through proc");
            check(guestDone.await(6, TimeUnit.SECONDS), "guest exits while launcher waits");
            String after = tracker.snapshot(root);
            check(after.contains("guest_seen=true guest_alive=0") && after.contains("role=helper"), "actual helper survives after guest disappears");
            CountDownLatch noiseDone = new CountDownLatch(1);
            ProcessHelper.exec(new String[]{"/system/bin/sh", "-c", "echo unrelated-process-noise >&2"}, new String[0], context.getCacheDir(), code -> noiseDone.countDown(), line -> {});
            check(noiseDone.await(5, TimeUnit.SECONDS) && !output.snapshot().contains("unrelated-process-noise"), "scoped output excludes unrelated child stderr");
            String exits = LinuxCrashEvidence.collect(context, since, tracker.knownPids());
            check(exits.startsWith("android_exit_info=") || exits.startsWith("pid="), "Android exit API yields evidence or explicit availability marker");
            android.os.Process.sendSignal(root, 15);
            check(finished.await(5, TimeUnit.SECONDS) && status.get() == 143, "helper termination is delivered after scoped output drain"); root = -1;

            LinuxLaunchOutput fatal = new LinuxLaunchOutput(); CountDownLatch fatalDone = new CountDownLatch(1); AtomicInteger signal = new AtomicInteger();
            int child = ProcessHelper.exec(new String[]{"/system/bin/sh", "-c", "echo 'fatal SIGSYS probe syscall unavailable' >&2; kill -SYS $$"}, new String[0], context.getCacheDir(), code -> { signal.set(code); fatalDone.countDown(); }, fatal::append);
            check(child > 0 && fatalDone.await(10, TimeUnit.SECONDS), "signal 31 fixture exits");
            check(signal.get() == 159 && fatal.snapshot().contains("fatal SIGSYS probe"), "SIGSYS status and immediate stderr retained before callback");
            enabled.setBoolean(null, false);
            check(LinuxDiagnostics.collect().isEmpty() && LinuxCrashEvidence.collect(context, since, java.util.Set.of(child)).isEmpty(),
                    "telemetry opt-out suppresses evidence collection");
            enabled.setBoolean(null, true);
            report.append("INFO signal fixture: ").append(LinuxCrashEvidence.collect(context, since, java.util.Set.of(child))).append('\n');
            report.append("INFO helper fixture: ").append(exits).append('\n');
        } finally {
            if (root > 0) android.os.Process.sendSignal(root, 15);
            LinuxDiagnostics.stop(); enabled.setBoolean(null, previous);
        }
        return report.toString();
    }
}
