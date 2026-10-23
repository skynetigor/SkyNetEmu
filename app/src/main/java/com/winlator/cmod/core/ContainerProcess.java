package com.winlator.cmod.core;

import android.util.Log;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A kernel subreaper owns all descendants, including double-forks and zombie leaders with live threads. */
public final class ContainerProcess {
    private static final String TAG = "ContainerProcess";
    private final java.lang.Process supervisor;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean delivered = new AtomicBoolean();
    private final int pid;
    private boolean ready;
    private boolean suspended;

    public ContainerProcess(File executable, String[] command, String[] envp, File workingDir,
                            Callback<Integer> terminated, Callback<String> output) throws IOException {
        String token = "SKYNET_ROOT_EXIT_" + UUID.randomUUID();
        ArrayList<String> argv = new ArrayList<>();
        argv.add(executable.getAbsolutePath());
        argv.add(token);
        argv.addAll(Arrays.asList(command));
        ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDir).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        if (envp != null) for (String item : envp) {
            int separator = item.indexOf('=');
            if (separator > 0) env.put(item.substring(0, separator), item.substring(separator + 1));
        }
        // Guest Wine/proot preloads must not run inside the Android supervisor itself.
        saveLoaderVariable(env, "LD_PRELOAD", "SKYNET_SUPERVISOR_PRELOAD");
        saveLoaderVariable(env, "LD_LIBRARY_PATH", "SKYNET_SUPERVISOR_LIBRARY_PATH");
        supervisor = builder.start();
        pid = getPid(supervisor);
        Thread reader = new Thread(() -> {
            try (BufferedReader stream = new BufferedReader(new InputStreamReader(supervisor.getInputStream()))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    if (line.equals(token + ":ready")) {
                        onReady();
                    } else if (line.startsWith(token + ":")) {
                        int status = Integer.parseInt(line.substring(token.length() + 1));
                        deliver(terminated, status);
                    } else {
                        if (output != null) output.call(line);
                        ProcessHelper.dispatchOutput(line);
                    }
                }
                int status = supervisor.waitFor();
                if (!delivered.get() && !stopping.get()) {
                    Log.e(TAG, "Supervisor exited before reporting the guest result: " + status);
                    deliver(terminated, status == 0 ? 125 : status);
                }
            } catch (IOException | InterruptedException | NumberFormatException error) {
                if (!stopping.get()) {
                    Log.e(TAG, "Unable to monitor container process " + pid, error);
                    deliver(terminated, 125);
                }
                requestStop();
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, "container-process-" + pid);
        reader.setDaemon(true);
        reader.start();
    }

    private static int getPid(java.lang.Process process) throws IOException {
        try {
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return field.getInt(process);
        } catch (ReflectiveOperationException | RuntimeException error) {
            process.destroy();
            throw new IOException("Cannot identify container supervisor", error);
        }
    }

    private static void saveLoaderVariable(Map<String, String> env, String name, String saved) {
        env.remove(saved);
        String value = env.remove(name);
        if (value != null) env.put(saved, value);
    }

    private void deliver(Callback<Integer> callback, int status) {
        if (delivered.compareAndSet(false, true) && !stopping.get() && callback != null)
            callback.call(status);
    }

    public int getPid() { return pid; }

    private synchronized void onReady() {
        ready = true;
        if (suspended && !stopping.get()) android.os.Process.sendSignal(pid, OsConstants.SIGUSR1);
    }

    public synchronized void setSuspended(boolean suspended) {
        this.suspended = suspended;
        if (ready && !stopping.get())
            android.os.Process.sendSignal(pid, suspended ? OsConstants.SIGUSR1 : OsConstants.SIGUSR2);
    }

    public void requestStop() {
        if (stopping.compareAndSet(false, true)) supervisor.destroy();
    }

    public boolean awaitStop(long timeoutMillis) throws InterruptedException {
        return supervisor.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
