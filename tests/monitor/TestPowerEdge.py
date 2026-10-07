"""Actual collector edge/shape code with counted battery, clock and store boundaries."""
from pathlib import Path
import subprocess,sys,tempfile
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
namespace={'__file__':str(HERE/'TestMonitor.py')}
text=(HERE/'TestMonitor.py').read_text(encoding='utf8')
exec(compile(text[:text.index('with tempfile.TemporaryDirectory(')],str(HERE/'TestMonitor.py'),'exec'),namespace)
stubs=namespace['stubs'];stubs.pop('com/zui/server/control/CollectorTest.java')
stubs['android/content/Intent.java']='''package android.content;public class Intent {
 public static final String ACTION_BATTERY_CHANGED="battery";public static int plugged=0,status=3,voltage=4000;
 public int getIntExtra(String key,int d){return key.equals("plugged")?plugged:key.equals("status")?status:voltage;}}
'''
stubs['android/os/BatteryManager.java']=stubs['android/os/BatteryManager.java'].replace(
 'public long getLongProperty(int n){return -2000000;}',
 'public static long currentUa=-2000000;public long getLongProperty(int n){return currentUa;}')
stubs['android/os/Handler.java']='''package android.os;import java.util.*;public class Handler {
 public static final List<Runnable> pending=new ArrayList<>();public static final List<Long> delays=new ArrayList<>();
 public static final List<Long> due=new ArrayList<>();
 public Handler(Object o){}public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long d){pending.add(r);delays.add(d);due.add(SystemClock.now+d);return true;}
 public void removeCallbacksAndMessages(Object o){pending.clear();delays.clear();due.clear();}
 public static long next(){long d=delays.remove(0);SystemClock.now=Math.max(SystemClock.now,due.remove(0));pending.remove(0).run();return d;}}
'''
stubs['com/zui/server/control/PowerEdgeTest.java']='''package com.zui.server.control;
import android.os.*;import android.content.*;import java.util.*;
public class PowerEdgeTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Client implements IBinder {String last="";int calls;
 public void linkToDeath(DeathRecipient d,int f){}public boolean unlinkToDeath(DeathRecipient d,int f){return true;}
 public boolean transact(int c,Parcel p,Parcel r,int f){calls++;last=p.text;return true;}}
 static long metric(MonitorCollector c,String key){for(String row:c.state().split("\\n"))if(row.startsWith(key+"="))return Long.parseLong(row.substring(key.length()+1));throw new AssertionError(key);}
 static Map<String,Object> snapshot(MonitorCollector c)throws Exception{return PolicyJson.object(PolicyJson.parse(c.snapshot().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
 static double watts(MonitorCollector c)throws Exception{return ((Number)snapshot(c).get("powerW")).doubleValue();}
 static void settled(boolean connected){Intent.plugged=connected?1:0;Intent.status=connected?2:3;BatteryManager.currentUa=-2000000;}
 static void event(MonitorCollector c){c.onBatteryStateChanged(new Intent());}
 static MonitorCollector begin(boolean full,Client client)throws Exception {
  SystemClock.now=100;settled(false);MonitorCollector c=new MonitorCollector(new Context());
  c.register(client);c.scene("org.example.app",0,true);if(full)c.command("full",0,"");Handler.next();return c;
 }
 static void delayed(boolean full,boolean connected,long delay)throws Exception {
  Client client=new Client();MonitorCollector c=begin(full,client);long start=SystemClock.now;
  settled(!connected);c.invalidatePower(connected);check(watts(c)<0,"immediate invalidation");
  Handler.next();check(watts(c)<0&&Handler.delays.get(0)==400,"stale edge unavailable");
  Runnable stale=Handler.pending.get(0);long samples=metric(c,"monitorSamples");
  event(c);check(metric(c,"monitorSamples")==samples&&Handler.pending.get(0)==stale,"unsettled noise ignored");
  while(Handler.due.get(0)<start+delay)Handler.next();
  SystemClock.now=start+delay;settled(connected);event(c);
  check(Handler.pending.size()==1&&Handler.delays.get(0)==0,"late fact replaces existing cadence");
  Runnable wake=Handler.pending.get(0);int callbacks=client.calls;event(c);
  check(Handler.pending.get(0)==wake&&client.calls==callbacks,"duplicate coalesced before sample");
  Handler.next();check(SystemClock.now==start+delay,"authoritative fact extra wait zero");
  check(connected?watts(c)<0:watts(c)==8,"existing strict source");
  check(Handler.delays.get(0)==(full?1000:5000),"normal cadence restored");
  samples=metric(c,"monitorSamples");event(c);stale.run();
  check(metric(c,"monitorSamples")==samples&&Handler.delays.get(0)==(full?1000:5000),"completed duplicate and canceled epoch ignored");
  check(metric(c,"monitorThreadEnumerations")==0&&metric(c,"monitorDbWrites")==0,"no event scan/write");
  check(HandlerThread.active==1,"one worker");c.unregister(client);check(HandlerThread.active==0,"retired");
  System.out.println("EVENT_CASE=PASS mode="+(full?"FULL":"OFF")+" connected="+connected+" settleMs="+delay+" EXTRA_WAIT=0");
 }
 static class RecordingCollector extends MonitorCollector {
  int scans;RecordingCollector(){super(new Context());}
  int findPid(){return 42;}MonitorSnapshot.Task identity(int p){return new MonitorSnapshot.Task(p,"GameThread",9,SystemClock.now/10);}
  List<MonitorSnapshot.Task> readTasks(int p){scans++;return Arrays.asList(identity(p));}
  List<ThreadAnalysis.Process> readAnalysisTasks(ThreadAnalysis a){return Arrays.asList(new ThreadAnalysis.Process(42,9,readTasks(42)));}
 }
 static void shape(MonitorCollector c)throws Exception {
  Map<String,Object> before=snapshot(c);Runnable pending=Handler.pending.get(0);long due=Handler.due.get(0);
  String state=c.state();SystemClock.now+=50;
  for(String action:new String[]{"circle","bar"}){
   check(c.command(action,0,"").startsWith("ok=1"),"shape accepted");
   Map<String,Object> after=snapshot(c);check(after.get("circle").equals(action.equals("circle")),"shape metadata");
   after.put("circle",before.get("circle"));check(after.equals(before),"all scalar age/validity/record fields preserved");
   check(c.state().equals(state)&&Handler.pending.get(0)==pending&&Handler.due.get(0)==due,"same samples/reads/DB/scan/worker/deadline");
  }
 }
 public static void main(String[] args)throws Exception {
  for(boolean full:new boolean[]{false,true})for(boolean connected:new boolean[]{false,true})
   for(long delay:new long[]{200,600,2500})delayed(full,connected,delay);
  Client client=new Client();MonitorCollector c=begin(false,client);
  BatteryManager.currentUa=0;c.invalidatePower(false);Handler.next();
  check(watts(c)<0&&Handler.delays.get(0)==400,"invalid current retains edge");
  SystemClock.now+=600;BatteryManager.currentUa=-2000000;event(c);check(Handler.delays.get(0)==0,"same tuple current-only wake");
  Handler.next();check(watts(c)==8&&Handler.delays.get(0)==5000,"same tuple validity qualified");
  settled(false);c.invalidatePower(true);Handler.next();long start=SystemClock.now;
  for(int i=0;i<5;i++)check(Handler.next()==400,"bounded confirmation");
  check(SystemClock.now==start+2000&&Handler.delays.get(0)==5000&&watts(c)<0,"deadline restores normal OFF");
  Handler.next();check(Handler.delays.get(0)==5000,"no perpetual edge clock");
  c.command("full",0,"");Handler.next();shape(c);
  settled(true);c.invalidatePower(false);Runnable older=Handler.pending.get(0);
  c.invalidatePower(true);Handler.next();long samples=metric(c,"monitorSamples");older.run();
  check(metric(c,"monitorSamples")==samples&&Handler.delays.get(0)==1000,"opposite edge replacement");
  c.invalidatePower(false);Handler.next();c.scene("org.example.app",0,false);settled(false);event(c);
  check(Handler.pending.isEmpty()&&HandlerThread.active==0,"screen ineligible has no scalar wake");
  c.scene("org.example.app",0,true);Handler.next();check(watts(c)==8,"screen resume source");
  settled(true);c.invalidatePower(false);Handler.next();Client replacement=new Client();c.register(replacement);
  int oldCalls=client.calls;settled(false);event(c);Handler.next();check(client.calls==oldCalls&&watts(c)==8,"replacement client only");
  c.armEdgeTrace(1);c.invalidatePower(false);Handler.next();
  String facts=c.edgeTrace();check(facts.contains("ACTION_POWER_DISCONNECTED")&&facts.contains("factSource=STICKY_BATTERY_AT_POWER_EVENT plugged=0 batteryStatus=3 voltageMv=4000 currentUa=-2000000")&&facts.contains("SAMPLE_START")&&facts.contains("SAMPLE_END")&&facts.contains("powerValidity=VALID powerW=8.0")&&facts.contains("SCHEDULE delayMs=1000"),"actual edge/sample/schedule facts");
  c.armEdgeTrace(1);for(int i=0;i<600;i++)c.traceUnlock(true,false);
  String trace=c.edgeTrace();check(trace.contains("monitorEdgeTrace[511]=")&&!trace.contains("monitorEdgeTrace[512]=")&&trace.contains("monitorEdgeTraceDropped=89"),"bounded memory");
  SystemClock.now+=1000;c.traceUnlock(false,true);check(c.edgeTrace().contains("monitorEdgeTraceActive=false")&&c.edgeTrace().contains("monitorEdgeTraceDropped=89"),"trace finite");
  for(int seconds:new int[]{0,91}){boolean denied=false;try{c.armEdgeTrace(seconds);}catch(IllegalArgumentException e){denied=true;}check(denied,"invalid duration");}
  c.unregister(replacement);check(HandlerThread.active==0,"replacement retired");
  RecordingCollector recording=new RecordingCollector();client=new Client();SystemClock.now=100;settled(true);
  recording.register(client);recording.scene("org.example.app",0,true);recording.command("full",0,"");recording.command("circle",0,"");
  check(recording.command("recordStart",0,recording.session.connectionEpoch+":"+recording.session.targetEpoch+":101").startsWith("ok=1"),"existing gesture start");Handler.next();
  shape(recording);recording.invalidatePower(false);Handler.next();Handler.next();long writes=metric(recording,"monitorDbWrites");int scans=recording.scans;
  SystemClock.now=900;settled(false);event(recording);check(metric(recording,"monitorDbWrites")==writes&&recording.scans==scans,"event itself never writes/scans");
  Handler.next();check(watts(recording)==8&&recording.scans==scans&&metric(recording,"monitorDbWrites")==writes+1,"shared Recording scalar row/scan deadline");
  recording.command("recordStop",0,"");recording.command("off",0,"");recording.unregister(client);check(HandlerThread.active==0,"record worker retired");
  System.out.println("POWER_EDGE_SHAPE=PASS bounded2s/late-current/OFF/FULL/duplicates/opposite/screen/client/recording/age/validity/no-extra-scans/bounded-trace; PHYSICAL_MEASUREMENT=NOT_CLAIMED");
 }
}'''
# Preserve shared Android stubs; only this focused snapshot fixture uses the real codec.
stubs['com/zui/server/control/SnapshotJson.java']='''package com.zui.server.control;
public class SnapshotJson {public static java.util.Map<String,Object> parse(String text)throws Exception{
 return PolicyJson.object(PolicyJson.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
 public static String encode(Object value){return PolicyJson.encode(value);}}
'''
stubs['org/json/JSONObject.java']='''package org.json;import java.util.*;import com.zui.server.control.SnapshotJson;
public class JSONObject {public static final Object NULL=new Object();Map<String,Object> data=new LinkedHashMap<>();
 public JSONObject(){}public JSONObject(String text)throws JSONException {try{data.putAll(SnapshotJson.parse(text));}catch(Exception e){throw new JSONException();}}
 public JSONObject put(String k,Object v)throws JSONException{data.put(k,v==NULL?null:v);return this;}
 public String toString(){return SnapshotJson.encode(data);}}
'''
with tempfile.TemporaryDirectory(prefix='monitor-power-edge-') as tmp:
 files=[]
 for name,text in stubs.items():
  p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
 production=['MonitorCollector.java','MonitorSources.java','MonitorSnapshot.java','MonitorSession.java','MonitorLifecycle.java','ThreadAnalysis.java','PolicyJson.java']
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(namespace['src']/n) for n in production]],check=True)
 subprocess.run(['java','-cp',tmp,'com.zui.server.control.PowerEdgeTest'],check=True)
