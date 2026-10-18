package com.winlator.cmod;

import android.app.*;
import android.os.*;
import android.view.*;
import com.winlator.cmod.widget.*;
import com.winlator.cmod.xserver.*;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.extensions.*;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.errors.*;
import com.winlator.cmod.xconnector.*;
import java.nio.*;
import java.lang.reflect.*;
import java.util.*;

/** Real MotionEvents and protocol requests, with packet decoding independent of the event encoder. */
public final class NativeTouchChecks {
    private final Instrumentation driver;
    public NativeTouchChecks(Instrumentation driver) { this.driver = driver; }
    private final StringBuilder report = new StringBuilder();
    private XServer server; private XInput2Extension xi;
    private void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); report.append("PASS ").append(label).append('\n'); }
    private static class Client extends XClient {
        final List<XITouchEvent> events = Collections.synchronizedList(new ArrayList<>());
        Client(XServer server) { super(server, new XInputStream(4096), new XOutputStream(4096)); getOutputStream().setByteOrder(ByteOrder.LITTLE_ENDIAN); }
        @Override public void sendEvent(Event e) { if (e instanceof XITouchEvent) events.add((XITouchEvent)e); }
        long count(int type) { return events.stream().filter(e -> e.type == type).count(); }
        XITouchEvent last(int type) { for (int i=events.size()-1;i>=0;i--) if(events.get(i).type==type)return events.get(i); throw new AssertionError("missing event "+type); }
    }
    private ByteBuffer request(Client client, int minor, ByteBuffer body) throws Exception {
        body.flip();
        Field active = XInputStream.class.getDeclaredField("activeBuffer"); active.setAccessible(true);
        active.set(client.getInputStream(), body);
        client.setRequestData((byte)minor); client.setRequestLength(body.remaining());
        client.getOutputStream().buffer.clear();
        xi.handleRequest(client, client.getInputStream(), client.getOutputStream());
        ByteBuffer reply=client.getOutputStream().buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);reply.flip();return reply;
    }
    private ByteBuffer body() { return ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN); }
    private void version(Client client,int minor) throws Exception { request(client,47,body().putShort((short)2).putShort((short)minor)); }
    private void select(Client client,Window window,int device,int mask) throws Exception {
        request(client,46,body().putInt(window.id).putShort((short)1).putShort((short)0)
                .putShort((short)device).putShort((short)1).putInt(mask));
    }
    private static final int TOUCH=(1<<18)|(1<<19)|(1<<20), OWN=TOUCH|(1<<21), RAW=(1<<22)|(1<<23)|(1<<24);
    private void grab(Client c, Window w, boolean install) throws Exception {
        if(install) request(c,54,body().putInt(0).putInt(w.id).putInt(0).putInt(0).putShort((short)1)
                .putShort((short)1).putShort((short)1).put((byte)4).put((byte)2).put((byte)1).put((byte)0)
                .putShort((short)0).putInt(OWN).putInt(1<<31));
        else request(c,55,body().putInt(w.id).putInt(0).putShort((short)1).putShort((short)1)
                .put((byte)4).put(new byte[3]).putInt(1<<31));
    }
    private void allow(Client c,int id,Window w,boolean accept) throws Exception {
        request(c,53,body().putInt(0).putShort((short)2).put((byte)(accept?6:7)).put((byte)0).putInt(id).putInt(w.id));
    }
    private void idle() throws Exception {
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        Field jobs=xi.touch.getClass().getDeclaredField("jobs"); // use a lifecycle barrier, never sleep to guess delivery
        Method post=xi.touch.getClass().getDeclaredMethod("post",int.class,Runnable.class); post.setAccessible(true);
        post.invoke(xi.touch,-1,(Runnable)done::countDown);
        check(done.await(5,java.util.concurrent.TimeUnit.SECONDS),"touch worker drains");
    }
    private void sample(int type,int id,double x,double y) { server.injectTouch(type,id,x,y,SystemClock.uptimeMillis()); }
    private void protocol() throws Exception {
        server=new XServer(new ScreenInfo(1280,720),"vortek",null);
        xi=new XInput2Extension(server); server.extensions.put(XInput2Extension.MAJOR_OPCODE,xi);
        Client client=new Client(server); version(client,2);
        ByteBuffer devices=request(client,48,body().putShort((short)0).putShort((short)0));
        check(devices.getShort(8)==5 && devices.remaining()==32+devices.getInt(4)*4,"device reply size and five devices");
        int cursor=32,touchClasses=0;
        for(int i=0;i<5;i++){
            int count=devices.getShort(cursor+6)&65535, name=devices.getShort(cursor+8)&65535;
            cursor+=12+((name+3)&~3);
            for(int j=0;j<count;j++){
                int type=devices.getShort(cursor)&65535,length=(devices.getShort(cursor+2)&65535)*4;
                check(length>=8,"device class has valid length");
                if(type==8){touchClasses++;check(devices.getShort(cursor+4)==6&&devices.get(cursor+6)==1,"direct touch class source");}
                cursor+=length;
            }
        }
        check(cursor==devices.limit()&&touchClasses==2,"master and source expose touch class");
        Client old=new Client(server);version(old,0);
        try{select(old,server.windowManager.rootWindow,1,TOUCH);throw new AssertionError("old version accepted touch");}catch(BadValue expected){check(true,"XI2.0 rejects touch selection");}
        try{select(client,server.windowManager.rootWindow,1,1<<18);throw new AssertionError("partial mask accepted");}catch(BadValue expected){check(true,"partial touch lifecycle mask rejected");}
        select(client,server.windowManager.rootWindow,1,TOUCH|RAW);
        Client competitor=new Client(server); version(competitor,2);
        try{select(competitor,server.windowManager.rootWindow,2,TOUCH);throw new AssertionError("conflict accepted");}catch(BadAccess expected){check(true,"conflicting touch selection rejected");}
        sample(18,17,100.25,200.5);sample(18,3,400.75,300.125);sample(19,17,110.5,210.25);sample(20,3,401.5,302.25);sample(20,17,-2.5,220.5);idle();
        check(client.count(18)==2&&client.count(20)==2&&client.count(22)==2&&client.count(24)==2,"two independent normal and raw lifecycles");
        XITouchEvent begin=client.events.stream().filter(e->e.type==18).findFirst().get();
        check(begin.x==100.25&&begin.y==200.5&&begin.device==2,"fractional coordinates and master routing");
        check(client.last(20).x==-2.5,"captured finger remains valid outside surface");
        int id=begin.detail;sample(18,17,1,2);sample(20,17,1,2);idle();check(client.last(18).detail!=id,"Android ID reuse allocates new XI contact ID");
        for(int type:new int[]{18,19,20,21,22,23,24}){
            XOutputStream out=new XOutputStream(256);out.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            new XITouchEvent(type,2,123,456,1,2,0,-2.5,20.125,1.25,2.5,0,0).send((short)9,out);
            ByteBuffer wire=out.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);int size=wire.position();wire.flip();
            check(size==32+wire.getInt(4)*4&&wire.getShort(8)==type&&wire.getInt(16)==123,"XI event wire length/type/contact "+type);
            if(type>=22)check(size==68&&wire.getInt(32)==3,"raw touch valuators layout");
            if(type<=20)check(wire.getInt(32)==-163840&&wire.getInt(84)==-3&&wire.getInt(88)==0x80000000,"signed fractional fixed-point coordinates");
        }
        Client grabber=new Client(server);version(grabber,2);grab(grabber,server.windowManager.rootWindow,true);
        sample(18,5,250,300);idle();int touch=grabber.last(18).detail;
        check(grabber.count(21)==1,"passive grab gets ownership notification");
        sample(19,5,350,400);idle();
        long previously=client.count(18);allow(grabber,touch,server.windowManager.rootWindow,false);
        check(client.count(18)==previously+1&&grabber.count(20)==1,"reject hands contact to normal listener");
        check(client.last(18).x==250&&client.last(19).x==350,"late owner receives original begin and latest coalesced update");
        sample(20,5,250,300);idle();
        sample(18,6,250,300);idle();allow(grabber,grabber.last(18).detail,server.windowManager.rootWindow,true);
        long ends=client.count(20);sample(20,6,250,300);idle();check(client.count(20)==ends,"accepted grab excludes other listeners");
        // Physical lift while ownership is pending must not duplicate the owner's end on rejection.
        sample(18,11,250,300);idle();int pending=grabber.last(18).detail;
        long grabEnds=grabber.count(20);sample(20,11,251,301);idle();
        check(grabber.count(20)==grabEnds+1,"pending grab receives physical end once");
        allow(grabber,pending,server.windowManager.rootWindow,false);
        check(grabber.count(20)==grabEnds+1,"reject after lift does not duplicate end");
        select(client,server.windowManager.rootWindow,1,RAW);
        sample(18,30,250,300);idle();long rawEnds=client.count(24);
        allow(grabber,grabber.last(18).detail,server.windowManager.rootWindow,false);
        sample(19,30,270,320);sample(20,30,270,320);idle();
        check(client.count(24)==rawEnds+1,"raw device sequence survives every normal listener rejecting");
        select(client,server.windowManager.rootWindow,1,TOUCH|RAW);
        grab(grabber,server.windowManager.rootWindow,false);
        sample(18,7,250,300);idle();long endCount=client.count(20);xi.touch.cancel();idle();check(client.count(20)==endCount+1,"cancel ends active contact");
        sample(18,8,250,300);idle();endCount=client.count(20);xi.touch.setEnabled(false);idle();check(client.count(20)==endCount+1,"mode switch ends contacts");
        sample(18,9,250,300);idle();check(client.count(20)==endCount+1,"disabled device rejects new contact");
        xi.touch.setEnabled(true);idle();
        windowLifecycle(client);
        potentialOwner();
        android(driver.getTargetContext(),client);
        slowClient();
        xi.touch.close();client.freeResources();old.freeResources();competitor.freeResources();grabber.freeResources();
    }
    private void windowLifecycle(Client rootClient) throws Exception {
        Client childClient=new Client(server);version(childClient,2);
        Window child=server.windowManager.createWindow(childClient.resourceIDBase+1,server.windowManager.rootWindow,
                (short)100,(short)100,(short)300,(short)300,WindowAttributes.WindowClass.INPUT_ONLY,null,(byte)0,childClient);
        server.windowManager.mapWindow(child);select(childClient,child,1,OWN);
        sample(18,20,150.5,160.25);idle();int id=childClient.last(18).detail;
        check(childClient.last(18).localX==50.5&&childClient.last(18).localY==60.25,"begin hit test and fractional local coordinates");
        sample(19,20,800,600);idle();check(childClient.last(19).window==child.id,"contact stays on begin target after moving outside window");
        long ends=childClient.count(20);server.windowManager.destroyWindow(child.id);
        check(childClient.count(20)==ends+1,"window destruction terminates its contacts");
        sample(20,20,800,600);idle();
        grab(childClient,server.windowManager.rootWindow,true);sample(18,21,800,600);idle();
        long before=rootClient.count(18);xi.removeClient(childClient);
        check(rootClient.count(18)==before+1,"disconnected owner transfers to next listener");
        sample(20,21,800,600);idle();childClient.freeResources();
    }
    private void potentialOwner() throws Exception {
        Client outer=new Client(server),inner=new Client(server);version(outer,2);version(inner,2);
        Window child=server.windowManager.createWindow(inner.resourceIDBase+1,server.windowManager.rootWindow,
                (short)100,(short)100,(short)300,(short)300,WindowAttributes.WindowClass.INPUT_ONLY,null,(byte)0,inner);
        server.windowManager.mapWindow(child);grab(outer,server.windowManager.rootWindow,true);grab(inner,child,true);
        sample(18,22,150,160);idle();int id=outer.last(18).detail;
        check(inner.count(18)==1&&inner.count(21)==0,"potential owner receives early events only with ownership mask");
        allow(inner,id,child,true);
        check(inner.count(21)==0,"potential owner can accept without taking current ownership");
        allow(outer,id,server.windowManager.rootWindow,false);
        check(inner.count(21)==1,"reject transfers ownership to pre-accepted child grab");
        sample(20,22,150,160);idle();check(inner.count(20)==1,"pre-accepted child receives a single final end");
        grab(outer,server.windowManager.rootWindow,false);grab(inner,child,false);
        server.windowManager.destroyWindow(child.id);outer.freeResources();inner.freeResources();
    }
    private void slowClient() throws Exception {
        String name="native-touch-stall-"+System.nanoTime();
        android.net.LocalServerSocket listener=new android.net.LocalServerSocket(name);
        android.net.LocalSocket sender=new android.net.LocalSocket();
        sender.connect(new android.net.LocalSocketAddress(name));
        android.net.LocalSocket receiver=listener.accept();
        android.os.ParcelFileDescriptor fd=android.os.ParcelFileDescriptor.dup(sender.getFileDescriptor());
        XOutputStream out=new XOutputStream(new ClientSocket(fd.getFd()),1024);out.enableAsync();
        receiver.setSoTimeout(3000);
        android.os.ParcelFileDescriptor[] pipe=android.os.ParcelFileDescriptor.createPipe();
        try(XStreamLock ignored=out.lock()){out.setAncillaryFd(pipe[1].getFd());out.writeByte((byte)11);}
        pipe[1].close(); // the async queue must own its own duplicate already
        try(XStreamLock ignored=out.lock()){out.writeByte((byte)12);}
        check(receiver.getInputStream().read()==11,"async output preserves first packet");
        java.io.FileDescriptor[] received=receiver.getAncillaryFileDescriptors();
        check(received!=null&&received.length==1,"async output preserves ancillary descriptor");
        try(java.io.FileOutputStream passed=new java.io.FileOutputStream(received[0])){passed.write(37);}
        try(java.io.InputStream read=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){
            check(read.read()==37,"queued descriptor survives caller closing original fd");
        }
        check(receiver.getInputStream().read()==12,"replies and events retain FIFO packet order");
        int largeSize=20*1024*1024;
        byte[] large=new byte[largeSize];Arrays.fill(large,(byte)0x5a);
        java.util.concurrent.CountDownLatch complete=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> readError=new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader=new Thread(()->{
            try{
                byte[] part=new byte[65536];int remaining=largeSize;
                while(remaining>0){int n=receiver.getInputStream().read(part,0,Math.min(part.length,remaining));if(n<0)throw new AssertionError("partial X11 packet");
                    for(int i=0;i<n;i++)if(part[i]!=(byte)0x5a)throw new AssertionError("corrupt X11 packet");remaining-=n;}
            }catch(Throwable e){readError.set(e);}finally{complete.countDown();}
        },"X11-large-reply-reader");reader.setDaemon(true);reader.start();
        try(XStreamLock ignored=out.lock()){out.write(large);}
        check(complete.await(5,java.util.concurrent.TimeUnit.SECONDS)&&readError.get()==null,"full native-resolution-sized reply survives partial socket writes");


        long start=SystemClock.uptimeMillis();
        driver.runOnMainSync(()->{
            try(XStreamLock ignored=out.lock()){out.write(new byte[2*1024*1024]);}catch(Exception e){throw new RuntimeException(e);}
        });
        check(SystemClock.uptimeMillis()-start<1000,"stalled socket cannot block Android UI writes");
        Field closed=XOutputStream.class.getDeclaredField("closed");closed.setAccessible(true);
        long deadline=SystemClock.uptimeMillis()+3500;
        while(!closed.getBoolean(out)&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20);
        check(closed.getBoolean(out),"unresponsive client is disconnected at write deadline");
        out.close();fd.close();sender.close();receiver.close();listener.close();
    }
    private MotionEvent motion(long down,int action,int[] ids,float[] xs,float[] ys) {
        MotionEvent.PointerProperties[] pp=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] pc=new MotionEvent.PointerCoords[ids.length];
        for(int i=0;i<ids.length;i++){pp[i]=new MotionEvent.PointerProperties();pp[i].id=ids[i];pp[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            pc[i]=new MotionEvent.PointerCoords();pc[i].x=xs[i];pc[i].y=ys[i];pc[i].pressure=1;pc[i].size=1;}
        return MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,ids.length,pp,pc,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
    }
    private void android(android.content.Context context,Client client)throws Exception{
        final TouchpadView[] view={null};
        driver.runOnMainSync(()->{
            // Fullscreen transform is sufficient here; rendered surface tests run against the live session separately.
            view[0]=new TouchpadView(context,server,new Handler(Looper.getMainLooper()),()->{});
            view[0].layout(0,0,1280,720);view[0].setLinuxTouchMode(true);view[0].setRealMouseConnected(true);view[0].setMouseEnabled(false);
        });idle();
        long begins=client.count(18),ends=client.count(20),down=SystemClock.uptimeMillis();
        int[] actions={0,5|(1<<8),2,6,1};
        int[][] ids={{17},{17,3},{3,17},{3,17},{17}};
        driver.runOnMainSync(()->{
            for(int n=0;n<actions.length;n++){
                float[] xs=new float[ids[n].length],ys=new float[ids[n].length];Arrays.fill(xs,300);Arrays.fill(ys,250);
                MotionEvent e=motion(down,actions[n],ids[n],xs,ys);view[0].onTouchEvent(e);e.recycle();
            }
        });idle();
        check(client.count(18)==begins+2&&client.count(20)==ends+2,"Android reordered pointer indices preserve both contacts");
        check(server.pointer.getX()==0&&server.pointer.getY()==0,"native fingers leave the independent mouse pointer untouched");
        InputControlsView[] controls={null};int[] controlUps={0};
        driver.runOnMainSync(()->{
            controls[0]=new InputControlsView(context);controls[0].setTouchpadView(view[0]);
            com.winlator.cmod.inputcontrols.ControlsProfile profile=new com.winlator.cmod.inputcontrols.ControlsProfile(context,-99);
            profile.addElement(new com.winlator.cmod.inputcontrols.ControlElement(controls[0]) {
                private int captured=-1;
                @Override public boolean handleTouchDown(int id,float x,float y){if(x<200){captured=id;return true;}return false;}
                @Override public boolean handleTouchMove(int id,float x,float y){return false;}
                @Override public boolean handleTouchUp(int id){if(id==captured){captured=-1;controlUps[0]++;return true;}return false;}
            });
            controls[0].setProfile(profile);
        });idle();begins=client.count(18);ends=client.count(20);
        driver.runOnMainSync(()->{
            int[] action={0,5|(1<<8),5|(2<<8),2,3};
            int[][] pointer={{9},{9,17},{9,17,3},{3,9,17},{3,9,17}};
            for(int n=0;n<action.length;n++){
                float[] xs=new float[pointer[n].length],ys=new float[pointer[n].length];
                for(int i=0;i<xs.length;i++){xs[i]=pointer[n][i]==9?(n>=3?900:100):400;ys[i]=300;}
                MotionEvent e=motion(down,action[n],pointer[n],xs,ys);controls[0].onTouchEvent(e);e.recycle();
            }
        });idle();
        check(client.count(18)==begins+2&&client.count(20)==ends+2&&controlUps[0]==1,"two guest fingers plus captured overlay finger cancel cleanly without handoff");
        long updates=client.count(19);check(updates>=1,"overlay forwards guest move samples");
        driver.runOnMainSync(()->{
            MotionEvent e=motion(down,0,new int[]{9},new float[]{100},new float[]{300});controls[0].onTouchEvent(e);e.recycle();
            controls[0].layout(0,0,1280,720);
        });idle();check(controlUps[0]==2,"overlay resize releases captured control bindings");
        driver.runOnMainSync(()->{
            MotionEvent e=motion(down,0,new int[]{9},new float[]{100},new float[]{300});controls[0].onTouchEvent(e);e.recycle();
            controls[0].setShowTouchscreenControls(false);
        });idle();check(controlUps[0]==3,"hiding overlay releases captured control bindings");

        driver.runOnMainSync(()->view[0].layout(0,0,2560,1440));idle();
        driver.runOnMainSync(()->{MotionEvent e=motion(down,0,new int[]{4},new float[]{640.5f},new float[]{500.5f});view[0].onTouchEvent(e);e.recycle();});idle();
        check(client.last(18).x==320.25&&client.last(18).y==250.25,"scaled surface preserves subpixel coordinates");
        long resizeEnds=client.count(20),rawEnds=client.count(24);
        driver.runOnMainSync(()->view[0].layout(0,0,1280,1000));idle();
        check(client.count(20)==resizeEnds+1&&client.count(24)==rawEnds+1,"transform replacement terminates normal and raw sequences");
        long barBegins=client.count(18);
        driver.runOnMainSync(()->{MotionEvent e=motion(down,0,new int[]{4},new float[]{500},new float[]{50});view[0].onTouchEvent(e);e.recycle();});idle();
        check(client.count(18)==barBegins,"letterbox finger-down is ignored");
        driver.runOnMainSync(()->{MotionEvent e=motion(down,0,new int[]{4},new float[]{500.5f},new float[]{500.25f});view[0].onTouchEvent(e);e.recycle();});idle();
        check(client.last(18).x==500.5&&client.last(18).y==360.25,"letterboxed surface subtracts render offset");
        driver.runOnMainSync(()->view[0].setLinuxTouchMode(false));idle();
        check(!view[0].isNativeTouch()&&!xi.touch.isEnabled(),"Linux touchpad switches routing and device state");
        int[] mouse={0,0};java.util.concurrent.CountDownLatch mouseRelease=new java.util.concurrent.CountDownLatch(1);
        server.pointer.addOnPointerMotionListener(new Pointer.OnPointerMotionListener(){
            @Override public void onPointerButtonPress(Pointer.Button button){if(button==Pointer.Button.BUTTON_LEFT)mouse[0]++;}
            @Override public void onPointerButtonRelease(Pointer.Button button){if(button==Pointer.Button.BUTTON_LEFT){mouse[1]++;mouseRelease.countDown();}}
        });
        android.content.SharedPreferences prefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        boolean global=prefs.getBoolean("touchscreen_toggle",false);
        try{
            prefs.edit().putBoolean("touchscreen_toggle",true).apply();
            driver.runOnMainSync(()->{
                view[0].setRealMouseConnected(false);view[0].setMouseEnabled(true);
                for(int action:new int[]{0,1}){MotionEvent e=motion(down,action,new int[]{0},new float[]{500},new float[]{500});view[0].onTouchEvent(e);e.recycle();}
                view[0].setLinuxTouchMode(true); // cancels the held/delayed mouse click immediately
            });idle();
            check(mouseRelease.await(1,java.util.concurrent.TimeUnit.SECONDS)&&mouse[0]==1&&mouse[1]==1,"Linux touchpad still clicks and mode switch releases its mouse button");
            check(server.pointer.getX()==0&&server.pointer.getY()==0,"Linux touchpad ignores Windows global touchscreen toggle");
        }finally{prefs.edit().putBoolean("touchscreen_toggle",global).apply();}


    }
    public String live(Bundle args) throws Exception {
        android.content.Context context = driver.getTargetContext();
        com.winlator.cmod.container.Container container = com.winlator.cmod.steam.SteamContainer.ensure(context,
                new com.winlator.cmod.container.ContainerManager(context));
        String asset = args.getString("probe", "touchtest");
        java.io.File binary = new java.io.File(context.getFilesDir(), "linux-tools/" + asset);
        binary.getParentFile().mkdirs();
        try (java.io.InputStream input = context.getAssets().open("linux/" + asset)) {
            java.nio.file.Files.copy(input, binary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        com.winlator.cmod.core.FileUtils.chmod(binary, 0755);
        java.util.Map<String,String> extras = new java.util.HashMap<>(com.winlator.cmod.linux.LinuxShortcutDefaults.of(context,container));
        extras.put("linuxTouchMode", "native"); extras.put("screenSize","1280x720");
        extras.put("fullscreenStretched","0"); extras.put("lsfgMultiplier","0");
        extras.put("linuxVulkanPresent","sw"); extras.put("hudMode","0");
        extras.put("linuxBionic", args.getString("bionic","0"));
        extras.put("linuxEmulator",args.getString("emulator","box64"));
        extras.put("linuxEmulatorId",args.getString("emulator","box64").equals("fex")?"fex-2610":"box64-0.4.4");
        extras.put("linuxNoProot","0");
        if(asset.startsWith("xitouch"))extras.put("launchOptions","%command% 30 "+args.getString("selector","1")+" "+args.getString("grab",""));
        java.io.File file = com.winlator.cmod.container.ContainerManager.addLinuxShortcut(context,container,"__native_touch_e2e",binary.getPath(),extras);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1), fingers = new java.util.concurrent.CountDownLatch(1);
        StringBuilder output = new StringBuilder();
        com.winlator.cmod.core.Callback<String> callback = line -> {
            synchronized(output) { output.append(line).append('\n'); }
            if(line.contains("SDL touch devices=") || line.contains("READY selector=")) ready.countDown();
            if(line.contains("up=2 mouse=") || line.contains("TOUCH type=20")) fingers.countDown();
        };
        com.winlator.cmod.core.ProcessHelper.addDebugCallback(callback);
        try {
            android.content.Intent intent = new android.content.Intent(context,XServerDisplayActivity.class)
                    .putExtra("container_id",container.id).putExtra("shortcut_path",file.getPath())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = driver.startActivitySync(intent);
            // The activity clears callbacks during setup. Observe its launcher's retained startup output as well.
            com.winlator.cmod.core.ProcessHelper.addDebugCallback(callback);
            long deadline = SystemClock.uptimeMillis() + 45000;
            while (ready.getCount() > 0 && SystemClock.uptimeMillis() < deadline) {
                Field environmentField=XServerDisplayActivity.class.getDeclaredField("environment"); environmentField.setAccessible(true);
                com.winlator.cmod.xenvironment.XEnvironment environment=(com.winlator.cmod.xenvironment.XEnvironment)environmentField.get(activity);
                if(environment!=null){
                    com.winlator.cmod.xenvironment.components.LinuxProgramLauncherComponent launcher=environment.getComponent(com.winlator.cmod.xenvironment.components.LinuxProgramLauncherComponent.class);
                    if(launcher!=null){String startup=launcher.lastOutput(80);if(startup.contains("SDL touch devices=")||startup.contains("READY selector=")){callback.call(startup);break;}}
                }
                SystemClock.sleep(100);
            }
            if(ready.getCount()>0)throw new AssertionError("probe did not initialize: "+output);
            // Minimal probes are not games; dismiss the game's startup overlay before global input injection.
            Field preloader=XServerDisplayActivity.class.getDeclaredField("preloaderDialog");preloader.setAccessible(true);
            Object dialog=preloader.get(activity);dialog.getClass().getMethod("closeOnUiThread").invoke(dialog);
            driver.waitForIdleSync();
            Field field=XServerDisplayActivity.class.getDeclaredField("touchpadView");field.setAccessible(true);
            TouchpadView view=(TouchpadView)field.get(activity);
            driver.runOnMainSync(()->{
                view.setOnTouchListener((v,event)->{synchronized(output){output.append("ANDROID action=").append(event.getActionMasked()).append(" pointers=").append(event.getPointerCount()).append('\n');}return false;});
                view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{synchronized(output){output.append("LAYOUT ").append(r-l).append('x').append(b-t).append('\n');}});
            });
            // Closing the startup dialog reveals/resizes the SurfaceView asynchronously.
            SystemClock.sleep(1200);
            driver.waitForIdleSync();
            int[] location=new int[2];driver.runOnMainSync(()->view.getLocationOnScreen(location));
            float x1=location[0]+view.getWidth()*.35f,y1=location[1]+view.getHeight()*.45f;
            float x2=location[0]+view.getWidth()*.65f,y2=location[1]+view.getHeight()*.55f;
            long down=SystemClock.uptimeMillis();
            int[] actions={0,5|(1<<8),2,6,1};int[][] ids={{17},{17,3},{3,17},{3,17},{17}};
            for(int n=0;n<actions.length;n++){
                float[] xs=new float[ids[n].length],ys=new float[ids[n].length];
                for(int i=0;i<ids[n].length;i++){xs[i]=(ids[n][i]==17?x1:x2)+(n>=2?35:0);ys[i]=(ids[n][i]==17?y1:y2)+(n>=2?25:0);}
                MotionEvent event=motion(down,actions[n],ids[n],xs,ys);
                if(!driver.getUiAutomation().injectInputEvent(event,true))throw new AssertionError("input injection failed at sample "+n+": "+output);
                check(true,"inject physical-style multi-pointer sample "+n);
                event.recycle();
                SystemClock.sleep(60);
            }
            if(!fingers.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("probe did not receive fingers: "+output);
            SystemClock.sleep(100);
            synchronized(output){
                report.append(output);
                if(asset.startsWith("touchtest"))check(output.toString().contains("active=2")&&output.toString().contains("up=2 mouse="),"SDL receives two independent fingers");
                if(asset.startsWith("touchtest"))check(output.toString().contains("FINGER type=702"),"SDL receives finger motion");
                // Initial window positioning can emit motion before injection; no mouse events may follow a finger event.
                String trace=output.toString();int first=trace.indexOf("FINGER type=");
                if(asset.startsWith("touchtest"))check(first>=0&&!trace.substring(first).contains("MOUSE type="),"native fingers generate no mouse events");
            }
            if(asset.startsWith("xitouch"))synchronized(output){
                String trace=output.toString();
                long begins=trace.lines().filter(l->l.startsWith("TOUCH type=18 ")).count();
                long ends=trace.lines().filter(l->l.startsWith("TOUCH type=20 ")).count();
                long rawBegins=trace.lines().filter(l->l.startsWith("RAW type=22 ")).count();
                long rawEnds=trace.lines().filter(l->l.startsWith("RAW type=24 ")).count();
                int expected="0".equals(args.getString("selector","1"))?4:2;
                check(begins==expected&&ends==expected&&rawBegins==expected&&rawEnds==expected,"libXi decodes complete normal/raw touch lifecycles");
                int first=trace.indexOf("TOUCH type=18 ");
                check(first>=0&&!trace.substring(first).contains("MOUSE type="),"libXi probe sees no finger-derived mouse events");
            }
            return report.toString();
        } finally { com.winlator.cmod.core.ProcessHelper.removeDebugCallback(callback); file.delete(); }
    }
    public String run() throws Exception { protocol(); return report.toString(); }
}
