package com.winlator.cmod.linux;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.FileUtils;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

/** Per-game Vulkan reporting only: no allocator, CPU memory or Android renderer changes. */
public final class LinuxGpuMemory {
    public static final String EXTRA = "linuxGpuMemoryMB";
    public static final String ENV = "SKY_GPU_MEMORY_MB";
    public static final String LAYER = "VK_LAYER_SKYNET_gpu_memory";
    private LinuxGpuMemory() {}

    public static String normalize(String value) {
        if (value == null) return "0";
        switch (value) {
            case "512": case "1024": case "2048": case "4096": case "8192": case "12288": case "16384": return value;
            default: return "0";
        }
    }

    /** stage all loader ABIs for a Steam client whose next game may use a different emulator */
    public static void prepare(Context context, Container container, Map<String,String> env, boolean bionic, boolean activate) {
        prepare(context,container,env,bionic,activate,0);
    }

    public static void prepare(Context context, Container container, Map<String,String> env, boolean bionic, boolean activate, int elfMachine) {
        String value=normalize(container.getExtra(EXTRA));
        // Always write zero too: an already-running Steam client may carry another game's budget.
        env.put(ENV,value);
        File root=new File(context.getFilesDir(),"linux-gpu-memory");
        env.put("SKY_GPU_MEMORY_LAYER_ROOT",root.getAbsolutePath());
        File script=LinuxSession.stageAsset(context,"linux-shim","skynet-gpu-memory.sh");
        if (script!=null) env.put("SKY_GPU_MEMORY_SCRIPT",script.getAbsolutePath());
        String variant="aarch64";
        LinuxEmulator.Installed emulator=LinuxEmulator.resolve(context,container);
        if (bionic) variant="bionic";
        else if (emulator!=null && LinuxEmulator.FEX.equals(emulator.emulator) && !LinuxFex.thunksEnabled(container,emulator)) variant="x86_64";
        if (!bionic && elfMachine==183) variant="aarch64";
        if (!bionic && elfMachine==3) variant="1".equals(env.get("SKY_FEX_GL32")) ? "aarch64" : "i686";
        env.put("SKY_GPU_MEMORY_VARIANT",variant);
        if (!"0".equals(value)) {
            try {
                for (String abi : new String[]{"aarch64","x86_64","i686","bionic"}) {
                    if (activate && !abi.equals(variant)) continue;
                    File library=LinuxSession.stageAsset(context,"linux-shim","libVkLayer_skynet_gpu_memory_"+abi+".so");
                    if (library==null) throw new IllegalStateException("Missing GPU memory budget library: "+abi);
                    File dir=new File(root,abi); if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create "+dir);
                    JSONObject layer=new JSONObject().put("name",LAYER).put("type","GLOBAL")
                            .put("library_path",library.getAbsolutePath()).put("api_version","1.3.0")
                            .put("implementation_version","1").put("description","SkyNet reported GPU memory budget");
                    File manifest=new File(dir,"VkLayer_skynet_gpu_memory.json");
                    if (!FileUtils.writeString(manifest,new JSONObject().put("file_format_version","1.2.0").put("layer",layer).toString()))
                        throw new IllegalStateException("Cannot write "+manifest);
                }
            } catch (Exception e) { throw new IllegalStateException("Cannot prepare GPU memory budget",e); }
        }
        apply(env,bionic,activate);
        Log.i("LinuxGpuMemory","budget_mib="+value+" loader="+variant+" active="+activate+" (reported budget)");
    }

    /** Shared semantics with skynet-gpu-memory.sh; preserve every unrelated layer and search path. */
    public static void apply(Map<String,String> env, boolean bionic, boolean activate) {
        String root=env.get("SKY_GPU_MEMORY_LAYER_ROOT");
        remove(env,"VK_INSTANCE_LAYERS",LAYER,false);
        if (root!=null) {
            remove(env,"VK_ADD_LAYER_PATH",root+"/",true);
            remove(env,"VK_LAYER_PATH",root+"/",true);
        }
        String value=normalize(env.get(ENV));env.put(ENV,value);
        if ("0".equals(value) || !activate) return;
        if (root==null) throw new IllegalStateException("GPU memory layer directory missing");
        String dir=new File(root,env.getOrDefault("SKY_GPU_MEMORY_VARIANT","aarch64")).getAbsolutePath();
        append(env,bionic ? "VK_LAYER_PATH" : "VK_ADD_LAYER_PATH",dir);
        append(env,"VK_INSTANCE_LAYERS",LAYER);
    }

    private static void remove(Map<String,String> env,String key,String match,boolean prefix) {
        String old=env.get(key); if (old==null) return;
        List<String> kept=new ArrayList<>();
        for (String item:old.split(":")) if (!item.isEmpty() && !(prefix ? item.startsWith(match) : item.equals(match))) kept.add(item);
        if (kept.isEmpty()) env.remove(key);else env.put(key,String.join(":",kept));
    }
    private static void append(Map<String,String> env,String key,String value) {
        String old=env.get(key);env.put(key,old==null || old.isEmpty() ? value : old+":"+value);
    }
}
