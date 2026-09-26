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

    private long lastGesture=-1;
    private final String producerEpoch=java.util.UUID.randomUUID().toString();
    MonitorCollector(Context context){this.context=context;}
    synchronized void register(IBinder binder)throws android.os.RemoteException{
        if(callback==binder)return;
        if(callback!=null)terminate("CLIENT_REPLACED",true);
        drain();
        if(callback!=null&&death!=null)callback.unlinkToDeath(death,0);
        callback=binder;death=null;session.connectionEpoch++;lastGesture=-1;
        if(binder!=null){
            final long connection=session.connectionEpoch;
            death=()->{synchronized(MonitorCollector.this){
                if(callback==binder&&connection==session.connectionEpoch)disconnect("CLIENT_DEATH");
            }};
            try { binder.linkToDeath(death,0); }
            catch(android.os.RemoteException e){disconnect("CLIENT_DEATH");throw e;}
        }
        stop();schedule();
    }
    synchronized void unregister(IBinder binder){if(callback==binder)disconnect("CLIENT_CLOSED");}
    private void terminate(String reason,boolean incomplete){
        if(!session.recording())return;
        long end=Math.min(SystemClock.elapsedRealtime(),session.recordingStart+MonitorSession.MAX_RECORD_MS);
        try { store.finish(end,reason,incomplete); }
        catch(RuntimeException e){store.abandon();throw e;}
        finally { session.stop();clearThreadBaseline(); }
    }
    private void disconnect(String reason){
        // A failed old connection never owns the new listener or desired mode.
        try { terminate(reason,true); } catch(RuntimeException e){error="record_finalize:"+e.getMessage();}
        callback=null;death=null;session.connectionEpoch++;lastGesture=-1;drain();
        lastSnapshot="{}";
    }
    synchronized void scene(String pkg,int user,boolean eligible){
        scene(pkg,user,eligible,eligible);
    }
    synchronized void scene(String pkg,int user,boolean eligible,boolean recordEligible){scene(pkg,user,eligible,recordEligible,"SCREEN_OR_LOCK",session.taskId);}
    synchronized void scene(String pkg,int user,boolean eligible,boolean recordEligible,String blockedReason,int taskId){
        boolean taskChanged=taskId!=session.taskId;
        if(taskChanged){session.taskId=taskId;session.targetEpoch++;}
        boolean changed=taskChanged||!pkg.equals(session.foreground)||user!=session.user||eligible!=session.eligible
                ||recordEligible!=session.recordEligible;
        session.scene(pkg,user,eligible,recordEligible);
        String terminal=session.terminal(SystemClock.elapsedRealtime());
        if(terminal.isEmpty()&&taskChanged&&session.recording())terminal="TASK_CHANGED";
        if(!terminal.isEmpty())terminate(!eligible&&terminal.equals("SCREEN_OR_LOCK")?blockedReason:terminal,false);
        if(changed){clearThreadBaseline();stop();}
        schedule();
    }
    synchronized String command(String action,int user,String arg){
        try{
            long now=SystemClock.elapsedRealtime();
            if("recordRead".equals(action))return store.read(user,arg);
            if("recordList".equals(action))return store.list(user);
            if("recordDelete".equals(action))return store.delete(user,arg);
            if("permissionGranted".equals(action)||"permissionLost".equals(action)){
                boolean allowed="permissionGranted".equals(action);
                if(!allowed)terminate("PERMISSION_LOST",false);
                if(allowed==session.overlayAllowed)return "ok=1";
                session.overlayAllowed=allowed;
            }else if("full".equals(action))session.toggle(MonitorSession.FULL);
            else if("fps".equals(action))session.toggle(MonitorSession.FPS);
            else if("off".equals(action))session.mode=MonitorSession.OFF;
            else if("circle".equals(action)||"bar".equals(action)){
                if(callback==null||session.mode!=MonitorSession.FULL)return "ok=0\nerror=not_visible";
                session.circle="circle".equals(action);
            }
            else if("recordStart".equals(action)){
                String[] token=arg.split(":",-1);
                if(token.length!=3)return "ok=0\nerror=start_identity";
                long connection=Long.parseLong(token[0]),target=Long.parseLong(token[1]),gesture=Long.parseLong(token[2]);
                if(callback==null||connection!=session.connectionEpoch||target!=session.targetEpoch||gesture<0)
                    return "ok=0\nerror=stale_start";
                if(gesture==lastGesture)return "ok=1\nrecordStartReplay=true";
                if(gesture<lastGesture||!session.canStart()||session.user!=user)return "ok=0\nerror=scene_changed";
                MonitorSnapshot.Task task=identity(findPid());
                if(task==null)return "ok=0\nerror=process_unavailable";
                String label=context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getApplicationInfo(session.foreground,0)).toString();
                store.start(session.foreground,label,user,task.tid,task.start,now,session.taskId,session.targetEpoch);
                session.started(now,task.tid,task.start);lastGesture=gesture;clearThreadBaseline();
            }else if("recordStop".equals(action)){
                if(session.recordingUser!=user)return "ok=0\nerror=wrong_user";
                terminate("EXPLICIT_STOP",false);
            }else if(!"state".equals(action))return "ok=0\nerror=unknown_monitor_action";
            if(session.mode!=MonitorSession.FULL)terminate("USER_DISABLE",false);
            if(!"state".equals(action)){stop();schedule();}
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
    private void drain(){
        epoch++;
        if(handler!=null)handler.removeCallbacksAndMessages(null);
        if(thread!=null)thread.quitSafely();
        handler=null;thread=null;
    }
    synchronized void stop(){
        drain();
        // Invalidate all cached values, never relabel old target readings as a fresh sample.
        try {
            lastSnapshot=metadata(new JSONObject()).put("elapsedMs",0).put("fps",-1)
                    .put("quietC",-1).put("powerW",-1).toString();
            deliver(lastSnapshot);
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    private JSONObject metadata(JSONObject value)throws org.json.JSONException{
        return value.put("producerEpoch",producerEpoch).put("active",session.visible()).put("mode",session.mode).put("circle",session.circle)
            .put("package",session.foreground).put("user",session.user).put("targetEpoch",session.targetEpoch)
            .put("taskId",session.taskId).put("connectionEpoch",session.connectionEpoch).put("sample",samples)
            .put("intervalMs",session.interval()).put("ttlMs",session.interval()==5000?7500:3500)
            .put("recordState",session.recordingState()).put("fpsValidity","UNAVAILABLE_SOURCE_UNQUALIFIED")
            .put("fpsSource","RAW_TASK_PRESENT_COUNTER_NOT_IMPLEMENTED")
            .put("sourceGeneration",0).put("sourceHealthy",false)
            .put("windowStart",JSONObject.NULL).put("windowEnd",JSONObject.NULL)
            .put("newPresents",JSONObject.NULL).put("lastPresent",JSONObject.NULL)
            .put("sourceMeasurementTime",JSONObject.NULL).put("inputPowerW",JSONObject.NULL);
    }
    synchronized void invalidatePower(){stop();schedule();}
    synchronized String snapshot(){return lastSnapshot;}
    synchronized String state(){
        return "\nmonitorActive="+(thread!=null)+"\nmonitorTarget="+session.foreground
            +"\nmonitorMode="+session.mode+"\nmonitorSamples="+samples+"\nmonitorReads="+sources.scalarReads
            +"\nmonitorScalarReads="+sources.scalarReads+"\nmonitorThreadReads="+threadReads
            +"\nmonitorThreadEnumerations="+enumerations+"\nmonitorDbWrites="+store.writes
            +"\nmonitorScalarRows="+store.scalarRows+"\nmonitorThreadRows="+store.threadRows
            +"\nmonitorRecordState="+session.recordingState()+"\nmonitorRecordTarget="+session.recordingPackage
            +"\nmonitorIntervalMs="+session.interval()+"\nmonitorConnectionEpoch="+session.connectionEpoch+"\nmonitorTargetEpoch="+session.targetEpoch+"\nmonitorThreadIntervalMs=3000\nmonitorTimer="+(handler!=null)
            +"\nmonitorQuietPath="+sources.quietPath+"\nmonitorQuietUnit=millidegree_C\nmonitorQuietDiscovery="+sources.discoveryReads
            +"\nmonitorQuietError="+sources.quietError+"\nmonitorError="+error;
    }
    private void sample(long ticket){synchronized(this){
        if(ticket!=epoch||handler==null||!session.sampling())return;
        try{
            KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            if(keyguard==null||keyguard.isKeyguardLocked()){
                terminate("LOCKED",false);session.scene(session.foreground,session.user,false);clearThreadBaseline();stop();return;
            }
            long now=SystemClock.elapsedRealtime();
            double fps=sources.fps(),quiet=-1,power=-1;
            int plugged=-1,batteryStatus=-1,milliVolts=-1;
            long microAmps=Long.MIN_VALUE;
            String terminal=session.terminal(now);
            if(!terminal.isEmpty())terminate(terminal,false);
            boolean capture=session.recording();
            { // All visible controller modes consume the same quiet/consumption sample.
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
                if(process!=null&&session.sameProcess(process.tid,process.start)){
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
                }else process=null;
                if(process==null)terminate("PROCESS_GENERATION_LOST",true);
            }else clearThreadBaseline();
            samples++;
            JSONObject data=metadata(new JSONObject()).put("elapsedMs",now)
                .put("fps",fps<0?JSONObject.NULL:fps)
                .put("consumptionPowerW",power<0?JSONObject.NULL:power).put("powerValidity",power<0?"UNAVAILABLE":"VALID")
                .put("quietValidity",quiet<0?"UNAVAILABLE":"VALID").put("powerW",power).put("powerSource","DEVICE_POWER_W_BATTERY_DISCHARGE_MV_UA")
                // Same-sample diagnostics in the existing in-memory snapshot; no extra sampler/storage.
                .put("batteryStatus",batteryStatus).put("batteryPlugged",plugged)
                .put("batteryVoltageMv",milliVolts).put("batteryCurrentUa",microAmps)
                .put("batteryCurrentMagnitudeA",power<0?-1:Math.abs(microAmps/1000000.0))
                .put("quietC",quiet).put("recordState",session.recordingState());
            lastSnapshot=data.toString();deliver(lastSnapshot);
        }catch(Exception e){error=e.getClass().getSimpleName()+":"+e.getMessage();
            terminate("SOURCE_PIPELINE_FAILURE",true);stop();}
        if(ticket==epoch&&handler!=null)handler.postDelayed(()->sample(ticket),session.recording()?Math.min(session.interval(),Math.max(1,session.recordingStart+MonitorSession.MAX_RECORD_MS-SystemClock.elapsedRealtime())):session.interval());
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
        try{data.writeInterfaceToken(CALLBACK);data.writeString(text);if(!callback.transact(1,data,null,IBinder.FLAG_ONEWAY))disconnect("CLIENT_TRANSPORT_FAILURE");}
        catch(android.os.RemoteException e){disconnect("CLIENT_TRANSPORT_FAILURE");}
        finally{data.recycle();}
    }
}
