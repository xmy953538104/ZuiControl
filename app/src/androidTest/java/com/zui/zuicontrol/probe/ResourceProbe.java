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

/** Test APK only. Never packaged in production; no policy/transport writes. */
public final class ResourceProbe extends Instrumentation {
    private WeakReference<Activity> current = new WeakReference<>(null);
    private boolean frozen;
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
        Log.i("R6Probe","READY_TARGET_UNMODIFIED");
    }
    @SuppressWarnings("unchecked") private void operation(String op) throws Exception {
        Activity activity=current.get();Context context=getTargetContext();
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
            Object inventory=cache.get(singleton);
            List<?> entries=(List<?>)inventory.getClass().getMethod("getEntries").invoke(inventory);
            Map<String,Object> replacement=new HashMap<>();List<Object> next=new ArrayList<>();
            for(Object e:entries){ApplicationInfo info=(ApplicationInfo)e.getClass().getMethod("getInfo").invoke(e);
                Object x=e.getClass().getConstructors()[0].newInstance(info,e.getClass().getMethod("getLabel").invoke(e),new ColorDrawable(0));next.add(x);replacement.put(info.packageName,x);}
            cache.set(singleton,inventory.getClass().getConstructors()[0].newInstance(inventory.getClass().getMethod("getVersion").invoke(inventory),next));
            if(activity!=null)put(activity,"appMetadata",replacement);
            Log.i("R6Probe","ICON_REFS_REPLACED="+next.size());return;
        }
        if("gc".equals(op)){Runtime.getRuntime().gc();System.runFinalization();Runtime.getRuntime().gc();Log.i("R6Probe","CONTROLLED_GC");return;}
        if("finish".equals(op)){finish(0,new Bundle());return;}
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
}