service=(namespace['src']/'ZuiControlService.java').read_text(encoding='utf8')
assert 'filter.addAction(Intent.ACTION_BATTERY_CHANGED)' in service
route=service.split('} else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {',1)[1].split('} else',1)[0]
assert 'mMonitor.onBatteryStateChanged(intent)' in route
assert all(x not in route for x in ('new MonitorCollector','postDelayed','getLongProperty','BatteryManager.EXTRA_LEVEL'))
print('POWER_BATTERY_EVENT_EXISTING_FRAMEWORK_ROUTE=PASS; PHYSICAL_MEASUREMENT=NOT_CLAIMED')

# Execute exact Service methods with the actual collector and actual policy State/Row.
def service_method(signature):
 start=service.index(signature); a=service.index('{',start); depth=1; b=a+1
 while depth:
  depth+=(service[b]=='{')-(service[b]=='}'); b+=1
 return service[start:b]
methods='\n'.join(service_method(s) for s in [
 'private synchronized void refreshMonitor()',
 'private synchronized void cancelMonitorUnlockRecheck()',
 'private synchronized void beginMonitorUnlockRecheck()',
 'private synchronized void onScreenInteractiveChanged(boolean interactive)',
 'private synchronized String controlsSnapshot()',
 'private java.util.List<Object> appPolicyRows(',
 'private static String sha256('])
