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
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One worker/scalar clock. Task enumeration requires explicit recording or analysis. */
class MonitorCollector {
    static final String CALLBACK="android.zui.IMonitorSnapshot";
    private final Context context;
    final MonitorSession session=new MonitorSession();
    private final MonitorSources sources;
    private boolean fpsSceneEligible;
    private final MonitorStore store=new MonitorStore();
    private final java.util.Map<Integer,ThreadAnalysis> analyses=new java.util.HashMap<>();
    private final java.util.Map<Integer,String> analysisStates=new java.util.HashMap<>();
    private final java.util.Map<Integer,List<ThreadAnalysis.Process>> analysisTasks=new java.util.HashMap<>();
    long analysisEnumerations,analysisReads,analysisWrites;
    interface Facts {
        String rulesGeneration();
        default String recordPolicy(int user,String pkg){return PolicyJson.encode(PolicyJson.map("schema",1,"zuioptGeneration",rulesGeneration(),"policy","UNAVAILABLE"));}
    }
    Facts facts=()->"UNAVAILABLE";
    private IBinder callback;
    private IBinder.DeathRecipient death;
    private final java.util.Map<Integer,IBinder> clients=new java.util.HashMap<>();
    private final java.util.Map<Integer,int[]> userModes=new java.util.HashMap<>();
    private HandlerThread thread;
    private Handler handler;
    private boolean scheduled;
    private int expectedPowerPlugged=-1;
    private boolean powerConfirmationPending;
    private String finalizeError="";
    private long epoch,samples,threadReads,enumerations,lastThreadTime;
    private long recordScanTime=-1;
    private List<MonitorSnapshot.Task> recordPrimaryTasks=Collections.emptyList();
    private String lastSnapshot="{}",error="",lastRequestError="";
    private List<MonitorSnapshot.Task> previous=Collections.emptyList();
    private int previousPid;
    private long previousStart;
    private final long hz=Os.sysconf(OsConstants._SC_CLK_TCK);

