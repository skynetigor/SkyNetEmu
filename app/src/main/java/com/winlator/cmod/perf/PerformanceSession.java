package com.winlator.cmod.perf;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.container.Shortcut;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Best-effort, session-scoped tuning. No frame-loop work and no thermal-policy changes. */
public final class PerformanceSession implements AutoCloseable {
    static { System.loadLibrary("winlator"); }
    public static native long getAffinity(int tid); // negative errno on failure
    public static native int setAffinity(int tid, int mask);
    public static native int getNice(int tid); // 1000 + errno on failure
    public static native int setNice(int tid, int value);
    public static native int setGpuTurbo(boolean enabled); // accepted request, NOT a clock measurement
    private static final String GPU_PENDING = "perfGpuRestorePending";
    private final Activity activity;
    private final Shortcut shortcut;
    private final SharedPreferences prefs;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PerformanceTuning"); t.setPriority(Thread.MIN_PRIORITY); return t;
    });
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (Arrays.asList(PerformanceTuning.KEYS).contains(key)) refresh();
    };
    private final Map<Integer, State> states = new HashMap<>();
    private volatile boolean foreground, closed;
    private int root = -1;
    private long rootBirth;
    private long readyAt;
    private boolean gpuOwned, lastPriority, lastCores;
    private int gpuError = -1, failures, changedNice, changedAffinity;
    private final int bigMask = detectCores();
    private final boolean sustainedSupported;
    private volatile String status = "Waiting for session";
    private static volatile String latest = "";
    private static final class State {
        final int tid, owner; final long birth; final int nice; final long mask;
        int appliedNice; long appliedMask;
        State(int tid, int owner, long birth, int nice, long mask) {
            this.tid=tid; this.owner=owner; this.birth=birth; this.nice=nice; this.mask=mask;
            appliedNice=nice; appliedMask=mask;
        }
    }
    public PerformanceSession(Activity activity, Shortcut shortcut) {
        this.activity=activity; this.shortcut=shortcut;
        prefs=PreferenceManager.getDefaultSharedPreferences(activity);
        PowerManager pm=(PowerManager)activity.getSystemService(Context.POWER_SERVICE);
        sustainedSupported=pm != null && pm.isSustainedPerformanceModeSupported();
        prefs.registerOnSharedPreferenceChangeListener(listener);
        worker.scheduleWithFixedDelay(this::tickSafely, 5, 5, TimeUnit.SECONDS);
    }
    public void setGuestRoot(int pid) {
        submit(() -> { root=pid; rootBirth=birth(pid); readyAt=android.os.SystemClock.elapsedRealtime()+4000; });
    }
    public void resume() { foreground=true; refresh(); }
    public void suspend() { foreground=false; refresh(); }
    public void refresh() { submit(this::tickSafely); }
    public String status() { return status; }
    public static String diagnostics() { return latest; }
    private void submit(Runnable action) {
        if (!closed) try { worker.execute(action); } catch (RejectedExecutionException ignored) {}
    }
    private void tickSafely() {
        try { tick(); } catch (Exception e) { Log.w("PerformanceTuning", "Tuning unavailable", e); }
    }
    private void tick() {
        boolean active=foreground && !closed;
        boolean sustained=active && PerformanceTuning.resolve(activity,shortcut,0);
        boolean priority=active && PerformanceTuning.resolve(activity,shortcut,1);
        boolean cores=active && PerformanceTuning.resolve(activity,shortcut,2);
        boolean gpuRequested=active && PerformanceTuning.resolve(activity,shortcut,3);
        // Quarantined after a severe device regression. Saved overrides must never invoke it.
        activity.runOnUiThread(() -> activity.getWindow().setSustainedPerformanceMode(
                foreground && !closed && sustained && sustainedSupported));
        if (gpuOwned || prefs.getBoolean(GPU_PENDING,false)) {
            gpuError=setGpuTurbo(false);
            if (gpuError==0) { gpuOwned=false; prefs.edit().putBoolean(GPU_PENDING,false).commit(); }
        }
        failures=0; changedNice=0; changedAffinity=0;
        // Capture descendants born since the last scan before restoring inherited policies.
        if ((lastPriority && !priority) || (lastCores && !cores)) tune(false,false);
        restoreDisabled(priority, cores);
        if ((priority || cores) && root>0 && android.os.SystemClock.elapsedRealtime()>=readyAt) tune(priority,cores);
        lastPriority=priority; lastCores=cores;
        status="Sustained: " + (sustained ? (sustainedSupported ? "requested" : "unsupported") : "off")
                + "\nPriority: " + changedNice + " boosted threads; cores: " + changedAffinity + " restricted threads"
                + "\nGPU turbo: disabled after performance regression"
                + (prefs.getBoolean(GPU_PENDING,false) ? " · restore pending (errno " + gpuError + ")" : "")
                + "\nThread operation failures: " + failures;
        String diagnostics="tuning_sustained="+(sustained?1:0)+" tuning_sustained_supported="+(sustainedSupported?1:0)
                +" tuning_priority="+(priority?1:0)+" tuning_cores="+(cores?1:0)+" tuning_big_mask="+Integer.toUnsignedString(bigMask)
                +" tuning_boosted="+changedNice+" tuning_affinity="+changedAffinity+" tuning_failures="+failures
                +" tuning_gpu_requested="+(gpuRequested?1:0)+" tuning_gpu_blocked=1"+" tuning_gpu_accepted="+(gpuOwned?1:0)+" tuning_gpu_errno="+gpuError;
        if (active) latest=diagnostics;
        Log.d("PerformanceTuning", diagnostics);
    }
    private void restoreDisabled(boolean priority, boolean cores) {
        Iterator<State> it=states.values().iterator();
        while(it.hasNext()) {
            State s=it.next();
            if (birth(s.tid)!=s.birth) { it.remove(); continue; }
            if (!priority && s.appliedNice!=s.nice) {
                int actual=getNice(s.tid);
                if (actual==s.appliedNice && setNice(s.tid,s.nice)!=0) failures++;
                else s.appliedNice=s.nice;
            }
            if (!cores && s.appliedMask!=s.mask) {
                long actual=getAffinity(s.tid);
                if (actual==s.appliedMask && setAffinity(s.tid,(int)s.mask)!=0) failures++;
                else s.appliedMask=s.mask;
            }
            if (!priority && !cores && s.appliedNice==s.nice && s.appliedMask==s.mask) it.remove();
        }
    }
    private void tune(boolean priority, boolean cores) {
        Map<Integer,Integer> parents=new HashMap<>();
        File[] proc=new File("/proc").listFiles();
        if (proc==null) return;
        for (File file:proc) {
            int pid=parse(file.getName()); if(pid<=0) continue;
            try {
                String status=read(file+"/status");
                if (!status.matches("(?s).*\nUid:\\s+"+android.os.Process.myUid()+"\\s+.*")) continue;
                String[] stat=stat(pid);
                parents.put(pid,Integer.parseInt(stat[1]));
                if(parents.size()>=256) break;
            } catch(Exception ignored) {}
        }
        Set<Integer> tree=new HashSet<>();
        if(rootBirth>0 && birth(root)==rootBirth) tree.add(root);
        // Keep known descendants if a launcher exits/reparents them. Birth times prevent PID reuse.
        for(State s:states.values()) if(s.owner!=android.os.Process.myPid() && birth(s.tid)==s.birth) tree.add(s.owner);
        for(int pass=0;pass<32;pass++) {
            boolean changed=false;
            for(Map.Entry<Integer,Integer> e:parents.entrySet()) if(tree.contains(e.getValue())) changed|=tree.add(e.getKey());
            if(!changed) break;
        }
        Set<Integer> scan=new HashSet<>(tree); scan.add(android.os.Process.myPid());
        List<State> batch=new ArrayList<>();
        for(int pid:scan) {
            File[] tasks=new File("/proc/"+pid+"/task").listFiles(); if(tasks==null) continue;
            for(File task:tasks) {
                if(batch.size()>=2048) break;
                int tid=parse(task.getName()); if(tid<=0) continue;
                if(pid==android.os.Process.myPid()) {
                    String name=read(task+"/comm").trim().toLowerCase(Locale.ROOT);
                    if(!(priority || lastPriority) || !name.matches(".*(audio|render|present|compositor|vulkan|vkqueue|glthread).*")) continue;
                }
                long born=birth(tid); if(born<=0) continue;
                State s=states.get(tid);
                if(s==null || s.birth!=born) {
                    int nice=getNice(tid); long mask=getAffinity(tid);
                    int actualNice=nice; long actualMask=mask;
                    if(nice>19 || mask<=0) { failures++; continue; }
                    // New threads inherit a tuned leader's policy; restore their pre-tuning policy too.
                    State leader=states.get(pid);
                    if(leader==null) leader=states.get(parents.getOrDefault(pid,-1));
                    if(leader!=null && birth(leader.tid)==leader.birth) {
                        if(nice==leader.appliedNice) nice=leader.nice;
                        if(mask==leader.appliedMask) mask=leader.mask;
                    }
                    s=new State(tid,pid,born,nice,mask);
                    s.appliedNice=actualNice; s.appliedMask=actualMask;
                    states.put(tid,s);
                }
                batch.add(s);
            }
        }
        // Capture every baseline before changing any policy in this scan.
        for(State s:batch) {
            if(priority && s.nice>-8) {
                int actual=getNice(s.tid);
                boolean owned=actual==s.appliedNice && s.appliedNice!=s.nice;
                if(actual>-8 && actual<=19) {
                    boolean accepted=false;
                    for(int target:new int[]{-8,-4,-2}) if(target<actual && setNice(s.tid,target)==0) { accepted=true; break; }
                    if(!accepted) failures++;
                    owned |= accepted;
                }
                int now=getNice(s.tid);
                if(owned && now<s.nice) { s.appliedNice=now; changedNice++; }
            }
            if(cores && s.owner!=android.os.Process.myPid() && bigMask!=0) {
                long target=s.mask & Integer.toUnsignedLong(bigMask);
                long actual=getAffinity(s.tid);
                if(target!=0 && target!=s.mask && (actual==s.mask || actual==s.appliedMask)) {
                    if(setAffinity(s.tid,(int)target)==0 && getAffinity(s.tid)==target) {
                        s.appliedMask=target; changedAffinity++;
                    } else failures++;
                }
            }
        }
    }
    private static int detectCores() {
        long[] speeds=new long[32];
        int frequencyCount=0, capacityCount=0;
        long[] capacities=new long[speeds.length];
        for(int i=0;i<speeds.length;i++) {
            speeds[i]=number("/sys/devices/system/cpu/cpu"+i+"/cpufreq/cpuinfo_max_freq");
            capacities[i]=number("/sys/devices/system/cpu/cpu"+i+"/cpu_capacity");
            if(speeds[i]>0) frequencyCount++; if(capacities[i]>0) capacityCount++;
        }
        return PerformanceTuning.selectCores(capacityCount>frequencyCount?capacities:speeds);
    }
    private static long number(String path) { try{return Long.parseLong(read(path).trim());}catch(Exception e){return 0;} }
    private static int parse(String value) { try{return Integer.parseInt(value);}catch(Exception e){return -1;} }
    private static String read(String path) { try{return new String(Files.readAllBytes(new File(path).toPath()));}catch(Exception e){return "";} }
    private static String[] stat(int tid) { String s=read("/proc/"+tid+"/stat"); return s.substring(s.lastIndexOf(')')+2).split("\\s+"); }
    private static long birth(int tid) { try{return Long.parseLong(stat(tid)[19]);}catch(Exception e){return -1;} }
    @Override public void close() {
        if(closed) return; closed=true; foreground=false;
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
        worker.execute(this::tickSafely); worker.shutdown();
    }
    public void awaitRestore() {
        try { worker.awaitTermination(2,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    /** Recover only requests made by this app; do not reset another app's GPU state on every launch. */
    public static void recoverGpu(Context context) {
        SharedPreferences p=PreferenceManager.getDefaultSharedPreferences(context);
        if(p.getBoolean(GPU_PENDING,false)) new Thread(() -> {
            int error=setGpuTurbo(false);
            if(error==0) p.edit().putBoolean(GPU_PENDING,false).commit();
            Log.d("PerformanceTuning","GPU recovery errno="+error);
        }, "PerformanceGpuRecovery").start();
    }
}
