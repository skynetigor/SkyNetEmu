package com.winlator.cmod.linux;

import android.content.Context;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.core.FileUtils;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.*;

/** Local, opt-in frame-flow sampling. Only the existing opted-in telemetry uploader receives snapshots. */
public final class FramePipelineDiagnostics {
    public static final String KEY="frame_pipeline_diagnostics";
    private static final String LAYER="VK_LAYER_SKYNET_frame_probe";
    private static final Pattern NUMBER=Pattern.compile("(\\w+)=(-?\\d+)");
    private static volatile boolean enabled;
    private static final class Target {
        final long window;final AtomicLong received=new AtomicLong();
        Target(long window){this.window=window;}
    }
    private static volatile Target target=new Target(0);
    private static volatile File reports;
    private static volatile Supplier<String> renderer=() -> "";
    private static volatile String overlay="Frame pipeline: waiting";
    private static volatile String latest="pipeline_enabled=0", display="Frame pipeline diagnostics disabled";
    private static ScheduledExecutorService worker;
    private static Map<String,Long> previous=new HashMap<>();
    private static long previousNs, previousWindow, generation;
    private static final class Sample {
        final long ns;final Map<String,Long> values;
        Sample(long ns,Map<String,Long> values){this.ns=ns;this.values=values;}
    }
    private static final ArrayDeque<Sample> history=new ArrayDeque<>();
    private static volatile boolean frameGeneration;
    private FramePipelineDiagnostics() {}
    public static synchronized void configure(Context context) {
        stop();
        enabled=PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY,false);
        target=new Target(0);previous.clear();history.clear();previousNs=0;overlay="Frame pipeline: waiting";
        if(!enabled) {latest="pipeline_enabled=0";display="Frame pipeline diagnostics disabled";return;}
        File parent=new File(context.getFilesDir(),"frame-probe-reports");
        // Previous run's bounded numeric diagnostics are disposable, never user game data.
        FileUtils.delete(parent); reports=new File(parent,UUID.randomUUID().toString());reports.mkdirs();
        latest="pipeline_enabled=1 guest_probe_available=0"; display="Waiting for game frame pipeline";
        worker=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"FramePipeline");t.setDaemon(true);return t;});
        long run=generation;
        worker.scheduleWithFixedDelay(() -> sampleRun(run),1,1,TimeUnit.SECONDS);
    }
    public static synchronized void stop() {
        generation++;enabled=false;renderer=() -> "";
        if(worker!=null){worker.shutdownNow();worker=null;}
    }
    public static boolean enabled() {return enabled;}
    public static void renderer(Supplier<String> provider) {renderer=provider;}
    public static void targetWindow(int window) {long w=Integer.toUnsignedLong(window);if(target.window!=w)target=new Target(w);}
    public static void received(int window) {Target t=target;if(enabled && Integer.toUnsignedLong(window)==t.window)t.received.incrementAndGet();}
    public static String snapshot() {return latest;}
    public static String display() {return display;}
    public static String overlay() {return overlay;}

    /** Called after all environment overrides. Does not change ICDs, present modes, or other layers. */
    public static void prepare(Context context, Map<String,String> env, boolean generatesFrames) {
        if(!enabled || reports==null)return;
        frameGeneration=generatesFrames;
        File library=LinuxSession.stageAsset(context,"frame-probe-layer","libVkLayer_skynet_frame_probe.so");
        if(library==null)return;
        try {
            File manifest=new File(library.getParentFile(),"VkLayer_skynet_frame_probe.json");
            JSONObject layer=new JSONObject(); layer.put("name",LAYER);layer.put("type","GLOBAL");
            layer.put("library_path",library.getAbsolutePath());layer.put("api_version","1.3.0");
            layer.put("implementation_version","1");layer.put("description","SkyNet queued frame probe");
            JSONObject json=new JSONObject();json.put("file_format_version","1.2.0");json.put("layer",layer);
            FileUtils.writeString(manifest,json.toString());
            append(env,"VK_ADD_LAYER_PATH",library.getParent(),":");
            append(env,"VK_INSTANCE_LAYERS",LAYER,":");
            env.put("SKY_FRAME_PROBE_DIR",reports.getAbsolutePath());
        } catch(Exception ignored) {}
    }
    private static void append(Map<String,String> env,String key,String value,String separator) {
        String old=env.get(key);if(old==null || old.isEmpty())env.put(key,value);
        else if(!Arrays.asList(old.split(Pattern.quote(separator))).contains(value))env.put(key,old+separator+value);
    }
    private static Map<String,Long> parse(String text) {
        Map<String,Long> result=new HashMap<>();Matcher m=NUMBER.matcher(text);
        while(m.find())try{result.put(m.group(1),Long.parseLong(m.group(2)));}catch(NumberFormatException ignored){}
        return result;
    }
    private static long value(Map<String,Long> m,String key) {return m.getOrDefault(key,0L);}
    private static String rate(Map<String,Long> now,String key,long elapsed) {
        Long old=previous.get(key);if(old==null || elapsed<=0 || value(now,key)<old)return "N/A";
        return String.format(Locale.US,"%.1f",(value(now,key)-old)*1e9/elapsed);
    }
    private static synchronized void sampleRun(long run) {
        if(run!=generation)return;try{sample();}catch(Exception ignored){}
    }
    private static synchronized void sample() throws Exception {
        if(!enabled)return;
        Target current=target;long window=current.window;
        if(window!=previousWindow){previous.clear();history.clear();previousNs=0;previousWindow=window;}
        Map<String,Long> now=parse(renderer.get());long clock=System.nanoTime();
        while(!history.isEmpty() && clock-history.peekFirst().ns>5_000_000_000L)history.removeFirst();
        if(!history.isEmpty()){previous=history.peekFirst().values;previousNs=history.peekFirst().ns;}
        long elapsed=previousNs==0?0:clock-previousNs;
        now.put("received",current.received.get());
        long queued=0,attempts=0,failures=0,acquires=0,acquireUs=0,presentUs=0,updated=0;int matched=0,unknown=0,unmatched=0;
        JSONObject newest=null;File[] files=reports.listFiles();
        if(files!=null)for(int i=0;i<Math.min(files.length,512);i++) {
            File file=files[i];if(!file.getName().endsWith(".json") || file.length()>4096)continue;
            try {
                JSONObject o=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                long sourceWindow=o.optLong("window");if(sourceWindow==0){unknown++;continue;}
                if(window==0 || sourceWindow!=window){unmatched++;continue;}
                matched++; queued+=o.optLong("queued");attempts+=o.optLong("attempts");failures+=o.optLong("failures");
                acquires+=o.optLong("acquires");acquireUs+=o.optLong("acquire_us");presentUs+=o.optLong("present_us");
                if(o.optLong("updated_ns")>=updated){updated=o.optLong("updated_ns");newest=o;}
            }catch(Exception ignored){}
        }
        now.put("guest_queued",queued);now.put("guest_attempts",attempts);now.put("guest_failures",failures);
        now.put("guest_acquires",acquires);now.put("guest_acquire_us",acquireUs);now.put("guest_present_us",presentUs);
        StringBuilder out=new StringBuilder("pipeline_enabled=1 pipeline_sample_ns="+clock+" pipeline_window="+window+" pipeline_rate_window_ms="+(elapsed/1000000));
        for(String k:new String[]{"guest_queued","guest_attempts","guest_failures","guest_acquires","guest_acquire_us","guest_present_us","received","pipe_accepted","pipe_unique","pipe_repeats","pipe_skipped","pipe_android_queued","pipe_displayed","pipe_display_available","pipe_display_result"}) {
            out.append(' ').append(k).append('=').append(now.getOrDefault(k,k.startsWith("pipe_")?-1L:0L));
        }
        out.append(" guest_probe_available=").append(matched>0?1:0).append(" guest_probe_age_ms=").append(updated>0?Math.max(0,(clock-updated)/1000000):-1)
                .append(" guest_unknown_surfaces=").append(unknown).append(" guest_unmatched_surfaces=").append(unmatched);
        if(newest!=null)out.append(" guest_present_mode=").append(newest.optInt("mode",-1)).append(" guest_min_images=").append(newest.optInt("images",-1));
        if(current!=target)return;
        out.append(" pipeline_frame_generation=").append(frameGeneration?1:0);
        latest=out.toString();
        display=(frameGeneration?"Frame generation is active: guest count depends on layer order. Disable it for comparison.\n":"")
                +"Frame rates (queued, not GPU execution):\nGame queued: "+(matched>0?rate(now,"guest_queued",elapsed):"N/A")
                +" · received: "+rate(now,"received",elapsed)+"\nAccepted: "+rate(now,"pipe_accepted",elapsed)
                +" · unique queued: "+rate(now,"pipe_unique",elapsed)+"\nAndroid queued: "+rate(now,"pipe_android_queued",elapsed)
                +" · displayed: "+(value(now,"pipe_display_available")==1?rate(now,"pipe_displayed",elapsed):"N/A")
                +"\nRepeated source: "+rate(now,"pipe_repeats",elapsed)+" · skipped source updates: "+rate(now,"pipe_skipped",elapsed)
                +"\nGuest sample age: "+(updated>0?Math.max(0,(clock-updated)/1000000): -1)+" ms";
        overlay="Frame pipeline FPS (~5s)"+(frameGeneration?" (FG on)":"")
                +"\nGame queued "+(matched>0?rate(now,"guest_queued",elapsed):"N/A")+" | X11 "+rate(now,"received",elapsed)
                +"\nAccepted "+rate(now,"pipe_accepted",elapsed)+" | Unique "+rate(now,"pipe_unique",elapsed)
                +"\nAndroid "+rate(now,"pipe_android_queued",elapsed)+" | Display "+(value(now,"pipe_display_available")==1?rate(now,"pipe_displayed",elapsed):"N/A")
                +"\nRepeat "+rate(now,"pipe_repeats",elapsed)+" | Skip "+rate(now,"pipe_skipped",elapsed);
        history.addLast(new Sample(clock,now));previous=now;previousNs=clock;
    }
}
