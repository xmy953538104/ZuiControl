"""R4 production state/collector tests. Stubs never qualify device permissions."""
from pathlib import Path
import subprocess,tempfile
from android_stubs import stubs

root=Path(__file__).resolve().parents[2]
src=root/'framework_patch/src/services/com/zui/server/control'
stubs['org/json/JSONObject.java']=stubs['org/json/JSONObject.java'].replace('public JSONObject(){}','public static final Object NULL=new Object();public JSONObject(){}').replace('public JSONObject put(String k,Object v){','public JSONObject put(String k,Object v)throws JSONException{')
stubs.update({
 'android/os/UserHandle.java':'package android.os;public class UserHandle {public final int id;private UserHandle(int id){this.id=id;}public static UserHandle getUserHandleForUid(int uid){return new UserHandle(uid/100000);}}',
 'android/os/PowerManager.java':'package android.os;public class PowerManager {public static boolean interactive=true;public boolean isInteractive(){return interactive;}}',
 'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}',
 'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {
 public static final List<Runnable> pending=new ArrayList<>();public Handler(Object o){}
 public boolean post(Runnable r){pending.add(r);return true;}
 public boolean postDelayed(Runnable r,long delay){if(delay<1||delay>5000)throw new AssertionError();pending.add(r);return true;}
 public void removeCallbacksAndMessages(Object o){pending.clear();}
 public static void next(){SystemClock.now+=1000;pending.remove(0).run();}}''',
 'android/os/BatteryManager.java':'''package android.os;public class BatteryManager {
 public static final String EXTRA_PLUGGED="plugged",EXTRA_STATUS="status",EXTRA_VOLTAGE="voltage";
 public static final int BATTERY_PROPERTY_CURRENT_NOW=2;public long getLongProperty(int n){return -2000000;}}''',
 'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager {
 public Object getApplicationInfo(String p,int f){return p;}public CharSequence getApplicationLabel(Object o){return o.toString();}}''',
 'android/content/Context.java':'''package android.content;public class Context {public static boolean failBattery;
 public Context createContextAsUser(android.os.UserHandle u,int f){return this;}
 public <T>T getSystemService(Class<T> c){try{return c.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 public Intent registerReceiver(Object r,IntentFilter f){if(failBattery)throw new IllegalStateException("battery fixture");return new Intent();}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}''',
 'com/zui/server/control/MonitorStore.java':'''package com.zui.server.control;import java.util.*;
 final class MonitorStore {long writes,scalarRows,threadRows,recordId,recordWall;boolean active;static int starts,finishes,analysisSaves;static byte[] lastAnalysis;static String terminal;static boolean incomplete;static double lastFps;static String fpsValidity;static boolean failFinish,failClose;
 void start(String p,String l,int u,int pid,long g,long t,int task,long epoch,String snapshot){if(active)throw new AssertionError();active=true;starts++;recordId=starts;recordWall=1700000000000L+starts;writes++;scalarRows=threadRows=0;}
 void append(long t,double f,double p,double q,int pid,long g,List<MonitorSnapshot.Row> r,String validity){lastFps=f;fpsValidity=validity;if(!active)throw new AssertionError();scalarRows++;threadRows+=r.size();writes+=1+r.size();}
 void finish(long n,String reason,boolean incomplete){if(failFinish)throw new IllegalStateException("SQLite finish fixture");if(active){finishes++;writes++;active=false;terminal=reason;MonitorStore.incomplete=incomplete;}}void abandon(){active=false;if(failClose)throw new IllegalStateException("close fixture");}
 void saveAnalysis(int user,String pkg,byte[] result){lastAnalysis=result;analysisSaves++;} String analysis(int user,String pkg,int offset,String hash,boolean delete){return "{}";}
 void removeUser(int user){if(user<=0)throw new AssertionError();}
 String read(int u,String key){return "{}";}String list(int u){return "{}";}String delete(int u,String p){return "ok=1";}}''',
 'com/zui/server/control/CollectorTest.java':'''package com.zui.server.control;
 import android.os.*;import android.app.*;import android.content.*;import java.util.*;
 public class CollectorTest {
 static java.nio.file.Path fpsFile;
 static MonitorSources source;
 static void check(boolean v){if(!v)throw new AssertionError();}
 static class Client implements IBinder {DeathRecipient death;String last;int calls;
 public void linkToDeath(DeathRecipient d,int f){death=d;}public boolean unlinkToDeath(DeathRecipient d,int f){death=null;return true;}
 public boolean transact(int c,Parcel p,Parcel r,int f){check(f==FLAG_ONEWAY);last=p.text;calls++;return true;}}
 static class Collector extends MonitorCollector {int scans;long generation=9;boolean missing;
 Collector(){super(new Context(),source);}int findPid(){return 42;}
 MonitorSnapshot.Task identity(int p){return missing?null:new MonitorSnapshot.Task(p,"GameThread",generation,SystemClock.now/10);}
 List<MonitorSnapshot.Task> readTasks(int p){scans++;return Arrays.asList(identity(p));}
 List<ThreadAnalysis.Process> readAnalysisTasks(ThreadAnalysis a){return Arrays.asList(new ThreadAnalysis.Process(42,generation,readTasks(42)));}}
 static long gesture; static String start(Collector c){c.command("circle",0,"");return c.command("recordStart",0,c.session.connectionEpoch+":"+c.session.targetEpoch+":"+(gesture=Math.max(gesture+1,SystemClock.now+1)));}
 public static void main(String[] args)throws Exception {
 fpsFile=java.nio.file.Files.createTempFile("measured-fps-",".txt");
 java.nio.file.Files.write(fpsFile,"fps: 59.9 duration:1000000 frame_count:60".getBytes());source=new MonitorSources(fpsFile.toString());
 Collector c=new Collector();Client client=new Client();c.register(client);c.scene("game",0,true);
 check(Handler.pending.size()==1&&c.session.interval()==5000);Handler.next();
 check(c.scans==0&&MonitorStore.starts==0&&client.last.contains("active=false")&&source.fpsReads==0);
 c.command("full",0,"");check(c.session.interval()==1000);for(int i=0;i<10;i++)Handler.next();
 check(c.scans==0&&c.state().contains("monitorDbWrites=0")&&client.last.contains("fps=59.9"));
 long reads=source.fpsReads,oldEpoch=c.session.targetEpoch;
 c.scene("other",0,true,true,"",12,true);check(c.session.targetEpoch>oldEpoch&&client.last.contains("fps=-1"));
 Handler.next();check(client.last.contains("fps=59.9"));
 for(String transientOwner:new String[]{"SystemUI","IME"}){
 c.scene("other",0,true,true,"",12,false);check(client.last.contains("UNAVAILABLE_SCENE_OWNERSHIP"));
 reads=source.fpsReads;Handler.next();check(source.fpsReads==reads&&!client.last.contains("fps=59.9"));
 c.scene("other",0,true,true,"",12,true);check(client.last.contains("fps=-1"));Handler.next();check(client.last.contains("fps=59.9"));}
 c.scene("game",0,true);
 check(c.command("arm",0,"").startsWith("ok=0"));
 check(start(c).startsWith("ok=1"));for(int i=0;i<7;i++)Handler.next();check(c.scans==3&&MonitorStore.lastFps==59.9&&MonitorStore.fpsValidity.equals("VALID"));
 c.scene("game",0,true,true,"",c.session.taskId,false);Handler.next();
 check(c.session.recording()&&MonitorStore.lastFps<0&&MonitorStore.fpsValidity.equals("UNAVAILABLE_SCENE_OWNERSHIP"));
 c.scene("game",0,true,true,"",c.session.taskId,true);
 java.nio.file.Files.write(fpsFile,"malformed".getBytes());Handler.next();
 check(MonitorStore.lastFps<0&&MonitorStore.fpsValidity.equals("UNAVAILABLE_MALFORMED_SOURCE"));
 java.nio.file.Files.write(fpsFile,"fps: 0.0 duration:1000000 frame_count:0".getBytes());Handler.next();
 check(MonitorStore.lastFps==0&&MonitorStore.fpsValidity.equals("VALID"));
 c.scene("other",0,true);check(!c.session.recording()&&MonitorStore.terminal.equals("FOREGROUND_CHANGED"));
 int scans=c.scans;c.scene("game",0,true);Handler.next();check(scans==c.scans);
 check(start(c).startsWith("ok=1"));c.generation++;Handler.next();
 check(MonitorStore.incomplete&&MonitorStore.terminal.equals("PROCESS_GENERATION_LOST"));
 // Both competing process-loss event orders use the exact recording generation.
 for(boolean sceneFirst:new boolean[]{true,false}){
  c.scene("game",0,true);check(start(c).startsWith("ok=1"));int beforeFinish=MonitorStore.finishes;
  c.missing=true;
  if(sceneFirst){c.scene("other",0,true);Handler.next();}
  else {Handler.next();c.scene("other",0,true);}
  check(MonitorStore.finishes==beforeFinish+1&&MonitorStore.incomplete&&MonitorStore.terminal.equals("PROCESS_GENERATION_LOST"));
  c.missing=false;c.scene("game",0,true);check(!c.session.recording());
 }
 check(start(c).startsWith("ok=1"));c.generation++;c.scene("other",0,true);
 check(MonitorStore.incomplete&&MonitorStore.terminal.equals("PROCESS_GENERATION_LOST"));c.scene("game",0,true);
 check(start(c).startsWith("ok=1"));c.missing=true;c.command("recordStop",0,"");
 check(!MonitorStore.incomplete&&MonitorStore.terminal.equals("EXPLICIT_STOP"));c.missing=false;
 for(boolean sceneFirst:new boolean[]{true,false}){
  check(start(c).startsWith("ok=1"));PowerManager.interactive=false;c.missing=true;
  if(sceneFirst)c.scene("other",0,true);else Handler.next();
  check(!MonitorStore.incomplete&&MonitorStore.terminal.equals("SCREEN_OFF"));
  c.missing=false;PowerManager.interactive=true;c.scene("game",0,true);
 }
 check(start(c).startsWith("ok=1"));KeyguardManager.locked=true;c.scene("other",0,true);
 check(!MonitorStore.incomplete&&MonitorStore.terminal.equals("LOCKED"));KeyguardManager.locked=false;c.scene("game",0,true);
 check(start(c).startsWith("ok=1"));IBinder.DeathRecipient staleDeath=client.death;
 Runnable staleSample=Handler.pending.get(0);staleDeath.binderDied();
 check(c.session.mode==MonitorSession.FULL&&!c.session.recording()&&Handler.pending.isEmpty());
 check(MonitorStore.incomplete&&MonitorStore.terminal.equals("CLIENT_DEATH"));
 Client replacement=new Client();c.register(replacement);c.scene("game",0,true);
 staleDeath.binderDied();staleSample.run();check(!Handler.pending.isEmpty());Handler.next();
 check(replacement.last.contains("active=true"));
 check(start(c).startsWith("ok=1"));SystemClock.now=c.session.recordingStart+1800000;Handler.next();
 check(!c.session.recording()&&MonitorStore.terminal.equals("DURATION_LIMIT")&&!MonitorStore.incomplete);
 check(start(c).startsWith("ok=1"));c.command("recordStop",0,"");int finishes=MonitorStore.finishes;
 c.command("recordStop",0,"");check(MonitorStore.finishes==finishes);
 check(start(c).startsWith("ok=1"));c.scene("game",0,false);
 check(!c.session.recording()&&Handler.pending.isEmpty()&&c.session.mode==MonitorSession.FULL&&replacement.last.contains("fps=-1"));
 c.scene("game",0,true);Handler.next();check(!c.session.recording());
 for(int i=0;i<100;i++){c.command("off",0,"");Handler.next();c.command("full",0,"");Handler.next();}
 c.command("off",0,"");check(c.session.interval()==5000&&HandlerThread.active==1);reads=source.fpsReads;Handler.next();check(source.fpsReads==reads);
 int workerCount=HandlerThread.created,callbacksBefore=replacement.calls;
 for(int i=0;i<50;i++){c.scene("off"+i,0,true,false,"",i,i%2==0);}
 check(HandlerThread.created==workerCount&&replacement.calls==callbacksBefore&&Handler.pending.size()==1);
 c.command("full",0,"");workerCount=HandlerThread.created;
 for(int i=0;i<50;i++){c.scene("on"+i,0,true,true,"",i,i%2==0);Handler.next();}
 check(HandlerThread.created==workerCount&&Handler.pending.size()==1);c.command("off",0,"");
 c.register(null);check(Handler.pending.isEmpty()&&HandlerThread.active==0);
 check(new Collector().session.mode==MonitorSession.OFF);
 c.register(new Client());c.scene("game",0,true);c.command("full",0,"");
 check(start(c).startsWith("ok=1"));c.command("permissionLost",0,"");
 check(!c.session.recording()&&c.session.mode==MonitorSession.FULL&&!c.session.visible()&&c.session.interval()==5000);
 c.command("permissionGranted",0,"");check(c.session.visible()&&!c.session.recording());
 c.register(null);
 // Every terminal caller must contain SQLite/close failure and recover scalar service.
 for(String terminal:new String[]{"scene","stop","source","screen","lock","replace","death","permission","disable","deadline"}){
  Client faultClient=new Client();c.register(faultClient);c.scene("game",0,true);
  c.command("permissionGranted",0,"");c.command("off",0,"");c.command("full",0,"");check(start(c).startsWith("ok=1"));
  MonitorStore.failFinish=true;MonitorStore.failClose=true;
  if(terminal.equals("scene"))c.scene("other",0,true);
  if(terminal.equals("stop"))c.command("recordStop",0,"");
  if(terminal.equals("source")){Context.failBattery=true;Handler.next();Context.failBattery=false;}
  if(terminal.equals("screen")){PowerManager.interactive=false;Handler.next();PowerManager.interactive=true;}
  if(terminal.equals("lock")){KeyguardManager.locked=true;c.scene("other",0,true);KeyguardManager.locked=false;}
  if(terminal.equals("replace"))c.register(new Client());
  if(terminal.equals("death"))faultClient.death.binderDied();
  if(terminal.equals("permission"))c.command("permissionLost",0,"");
  if(terminal.equals("disable"))c.command("off",0,"");
  if(terminal.equals("deadline")){SystemClock.now=c.session.recordingStart+1800000;Handler.next();}
  check(!c.session.recording()&&c.state().contains("monitorFinalizeError=record_finalize:"));
  MonitorStore.failFinish=MonitorStore.failClose=false;c.register(new Client());c.scene("game",0,true);Handler.next();
  check(c.state().contains("monitorFinalizeError=record_finalize:"));c.register(null);
 }
 Collector multi=new Collector();Client owner=new Client(),secondary=new Client();
 multi.register(owner,0);multi.scene("owner.app",0,true);multi.command("full",0,"");
 check(multi.command("off",10,"").contains("inactive_user")&&multi.session.mode==MonitorSession.FULL);
 multi.register(secondary,10);check(secondary.calls==0&&multi.session.mode==MonitorSession.FULL);
 check(!multi.command("state",10,"").contains("owner.app"));
 multi.scene("secondary.app",10,true);check(multi.session.mode==MonitorSession.OFF);
 check(!secondary.last.contains("owner.app")&&owner.last.contains("active=false"));
 multi.command("fps",10,"");int secondaryCalls=secondary.calls;
 multi.scene("owner.app",0,true);check(multi.session.mode==MonitorSession.FULL);
 check(!secondary.last.contains("owner.app")&&secondary.calls>=secondaryCalls);
 multi.unregister(secondary);check(multi.command("state",0,"").contains("owner.app"));
 multi.register(null);check(HandlerThread.active==0);
 System.out.println("MONITOR_CROSS_USER_REGISTRATION_COMMAND_SNAPSHOT_DESIRED=PASS");
 java.nio.file.Path thermal=java.nio.file.Files.createTempDirectory("quiet-host-");
 java.nio.file.Path zone=java.nio.file.Files.createDirectory(thermal.resolve("thermal_zone987"));
 java.nio.file.Files.write(zone.resolve("type"),"quiet-therm".getBytes());
 java.nio.file.Files.write(zone.resolve("temp"),"42000".getBytes());
 MonitorSources quiet=new MonitorSources(fpsFile.toString(),thermal.toFile());check(quiet.quiet()==42);
 java.nio.file.Files.delete(zone.resolve("temp"));check(quiet.quiet()<0);long attempts=quiet.scalarReads;
 java.nio.file.Files.write(zone.resolve("temp"),"41000".getBytes());for(int i=0;i<20;i++)check(quiet.quiet()<0);
 check(quiet.scalarReads==attempts);quiet.resetQuiet();check(quiet.quiet()==41&&quiet.discoveryReads==1);
 java.nio.file.Files.delete(zone.resolve("temp"));java.nio.file.Files.delete(zone.resolve("type"));java.nio.file.Files.delete(zone);java.nio.file.Files.delete(thermal);
 java.nio.file.Files.delete(fpsFile);
 System.out.println("MONITOR_SHARED_DEMAND_EPOCH_RECONNECT_TERMINALS_NO_RESUME_ZERO_IDLE_WRITES_SCANS=PASS");}}
'''
})
stubs['com/zui/server/control/CollectorTest.java']=stubs['com/zui/server/control/CollectorTest.java'].replace('"game"','"org.game"').replace('"other"','"org.other"')
stubs['android/os/HandlerThread.java']=stubs['android/os/HandlerThread.java'].replace('public static int active;', 'public static int active,created;').replace('public void start(){active++;','public void start(){created++;active++;')
with tempfile.TemporaryDirectory(prefix='zui-monitor-r4-') as tmp:
 out=Path(tmp);files=[]
 for name,text in stubs.items():
  p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
 production=['MonitorCollector.java','MonitorSources.java','MonitorSnapshot.java','MonitorSession.java','MonitorLifecycle.java','ThreadAnalysis.java','PolicyJson.java']
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(src/n) for n in production],str(Path(__file__).with_name('MonitorTest.java')),str(Path(__file__).with_name('MonitorR4Test.java'))],check=True)
 for test in ('MonitorTest','MonitorR4Test','CollectorTest'):subprocess.run(['java','-cp',tmp,'com.zui.server.control.'+test],check=True)
