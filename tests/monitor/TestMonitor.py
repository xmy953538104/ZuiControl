"""R4 production state/collector tests. Stubs never qualify device permissions."""
from pathlib import Path
import subprocess,tempfile
from android_stubs import stubs

root=Path(__file__).resolve().parents[2]
src=root/'framework_patch/src/services/com/zui/server/control'
stubs.update({
 'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}',
 'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {
 public static final List<Runnable> pending=new ArrayList<>();public Handler(Object o){}
 public boolean post(Runnable r){pending.add(r);return true;}
 public boolean postDelayed(Runnable r,long delay){if(delay!=1000)throw new AssertionError();pending.add(r);return true;}
 public void removeCallbacksAndMessages(Object o){pending.clear();}
 public static void next(){SystemClock.now+=1000;pending.remove(0).run();}}''',
 'android/os/BatteryManager.java':'''package android.os;public class BatteryManager {
 public static final String EXTRA_PLUGGED="plugged",EXTRA_STATUS="status",EXTRA_VOLTAGE="voltage";
 public static final int BATTERY_PROPERTY_CURRENT_NOW=2;public long getLongProperty(int n){return -2000000;}}''',
 'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager {
 public Object getApplicationInfo(String p,int f){return p;}public CharSequence getApplicationLabel(Object o){return o.toString();}}''',
 'android/content/Context.java':'''package android.content;public class Context {
 public <T>T getSystemService(Class<T> c){try{return c.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 public Intent registerReceiver(Object r,IntentFilter f){return new Intent();}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}''',
 'com/zui/server/control/MonitorStore.java':'''package com.zui.server.control;import java.util.*;
 final class MonitorStore {long writes,scalarRows,threadRows;boolean active;static int starts,finishes;
 void start(String p,String l,int u,int pid,long g,long t){if(active)throw new AssertionError();active=true;starts++;writes++;scalarRows=threadRows=0;}
 void append(long t,double f,double p,double q,int pid,long g,List<MonitorSnapshot.Row> r){if(!active)throw new AssertionError();scalarRows++;threadRows+=r.size();writes+=1+r.size();}
 void finish(long n){if(active){finishes++;writes++;active=false;}}void abandon(){active=false;}
 String read(int u,String key){return "{}";}String list(int u){return "{}";}String delete(int u,String p){return "ok=1";}}''',
 'com/zui/server/control/CollectorTest.java':'''package com.zui.server.control;
 import android.os.*;import android.app.*;import android.content.*;import java.util.*;
 public class CollectorTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static class Client implements IBinder {DeathRecipient death;String last;int calls;
 public void linkToDeath(DeathRecipient d,int f){death=d;}public boolean unlinkToDeath(DeathRecipient d,int f){death=null;return true;}
 public boolean transact(int c,Parcel p,Parcel r,int f){check(f==FLAG_ONEWAY);last=p.text;calls++;return true;}}
 static class Collector extends MonitorCollector {int scans;long generation=9;boolean missing;
 Collector(){super(new Context());}int findPid(){return 42;}
 MonitorSnapshot.Task identity(int p){return missing?null:new MonitorSnapshot.Task(p,"GameThread",generation,SystemClock.now/10);}
 List<MonitorSnapshot.Task> readTasks(int p){scans++;return Arrays.asList(identity(p));}}
 public static void main(String[] args)throws Exception {
 Collector c=new Collector();Client client=new Client();c.register(client);c.scene("game",0,true);
 check(Handler.pending.isEmpty()&&c.scans==0&&MonitorStore.starts==0);
 c.command("full",0,"");check(Handler.pending.size()==1);for(int i=0;i<10;i++)Handler.next();
 check(c.scans==0&&c.state().contains("monitorDbWrites=0"));
 c.command("fps",0,"");check(Handler.pending.size()==1);Handler.next();check(c.scans==0);
 c.command("full",0,"");c.command("arm",0,"");SystemClock.now+=1999;
 check(c.command("recordStart",0,"").startsWith("ok=0")&&MonitorStore.starts==0);
 c.command("cancelArm",0,"");SystemClock.now+=100;
 check(c.command("recordStart",0,"").startsWith("ok=0"));
 c.command("arm",0,"");SystemClock.now+=2000;
 check(c.command("recordStart",0,"").startsWith("ok=1")&&MonitorStore.starts==1);
 check(c.command("recordStart",0,"").startsWith("ok=0")&&MonitorStore.starts==1);
 for(int i=0;i<7;i++)Handler.next();check(c.scans==3);
 c.scene("other",0,true);String before=c.state();for(int i=0;i<4;i++)Handler.next();
 check(c.scans==3&&c.session.recordingState().equals("PAUSED")&&MonitorStore.finishes==0);
 c.scene("game",0,true);Handler.next();check(c.scans==4&&c.session.recordingState().equals("RECORDING"));
 c.scene("game",0,false);check(Handler.pending.isEmpty()&&c.session.recordingState().equals("PAUSED"));
 c.generation++;c.scene("game",0,true);Handler.next();check(c.scans==5);
 c.missing=true;Handler.next();long stoppedRows=Long.parseLong(c.state().split("monitorScalarRows=")[1].split("\\n")[0]);
 check(c.session.recordingState().equals("PAUSED")&&MonitorStore.finishes==0);
 Handler.next();check(c.state().contains("monitorScalarRows="+stoppedRows+"\\n")&&c.scans==5);
 c.missing=false;c.generation++;Handler.next();check(c.scans==6&&c.session.recordingState().equals("RECORDING"));
 c.command("recordStop",0,"");check(MonitorStore.finishes==1&&!c.session.recording());
 int scans=c.scans;Handler.next();check(scans==c.scans);
 c.command("arm",0,"");SystemClock.now+=100;c.command("cancelArm",0,"");check(MonitorStore.starts==1);
 c.command("arm",0,"");SystemClock.now+=2000;c.command("recordStart",0,"");check(MonitorStore.starts==2);
 Runnable stale=Handler.pending.get(0);c.command("off",0,"");check(Handler.pending.isEmpty()&&HandlerThread.active==0);
 stale.run();check(c.scans==scans&&MonitorStore.finishes==1);
 c.command("full",0,"");Handler.next();client.death.binderDied();
 check(Handler.pending.isEmpty()&&HandlerThread.active==0&&MonitorStore.finishes==1);
 c.register(new Client());c.scene("game",0,true);check(Handler.pending.isEmpty());
 for(int i=0;i<100;i++){c.command("full",0,"");Handler.next();c.command("off",0,"");}
 check(HandlerThread.active==0&&Handler.pending.isEmpty());
 System.out.println("R4_REAL_COLLECTOR_ZERO_DISPLAY_WRITES_SCANS_PAUSE_RESUME_HOLD_DEATH_DRAIN_100=PASS");}}
'''
})
with tempfile.TemporaryDirectory(prefix='zui-monitor-r4-') as tmp:
 out=Path(tmp);files=[]
 for name,text in stubs.items():
  p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
 production=['MonitorCollector.java','MonitorSources.java','MonitorSnapshot.java','MonitorSession.java','MonitorLifecycle.java']
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(src/n) for n in production],str(Path(__file__).with_name('MonitorTest.java')),str(Path(__file__).with_name('MonitorR4Test.java'))],check=True)
 for test in ('MonitorTest','MonitorR4Test','CollectorTest'):subprocess.run(['java','-cp',tmp,'com.zui.server.control.'+test],check=True)
collector=(src/'MonitorCollector.java').read_text(encoding='utf8')
sources=(src/'MonitorSources.java').read_text(encoding='utf8')
for forbidden in ('kgsl','gpu_busy','/proc/stat','ProcessBuilder','Runtime.getRuntime','setAffinity','renice'):
 assert forbidden not in collector+sources,forbidden
assert collector.count('postDelayed(')==1 and 'if(capture)' in collector
assert 'mode==MonitorSession.FULL||capture' in collector
print('MONITOR_R4_STATIC_SINGLE_PIPELINE=PASS')
