package com.winlator.cmod.linux;

import android.app.Instrumentation;
import android.content.Context;
import com.winlator.cmod.container.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Temporary fixtures and a real Turnip loader; does not modify game settings. */
public final class LinuxGpuMemoryChecks {
    private static final StringBuilder report=new StringBuilder();
    private static void check(boolean ok,String text) { if(!ok)throw new AssertionError(report+"FAIL "+text);report.append("PASS ").append(text).append('\n'); }
    public static String run(Instrumentation driver) throws Exception {
        report.setLength(0);Context context=driver.getTargetContext();
        Container container=new Container(-1,new ContainerManager(context));container.setType("linux");
        container.putExtra(LinuxGpuMemory.EXTRA,"4096");
        File fixture=new File(context.getCacheDir(),"gpu-memory-test.desktop"),clone=new File(context.getCacheDir(),"gpu-memory-clone.desktop");
        try {
            Files.write(fixture.toPath(),"[Desktop Entry]\nName=GPU budget test\nExec=/bin/true\n".getBytes());
            Shortcut shortcut=new Shortcut(container,fixture);shortcut.putExtra(LinuxShortcutDefaults.EXTRA_OWN,"1");
            shortcut.putExtra(LinuxGpuMemory.EXTRA,"2048");shortcut.saveData();shortcut=new Shortcut(container,fixture);
            check("2048".equals(shortcut.getExtra(LinuxGpuMemory.EXTRA)),"shortcut saves and reloads 2 GB");
            check("2048".equals(LinuxEmulatorSetup.of(container,shortcut).getExtra(LinuxGpuMemory.EXTRA)),"launch receives shortcut budget instead of container budget");
            Files.copy(fixture.toPath(),clone.toPath(),StandardCopyOption.REPLACE_EXISTING);
            check("2048".equals(new Shortcut(container,clone).getExtra(LinuxGpuMemory.EXTRA)),"shortcut duplication preserves budget");
            shortcut.putExtra(LinuxGpuMemory.EXTRA,"0");shortcut.saveData();
            check("0".equals(LinuxEmulatorSetup.of(container,new Shortcut(container,fixture)).getExtra(LinuxGpuMemory.EXTRA)),"explicit Default does not inherit container budget");
            JSONObject imported=new JSONObject().put("type","linux").put("extraData",new JSONObject().put(LinuxGpuMemory.EXTRA,"2048"));
            check("2048".equals(LinuxShortcutDefaults.fromData(context,container.getManager(),imported).get(LinuxGpuMemory.EXTRA)),"imported Linux settings preserve budget");
            Map<String,String> env=new HashMap<>();
            LinuxGpuMemory.prepare(context,container,env,false,false);
            check(!env.containsKey("VK_INSTANCE_LAYERS"),"Steam client is not capped");
            env.put("VK_INSTANCE_LAYERS","VK_LAYER_LSFG:VK_LAYER_SKYNET_frame_probe");env.put("VK_ADD_LAYER_PATH","/other");
            LinuxGpuMemory.apply(env,false,true);
            check(env.get("VK_INSTANCE_LAYERS").equals("VK_LAYER_LSFG:VK_LAYER_SKYNET_frame_probe:"+LinuxGpuMemory.LAYER),"budget coexists with LSFG and frame diagnostics");
            env.put(LinuxGpuMemory.ENV,"0");LinuxGpuMemory.apply(env,false,true);
            check("/other".equals(env.get("VK_ADD_LAYER_PATH")) && "VK_LAYER_LSFG:VK_LAYER_SKYNET_frame_probe".equals(env.get("VK_INSTANCE_LAYERS")),"Default removes only budget layer and its paths");
            File rt=null;File[] installed=LinuxRuntime.runtimesDir(context).listFiles();
            if(installed!=null)for(File candidate:installed)if(new File(candidate,"usr/lib/libvulkan.so.1").isFile()){rt=candidate;break;}
            check(rt!=null,"installed Vulkan runtime available");
            File libc=new File(context.getFilesDir(),"linux-shim/libc-v2-"+rt.getName()),ld=new File(libc,"ld-linux-aarch64.so.1");
            check(ld.isFile(),"seccomp-compatible Linux loader available");
            File executable=new File(context.getCacheDir(),"gpu-memory-smoke");
            try(InputStream in=driver.getContext().getAssets().open("gpu_memory_smoke_aarch64")){Files.copy(in,executable.toPath(),StandardCopyOption.REPLACE_EXISTING);}executable.setExecutable(true);
            File library=new File(rt,"usr/lib/libvulkan_freedreno.so");
            List<LinuxDriverManager.Installed> drivers=LinuxDriverManager.list(context);
            if(!drivers.isEmpty())library=new File(new File(LinuxDriverManager.driversDir(context),drivers.get(0).id),"libvulkan_freedreno.so");
            File icd=new File(context.getCacheDir(),"gpu-memory-smoke-icd.json"),output=new File(context.getCacheDir(),"gpu-memory-smoke.txt");
            try {
                Files.write(icd.toPath(),new JSONObject().put("file_format_version","1.0.0").put("ICD",new JSONObject().put("library_path",library.getAbsolutePath()).put("api_version","1.3.0")).toString().getBytes());
                for(String value:new String[]{"0","2048","4096","0"}) {
                    container.putExtra(LinuxGpuMemory.EXTRA,value);
                    ProcessBuilder pb=new ProcessBuilder(ld.getAbsolutePath(),"--library-path",libc+":"+new File(rt,"usr/lib"),executable.getAbsolutePath());
                    Map<String,String> smokeEnv=pb.environment();
                    LinuxGpuMemory.prepare(context,container,smokeEnv,false,true);
                    // This native smoke process represents Box64/native Vulkan calls and FEX host thunks.
                    smokeEnv.put("VK_DRIVER_FILES",icd.getAbsolutePath());smokeEnv.put("HOME",context.getCacheDir().getAbsolutePath());
                    pb.redirectErrorStream(true);pb.redirectOutput(output);Process child=pb.start();
                    boolean finished=child.waitFor(25,TimeUnit.SECONDS);if(!finished)child.destroyForcibly();
                    String text=new String(Files.readAllBytes(output.toPath()));
                    check(finished && child.exitValue()==0 && text.contains("PASS actual Vulkan"),"Turnip budget "+value+" MiB: "+text.trim());
                    if(!"0".equals(value))check(text.contains("requested_mib="+value+" native_local_mib="),"real loader activated the budget layer");
                }
            } finally { executable.delete();icd.delete();output.delete(); }
            return report.toString();
        } finally { fixture.delete();clone.delete(); }
    }
}