    private long lastGesture=-1;
    private final String producerEpoch=java.util.UUID.randomUUID().toString();
    MonitorCollector(Context context){this(context,new MonitorSources());}
    MonitorCollector(Context context,MonitorSources sources){this.context=context;this.sources=sources;}
    synchronized void register(IBinder binder,int user)throws android.os.RemoteException{
        if(binder==null)throw new IllegalArgumentException("monitor callback required");
        clients.put(user,binder);
        if(user==session.user)register(binder);
    }
    synchronized void register(IBinder binder)throws android.os.RemoteException{
        if(callback==binder)return;
        if(callback!=null)terminate("CLIENT_REPLACED",true);
        drain();
        retireCallback();
        callback=binder;death=null;session.connectionEpoch++;lastGesture=-1;sources.resetFps();sources.resetQuiet();
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
    synchronized void unregister(IBinder binder){clients.values().removeIf(value->value==binder);if(callback==binder)disconnect("CLIENT_CLOSED");}
    synchronized void removeUser(int user){
        analyses.remove(user);analysisStates.remove(user);analysisTasks.remove(user);
        if(user==session.user){terminate("USER_REMOVED",false);disconnect("USER_REMOVED");}
        clients.remove(user);userModes.remove(user);store.removeUser(user);
    }
    private void terminate(String reason,boolean incomplete){
        if(!session.recording())return;
        ThreadAnalysis analysis=analyses.get(session.recordingUser);
        boolean linked=analysis!=null&&analysis.recordBound;
        long end=Math.min(SystemClock.elapsedRealtime(),session.recordingStart+MonitorSession.MAX_RECORD_MS);
        try { store.finish(end,reason,incomplete);finalizeError=""; }
        catch(RuntimeException e){
            reason="FINALIZE_ERROR";incomplete=true;
            finalizeError=boundedError("record_finalize:"+e.getClass().getSimpleName()+":"+e.getMessage());
            // A failed write leaves the existing INCOMPLETE row. Best-effort mark it explicitly.
            try { store.finish(end,"FINALIZE_ERROR",true); } catch(RuntimeException ignored) { }
            try { store.abandon(); } catch(RuntimeException close) { finalizeError=boundedError(finalizeError+";close:"+close.getMessage()); }
        }
        finally {
            session.stop();clearThreadBaseline();recordScanTime=-1;recordPrimaryTasks=Collections.emptyList();
            if(linked){
                analysis.sourceRecordCompletion=incomplete?"INCOMPLETE":"COMPLETE";analysis.sourceRecordTerminalReason=reason;
                analysis.finish("FINISHED",end);finishAnalysis(analysis,end);
            }
        }
    }
    private String screenTerminal(){
        PowerManager power=context.getSystemService(PowerManager.class);
        if(power!=null&&!power.isInteractive())return "SCREEN_OFF";
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        return keyguard==null||keyguard.isKeyguardLocked()?"LOCKED":"";
    }
    private void checkTerminal(String terminal){
        if(!session.recording())return;
        String screen=screenTerminal();
        if(!screen.isEmpty()){terminate(screen,false);return;}
        MonitorSnapshot.Task bound=identity(session.recordingPid);
        if(bound==null||!session.sameProcess(bound.tid,bound.start)){
            terminate("PROCESS_GENERATION_LOST",true);return;
        }
        if(!terminal.isEmpty())terminate(terminal,false);
    }
    private void disconnect(String reason){
        // A failed old connection never owns the new listener or desired mode.
        try { terminate(reason,true); } catch(RuntimeException e){finalizeError=boundedError("record_finalize:"+e.getMessage());}
        retireCallback();session.connectionEpoch++;lastGesture=-1;drain();
        lastSnapshot="{}";
        schedule();
    }
    private void retireCallback(){
        IBinder old=callback;IBinder.DeathRecipient recipient=death;
        try { if(old!=null&&recipient!=null)old.unlinkToDeath(recipient,0); }
        catch(java.util.NoSuchElementException alreadyRetired) { }
        finally { callback=null;death=null; }
    }
    synchronized void scene(String pkg,int user,boolean eligible){
        scene(pkg,user,eligible,eligible);
    }
    synchronized void scene(String pkg,int user,boolean eligible,boolean recordEligible){scene(pkg,user,eligible,recordEligible,"SCREEN_OR_LOCK",session.taskId);}
    synchronized void scene(String pkg,int user,boolean eligible,boolean recordEligible,String blockedReason,int taskId){
        scene(pkg,user,eligible,recordEligible,blockedReason,taskId,eligible&&!pkg.isEmpty());
    }
    synchronized void scene(String pkg,int user,boolean eligible,boolean recordEligible,String blockedReason,int taskId,boolean fpsEligible){
        if(user!=session.user){
            userModes.put(session.user,new int[]{session.mode,session.circle?1:0,session.overlayAllowed?1:0});
            terminate("FOREGROUND_CHANGED",false);
            session.eligible=false;stop(); // Hide the old user's overlay before retiring its callback.
            disconnect("USER_CHANGED");
            int[] saved=userModes.getOrDefault(user,new int[]{MonitorSession.OFF,0,1});
            session.mode=saved[0];session.circle=saved[1]!=0;session.overlayAllowed=saved[2]!=0;
            session.scene("",user,false,false);session.taskId=-1;fpsSceneEligible=false;
            try{register(clients.get(user));}catch(android.os.RemoteException unavailable){clients.remove(user);}
        }
        boolean fpsChanged=fpsSceneEligible!=fpsEligible;
        fpsSceneEligible=fpsEligible;
        boolean taskChanged=taskId!=session.taskId;
        if(taskChanged){session.taskId=taskId;session.targetEpoch++;}
        boolean changed=eligible!=session.eligible||((session.visible()||session.recording())
                &&(fpsChanged||taskChanged||!pkg.equals(session.foreground)||user!=session.user
                ||recordEligible!=session.recordEligible));
        session.scene(pkg,user,eligible,recordEligible);
        long now=SystemClock.elapsedRealtime();
        for(ThreadAnalysis a:analyses.values())a.scene(a.user==user&&a.pkg.equals(pkg)&&recordEligible,eligible,
                a.recordBound?Math.min(now,a.began+MonitorSession.MAX_RECORD_MS):now);
        String terminal=session.terminal(SystemClock.elapsedRealtime());
        if(terminal.isEmpty()&&taskChanged&&session.recording())terminal="TASK_CHANGED";
        checkTerminal(!eligible&&terminal.equals("SCREEN_OR_LOCK")?blockedReason:terminal);
        if(changed){clearThreadBaseline();stop();}
        schedule();
    }
    synchronized String command(String action,int user,String arg){
        try{
            long now=SystemClock.elapsedRealtime();
            if(action.startsWith("analysis"))return analysisCommand(action,user,arg,now);
            if("recordRead".equals(action))return store.read(user,arg);
            if("recordList".equals(action))return store.list(user);
            if("recordDelete".equals(action))return store.delete(user,arg);
            if(user!=session.user){
                if("state".equals(action))return "ok=1\nmonitorSnapshot="+new JSONObject()
                        .put("producerEpoch",producerEpoch).put("user",user).put("package","")
                        .put("active",false).put("mode",MonitorSession.OFF).put("recordState","IDLE");
                if("permissionGranted".equals(action)||"permissionLost".equals(action)){
                    int[] saved=userModes.computeIfAbsent(user,u->new int[]{MonitorSession.OFF,0,1});
                    saved[2]="permissionGranted".equals(action)?1:0;return "ok=1";
                }
                return reject("inactive_user");
            }
            if("permissionGranted".equals(action)||"permissionLost".equals(action)){
                boolean allowed="permissionGranted".equals(action);
                if(!allowed)terminate("PERMISSION_LOST",false);
                if(allowed==session.overlayAllowed)return "ok=1";
                session.overlayAllowed=allowed;
            }else if("full".equals(action))session.toggle(MonitorSession.FULL);
            else if("fps".equals(action))session.toggle(MonitorSession.FPS);
            else if("off".equals(action))session.mode=MonitorSession.OFF;
            else if("circle".equals(action)||"bar".equals(action)){
                if(callback==null||session.mode!=MonitorSession.FULL)return reject("not_visible");
                session.circle="circle".equals(action);
            }
            else if("recordStart".equals(action)){
                String[] token=arg.split(":",-1);
                if(token.length!=3)return reject("start_identity");
                long connection=Long.parseLong(token[0]),target=Long.parseLong(token[1]),gesture=Long.parseLong(token[2]);
                if(callback==null||connection!=session.connectionEpoch||target!=session.targetEpoch||gesture<0)
                    return reject("stale_start");
                if(gesture==lastGesture)return "ok=1\nrecordStartReplay=true";
                if(gesture<lastGesture||!session.canStart()||session.user!=user)return reject("scene_changed");
                MonitorSnapshot.Task task=identity(findPid());
                if(task==null)return reject("process_unavailable");
                android.content.pm.PackageManager packages=((Context)Context.class.getMethod("createContextAsUser",android.os.UserHandle.class,int.class)
                        .invoke(context,android.os.UserHandle.getUserHandleForUid(user*100000),0)).getPackageManager();
                String label=packages.getApplicationLabel(packages.getApplicationInfo(session.foreground,0)).toString();
                ThreadAnalysis linked=new ThreadAnalysis(user,session.foreground,ThreadAnalysis.MAX_WALL,now,facts.rulesGeneration(),true);
                store.start(session.foreground,label,user,task.tid,task.start,now,session.taskId,session.targetEpoch,facts.recordPolicy(user,session.foreground));
                session.started(now,task.tid,task.start);lastGesture=gesture;clearThreadBaseline();
                ThreadAnalysis prior=analyses.get(user);
                if(prior!=null){prior.interruptions.merge("SUPERSEDED_BY_RECORDING",1,Integer::sum);prior.finish("FINISHED",now);finishAnalysis(prior,now);}
                linked.bindRecord(store.recordId,store.recordWall,now);linked.scene(true,session.eligible,now);
                analyses.put(user,linked);analysisStates.remove(user);analysisTasks.remove(user);recordScanTime=-1;recordPrimaryTasks=Collections.emptyList();
            }else if("recordStop".equals(action)){
                if(session.recordingUser!=user)return reject("wrong_user");
                terminate("EXPLICIT_STOP",false);
            }else if(!"state".equals(action))return reject("unknown_monitor_action");
            if(("full".equals(action)||"fps".equals(action))&&session.mode!=MonitorSession.OFF){sources.resetFps();sources.resetQuiet();}
            if(session.mode!=MonitorSession.FULL)terminate("USER_DISABLE",false);
            if(!"state".equals(action)){stop();schedule();}
            return "ok=1"+state()+"\nmonitorSnapshot="+lastSnapshot;
        }catch(Exception e){return reject(e.getClass().getSimpleName()+":"+e.getMessage());}
    }
    private static String boundedError(String value){
        String text=value.replace('\n',' ').replace('\r',' ');
        return text.substring(0,Math.min(160,text.length()));
    }
    private String reject(String reason){
        // Request history is diagnostic only; neither runtime nor finalization health changes.
        lastRequestError=boundedError(reason);return "ok=0\nerror="+lastRequestError;
    }
    private void clearThreadBaseline(){previous=Collections.emptyList();previousPid=0;previousStart=0;lastThreadTime=0;}
    private boolean scalarActive(){return callback!=null&&session.sampling();}
    private void schedule(){
        if(!scalarActive()&&analyses.isEmpty()){stop();return;}
        if(handler==null){thread=new HandlerThread("ZuiMonitor");thread.start();handler=new Handler(thread.getLooper());}
        if(scheduled)return;
        scheduled=true;long ticket=epoch;handler.post(()->sample(ticket));
    }
    private void drain(){
        epoch++;
        if(handler!=null)handler.removeCallbacksAndMessages(null);
        if(thread!=null)thread.quitSafely();
        handler=null;thread=null;scheduled=false;
    }
    synchronized void stop(){
        // Keep the one worker while the client has an active sampling lifetime.
        if(!scalarActive()&&analyses.isEmpty())drain();
        else {epoch++;scheduled=false;if(handler!=null)handler.removeCallbacksAndMessages(null);}
        // Invalidate all cached values, never relabel old target readings as a fresh sample.
        try {
            String invalid=metadata(new JSONObject()).put("elapsedMs",0).put("fps",-1)
                    .put("quietC",-1).put("powerW",-1).toString();
            if(!invalid.equals(lastSnapshot)){lastSnapshot=invalid;deliver(lastSnapshot);}
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    private JSONObject metadata(JSONObject value)throws org.json.JSONException{
        return value.put("producerEpoch",producerEpoch).put("active",session.visible()).put("mode",session.mode).put("circle",session.circle)
            .put("package",session.foreground).put("user",session.user).put("targetEpoch",session.targetEpoch)
            .put("taskId",session.taskId).put("connectionEpoch",session.connectionEpoch).put("sample",samples)
            .put("intervalMs",session.interval()).put("ttlMs",session.interval()==5000?7500:3500)
            .put("recordState",session.recordingState()).put("fpsValidity",fpsBlockedReason().isEmpty()?"UNAVAILABLE_NOT_SAMPLED":fpsBlockedReason())
            .put("fpsSource","DISPLAY_MEASURED_FPS").put("fpsSourcePath",MonitorSources.FPS_PATH)
            .put("sourceGeneration",0).put("sourceHealthy",false)
            .put("windowStart",JSONObject.NULL).put("windowEnd",JSONObject.NULL)
            .put("newPresents",JSONObject.NULL).put("lastPresent",JSONObject.NULL)
            .put("sourceMeasurementTime",JSONObject.NULL).put("inputPowerW",JSONObject.NULL);
    }
    private String fpsBlockedReason(){
        if(!session.eligible)return "UNAVAILABLE_SCREEN_OR_TARGET";
        if(!fpsSceneEligible)return "UNAVAILABLE_SCENE_OWNERSHIP";
        if(!session.visible()&&!session.recording())return "UNAVAILABLE_NO_FPS_DEMAND";
        return "";
    }
    synchronized void invalidatePower(){stop();schedule();}
    synchronized void invalidatePower(boolean connected){
        // Replace the pending edge on the existing clock; never retain pre-edge watts.
        stop();expectedPowerPlugged=connected?1:0;powerConfirmationPending=true;schedule();
    }
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
            +"\nmonitorFpsReads="+sources.fpsReads+"\nmonitorFpsError="+sources.fpsError
            +"\nmonitorQuietError="+sources.quietError+"\nmonitorError="+error+"\nmonitorFinalizeError="+finalizeError
            +"\nmonitorLastRequestError="+lastRequestError
            +"\nanalysisEnumerations="+analysisEnumerations+"\nanalysisTaskReads="+analysisReads+"\nanalysisResultWrites="+analysisWrites;
    }
    private void sample(long ticket){synchronized(this){
        if(ticket!=epoch||handler==null||(!scalarActive()&&analyses.isEmpty()))return;
        scheduled=false;
        long powerDelay=0;
        try{
            // End the recording before enumerating at or beyond its deadline.
            checkTerminal(session.terminal(SystemClock.elapsedRealtime()));
            updateAnalyses(SystemClock.elapsedRealtime());
            if(!scalarActive()){reschedule(0);return;}
            String screen=screenTerminal();
            if(!screen.isEmpty()){
                terminate(screen,false);session.scene(session.foreground,session.user,false);clearThreadBaseline();stop();reschedule(0);return;
            }
            long now=SystemClock.elapsedRealtime();
            String fpsValidity=fpsBlockedReason();
            double fps=fpsValidity.isEmpty()?sources.fps():-1,quiet=-1,power=-1;
            if(fpsValidity.isEmpty())fpsValidity=sources.fpsError;
            int plugged=-1,batteryStatus=-1,milliVolts=-1;
            long microAmps=Long.MIN_VALUE;
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
            if(expectedPowerPlugged!=-1){
                boolean settled=expectedPowerPlugged==1?plugged>0:plugged==0&&batteryStatus==3;
                if(settled){expectedPowerPlugged=-1;powerConfirmationPending=false;}
                else{
                    power=-1;
                    if(powerConfirmationPending){powerDelay=400;powerConfirmationPending=false;}
                }
            }
            if(capture){
                MonitorSnapshot.Task process=identity(session.recordingPid);
                if(process!=null&&session.sameProcess(process.tid,process.start)){
                    List<MonitorSnapshot.Row> rows=Collections.emptyList();
                    ThreadAnalysis analysis=analyses.get(session.user);
                    boolean shared=analysis!=null&&analysis.recordBound&&analysis.pkg.equals(session.recordingPackage);
                    long taskTime=shared?recordScanTime:now;
                    if(shared?taskTime>=0&&taskTime!=lastThreadTime:lastThreadTime==0||now-lastThreadTime>=3000){
                        List<MonitorSnapshot.Task> tasks;
                        if(shared){tasks=recordPrimaryTasks;for(ThreadAnalysis.Process p:analysisTasks.getOrDefault(session.user,Collections.emptyList()))if(p.pid==process.tid&&p.start==process.start)tasks=p.tasks;}
                        else {enumerations++;tasks=readTasks(process.tid);}
                        MonitorSnapshot.Task after=identity(process.tid);
                        if(after==null||after.start!=process.start){clearThreadBaseline();process=null;}
                        else{
                            rows=MonitorSnapshot.delta(previous,tasks,taskTime-lastThreadTime,hz,previousPid==process.tid&&previousStart==process.start);
                            previous=tasks;previousPid=process.tid;previousStart=process.start;lastThreadTime=taskTime;
                        }
                    }
                    if(process!=null)store.append(now,fps,power,quiet,process.tid,process.start,rows,fpsValidity);
                }else process=null;
                if(process==null)terminate("PROCESS_GENERATION_LOST",true);
            }else clearThreadBaseline();
            samples++;
            JSONObject data=metadata(new JSONObject()).put("elapsedMs",now)
                .put("fps",fps<0?JSONObject.NULL:fps).put("fpsValidity",fpsValidity).put("sourceHealthy",fps>=0)
                .put("consumptionPowerW",power<0?JSONObject.NULL:power).put("powerValidity",power<0?"UNAVAILABLE":"VALID")
                .put("quietValidity",quiet<0?"UNAVAILABLE":"VALID").put("powerW",power).put("powerSource","DEVICE_POWER_W_BATTERY_DISCHARGE_MV_UA")
                // Same-sample diagnostics in the existing in-memory snapshot; no extra sampler/storage.
                .put("batteryStatus",batteryStatus).put("batteryPlugged",plugged)
                .put("batteryVoltageMv",milliVolts).put("batteryCurrentUa",microAmps)
                .put("batteryCurrentMagnitudeA",power<0?-1:Math.abs(microAmps/1000000.0))
                .put("quietC",quiet).put("recordState",session.recordingState());
            lastSnapshot=data.toString();deliver(lastSnapshot);
            // Only a completed sample proves pipeline recovery. Expected lack of demand or
            // charging power unavailability is not a source fault; failed demanded reads are.
            error=boundedError(!sources.quietError.isEmpty()?"quiet:"+sources.quietError:
                    fpsBlockedReason().isEmpty()&&fps<0?"fps:"+fpsValidity:"");
        }catch(Exception e){error=boundedError(e.getClass().getSimpleName()+":"+e.getMessage());
            terminate("SOURCE_PIPELINE_FAILURE",true);stop();}
        reschedule(powerDelay);
    }}
    private void reschedule(long powerDelay){
        if(!scalarActive()&&analyses.isEmpty()){drain();return;}
        if(handler!=null&&(scalarActive()||!analyses.isEmpty())&&!scheduled){
            scheduled=true;long next=epoch;
            long now=SystemClock.elapsedRealtime();
            long delay=scalarActive()?(powerDelay>0?powerDelay:session.interval()):ThreadAnalysis.MAX_WALL;
            for(ThreadAnalysis a:analyses.values()){
                if(a.recordBound)continue; // The Recording deadline and shared scan clock own this lifetime.
                delay=Math.min(delay,Math.max(1,a.began+ThreadAnalysis.MAX_WALL-now));
                if(a.collecting){delay=Math.min(delay,Math.max(1,a.requested-a.active));delay=Math.min(delay,a.lastSample<0?1:Math.max(1,a.lastSample+ThreadAnalysis.INTERVAL-now));}
                if(!a.live())delay=1;
            }
            if(session.recording()){
                delay=Math.min(delay,recordScanTime<0?1:Math.max(1,recordScanTime+ThreadAnalysis.INTERVAL-now));
                delay=Math.min(delay,Math.max(1,session.recordingStart+MonitorSession.MAX_RECORD_MS-now));
            }
            handler.postDelayed(()->sample(next),delay);
        }
    }
    private String analysisCommand(String action,int user,String arg,long now)throws Exception{
        java.util.Map<String,Object> request=arg.isEmpty()?PolicyJson.map():PolicyJson.object(PolicyJson.parse(arg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if(action.equals("analysisStart")){
            PolicyJson.keys(request,"package","activeMs");PolicyJson.require(user==session.user&&!analyses.containsKey(user)&&!session.recording(),"analysis busy/inactive user");
            ThreadAnalysis a=new ThreadAnalysis(user,PolicyJson.string(request.get("package")),PolicyJson.integer(request.get("activeMs")),now,facts.rulesGeneration());
            analyses.put(user,a);a.scene(a.pkg.equals(session.foreground)&&session.recordEligible,session.eligible,now);
            stop();schedule();return PolicyJson.encode(a.summary(now));
        }
        if(action.equals("analysisState")){
            PolicyJson.keys(request);ThreadAnalysis a=analyses.get(user);
            return a==null?analysisStates.getOrDefault(user,"{}"):PolicyJson.encode(a.summary(now));
        }
        if(action.equals("analysisStop")){
            PolicyJson.keys(request,"session");ThreadAnalysis a=analyses.get(user);
            PolicyJson.require(a!=null&&!a.recordBound&&a.id.equals(request.get("session")),"analysis session changed or recording owned");a.finish("FINISHED",now);finishAnalysis(a,now);stop();schedule();return analysisStates.get(user);
        }
        if(action.equals("analysisRead")||action.equals("analysisDelete")){
            boolean delete=action.equals("analysisDelete");
            if(delete)PolicyJson.keys(request,"package");else PolicyJson.keys(request,"package","offset","hash");
            String pkg=PolicyJson.string(request.get("package"));ThreadAnalysis a=analyses.get(user);
            PolicyJson.require(!delete||a==null||!a.pkg.equals(pkg),"stop analysis before delete");
            return store.analysis(user,pkg,delete?0:PolicyJson.intValue(request.get("offset")),delete?"":PolicyJson.string(request.get("hash")),delete);
        }
        throw new IllegalArgumentException("analysis action");
    }
    private void finishAnalysis(ThreadAnalysis a,long now){
        a.endRules=facts.rulesGeneration();
        try{store.saveAnalysis(a.user,a.pkg,a.result(now));analysisWrites++;}
        catch(Exception e){a.state="FAILED";a.error="persist:"+e.getClass().getSimpleName();if(a.recordBound)finalizeError=boundedError((finalizeError.isEmpty()?"":finalizeError+";")+"analysis_finalize:"+e.getMessage());}
        analysisStates.put(a.user,PolicyJson.encode(a.summary(now)));analyses.remove(a.user);analysisTasks.remove(a.user);
    }
    private void updateAnalyses(long now){
        boolean screen=screenTerminal().isEmpty()&&session.eligible;
        for(ThreadAnalysis a:new ArrayList<>(analyses.values())){
            a.scene(a.user==session.user&&a.pkg.equals(session.foreground)&&session.recordEligible,screen,now);
            boolean due=a.recordBound?session.recording()&&(recordScanTime<0||now-recordScanTime>=ThreadAnalysis.INTERVAL):a.due(now);
            if(due)try{
                long before=threadReads;List<ThreadAnalysis.Process> tasks=readAnalysisTasks(a);analysisReads+=threadReads-before;analysisEnumerations++;
                analysisTasks.put(a.user,tasks);if(a.recordBound)recordScanTime=now;
                if(a.due(now))try{a.sample(tasks,now,hz);}catch(Exception e){a.fail(e.getClass().getSimpleName()+":"+e.getMessage(),now);}
            }catch(Exception e){
                a.fail(e.getClass().getSimpleName()+":"+e.getMessage(),now);
                if(a.recordBound){terminate("ANALYSIS_SOURCE_FAILURE",true);throw new IllegalStateException("analysis source:"+e.getMessage(),e);}
            }
            if(!a.live()&&!a.recordBound)finishAnalysis(a,now);
        }
    }
    List<ThreadAnalysis.Process> readAnalysisTasks(ThreadAnalysis a){
        ActivityManager manager=context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes=manager.getRunningAppProcesses();
        if(processes==null)throw new IllegalStateException("analysis process list unavailable");
        List<ThreadAnalysis.Process> out=new ArrayList<>();int count=0;boolean primary=false;
        java.util.Set<Integer> selected=new java.util.HashSet<>();
        recordPrimaryTasks=Collections.emptyList();
        for(ActivityManager.RunningAppProcessInfo p:processes){
            if(p.uid/100000!=a.user||p.pkgList==null||p.pkgList.length!=1||!a.pkg.equals(p.pkgList[0]))continue;
            PolicyJson.require(selected.add(p.pid),"analysis duplicate process");
            MonitorSnapshot.Task before=identity(p.pid);if(before==null)continue;
            List<MonitorSnapshot.Task> tasks=readTasks(p.pid);MonitorSnapshot.Task after=identity(p.pid);
            if(after==null||after.start!=before.start)continue;
            if(a.recordBound&&p.pid==session.recordingPid&&before.start==session.processStart){recordPrimaryTasks=tasks;primary=true;}
            count+=tasks.size();if(count>ThreadAnalysis.MAX_TASKS||out.size()>=64)throw new IllegalStateException("analysis target task bound");
            out.add(new ThreadAnalysis.Process(p.pid,before.start,tasks));
        }
        // Preserve the original Recording primary snapshot if ActivityManager omits it
        // or reports an ambiguous pkgList. It is not admitted to qualified analysis.
        // A qualified primary above is never enumerated twice.
        if(a.recordBound&&!primary){
            MonitorSnapshot.Task before=identity(session.recordingPid);
            if(before!=null&&session.sameProcess(before.tid,before.start)){
                List<MonitorSnapshot.Task> tasks=readTasks(before.tid);MonitorSnapshot.Task after=identity(before.tid);
                if(after!=null&&after.start==before.start)recordPrimaryTasks=tasks;
            }
        }
        return out;
    }
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