user_present=service.split('} else if (Intent.ACTION_USER_PRESENT.equals(action)) {',1)[1].split('\n                    }',1)[0].strip()
fixture='''package com.zui.server.control;
import android.os.*;import android.content.*;import android.app.*;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
public class ZuiControlService {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Worker {
  List<Runnable> pending=new ArrayList<>();List<Long> due=new ArrayList<>();
  void postDelayed(Runnable r,long delay){check(delay>0&&delay<=150,"bounded worker delay");pending.add(r);due.add(SystemClock.now+delay);}
  void removeCallbacks(Runnable r){for(int i=pending.size()-1;i>=0;i--)if(pending.get(i)==r){pending.remove(i);due.remove(i);}}
  void next(){SystemClock.now=Math.max(SystemClock.now,due.remove(0));pending.remove(0).run();}
 }
 static class ContextFixture extends Context {
  boolean nullPower,nullLock;
  public <T>T getSystemService(Class<T> c){if(c==PowerManager.class&&nullPower||c==KeyguardManager.class&&nullLock)return null;return super.getSystemService(c);}
 }
 static class Top {String pkg="org.example.app";int user;String stablePackage(){return pkg;}int stableUserId(){return user;}}
 static class SystemProperties {static boolean getBoolean(String p,boolean d){return d;}}
 static class Display {static final int DEFAULT_DISPLAY=0;}
 static class Uperf {int edges;String mDesiredMode="performance";void onInteractiveChanged(boolean i,String reason,long time){edges++;}}
 static final String PROP_GLOBAL_DISABLE="disabled";
 ContextFixture mContext=new ContextFixture();Top mTopResumedState=new Top();
 MonitorCollector mMonitor=new MonitorCollector(mContext);Worker mWorker=new Worker();Uperf mUperfScenePolicy=new Uperf();
 boolean mScreenInteractive=true,mRawFocusTransient=false,mImeVisible=false,mPolicyReady=true;
 long mMonitorUnlockEpoch;Runnable mMonitorUnlockRecheck;
 int mMonitorTaskId=7,mRawFocusedUserId=0,mRawFocusedDisplayId=0,mCurrentUserId=0;
 String mRawFocusedPackage="org.example.app";
 AppPolicyStore mAppPolicies;String editablePackage="home";int publishes;
 String monitorHome(int u){return "home";}boolean isTransientPackage(String p){return false;}
 void publishState(){publishes++;}
 String editableScenePackage(){return editablePackage;}
 String currentSceneState(){return "editableDisplayHz="+(editablePackage.equals("self")?90:editablePackage.equals("calc")?165:editablePackage.equals("home")?120:60);}
 static String key(int u,String p){return AppPolicyStore.key(u,p);}
 METHODS
 void actualUserPresent(){ROUTE}
 public static void main(String[] args)throws Exception {
  SystemClock.now=100;KeyguardManager.locked=true;PowerManager.interactive=true;
  ZuiControlService s=new ZuiControlService();PowerEdgeTest.Client client=new PowerEdgeTest.Client();s.mMonitor.register(client);
  s.actualUserPresent();check(!s.mMonitor.session.eligible&&Handler.pending.isEmpty()&&s.mWorker.pending.size()==1,"early USER_PRESENT locked");
  for(int i=0;i<3;i++)s.mWorker.next();
  SystemClock.now=700;KeyguardManager.locked=false;s.mWorker.next();
  check(s.mWorker.pending.isEmpty()&&Handler.pending.size()==1&&Handler.delays.get(0)==0,"eligible schedules existing scalar immediately");
  check(s.mScreenInteractive&&s.mUperfScenePolicy.edges==0,"unlock does not rewrite screen/Uperf authority");
  Handler.next();check(SystemClock.now==700&&PowerEdgeTest.watts(s.mMonitor)==8&&Handler.delays.get(0)==5000,"actual collector extra wait zero");
  check(!s.mMonitor.session.recording()&&PowerEdgeTest.metric(s.mMonitor,"monitorDbWrites")==0,"never resume Recording");
  s.mMonitor.unregister(client);
  // Persistent keyguard state ends at 2s with no worker leak.
  KeyguardManager.locked=true;SystemClock.now=100;s=new ZuiControlService();s.actualUserPresent();
  while(!s.mWorker.pending.isEmpty())s.mWorker.next();
  check(SystemClock.now==2100&&s.mMonitorUnlockRecheck==null&&Handler.pending.isEmpty(),"finite unlock deadline");
  // Screen-off cancels callbacks and stale epoch, without changing Recording authority.
  SystemClock.now=100;s=new ZuiControlService();s.actualUserPresent();Runnable stale=s.mWorker.pending.get(0);
  s.onScreenInteractiveChanged(false);KeyguardManager.locked=false;stale.run();
  check(s.mWorker.pending.isEmpty()&&!s.mMonitor.session.eligible&&Handler.pending.isEmpty(),"screen-off cancels old eligibility");
  check(s.mUperfScenePolicy.edges==1&&s.publishes==1,"original screen/Uperf publish preserved");
  // A duplicate SCREEN_ON creates a new bounded epoch but no duplicate callback.
  KeyguardManager.locked=true;PowerManager.interactive=true;SystemClock.now=100;s=new ZuiControlService();s.actualUserPresent();stale=s.mWorker.pending.get(0);
  long epoch=s.mMonitorUnlockEpoch;s.onScreenInteractiveChanged(true);Runnable fresh=s.mWorker.pending.get(0);stale.run();
  check(s.mMonitorUnlockEpoch>epoch&&s.mWorker.pending.size()==1&&s.mWorker.pending.get(0)==fresh&&s.mUperfScenePolicy.edges==0,"duplicate SCREEN_ON epoch coalescing");
  s.onScreenInteractiveChanged(false);
  // Real PowerManager and keyguard absence remain fail-closed.
  s=new ZuiControlService();s.mContext.nullPower=true;s.refreshMonitor();check(!s.mMonitor.session.eligible,"null power");
  s.mContext.nullPower=false;s.mContext.nullLock=true;s.refreshMonitor();check(!s.mMonitor.session.eligible,"null lock");
  // Use the actual policy store State/global implementation for scene-independent values.
  s=new ZuiControlService();s.mAppPolicies=new AppPolicyStore(new AppPolicyStore.Storage(){public byte[] read(String n){return new byte[0];}public void write(String n,byte[] b){}});
  AppPolicyStore.State state=new AppPolicyStore.State();s.mAppPolicies.current=state;
  state.globals.put(0,new AppPolicyStore.Row(144,"performance",422,903));
  state.globals.put(10,new AppPolicyStore.Row(90,"balance",231,629));
  state.apps.put(key(0,"self"),new AppPolicyStore.Row(90,"balance",231,629));
  state.apps.put(key(0,"calc"),new AppPolicyStore.Row(165,"fast",629,903));
  for(String pkg:new String[]{"self","calc","home","unconfigured"}){
   s.editablePackage=pkg;String value=s.controlsSnapshot();check(value.contains("savedGlobalRefresh=144")&&value.contains("savedGlobalUperf=performance"),"Global authority in "+pkg);
  }
  s.mCurrentUserId=10;check(s.controlsSnapshot().contains("savedGlobalRefresh=90"),"user10 authority");
  s.mPolicyReady=false;check(s.controlsSnapshot().contains("savedGlobalRefresh=0"),"policy not ready");
  s.mPolicyReady=true;s.mAppPolicies.current=null;check(s.controlsSnapshot().contains("savedGlobalRefresh=0"),"null state");
  s.mAppPolicies=null;check(s.controlsSnapshot().contains("savedGlobalRefresh=0"),"null store");
  System.out.println("ACTUAL_SERVICE_UNLOCK_GLOBAL=PASS eligible-to-existing-scalar-wait0/finite2s/OFF/new-epoch/no-Recording-resume/fail-closed/Global144-four-scenes/user10-90/notready-null0; PHYSICAL_MEASUREMENT=NOT_CLAIMED");
 }
}'''.replace('\n METHODS\n','\n'+methods+'\n').replace('actualUserPresent(){ROUTE}','actualUserPresent(){'+user_present+'}')
stubs['com/zui/server/control/ZuiControlService.java']=fixture
stubs['android/os/SystemClock.java']=stubs['android/os/SystemClock.java'].replace(
 'public static long elapsedRealtime(){return now;}','public static long elapsedRealtime(){return now;}public static long elapsedRealtimeNanos(){return now*1000000;}')
with tempfile.TemporaryDirectory(prefix='service-unlock-global-') as tmp:
 files=[]
 for name,text in stubs.items():
  p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
 production+=['AppPolicyStore.java','GpuRange.java','SettingsBackup.java','UperfConfigStore.java']
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(namespace['src']/n) for n in production]],check=True)
 subprocess.run(['java','-cp',tmp,'com.zui.server.control.ZuiControlService'],check=True)
