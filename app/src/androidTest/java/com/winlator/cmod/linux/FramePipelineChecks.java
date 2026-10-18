package com.winlator.cmod.linux;

import android.app.Instrumentation;
import android.content.Context;
import androidx.preference.PreferenceManager;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class FramePipelineChecks {
    private static final StringBuilder report=new StringBuilder();
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(report+"FAIL "+text);report.append("PASS ").append(text).append('\n');}
    public static String run(Instrumentation driver) throws Exception {
        report.setLength(0);Context context=driver.getTargetContext();
        android.content.SharedPreferences prefs=PreferenceManager.getDefaultSharedPreferences(context);
        boolean had=prefs.contains(FramePipelineDiagnostics.KEY),old=prefs.getBoolean(FramePipelineDiagnostics.KEY,false);
        try {
            prefs.edit().putBoolean(FramePipelineDiagnostics.KEY,false).commit();FramePipelineDiagnostics.configure(context);
            Map<String,String> env=new HashMap<>();env.put("VK_DRIVER_FILES","keep-icd");env.put("MESA_VK_WSI_PRESENT_MODE","mailbox");
            FramePipelineDiagnostics.prepare(context,env,false);check(env.size()==2,"disabled probe does not alter launch environment");
            prefs.edit().putBoolean(FramePipelineDiagnostics.KEY,true).commit();FramePipelineDiagnostics.configure(context);
            FramePipelineDiagnostics.prepare(context,env,false);
            check(env.get("VK_INSTANCE_LAYERS").contains("VK_LAYER_SKYNET_frame_probe") && "keep-icd".equals(env.get("VK_DRIVER_FILES")) && "mailbox".equals(env.get("MESA_VK_WSI_PRESENT_MODE")),"probe enables only its layer and preserves driver and present mode");
            File reports=new File(env.get("SKY_FRAME_PROBE_DIR"));FramePipelineDiagnostics.targetWindow(42);
            FramePipelineDiagnostics.received(42);FramePipelineDiagnostics.received(43);
            JSONObject fixture=new JSONObject().put("window",42).put("queued",17).put("attempts",18).put("failures",1).put("updated_ns",System.nanoTime()).put("mode",1).put("images",3);
            Files.write(new File(reports,"vk-test-1.json").toPath(),fixture.toString().getBytes());
            fixture.put("window",43).put("queued",999);
            Files.write(new File(reports,"vk-test-2.json").toPath(),fixture.toString().getBytes());
            FramePipelineDiagnostics.renderer(() -> "pipe_accepted=16 pipe_unique=10 pipe_repeats=5 pipe_skipped=6 pipe_android_queued=15 pipe_displayed=-1 pipe_display_available=0 pipe_display_result=1");
            java.lang.reflect.Method sample=FramePipelineDiagnostics.class.getDeclaredMethod("sample");sample.setAccessible(true);sample.invoke(null);
            String snapshot=FramePipelineDiagnostics.snapshot();
            check(snapshot.contains("pipeline_frame_generation=0"),"pass-through LSFG is not reported as active frame generation");
            FramePipelineDiagnostics.prepare(context,env,true);sample.invoke(null);
            check(FramePipelineDiagnostics.snapshot().contains("pipeline_frame_generation=1"),"active frame generation is reported");
            FramePipelineDiagnostics.prepare(context,env,false);
            check(snapshot.contains("guest_queued=17 ") && snapshot.contains("received=1 ") && snapshot.contains("guest_probe_available=1"),"reports count only the selected game window, excluding other Vulkan surfaces");
            check(FramePipelineDiagnostics.display().contains("displayed: N/A"),"missing screen feedback stays unavailable");
            // Real native loader/driver chain on the installed Linux runtime, without launching a game.
            File runtimes=LinuxRuntime.runtimesDir(context);File[] installed=runtimes.listFiles();
            File rt=null;if(installed!=null)for(File candidate:installed)if(new File(candidate,"usr/lib/libvulkan.so.1").isFile()){rt=candidate;break;}
            if(rt==null){report.append("SKIP actual Linux loader: runtime absent\n");return report.toString();}
            File libc=new File(context.getFilesDir(),"linux-shim/libc-v2-"+rt.getName());File ld=new File(libc,"ld-linux-aarch64.so.1");
            if(!ld.isFile()){report.append("SKIP actual Linux loader: seccomp-compatible libc not staged\n");return report.toString();}
            File executable=new File(context.getCacheDir(),"frame-probe-smoke");
            try(InputStream in=driver.getContext().getAssets().open("frame_probe_smoke")){Files.copy(in,executable.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}executable.setExecutable(true);
            File library=new File(rt,"usr/lib/libvulkan_freedreno.so");
            List<LinuxDriverManager.Installed> drivers=LinuxDriverManager.list(context);
            if(!drivers.isEmpty())library=new File(new File(LinuxDriverManager.driversDir(context),drivers.get(0).id),"libvulkan_freedreno.so");
            File icd=new File(context.getCacheDir(),"frame-probe-smoke-icd.json");
            Files.write(icd.toPath(),new JSONObject().put("file_format_version","1.0.0").put("ICD",new JSONObject().put("library_path",library.getAbsolutePath()).put("api_version","1.3.0")).toString().getBytes());
            File output=new File(context.getCacheDir(),"frame-probe-smoke.txt");
            ProcessBuilder pb=new ProcessBuilder(ld.getAbsolutePath(),"--library-path",libc.getAbsolutePath()+":"+new File(rt,"usr/lib").getAbsolutePath(),executable.getAbsolutePath());
            Map<String,String> smokeEnv=new HashMap<>();FramePipelineDiagnostics.prepare(context,smokeEnv,false);pb.environment().putAll(smokeEnv);
            pb.environment().put("VK_DRIVER_FILES",icd.getAbsolutePath());pb.environment().put("HOME",context.getCacheDir().getAbsolutePath());pb.redirectErrorStream(true);pb.redirectOutput(output);
            Process child=pb.start();
            boolean finished=child.waitFor(20,TimeUnit.SECONDS);if(!finished)child.destroyForcibly();
            String text=new String(Files.readAllBytes(output.toPath()));
            check(finished && child.exitValue()==0 && text.contains("PASS actual Linux loader"),"real glibc Vulkan loader/device accepts probe: "+text.trim());
            executable.delete();icd.delete();output.delete();
            return report.toString();
        } finally {
            FramePipelineDiagnostics.stop();if(had)prefs.edit().putBoolean(FramePipelineDiagnostics.KEY,old).commit();else prefs.edit().remove(FramePipelineDiagnostics.KEY).commit();
        }
    }
}