collector=(src/'MonitorCollector.java').read_text(encoding='utf8')
sources=(src/'MonitorSources.java').read_text(encoding='utf8')
for forbidden in ('kgsl','gpu_busy','/proc/stat','ProcessBuilder','Runtime.getRuntime','setAffinity','renice'):
 assert forbidden not in collector+sources,forbidden
assert collector.count('postDelayed(')==1 and 'if(capture)' in collector
assert 'FPS_PATH = "/sys/class/drm/sde-crtc-0/measured_fps"' in sources and 'session.interval()' in collector
service=(src/'ZuiControlService.java').read_text(encoding='utf8')
assert '!mRawFocusTransient && !mImeVisible' in service and 'pkg.equals(mRawFocusedPackage)' in service
for forbidden in ('TaskFpsCallback','registerTaskFps','1013','getRefreshRate','displayHz'):
 assert forbidden not in collector+sources,forbidden
store=(src/'MonitorStore.java').read_text(encoding='utf8')
assert 'valid(fps)' in store and '"fps="+fpsValidity+";fpsSource=DISPLAY_MEASURED_FPS"' in store
ui=(root/'app/src/main/java/com/zui/zuicontrol/PerformanceMonitor.kt').read_text(encoding='utf8')
assert 'if (previous != metrics || shapeChanged)' in ui and 'if (stateChanged) invalidate()' in ui
print('MONITOR_R4_STATIC_SINGLE_PIPELINE=PASS')
