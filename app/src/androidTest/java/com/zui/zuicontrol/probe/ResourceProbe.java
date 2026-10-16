package com.zui.zuicontrol.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.animation.Animator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Debug;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test APK only. Controlled formal requests restore their initial intent. */
public final class ResourceProbe extends Instrumentation {
    private WeakReference<Activity> current = new WeakReference<>(null);
    private boolean frozen;
    private Object controlsState;
    private android.zui.ZuiControlManager controlsOwner;
    private IBinder originalCallback,probeCallback;
    private long subscriptionEpoch;
    private String subscribedSnapshot="";
    private final AckFaultGate fault=new AckFaultGate();
    private volatile boolean callbackRunning;
    private MasterListFixture fixture;
    private Activity windowTarget;
    private int windowMode;
    private float windowRefresh;
    private android.zui.ZuiControlManager moduleTarget;
    private void restoreModule(){
        if(moduleTarget==null)return;
        String reply=moduleTarget.setModuleEnabled("refresh",true);
        if(!reply.startsWith("ok=1"))throw new IllegalStateException("Module restore rejected: "+reply);
        moduleTarget=null;Log.i("R6Probe","REFRESH_MODULE_RESTORED");
    }
    private void restoreWindow(){
        if(windowTarget==null)return;
        android.view.WindowManager.LayoutParams lp=windowTarget.getWindow().getAttributes();
        lp.preferredDisplayModeId=windowMode;lp.preferredRefreshRate=windowRefresh;
        windowTarget.getWindow().setAttributes(lp);windowTarget=null;
    }
    private final List<WeakReference<View>> watchedRoots=new ArrayList<>();
    private final java.util.Set<Integer> watchedIds=new java.util.HashSet<>();
    private void objectSnapshot(Activity activity)throws Exception{
        JSONObject j=new JSONObject().put("event","RESOURCE_OBJECTS").put("boottime_ns",SystemClock.elapsedRealtimeNanos())
            .put("pid",android.os.Process.myPid()).put("profileableByShell",activity.getApplicationInfo().isProfileableByShell())
            .put("native_size",Debug.getNativeHeapSize()).put("native_alloc",Debug.getNativeHeapAllocatedSize()).put("native_free",Debug.getNativeHeapFreeSize())
            .put("java_used",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).put("natural_gc_count",Debug.getRuntimeStat("art.gc.gc-count"));
        for(String name:new String[]{"detail","ownerHost"}){Object v=field(activity,name);if(v instanceof View){View root=(View)v;int id=System.identityHashCode(root);
            if(watchedIds.add(id)&&watchedRoots.size()<96)watchedRoots.add(new WeakReference<>(root));j.put(name+"_identity",id);}}
        Object modal=field(activity,"ownerModal");Object overlay=field(modal,"overlay");
        if(overlay instanceof View){View view=(View)overlay;int id=System.identityHashCode(view);if(watchedIds.add(id)&&watchedRoots.size()<96)watchedRoots.add(new WeakReference<>(view));}
        j.put("modal_open",overlay!=null);j.put("modal_host_children",((ViewGroup)field(activity,"ownerHost")).getChildCount());
        Object pages=field(activity,"pageHost");j.put("page_children",((ViewGroup)pages).getChildCount());
        for(String name:new String[]{"pageBindings","masterSelectionBindings","ownerControls","coreHealthBindings","controlBindings","loading"}){
            Object value=field(activity,name);j.put(name,value instanceof Map?((Map<?,?>)value).values().stream().mapToInt(v->v instanceof java.util.Collection?((java.util.Collection<?>)v).size():1).sum():((java.util.Collection<?>)value).size());}
        int alive=0,attached=0;for(WeakReference<View> ref:watchedRoots){View v=ref.get();if(v!=null){alive++;if(v.isAttachedToWindow())attached++;}}
        j.put("watched",watchedRoots.size()).put("weak_alive",alive).put("weak_attached",attached).put("weak_cleared",watchedRoots.size()-alive);
        Log.i("R6Probe",j.toString());
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity); current = new WeakReference<>(activity);
    }
    private Object field(Object value,String name) throws Exception {
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private void put(Object value,String name,Object data) throws Exception {
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,data);
    }
    private List<View> views(View v) {
        List<View> all=new ArrayList<>();all.add(v);
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)all.addAll(views(g.getChildAt(i)));}
        return all;
    }
    @Override public void onStart() {
        runOnMainSync(() -> getTargetContext().registerReceiver(new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                try { operation(intent.getStringExtra("op")); }
                catch(Exception e){Log.e("R6Probe","PROBE_ERROR",e);}
            }
        },new IntentFilter("com.zui.zuicontrol.probe.OP"),"android.permission.DUMP",null,Context.RECEIVER_EXPORTED));
        Intent launch=new Intent().setClassName(getTargetContext(),"com.zui.zuicontrol.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivitySync(launch);
        Log.i("R6Probe","READY_TARGET_UNMODIFIED");
    }
    private void restoreCallback()throws Exception{
        if(probeCallback==null)return;
        Object state=controlsState;android.zui.ZuiControlManager owner=controlsOwner;IBinder proxy=probeCallback,original=originalCallback;
        probeCallback=null;
        owner.controls(false,proxy);
        if(((Number)field(state,"epoch")).longValue()==subscriptionEpoch&&field(state,"callback")==proxy){
            put(state,"callback",original);owner.controls(true,original);
        }
    }
    private void wrapCallback()throws Exception{
        Class<?> type=Class.forName("com.zui.zuicontrol.ControlsState");controlsState=type.getField("INSTANCE").get(null);
        controlsOwner=(android.zui.ZuiControlManager)field(controlsState,"backend");originalCallback=(IBinder)field(controlsState,"callback");
        if(controlsOwner==null||originalCallback==null)throw new IllegalStateException("Real UID/epoch subscription not ready");
        subscriptionEpoch=((Number)field(controlsState,"epoch")).longValue();
        final IBinder delegate=originalCallback;
        probeCallback=new Binder(){
            {attachInterface(null,"android.zui.IControlsSnapshotV2");}
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws android.os.RemoteException{
                if(code==IBinder.INTERFACE_TRANSACTION)return super.onTransact(code,data,reply,flags);
                if(Binder.getCallingUid()!=1000||(flags&IBinder.FLAG_ONEWAY)==0)return false;
                if(code==2){
                    int position=data.dataPosition();data.enforceInterface("android.zui.IControlsSnapshotV2");
                    String id=data.readString(),sha=data.readString(),ack=data.readString();data.setDataPosition(position);
                    int action=fault.take(id,sha,ack);
                    if(action!=0){
                        Log.i("R6Probe","ACK_FAULT uid="+Binder.getCallingUid()+" epoch="+subscriptionEpoch+" id="+id+" action="+action+" ns="+SystemClock.elapsedRealtimeNanos());
                        if(action==1)return true;
                        SystemClock.sleep(1500);
                        Log.i("R6Probe","ACK_LATE_FORWARD uid="+Binder.getCallingUid()+" id="+id+" ns="+SystemClock.elapsedRealtimeNanos());
                    }
                }
                return delegate.transact(code,data,reply,flags);
            }
        };
        controlsOwner.controls(false,originalCallback);
        put(controlsState,"callback",probeCallback);
        String initial=controlsOwner.controls(true,probeCallback);
        if(!initial.toString().startsWith("ok=1"))throw new IllegalStateException("Real registration failed");
        subscribedSnapshot=initial;
        Log.i("R6Probe","ACK_SUBSCRIPTION_PROOF appUid="+android.os.Process.myUid()+" epoch="+subscriptionEpoch+" descriptor="+probeCallback.getInterfaceDescriptor());
    }
    private void callbackCase(String mode){
        if(callbackRunning)throw new IllegalStateException("Prior finite callback case active");callbackRunning=true;
        new Thread(()->{
            Context context=getTargetContext();int original=0;Throwable primary=null;
            try{
                Exception[] connected={null};runOnMainSync(()->{try{wrapCallback();}catch(Exception e){connected[0]=e;}});
                if(connected[0]!=null)throw connected[0];
                String scene=com.zui.zuicontrol.ZuiControlClient.INSTANCE.currentSceneText();
                original=globalRefresh(subscribedSnapshot);
                int target=original==90?120:90;
                String id=com.zui.zuicontrol.ZuiControlClient.INSTANCE.sendPolicy(context,"refresh","","GLOBAL",target,"",0,0,scene,null,null);
                Object request=Class.forName("com.zui.zuicontrol.ZuiControlRequest").getField("INSTANCE").get(null),pending=null;
                for(java.lang.reflect.Method m:request.getClass().getDeclaredMethods())if(m.getName().startsWith("pendingIdentity")){m.setAccessible(true);pending=m.invoke(request,context);break;}
                if(pending==null)throw new IllegalStateException("Exact pending identity already gone");
                String text=(String)pending.getClass().getMethod("getSecond").invoke(pending);
                byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder digest=new StringBuilder();for(byte b:bytes)digest.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                fault.arm(id,digest.toString(),mode.equals("ackDrop")?1:2);
                Log.i("R6Probe","ACK_TARGET_ARMED id="+id+" sha="+digest+" ns="+SystemClock.elapsedRealtimeNanos());
                com.zui.zuicontrol.ZuiControlRequest.Ack ack=com.zui.zuicontrol.ZuiControlRequest.INSTANCE.awaitTerminalAck(context,id,5000,null);
                if(!fault.consumed()||!ack.getSucceeded())throw new IllegalStateException("Fault not injected or command failed");
                SystemClock.sleep(mode.equals("ackDrop")?50:1700);
                Log.i("R6Probe","ACK_CASE_PASS mode="+mode+" id="+id+" ns="+SystemClock.elapsedRealtimeNanos());
            }catch(Throwable e){primary=e;Log.e("R6Probe","PRIMARY_FAILURE mode="+mode,e);}
            finally{
                try{
                    Exception[] restored={null};runOnMainSync(()->{try{restoreCallback();}catch(Exception e){restored[0]=e;}});
                    if(restored[0]!=null)throw restored[0];
                    if(original>0){String id=com.zui.zuicontrol.ZuiControlClient.INSTANCE.sendPolicy(context,"refresh","","GLOBAL",original,"",0,0,
                        com.zui.zuicontrol.ZuiControlClient.INSTANCE.currentSceneText(),null,null);
                        if(!com.zui.zuicontrol.ZuiControlRequest.INSTANCE.awaitTerminalAck(context,id,5000,null).getSucceeded())throw new IllegalStateException("Restore rejected");}
                    Log.i("R6Probe","ACK_CASE_CLEANUP_PASS mode="+mode+" primary="+(primary!=null));
                }catch(Throwable e){Log.e("R6Probe","CLEANUP_FAILURE mode="+mode,e);}
                finally{callbackRunning=false;}
            }
        },"R5-once-ack-test").start();
    }
    @SuppressWarnings("unchecked") private void operation(String op) throws Exception {
        Activity activity=current.get();Context context=getTargetContext();
        if("moduleOff".equals(op)){
            if(moduleTarget!=null)throw new IllegalStateException("Module test already active");
            Object state=Class.forName("com.zui.zuicontrol.ControlsState").getField("INSTANCE").get(null);
            android.zui.ZuiControlManager owner=(android.zui.ZuiControlManager)field(state,"backend");
            if(owner==null||!owner.getState().contains("\nrefreshDisabled=false\n"))
                throw new IllegalStateException("Owner module not enabled; do not overwrite intent");
            moduleTarget=owner;String reply=owner.setModuleEnabled("refresh",false);
            if(!reply.startsWith("ok=1"))throw new IllegalStateException("Module disable rejected: "+reply);
            Log.i("R6Probe","REFRESH_MODULE_DISABLED_REAL_APP_UID="+android.os.Process.myUid());return;
        }
        if("moduleRestore".equals(op)){restoreModule();return;}
        if("window60".equals(op)){
            if(windowTarget!=null)throw new IllegalStateException("Window request already active");
            android.view.Display.Mode chosen=null;
            for(android.view.Display.Mode mode:activity.getDisplay().getSupportedModes())
                if(Math.abs(mode.getRefreshRate()-60f)<.1f)chosen=mode;
            if(chosen==null)throw new IllegalStateException("No real 60Hz mode");
            android.view.WindowManager.LayoutParams lp=activity.getWindow().getAttributes();
            windowTarget=activity;windowMode=lp.preferredDisplayModeId;windowRefresh=lp.preferredRefreshRate;
            lp.preferredDisplayModeId=chosen.getModeId();lp.preferredRefreshRate=chosen.getRefreshRate();
            activity.getWindow().setAttributes(lp);Log.i("R6Probe","REAL_WINDOW_MODE_60 id="+chosen.getModeId());return;
        }
        if("windowRestore".equals(op)){restoreWindow();Log.i("R6Probe","REAL_WINDOW_MODE_RESTORED");return;}
        if("fixture3".equals(op)||"fixture500".equals(op)){
            if(field(field(activity,"ownerModal"),"overlay")!=null)throw new IllegalStateException("Owner modal active");
            if(fixture!=null)fixture.close();fixture=new MasterListFixture(activity,"fixture3".equals(op)?3:500);return;
        }
        if("fixtureSnapshot".equals(op)){fixture.snapshot("OBSERVE");return;}
        if("fixtureEnd".equals(op)){fixture.end();return;}
        if("fixtureClose".equals(op)){if(fixture!=null)fixture.close();fixture=null;return;}
        if("objects".equals(op)){objectSnapshot(activity);return;}
        if("ackContract".equals(op)){
            try{wrapCallback();
                globalRefresh(subscribedSnapshot);
                Class<?> request=Class.forName("com.zui.zuicontrol.ZuiControlRequest");boolean pending=false;
                for(java.lang.reflect.Method m:request.getDeclaredMethods())if(m.getName().startsWith("pendingIdentity"))pending=true;
                if(!pending)throw new IllegalStateException("Pending identity entry absent");
                Log.i("R6Probe","READ_ONLY_ACK_CONTRACT_PASS appUid="+android.os.Process.myUid()+" epoch="+subscriptionEpoch+" snapshotBytes="+subscribedSnapshot.length());
            }finally{restoreCallback();}return;
        }
        if("ackDrop".equals(op)||"ackLate".equals(op)){callbackCase(op);return;}
        if("finish".equals(op)){if(fixture!=null)fixture.close();fixture=null;restoreWindow();restoreModule();restoreCallback();finish(0,new Bundle());return;}
        if("controlsDisconnect".equals(op)||"controlsReconnect".equals(op)) {
            Class<?> type=Class.forName("com.zui.zuicontrol.ControlsState");
            Object state=type.getField("INSTANCE").get(null);
            Object owner=field(state,"backend"),callback=field(state,"callback");
            if(owner==null||callback==null)throw new IllegalStateException("Real callback not connected");
            ((android.zui.ZuiControlManager)owner).controls("controlsReconnect".equals(op),callback);
            Log.i("R6Probe",op+"_COMPLETE_EXISTING_CLIENT_ONLY");return;
        }
        Class<?> packages=Class.forName("com.zui.zuicontrol.FrontendPackages");
        Object singleton=packages.getField("INSTANCE").get(null);
        Field cache=packages.getDeclaredField("cached");cache.setAccessible(true);
        if("metadata".equals(op)) {
            List<ApplicationInfo> infos=context.getPackageManager().getInstalledApplications(0);
            for(ApplicationInfo info:infos)info.loadLabel(context.getPackageManager());
            Log.i("R6Probe","METADATA_ONLY_COUNT="+infos.size());return;
        }
        if("freeze".equals(op)) {
            int count=0;
            for(View v:views(activity.getWindow().getDecorView()))if(v.getClass().getSimpleName().equals("OwnerPing")){
                ((Animator)field(v,"motion")).cancel();put(v,"phase",0f);v.invalidate();count++;
            }
            frozen=true;Log.i("R6Probe","FROZEN_PING_COUNT="+count);return;
        }
        if("clearRetainedIcons".equals(op)) {
            Object lru=field(singleton,"icons");lru.getClass().getMethod("evictAll").invoke(lru);
            Log.i("R6Probe","ICON_CACHE_EVICTED; METADATA_UNCHANGED");return;
        }
        if("gc".equals(op)){Runtime.getRuntime().gc();System.runFinalization();Runtime.getRuntime().gc();Log.i("R6Probe","CONTROLLED_GC");return;}
        JSONObject j=new JSONObject();j.put("frozen",frozen);j.put("native_allocated_bytes",Debug.getNativeHeapAllocatedSize());
        Object inventory=cache.get(singleton);int entries=0,icons=0;
        if(inventory!=null){List<?> es=(List<?>)inventory.getClass().getMethod("getEntries").invoke(inventory);entries=es.size();
            for(Object e:es){try {Object icon=e.getClass().getMethod("getIcon").invoke(e);if(icon!=null&&!(icon instanceof ColorDrawable))icons++;}catch(NoSuchMethodException ignored){}}}
        j.put("inventory_entries",entries);j.put("retained_entry_icons",icons);
        try{Object lru=field(singleton,"icons");j.put("icon_cache_size",lru.getClass().getMethod("size").invoke(lru));}catch(NoSuchFieldException ignored){}
        j.put("package_listeners",((java.util.Collection<?>)field(singleton,"listeners")).size());
        if(activity!=null){j.put("activity_identity",System.identityHashCode(activity));j.put("views",views(activity.getWindow().getDecorView()).size());
            for(String name:new String[]{"viewBindings","coreHealthBindings","controlBindings","loading"})try{Object v=field(activity,name);j.put(name,v instanceof Map?((Map<?,?>)v).size():((java.util.Collection<?>)v).size());}catch(NoSuchFieldException ignored){}
        }
        Log.i("R6Probe",j.toString());
    }
    private static int globalRefresh(String snapshot){
        for(String line:snapshot.split("\n"))if(line.startsWith("savedGlobalRefresh=")){
            int value=Integer.parseInt(line.substring("savedGlobalRefresh=".length()).trim());
            if(value==60||value==90||value==120||value==144||value==165)return value;
        }
        throw new IllegalStateException("Fresh registered controls snapshot global contract absent");
    }
}
