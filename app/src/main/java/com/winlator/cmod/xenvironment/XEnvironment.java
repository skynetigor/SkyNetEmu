package com.winlator.cmod.xenvironment;

import android.content.Context;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ContainerProcess;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.LinuxProgramLauncherComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final Context context;
    private final ImageFs imageFs;
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();
    private final ArrayList<ContainerProcess> processes = new ArrayList<>();
    private static final java.util.Set<XEnvironment> active =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean stopping;

    public XEnvironment(Context context, ImageFs imageFs) {
        this.context = context;
        this.imageFs = imageFs;
    }

    public Context getContext() {
        return context;
    }

    public ImageFs getImageFs() {
        return imageFs;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        for (EnvironmentComponent component : components) {
            if (component.getClass() == componentClass) return (T)component;
        }
        return null;
    }

    @Override
    public Iterator<EnvironmentComponent> iterator() {
        return components.iterator();
    }

    public File getTmpDir() {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 0771);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        active.add(this);
        FileUtils.clear(getTmpDir());
        try {
            for (EnvironmentComponent environmentComponent : this) environmentComponent.start();
        } catch (RuntimeException error) {
            stopEnvironmentComponents();
            throw error;
        }
    }

    public synchronized ContainerProcess startProcess(String[] command, String[] env, File directory,
            Callback<Integer> terminated, Callback<String> output) {
        if (stopping) throw new IllegalStateException("Container is stopping");
        try {
            ContainerProcess process = new ContainerProcess(
                    new File(context.getApplicationInfo().nativeLibraryDir, "libcontainer_supervisor.so"),
                    command, env, directory, terminated, output);
            processes.add(process);
            active.add(this);
            return process;
        } catch (java.io.IOException error) {
            android.util.Log.e("XEnvironment", "Unable to supervise container process", error);
            throw new IllegalStateException("Unable to supervise container process", error);
        }
    }

    public void stopEnvironmentComponents() {
        synchronized (this) {
            if (stopping) return;
            stopping = true;
            for (ContainerProcess process : processes) process.requestStop();
            android.util.Log.i("ContainerShutdown", "Stopping " + processes.size() + " owned process supervisors");
        }
        for (EnvironmentComponent environmentComponent : this) environmentComponent.stop();
    }

    /** Must complete before restarting/killing the Android owner. Never falls back to name/path matching. */
    public void awaitTeardown() {
        ArrayList<ContainerProcess> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(processes); }
        long deadline = android.os.SystemClock.elapsedRealtime() + 7000;
        for (ContainerProcess process : snapshot) {
            try {
                if (!process.awaitStop(Math.max(1, deadline - android.os.SystemClock.elapsedRealtime())))
                    throw new IllegalStateException("Container workers remain under supervisor " + process.getPid());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping container workers", error);
            }
        }
        active.remove(this);
        android.util.Log.i("ContainerShutdown", "Verified shutdown of " + snapshot.size() + " owned process supervisors");
    }

    public static void stopAll() {
        ArrayList<XEnvironment> snapshot = new ArrayList<>(active);
        for (XEnvironment environment : snapshot) environment.stopEnvironmentComponents();
        for (XEnvironment environment : snapshot) environment.awaitTeardown();
    }

    public void onPause() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) guestProgramLauncherComponent.suspendProcess();
        LinuxProgramLauncherComponent linuxLauncher = getComponent(LinuxProgramLauncherComponent.class);
        if (linuxLauncher != null) linuxLauncher.suspendProcess();
    }

    public void onResume() {
        GuestProgramLauncherComponent guestProgramLauncherComponent = getComponent(GuestProgramLauncherComponent.class);
        if (guestProgramLauncherComponent != null) guestProgramLauncherComponent.resumeProcess();
        LinuxProgramLauncherComponent linuxLauncher = getComponent(LinuxProgramLauncherComponent.class);
        if (linuxLauncher != null) linuxLauncher.resumeProcess();
    }
}
