package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.linux.LinuxRuntime;
import com.winlator.cmod.linux.LinuxSession;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Starts a Linux session (proot plus the guest program) and takes the whole process tree down on stop. */
public class LinuxProgramLauncherComponent extends EnvironmentComponent {
    private static final String TAG = "LinuxProgramLauncher";

    private final Container container;
    private final LinuxRuntime.Installed runtime;
    private final String[] guestCommand;
    private Callback<Integer> terminationCallback;
    private final Object lock = new Object();
    private int pid = -1;
    public int getPid() { synchronized (lock) { return pid; } }
    private com.winlator.cmod.core.ContainerProcess process;
    private long startedAt;
    private volatile String executionMode = "not_built";

    private volatile String phase = "created";
    private volatile int lastPid = -1, exitStatus = Integer.MIN_VALUE;
    private final com.winlator.cmod.core.ProgramOutput rawOutput = new com.winlator.cmod.core.ProgramOutput();
    private final com.winlator.cmod.telemetry.LinuxLaunchOutput diagnosticOutput = new com.winlator.cmod.telemetry.LinuxLaunchOutput();
    private final com.winlator.cmod.telemetry.LinuxProcessTracker processes;
    private final Callback<String> outputCallback = line -> {
        rawOutput.append(line);
        if (com.winlator.cmod.telemetry.Telemetry.isEnabled()) diagnosticOutput.append(line);
    };

