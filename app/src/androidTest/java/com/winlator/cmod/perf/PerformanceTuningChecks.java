package com.winlator.cmod.perf;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.MainActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.ProcessHelper;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Opt-in device checks: temporary cache shortcut/child process; no user containers or game data. */
public final class PerformanceTuningChecks {
    private static final StringBuilder report=new StringBuilder();
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(report.toString()+"FAIL "+message); report.append("PASS ").append(message).append('\n'); }
    private static Field field(String name) throws Exception { Field f=PerformanceSession.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void drain(PerformanceSession session) throws Exception {
        ((ScheduledExecutorService)field("worker").get(session)).submit(() -> {}).get(10,TimeUnit.SECONDS);
    }
    public static String run(Instrumentation instrumentation) throws Exception {
        report.setLength(0);
        android.content.Context context=instrumentation.getTargetContext();
        SharedPreferences prefs=PreferenceManager.getDefaultSharedPreferences(context);
        String key=PerformanceTuning.KEYS[0];
        boolean had=prefs.contains(key), previous=prefs.getBoolean(key,false);
        File file=new File(context.getCacheDir(),"performance-check.desktop");
        Files.write(file.toPath(),"[Desktop Entry]\nName=Performance check\nExec=/system/bin/true\n".getBytes());
        Container container=new Container(-12345); container.setRootDir(context.getCacheDir());
        Shortcut shortcut=new Shortcut(container,file);
        Activity activity=null; PerformanceSession session=null; int pid=-1, inheritedChild=-1; boolean gpu=false;
        File signal=new File(context.getCacheDir(),"performance-check.signal");
        File childPid=new File(context.getCacheDir(),"performance-check.pid");
        signal.delete(); childPid.delete();
        try {
            for(String option:PerformanceTuning.KEYS) shortcut.putExtra(option,"0");
            prefs.edit().remove(key).commit(); shortcut.putExtra(key,null);
            check(!PerformanceTuning.resolve(context,shortcut,0),"missing global defaults to Off");
            prefs.edit().putBoolean(key,true).commit();
            check(PerformanceTuning.resolve(context,shortcut,0),"missing game override inherits global On");
            shortcut.putExtra(key,"0");
            check(!PerformanceTuning.resolve(context,shortcut,0),"game Off overrides global On");
            prefs.edit().putBoolean(key,false).commit(); shortcut.putExtra(key,"1");
            check(PerformanceTuning.resolve(context,shortcut,0),"game On overrides global Off");
            shortcut.putExtra(key,"0");
            check(PerformanceTuning.selectCores(new long[]{3530,3530,3530,3530,3530,3530,4320,4320})==255,"8 Elite topology retains all eight fast cores");
            check(PerformanceTuning.selectCores(new long[]{1800,1800,2800,3200})==12,"mixed topology selects performance cores");
            check(PerformanceTuning.selectCores(new long[]{1800,0,3200})==6,"unknown core stays eligible");
            check(PerformanceTuning.selectCores(new long[]{0,0})==0,"unknown topology makes no affinity request");
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            pid=ProcessHelper.exec(new String[]{"/system/bin/sh","-c","while [ ! -f "+signal.getAbsolutePath()+" ]; do sleep 0.1; done; sleep 90 & echo $! > "+childPid.getAbsolutePath()+"; wait"},new String[0],context.getCacheDir(),null);
            check(pid>0,"temporary own-UID child starts");
            long originalMask=PerformanceSession.getAffinity(pid);
            check(PerformanceSession.setNice(pid,0)==0 && PerformanceSession.getNice(pid)==0,"temporary child starts with controlled normal priority");
            int originalNice=PerformanceSession.getNice(pid);
            check(originalMask>0 && originalNice<=19,"JNI reads actual child affinity and priority");
            long subset=Long.lowestOneBit(originalMask);
            check(PerformanceSession.setAffinity(pid,(int)subset)==0 && PerformanceSession.getAffinity(pid)==subset,"JNI changes and reads back affinity");
            check(PerformanceSession.setAffinity(pid,(int)originalMask)==0 && PerformanceSession.getAffinity(pid)==originalMask,"JNI restores original affinity");
            final Activity owner=activity; final PerformanceSession[] holder=new PerformanceSession[1];
            instrumentation.runOnMainSync(() -> holder[0]=new PerformanceSession(owner,shortcut));
            session=holder[0];
            final android.app.Dialog[] dialog=new android.app.Dialog[1];
            instrumentation.runOnMainSync(() -> dialog[0]=com.winlator.cmod.ui.settings.PerformanceTuningDialog.show(owner,shortcut,holder[0]));
            instrumentation.waitForIdleSync();
            check(dialog[0].isShowing(),"in-game Compose tuning dialog opens with lifecycle owner");
            instrumentation.runOnMainSync(() -> dialog[0].dismiss());
            instrumentation.runOnMainSync(() -> dialog[0]=com.winlator.cmod.ui.settings.PerformanceTuningDialog.show(owner,null,holder[0]));
            instrumentation.waitForIdleSync();
            check(dialog[0].isShowing(),"global tuning card renders");
            instrumentation.runOnMainSync(() -> dialog[0].dismiss());
            // Force an eligible subset to test lifecycle restoration even on homogeneous SoCs.
            field("bigMask").setInt(session,(int)subset);
            session.setGuestRoot(pid); drain(session); field("readyAt").setLong(session,0);
            shortcut.putExtra(PerformanceTuning.KEYS[1],"1"); shortcut.putExtra(PerformanceTuning.KEYS[2],"1");
            session.resume(); drain(session);
            check(PerformanceSession.getAffinity(pid)==subset,"session applies CPU preference to guest child");
            int boosted=PerformanceSession.getNice(pid);
            check(boosted<=originalNice,"priority request never lowers existing priority");
            report.append("INFO nice before=").append(originalNice).append(" after=").append(boosted).append('\n');
            check(session.status().contains("Thread operation failures:"),"live status exposes thread results and failures");
            session.suspend(); drain(session);
            check(PerformanceSession.getAffinity(pid)==originalMask && PerformanceSession.getNice(pid)==originalNice,"backgrounding restores child affinity and priority");
            session.resume(); drain(session);
            check(PerformanceSession.getAffinity(pid)==subset,"resuming reapplies tuning");
            shortcut.putExtra(PerformanceTuning.KEYS[1],"0"); shortcut.putExtra(PerformanceTuning.KEYS[2],"0");
            session.refresh(); drain(session);
            check(PerformanceSession.getAffinity(pid)==originalMask && PerformanceSession.getNice(pid)==originalNice,"live Off restores original policy");
            shortcut.putExtra(PerformanceTuning.KEYS[2],"1"); session.refresh(); drain(session);
            Files.write(signal.toPath(),new byte[]{1});
            for(int attempt=0;attempt<100 && !childPid.isFile();attempt++) Thread.sleep(20);
            inheritedChild=Integer.parseInt(new String(Files.readAllBytes(childPid.toPath())).trim());
            check(PerformanceSession.getAffinity(inheritedChild)==subset,"new descendant inherits tuned affinity");
            session.close(); session.awaitRestore();
            check(PerformanceSession.getAffinity(pid)==originalMask,"session exit restores original policy");
            check(PerformanceSession.getAffinity(inheritedChild)==originalMask,"exit discovers and restores newly inherited descendant policy");
            for(String option:PerformanceTuning.KEYS) shortcut.putExtra(option,"0");
            shortcut.putExtra(PerformanceTuning.KEYS[0],"1"); shortcut.putExtra(PerformanceTuning.KEYS[3],"1");
            instrumentation.runOnMainSync(() -> holder[0]=new PerformanceSession(owner,shortcut));
            session=holder[0]; session.resume(); drain(session);
            boolean supported=((android.os.PowerManager)context.getSystemService(android.content.Context.POWER_SERVICE)).isSustainedPerformanceModeSupported();
            check(session.status().contains("Sustained: "+(supported?"requested":"unsupported")),"sustained mode reports actual Android device support");
            report.append("INFO sustained supported=").append(supported).append('\n');
            check(session.status().contains("GPU turbo: disabled"),"saved GPU On is blocked by session controller");
            check(!field("gpuOwned").getBoolean(session) && !prefs.getBoolean("perfGpuRestorePending",false),"blocked GPU option makes no power-control request");
            check(PerformanceSession.setGpuTurbo(true)==android.system.OsConstants.EOPNOTSUPP,"native guard rejects legacy turbo before device IO");
            // Exercise recovery from an old app's marker without first enabling the harmful override.
            prefs.edit().putBoolean("perfGpuRestorePending",true).commit();
            session.refresh(); drain(session);
            check(field("gpuError").getInt(session)==0 && !prefs.getBoolean("perfGpuRestorePending",false),"legacy pending request releases successfully");
            session.suspend(); drain(session);
            session.resume(); drain(session);
            check(!field("gpuOwned").getBoolean(session) && !prefs.getBoolean("perfGpuRestorePending",false),"resume cannot re-enable a saved GPU On value");
            session.close(); session.awaitRestore();
            return report.toString();
        } finally {
            if(session!=null) {session.close();session.awaitRestore();}
            if(gpu) PerformanceSession.setGpuTurbo(false);
            if(inheritedChild>0) android.os.Process.killProcess(inheritedChild);
            if(pid>0) android.os.Process.killProcess(pid);
            signal.delete(); childPid.delete();
            if(had) prefs.edit().putBoolean(key,previous).commit(); else prefs.edit().remove(key).commit();
            file.delete();
            if(activity!=null) { Activity owner=activity; instrumentation.runOnMainSync(owner::finish); }
        }
    }
}
