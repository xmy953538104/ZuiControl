package com.zui.server.control;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One scalar clock; task enumeration exists only inside an active user recording. */
class MonitorCollector {
    static final String CALLBACK="android.zui.IMonitorSnapshot";
    private final Context context;
    final MonitorSession session=new MonitorSession();
    private final MonitorSources sources=new MonitorSources();
    private final MonitorStore store=new MonitorStore();
    private IBinder callback;
    private IBinder.DeathRecipient death;
    private HandlerThread thread;
    private Handler handler;
    private long epoch,samples,threadReads,enumerations,lastThreadTime;
    private String lastSnapshot="{}",error="";
    private List<MonitorSnapshot.Task> previous=Collections.emptyList();
    private int previousPid;
    private long previousStart;
    private final long hz=Os.sysconf(OsConstants._SC_CLK_TCK);

    MonitorCollector(Context context){this.context=context;}
    synchronized void register(IBinder binder)throws android.os.RemoteException{
        if(callback==binder)return;
        stop();
        if(callback!=null&&death!=null)callback.unlinkToDeath(death,0);
        callback=binder;death=null;
        if(binder!=null){
            death=()->{synchronized(MonitorCollector.this){if(callback==binder){
                stop();callback=null;death=null;session.mode=MonitorSession.OFF;
                session.stop();store.abandon();
            }}};
            binder.linkToDeath(death,0);
        }else{session.mode=MonitorSession.OFF;session.stop();store.abandon();}
    }
    synchronized void scene(String pkg,int user,boolean eligible){
        scene(pkg,user,eligible,eligible);
    }
    synchronized void scene(String pkg,int user,boolean eligible,boolean recordEligible){
        boolean changed=!pkg.equals(session.foreground)||user!=session.user||eligible!=session.eligible
                ||recordEligible!=session.recordEligible;
        session.scene(pkg,user,eligible,recordEligible);
        if(changed){clearThreadBaseline();stop();}
        schedule();
    }
    synchronized String command(String action,int user,String arg){
        try{
            long now=SystemClock.elapsedRealtime();
            if("recordRead".equals(action))return store.read(user,arg);
            if("recordList".equals(action))return store.list(user);
            if("recordDelete".equals(action))return store.delete(user,arg);
            if("full".equals(action))session.toggle(MonitorSession.FULL);
            else if("fps".equals(action))session.toggle(MonitorSession.FPS);
            else if("off".equals(action)){session.mode=MonitorSession.OFF;session.cancelArm();}
            else if("arm".equals(action)){if(!session.arm(now))return "ok=0\nerror=not_eligible";}
            else if("cancelArm".equals(action))session.cancelArm();
            else if("recordStart".equals(action)){
                if(!session.canStart(now)||session.user!=user)return "ok=0\nerror=hold_or_scene_changed";
                MonitorSnapshot.Task task=identity(findPid());
                if(task==null)return "ok=0\nerror=process_unavailable";
                String label=context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getApplicationInfo(session.foreground,0)).toString();
                store.start(session.foreground,label,user,task.tid,task.start,now);
                session.started();clearThreadBaseline();
            }else if("recordStop".equals(action)){
                if(session.recordingUser!=user)return "ok=0\nerror=wrong_user";
                store.finish(now);session.stop();clearThreadBaseline();
            }else if(!"state".equals(action))return "ok=0\nerror=unknown_monitor_action";
            if(!"state".equals(action)&&!"arm".equals(action)&&!"cancelArm".equals(action)){stop();schedule();}
            return "ok=1"+state()+"\nmonitorSnapshot="+lastSnapshot;
        }catch(Exception e){error=e.getClass().getSimpleName()+":"+e.getMessage();return "ok=0\nerror="+error;}
    }
    private void clearThreadBaseline(){previous=Collections.emptyList();previousPid=0;previousStart=0;lastThreadTime=0;}
    private void schedule(){
        if(callback==null||!session.sampling()){stop();return;}
        if(handler!=null)return;
        thread=new HandlerThread("ZuiMonitor");thread.start();handler=new Handler(thread.getLooper());
        long ticket=epoch;handler.post(()->sample(ticket));
    }
    synchronized void stop(){
        epoch++;
        if(handler!=null)handler.removeCallbacksAndMessages(null);
        if(thread!=null)thread.quitSafely();
        handler=null;thread=null;
        // A clock/generation restart is not a request to tear down the display window.
        try {
            deliver(new JSONObject(lastSnapshot).put("active",session.sampling())
                    .put("mode",session.mode).put("package",session.foreground)
                    .put("recordState",session.recordingState()).toString());
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    synchronized String snapshot(){return lastSnapshot;}
    synchronized String state(){
        return "\nmonitorActive="+(thread!=null)+"\nmonitorTarget="+session.foreground
            +"\nmonitorMode="+session.mode+"\nmonitorSamples="+samples+"\nmonitorReads="+sources.scalarReads
            +"\nmonitorScalarReads="+sources.scalarReads+"\nmonitorThreadReads="+threadReads
            +"\nmonitorThreadEnumerations="+enumerations+"\nmonitorDbWrites="+store.writes
            +"\nmonitorScalarRows="+store.scalarRows+"\nmonitorThreadRows="+store.threadRows
            +"\nmonitorRecordState="+session.recordingState()+"\nmonitorRecordTarget="+session.recordingPackage
            +"\nmonitorIntervalMs=1000\nmonitorThreadIntervalMs=3000\nmonitorTimer="+(handler!=null)
            +"\nmonitorQuietPath="+sources.quietPath+"\nmonitorQuietUnit=millidegree_C\nmonitorQuietDiscovery="+sources.discoveryReads
            +"\nmonitorQuietError="+sources.quietError+"\nmonitorError="+error;
    }
    private void sample(long ticket){synchronized(this){
        if(ticket!=epoch||handler==null||!session.sampling())return;
        try{
            KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            if(keyguard==null||keyguard.isKeyguardLocked()){
                session.scene(session.foreground,session.user,false);clearThreadBaseline();stop();return;
            }
            long now=SystemClock.elapsedRealtime();
            double fps=sources.fps(),quiet=-1,power=-1;
            int plugged=-1,batteryStatus=-1,milliVolts=-1;
            long microAmps=Long.MIN_VALUE;
            boolean capture=session.recordingActive();
            if(session.mode==MonitorSession.FULL||capture){
                quiet=sources.quiet();sources.scalarReads++;
                Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                BatteryManager manager=context.getSystemService(BatteryManager.class);
                if(battery!=null&&manager!=null){
                    plugged=battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1);
                    batteryStatus=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                    milliVolts=battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1);
                    microAmps=manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    power=MonitorSources.batteryWatts(plugged,batteryStatus,milliVolts,microAmps);
                }
            }
            if(capture){
                MonitorSnapshot.Task process=identity(findPid());
                if(process!=null){
                    List<MonitorSnapshot.Row> rows=Collections.emptyList();
                    if(lastThreadTime==0||now-lastThreadTime>=3000){
                        enumerations++;
                        List<MonitorSnapshot.Task> tasks=readTasks(process.tid);
                        MonitorSnapshot.Task after=identity(process.tid);
                        if(after==null||after.start!=process.start){clearThreadBaseline();process=null;}
                        else{
                            rows=MonitorSnapshot.delta(previous,tasks,now-lastThreadTime,hz,previousPid==process.tid&&previousStart==process.start);
                            previous=tasks;previousPid=process.tid;previousStart=process.start;lastThreadTime=now;
                        }
                    }
                    if(process!=null)store.append(now,fps,power,quiet,process.tid,process.start,rows);
                }else clearThreadBaseline();
                session.processAvailable=process!=null;
            }else clearThreadBaseline();
            JSONObject data=new JSONObject().put("active",true).put("package",session.foreground)
                .put("mode",session.mode).put("sample",++samples).put("elapsedMs",now)
                .put("fps",fps).put("fpsSource","display_measured_fps_not_game_present")
                .put("powerW",power).put("powerSource","DEVICE_POWER_W_BATTERY_DISCHARGE_MV_UA")
                // Same-sample diagnostics in the existing in-memory snapshot; no extra sampler/storage.
                .put("batteryStatus",batteryStatus).put("batteryPlugged",plugged)
                .put("batteryVoltageMv",milliVolts).put("batteryCurrentUa",microAmps)
                .put("batteryCurrentMagnitudeA",power<0?-1:Math.abs(microAmps/1000000.0))
                .put("quietC",quiet).put("recordState",session.recordingState());
            lastSnapshot=data.toString();deliver(lastSnapshot);
        }catch(Exception e){error=e.getClass().getSimpleName()+":"+e.getMessage();
            session.mode=MonitorSession.OFF;session.stop();store.abandon();stop();}
        if(ticket==epoch&&handler!=null)handler.postDelayed(()->sample(ticket),1000);
    }}
    private MonitorSnapshot.Task parse(String path){
        try{return MonitorSnapshot.Task.parse(MonitorSources.line(path));}catch(java.io.IOException e){return null;}
    }
    List<MonitorSnapshot.Task> readTasks(int pid){
        List<MonitorSnapshot.Task> tasks=new ArrayList<>();
        File[] entries=new File("/proc/"+pid+"/task").listFiles();
        if(entries==null && identity(pid)==null)return tasks;
        if(entries==null||entries.length>8192)throw new IllegalStateException("task_directory_unavailable");
        for(File entry:entries){threadReads++;MonitorSnapshot.Task task=parse(entry.getPath()+"/stat");if(task!=null)tasks.add(task);}
        return tasks;
    }
    MonitorSnapshot.Task identity(int pid){return pid>0?parse("/proc/"+pid+"/stat"):null;}
    int findPid(){
        ActivityManager manager=context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes=manager.getRunningAppProcesses();
        if(processes!=null)for(ActivityManager.RunningAppProcessInfo p:processes)
            if(session.foreground.equals(p.processName)&&p.uid/100000==session.user)return p.pid;
        return 0;
    }
    private void deliver(String text){
        if(callback==null)return;Parcel data=Parcel.obtain();
        try{data.writeInterfaceToken(CALLBACK);data.writeString(text);callback.transact(1,data,null,IBinder.FLAG_ONEWAY);}
        catch(android.os.RemoteException e){callback=null;session.mode=MonitorSession.OFF;session.stop();store.abandon();
            if(handler!=null)handler.removeCallbacksAndMessages(null);if(thread!=null)thread.quitSafely();handler=null;thread=null;epoch++;}
        finally{data.recycle();}
    }
}