    public LinuxProgramLauncherComponent(Container container, LinuxRuntime.Installed runtime, String[] guestCommand) {
        this.container = container;
        this.runtime = runtime;
        this.guestCommand = guestCommand;
        String guest = "";
        for (int i = 0; i + 1 < guestCommand.length; i++)
            if ("bannerlator-run".equals(guestCommand[i])) { guest = guestCommand[i + 1]; break; }
        processes = new com.winlator.cmod.telemetry.LinuxProcessTracker(new File("/proc"), Process.myUid(), guest);
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    private void startupStage(String stage) {
        Log.i("LinuxStartup", "stage=" + stage + " boot_ms=" + android.os.SystemClock.elapsedRealtime());
    }

    /** The X server is started just before this component; a program must not run before it accepts connections. */
    private static void waitForXServer(File socket) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try (android.net.LocalSocket probe = new android.net.LocalSocket()) {
                probe.connect(new android.net.LocalSocketAddress(socket.getPath(), android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                Log.i("LinuxStartup", "stage=xserver_ready boot_ms=" + android.os.SystemClock.elapsedRealtime());
                return;
            }
            catch (IOException e) {
                try {
                    Thread.sleep(50);
                }
                catch (InterruptedException ignored) {
                    return;
                }
            }
        }
        Log.w(TAG, "The X server did not accept connections within 10 s");
        Log.w("LinuxStartup", "stage=xserver_timeout boot_ms=" + android.os.SystemClock.elapsedRealtime());
    }

    @Override
    public void start() {
        Context context = environment.getContext();
        ImageFs imageFs = environment.getImageFs();
        startedAt = System.currentTimeMillis();
        phase = "waiting_xserver";
        startupStage(phase);
        waitForXServer(new File(imageFs.getRootDir(), "usr/tmp/.X11-unix/X0"));
        LinuxSession.Launch launch;
        try {
            phase = "building";
            startupStage(phase);
            launch = LinuxSession.build(context, imageFs, container, runtime, guestCommand);
            executionMode = launch.executionMode;
            startupStage("build_complete");
        }
        catch (RuntimeException e) {
            phase = "build_failed";
            com.winlator.cmod.telemetry.ErrorReporter.report("linux_session_build", e, "skynet.linux.setup", setupDescription());
            throw e;
        }
        // Root exit status does not wait for output pipes inherited by surviving daemons.
        phase = "spawning";
        startupStage(phase);
        synchronized (lock) {
            process = environment.startProcess(launch.argv, launch.hostEnv, launch.workingDir, status -> {
                synchronized (lock) {
                    pid = -1;
                    phase = "exited";
                    exitStatus = status;
                }
                if (terminationCallback != null) terminationCallback.call(status);
            }, outputCallback);
            pid = process.getPid();
            lastPid = pid;
            if (pid > 0) phase = "running";
            startupStage(phase);
            if (pid == -1) {
                phase = "spawn_failed";
                Log.e(TAG, "The Linux session did not start");
                com.winlator.cmod.telemetry.ErrorReporter.linux("session_start", "The Linux session did not start (" + setupDescription() + ")", null);
            }
        }
        applyCpuAffinity();
    }

    /**
     * Pins the session to the container's CPU cores. proot forks the guest's first process before we know
     * its pid, so the mask is applied to the whole tree a few times while the session starts up; processes
     * started after that inherit it.
     */
    private void applyCpuAffinity() {
        final int root;
        synchronized (lock) {
            root = pid;
        }
        final int mask = affinityMask(container);
        if (root == -1 || mask == 0) return;
        new Thread(() -> {
            long[] delays = {0, 200, 800, 2000};
            for (long delay : delays) {
                try {
                    Thread.sleep(delay);
                }
                catch (InterruptedException ignored) {}
                synchronized (lock) {
                    if (pid != root) return;
                }
                for (int p : processTree(root)) {
                    int error = ProcessHelper.setProcessAffinity(p, mask);
                    if (error != 0) Log.w(TAG, "sched_setaffinity(" + p + ") failed: " + error);
                }
            }
        }, "linux-session-affinity").start();
    }

    /** The mask for the container's cores, or 0 when it uses all of them (or the list is unusable). */
    private static int affinityMask(Container container) {
        String cpuList = container.getCPUList();
        if (cpuList == null || cpuList.isEmpty()) return 0;
        int mask;
        try {
            mask = ProcessHelper.getAffinityMask(cpuList);
        }
        catch (NumberFormatException e) {
            return 0;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int all = cores >= 32 ? -1 : (1 << cores) - 1;
        return mask == all ? 0 : mask;
    }

    /**
     * The guest program's own result. proot's exit code does not say whether the program crashed, but it
     * prints "vpid 1: terminated with signal N" or "vpid 1: exited with status N" for the first guest process.
     */
    public int guestStatus(int prootStatus) {
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile("vpid 1: (terminated with signal|exited with status) (\\d+)");
        String[] lines = rawOutput.lastOutput(80).split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            java.util.regex.Matcher matcher = pattern.matcher(lines[i]);
            if (!matcher.find()) continue;
            int value = Integer.parseInt(matcher.group(2));
            return matcher.group(1).startsWith("terminated") ? 128 + value : value;
        }
        return prootStatus;
    }

    /** What the session runs on, for a report: the emulator, the runtime, and whether it is proot-less / on Android's C library. */
    public String setupDescription() {
        return "emulator=" + container.getExtra("linuxEmulator") + " runtime=" + (runtime != null ? runtime.id : "") + " noProot=" + container.getExtra("linuxNoProot")
                + " executionMode=" + executionMode + " launcherPhase=" + phase + " rootPid=" + lastPid
                + " rootExit=" + (exitStatus == Integer.MIN_VALUE ? "pending" : exitStatus)
                + " bionic=" + container.getExtra("linuxBionic")
                + " emulatorBuild=" + container.getExtra("linuxEmulatorId")
                + " box64Preset=" + container.getExtra("linuxEmulatorPreset")
                + " fexPreset=" + container.getExtra("linuxFexPreset")
                + " fexThunks=" + container.getExtra("linuxFexThunks")
                + " linuxDriverRequested=" + container.getExtra("linuxVulkanDriver")
                + " vulkanOutput=" + container.getExtra("linuxVulkanPresent")
                + " glDriver=" + container.getExtra("linuxGlDriver")
                + " tuSysmem=" + container.getExtra("linuxTuSysmem")
                + " cpuList=" + container.getCPUList();
    }

    /** Seconds since the program was started. */
    public long secondsRunning() {
        return (System.currentTimeMillis() - startedAt) / 1000;
    }

    /** The last output lines worth showing to the user (proot's own sandbox warnings are left out). */
    public String lastOutput(int maxLines) {
        return rawOutput.lastOutput(maxLines).replace("proot info: ", "");
    }

    public String diagnosticOutput() { return diagnosticOutput.snapshot(); }

    /** Called off the UI thread only, with telemetry enabled. Does not inspect unrelated process command lines. */
    public String processSnapshot() {
        if (!com.winlator.cmod.telemetry.Telemetry.isEnabled()) return "";
        return processes.snapshot(lastPid);
    }

    public String crashEvidence() {
        if (!com.winlator.cmod.telemetry.Telemetry.isEnabled()) return "";
        return com.winlator.cmod.telemetry.LinuxCrashEvidence.collect(environment.getContext(), startedAt, sessionPids());
    }

    private java.util.Set<Integer> sessionPids() {
        java.util.Set<Integer> pids = processes.knownPids();
        if (lastPid > 0) pids.add(lastPid);
        return pids;
    }

    @Override
    public void stop() {
        phase = "stopping";
        synchronized (lock) {
            if (process != null) process.requestStop();
            pid = -1;
        }
    }

    /** Waits for owned descendants to be reaped. Call off the main thread. */
    public void awaitTeardown() {
        environment.awaitTeardown();
    }

    public void suspendProcess() {
        synchronized (lock) {
            if (process != null) process.setSuspended(true);
        }
    }

    public void resumeProcess() {
        synchronized (lock) {
            if (process != null) process.setSuspended(false);
        }
    }

    /** The root process and every descendant, read from /proc (the app can see its own uid's processes). */
    private static List<Integer> processTree(int root) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries != null) {
            for (File entry : entries) {
                int pid;
                try {
                    pid = Integer.parseInt(entry.getName());
                }
                catch (NumberFormatException e) {
                    continue;
                }
                int parent = parentOf(pid);
                if (parent > 0) children.computeIfAbsent(parent, k -> new ArrayList<>()).add(pid);
            }
        }
        List<Integer> result = new ArrayList<>();
        result.add(root);
        for (int i = 0; i < result.size(); i++) {
            List<Integer> kids = children.get(result.get(i));
            if (kids != null) result.addAll(kids);
        }
        return result;
    }

    private static int parentOf(int pid) {
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()));
            // "pid (comm) S ppid ...": comm can hold spaces and parentheses, so start after the last ')'.
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Integer.parseInt(fields[1]);
        }
        catch (Exception e) {
            return -1;
        }
    }

}
