package com.winlator.cmod.linux;

import android.app.Instrumentation;
import android.content.Context;
import com.winlator.cmod.container.*;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Exercises real x86 guest callbacks through the installed FEX package, including the stock failure. */
public final class FexVulkanCompatChecks {
    private static final StringBuilder report=new StringBuilder();
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(report+"FAIL "+text);report.append("PASS ").append(text).append('\n');}
    public static String run(Instrumentation driver) throws Exception {
        report.setLength(0);Context context=driver.getTargetContext();ContainerManager manager=new ContainerManager(context);
        Container shared=null;for(Container c:manager.getContainers())if(c.isLinux()){shared=c;break;}
        check(shared!=null,"Linux container available");
        LinuxEmulator.Installed fex=null;for(LinuxEmulator.Installed i:LinuxEmulator.list(context,LinuxEmulator.FEX))if(i.id.equals("fex-2610")){fex=i;break;}
        check(fex!=null,"matched FEX 2610 installed");
        File stock=new File(fex.dir,"lib/fex-emu/HostThunks/libvulkan-host.so");byte[] stockBefore=Files.readAllBytes(stock.toPath());
        File config=new File(shared.getLinuxHomeDir(),".config/fex-emu/Config.json");byte[] configBefore=config.isFile()?Files.readAllBytes(config.toPath()):null;
        File probe=new File(context.getCacheDir(),"fex-vulkan-callback-test"),output=new File(context.getCacheDir(),"fex-vulkan-callback-test.txt");
        try {
            try(InputStream in=driver.getContext().getAssets().open("fex_vulkan_callback_test")){Files.copy(in,probe.toPath(),StandardCopyOption.REPLACE_EXISTING);}probe.setExecutable(true);
            Map<String,String> extras=new HashMap<>();extras.put(LinuxEmulator.EXTRA_EMULATOR,LinuxEmulator.FEX);extras.put(LinuxEmulator.EXTRA_ID,fex.id);
            extras.put(LinuxEmulator.EXTRA_FEX_THUNKS,"1");extras.put(LinuxGpuMemory.EXTRA,"0");
            Container effective=shared.copyWithExtras(extras);
            File overlay=LinuxFexVulkanCompat.hostLibraries(context,fex);
            check(!overlay.equals(stock.getParentFile()) && new File(overlay,"libvulkan-host.so").isFile(),"corrected host thunk selected for matched package");
            check(new File(overlay,"libGL-host.so").getCanonicalFile().equals(new File(stock.getParentFile(),"libGL-host.so").getCanonicalFile()),"OpenGL thunk remains the original component");
            check(new File(overlay.getParentFile(),"HostThunks_32").getCanonicalFile().equals(new File(stock.getParentFile().getParentFile(),"HostThunks_32").getCanonicalFile()),"32-bit thunk directory remains original");
            for(String mode:new String[]{"stock-direct","stock-proc","fixed-proc","fixed-direct"}) {
                String variables=mode.startsWith("stock") ? "FEX_THUNKHOSTLIBS="+stock.getParent() : "";
                effective.setEnvVars(variables);
                String[] command=LinuxSession.programCommand(context,effective,probe.getAbsolutePath(),mode.endsWith("direct") ? "direct":"proc");
                LinuxSession.Launch launch=LinuxSession.build(context,ImageFs.find(context),effective,LinuxRuntime.resolve(context,effective),command);
                ProcessBuilder pb=new ProcessBuilder(launch.argv);pb.directory(launch.workingDir);pb.environment().clear();
                for(String pair:launch.hostEnv){int eq=pair.indexOf('=');if(eq>0)pb.environment().put(pair.substring(0,eq),pair.substring(eq+1));}
                pb.redirectErrorStream(true);pb.redirectOutput(output);Process child=pb.start();
                boolean finished=child.waitFor(25,TimeUnit.SECONDS);if(!finished)child.destroyForcibly();
                String text=new String(Files.readAllBytes(output.toPath()));
                boolean pass=finished && child.exitValue()==0 && text.contains("PASS FEX Vulkan");
                if(mode.equals("stock-proc")) check(finished && text.contains("terminated with signal 4") && !text.contains("PASS FEX Vulkan"),"stock proc-address callback reproduces SIGILL"+(text.contains("terminated with signal 4") ? " (proot status "+child.exitValue()+")" : ": "+text));
                else check(pass,mode+" callback route and cleanup: "+(pass?"verified":text));
            }
            check(Arrays.equals(stockBefore,Files.readAllBytes(stock.toPath())),"installed FEX component is unchanged");
            return report.toString();
        } finally {
            if(configBefore==null)config.delete();else Files.write(config.toPath(),configBefore);
            probe.delete();output.delete();
        }
    }
}
