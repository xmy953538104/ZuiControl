stubs = {
    'android/os/RemoteException.java': 'package android.os; public class RemoteException extends Exception {}',
    'android/os/IBinder.java': '''package android.os; public interface IBinder { int FLAG_ONEWAY=1;
        interface DeathRecipient { void binderDied(); }
        void linkToDeath(DeathRecipient d,int f)throws RemoteException;
        boolean unlinkToDeath(DeathRecipient d,int f);
        boolean transact(int c,Parcel p,Parcel r,int f)throws RemoteException; }''',
    'android/os/Parcel.java': '''package android.os; public class Parcel {public String text;
        public static Parcel obtain(){return new Parcel();} public void writeInterfaceToken(String s){}
        public void writeString(String s){text=s;} public void recycle(){}}''',
    'android/os/HandlerThread.java': '''package android.os; public class HandlerThread {
        public static int active; boolean live; public HandlerThread(String n){}
        public void start(){active++;live=true;} public Object getLooper(){return this;}
        public boolean quitSafely(){if(live){active--;live=false;}return true;}}''',
    'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
        public static final List<Runnable> pending=new ArrayList<>();
        public Handler(Object o){} public boolean post(Runnable r){pending.add(r);return true;}
        public boolean postDelayed(Runnable r,long delay){if(delay!=3000)throw new AssertionError();pending.add(r);return true;}
        public void removeCallbacksAndMessages(Object o){pending.clear();}
        public static void next(){pending.remove(0).run();}}''',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {static long t; public static long elapsedRealtime(){return t+=3000;}}',
    'android/os/BatteryManager.java': 'package android.os; public class BatteryManager {public static final String EXTRA_TEMPERATURE="temp";}',
    'android/system/OsConstants.java': 'package android.system; public class OsConstants {public static final int _SC_CLK_TCK=1;}',
    'android/system/Os.java': 'package android.system; public class Os {public static long sysconf(int c){return 100;}}',
    'android/app/KeyguardManager.java': 'package android.app; public class KeyguardManager {public static boolean locked;public boolean isKeyguardLocked(){return locked;}}',
    'android/app/ActivityManager.java': '''package android.app; import java.util.*; public class ActivityManager {
        public static class RunningAppProcessInfo {public String processName;public int uid,pid;}
        public List<RunningAppProcessInfo> getRunningAppProcesses(){return Collections.emptyList();}}''',
    'android/content/Intent.java': '''package android.content;public class Intent {public static final String ACTION_BATTERY_CHANGED="battery";
        public boolean hasExtra(String s){return true;}public int getIntExtra(String s,int d){return 320;}}''',
    'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public IntentFilter(String s){}}',
    'android/content/Context.java': '''package android.content; public class Context {
        public <T>T getSystemService(Class<T> c){try{return c.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
        public Intent registerReceiver(Object r,IntentFilter f){return new Intent();}}''',
    'org/json/JSONObject.java': '''package org.json;import java.util.*; public class JSONObject {
        Map<String,Object> data=new LinkedHashMap<>();public JSONObject put(String k,Object v){data.put(k,v);return this;}
        public String toString(){return data.toString();}}''',
    'org/json/JSONArray.java': 'package org.json;public class JSONArray {public JSONArray put(Object o){return this;}}',
    'com/zui/server/control/CollectorTest.java': '''package com.zui.server.control;
        import android.os.*; import android.app.*; import android.content.*;
        public class CollectorTest {
          static class Client implements IBinder {DeathRecipient death;String last;int calls;
            public void linkToDeath(DeathRecipient d,int f){death=d;}public boolean unlinkToDeath(DeathRecipient d,int f){death=null;return true;}
            public boolean transact(int c,Parcel p,Parcel r,int f){check(f==FLAG_ONEWAY);last=p.text;calls++;return true;}}
          static void check(boolean v){if(!v)throw new AssertionError();}
          public static void main(String[] a)throws Exception {
            MonitorCollector c=new MonitorCollector(new Context()); Client one=new Client();
            c.register(one);check(Handler.pending.isEmpty());
            c.select("game",0,false);check(Handler.pending.size()==1); Handler.next();
            check(Handler.pending.size()==1 && c.snapshot().equals(one.last));
            String snapshot=c.snapshot();c.snapshot();check(c.snapshot().equals(snapshot)&&Handler.pending.size()==1);
            c.select("game",0,false);check(Handler.pending.size()==1); // same-target no second timer
            Runnable stale=Handler.pending.get(0);c.select("",0,false);
            check(Handler.pending.isEmpty()&&HandlerThread.active==0);int count=one.calls;stale.run();check(one.calls==count);
            c.select("game",0,true);Handler.next();check(one.last.contains("expanded=true"));
            KeyguardManager.locked=true;Handler.next();check(Handler.pending.isEmpty()&&HandlerThread.active==0);
            KeyguardManager.locked=false;c.select("game",0,true);Handler.next();
            one.death.binderDied();check(Handler.pending.isEmpty()&&HandlerThread.active==0);
            Client two=new Client();c.register(two);c.select("game",0,false);Handler.next();check(two.calls>0);
            for(int i=0;i<100;i++){c.select("",0,false);c.select("game",0,false);Handler.next();}
            c.register(null);check(Handler.pending.isEmpty()&&HandlerThread.active==0);
            System.out.println("MONITOR_REAL_COLLECTOR_START_STOP_SHARED_SNAPSHOT_STALE_DRAIN_DEATH_100_TRANSITIONS=PASS");
          }
        }''',
}
