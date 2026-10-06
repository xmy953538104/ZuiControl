"""Real collector power-edge scheduling with fake sticky battery/clock boundaries."""
from pathlib import Path
import subprocess,sys,tempfile
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
namespace={'__file__':str(HERE/'TestMonitor.py')}
text=(HERE/'TestMonitor.py').read_text(encoding='utf8')
exec(compile(text[:text.index('with tempfile.TemporaryDirectory(')],str(HERE/'TestMonitor.py'),'exec'),namespace)
stubs=namespace['stubs'];stubs.pop('com/zui/server/control/CollectorTest.java')
stubs['android/content/Intent.java']='''package android.content;public class Intent {
 public static final String ACTION_BATTERY_CHANGED="battery";public static int plugged=0,status=3;
 public int getIntExtra(String key,int d){return key.equals("plugged")?plugged:key.equals("status")?status:4000;}}
'''
stubs['android/os/Handler.java']='''package android.os;import java.util.*;public class Handler {
 public static final List<Runnable> pending=new ArrayList<>();public static final List<Long> delays=new ArrayList<>();
 public static final List<Long> due=new ArrayList<>();
 public Handler(Object o){}public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long d){pending.add(r);delays.add(d);due.add(SystemClock.now+d);return true;}
 public void removeCallbacksAndMessages(Object o){pending.clear();delays.clear();due.clear();}
 public static long next(){long d=delays.remove(0);SystemClock.now=Math.max(SystemClock.now,due.remove(0));pending.remove(0).run();return d;}}
'''
stubs['com/zui/server/control/PowerEdgeTest.java']='''package com.zui.server.control;
import android.os.*;import android.content.*;
public class PowerEdgeTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Client implements IBinder {String last="";
 public void linkToDeath(DeathRecipient d,int f){}public boolean unlinkToDeath(DeathRecipient d,int f){return true;}
 int calls;public boolean transact(int c,Parcel p,Parcel r,int f){calls++;last=p.text;return true;}}
 static long metric(MonitorCollector c,String key){String s=c.state();for(String row:s.split("\\n"))if(row.startsWith(key+"="))return Long.parseLong(row.substring(key.length()+1));throw new AssertionError(key);}
 static void settled(boolean connected){Intent.plugged=connected?1:0;Intent.status=connected?2:3;}
 static void event(MonitorCollector c){c.onBatteryStateChanged(Intent.plugged,Intent.status);}
 static void delayed(boolean full,boolean connected,long delay)throws Exception {
  MonitorCollector c=new MonitorCollector(new Context());Client client=new Client();
  SystemClock.now=100;settled(false);c.register(client);c.scene("org.example.app",0,true);if(full)c.command("full",0,"");Handler.next();
  long start=SystemClock.now;settled(!connected);c.invalidatePower(connected);
  check(client.last.contains("powerW=-1"),"immediate invalidation");Handler.next();
  check(client.last.contains("powerW=-1.0")&&Handler.delays.get(0)==400,"stale edge unavailable");
  Runnable stale=Handler.pending.get(0);long samples=metric(c,"monitorSamples");
  // A level/current-only broadcast carrying unchanged power facts is ignored.
  event(c);check(metric(c,"monitorSamples")==samples&&Handler.delays.get(0)==400,"level noise");
  if(delay>400){Handler.next();check(Handler.delays.get(0)==(full?1000:5000),"one bounded fallback");}
  SystemClock.now=start+delay;settled(connected);event(c);
  check(Handler.pending.size()==1&&Handler.delays.get(0)==0,"settled event replaces normal cadence");
  Runnable wake=Handler.pending.get(0);int callbacks=client.calls;event(c);
  check(Handler.pending.get(0)==wake&&client.calls==callbacks,"duplicate event coalesced before sample");
  Handler.next();check(SystemClock.now==start+delay,"extra normal cadence wait must be zero");
  check(client.last.contains(connected?"powerW=-1.0":"powerW=8.0"),"authoritative sticky facts");
  check(Handler.delays.get(0)==(full?1000:5000),"normal cadence restored");
  samples=metric(c,"monitorSamples");event(c);check(metric(c,"monitorSamples")==samples&&Handler.delays.get(0)==(full?1000:5000),"duplicate after completion");
  stale.run();check(metric(c,"monitorSamples")==samples,"canceled sample epoch");
  check(metric(c,"monitorThreadEnumerations")==0&&metric(c,"monitorDbWrites")==0,"edge never scans/writes");
  check(HandlerThread.active==1,"one worker");c.unregister(client);check(HandlerThread.active==0,"worker retired");
  System.out.println("EVENT_CASE=PASS mode="+(full?"FULL":"OFF")+" connected="+connected+" settleMs="+delay+" EXTRA_WAIT=0");
 }
 static class RecordingCollector extends MonitorCollector {
  int scans;RecordingCollector(){super(new Context());}
  int findPid(){return 42;}MonitorSnapshot.Task identity(int p){return new MonitorSnapshot.Task(p,"GameThread",9,SystemClock.now/10);}
  java.util.List<MonitorSnapshot.Task> readTasks(int p){scans++;return java.util.Arrays.asList(identity(p));}
  java.util.List<ThreadAnalysis.Process> readAnalysisTasks(ThreadAnalysis a){return java.util.Arrays.asList(new ThreadAnalysis.Process(42,9,readTasks(42)));}
 }
 public static void main(String[] a)throws Exception {
 MonitorCollector c=new MonitorCollector(new Context());Client client=new Client();
 c.register(client);c.scene("org.example.app",0,true);SystemClock.now=100;
 Handler.next();check(client.last.contains("powerW=8.0"),"initial discharge");
 for(boolean connected:new boolean[]{true,false}){
  // Sticky still describes the opposite state: never expose its old power.
  Intent.plugged=connected?0:1;Intent.status=connected?3:2;
  c.invalidatePower(connected);check(Handler.next()==0,"immediate edge sample");
  check(client.last.contains("powerW=-1.0"),"old watts after edge");
  check(Handler.delays.get(0)==400,"one confirmation scheduled");
  Intent.plugged=connected?1:0;Intent.status=connected?2:3;
  check(Handler.next()==400,"confirmation delay");
  check(client.last.contains(connected?"powerW=-1.0":"powerW=8.0"),"settled direction");
  check(Handler.delays.get(0)==5000,"restore OFF cadence");
 }
 // Persistent mismatch gets no recurring fast clock, and no fake valid value.
 c.invalidatePower(true);Handler.next();Handler.next();
 check(Handler.delays.get(0)==5000&&client.last.contains("powerW=-1.0"),"bounded confirmation");
 Handler.next();check(Handler.delays.get(0)==5000,"no second confirmation");
 Intent.plugged=1;Intent.status=2;c.invalidatePower(true);Handler.next();
 check(Handler.delays.get(0)==5000,"already-settled zero confirmation");
 c.command("full",0,"");Handler.next();check(Handler.delays.get(0)==1000,"FULL cadence");
 c.invalidatePower(false);Handler.next();check(Handler.delays.get(0)==400,"FULL edge");
 // Newer edge cancels an older queued confirmation.
 c.invalidatePower(true);Handler.next();check(Handler.pending.size()==1&&Handler.delays.get(0)==1000,"replace edge");
 check(c.state().contains("monitorThreadEnumerations=0")&&c.state().contains("monitorDbWrites=0"),"no scan or record writes");
 check(HandlerThread.active==1,"single worker");c.unregister(client);check(HandlerThread.active==0,"retire");
 for(boolean full:new boolean[]{false,true})for(boolean connected:new boolean[]{false,true})for(long delay:new long[]{200,800})delayed(full,connected,delay);
 // Opposite edges, screen eligibility, and client replacement keep the same pending authority.
 c=new MonitorCollector(new Context());client=new Client();settled(true);c.register(client);c.scene("org.example.app",0,true);Handler.next();
 c.invalidatePower(false);Handler.next();c.invalidatePower(true);Handler.next();
 event(c);check(Handler.delays.get(0)==5000,"new opposite edge already settled");
 c.invalidatePower(false);Handler.next();c.scene("org.example.app",0,false);settled(false);event(c);
 check(Handler.pending.isEmpty()&&HandlerThread.active==0,"no scalar worker while screen ineligible");
 c.scene("org.example.app",0,true);Handler.next();check(client.last.contains("powerW=8.0"),"screen resume authoritative");
 settled(true);c.invalidatePower(false);Handler.next();Client replacement=new Client();c.register(replacement);int oldCalls=client.calls;
 settled(false);event(c);Handler.next();check(client.calls==oldCalls&&replacement.last.contains("powerW=8.0"),"new client only");
 c.unregister(replacement);check(HandlerThread.active==0,"replacement retired");
 RecordingCollector recording=new RecordingCollector();client=new Client();SystemClock.now=100;settled(true);
 recording.register(client);recording.scene("org.example.app",0,true);recording.command("full",0,"");recording.command("circle",0,"");
 check(recording.command("recordStart",0,recording.session.connectionEpoch+":"+recording.session.targetEpoch+":101").startsWith("ok=1"),"record start existing gesture authority");Handler.next();
 recording.invalidatePower(false);Handler.next();Handler.next();long writes=metric(recording,"monitorDbWrites");int scans=recording.scans;
 SystemClock.now=900;settled(false);event(recording);check(metric(recording,"monitorDbWrites")==writes&&recording.scans==scans,"battery event itself does not write/scan");
 Handler.next();check(client.last.contains("powerW=8.0")&&recording.scans==scans,"recording shares existing scalar/scan deadlines");
 check(metric(recording,"monitorDbWrites")==writes+1,"only the ordinary existing recording scalar row");
 recording.command("recordStop",0,"");recording.command("off",0,"");recording.unregister(client);check(HandlerThread.active==0,"record worker retired");
 System.out.println("POWER_EDGE_COLLECTOR=PASS settled/stale/replaced/one-confirm/OFF/FULL/duplicate/level/screen/client/recording/no-extra-scan/no-extra-event-write");
 }}'''
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
assert 'mMonitor.onBatteryStateChanged' in route and 'BatteryManager.EXTRA_PLUGGED' in route and 'BatteryManager.EXTRA_STATUS' in route
assert all(x not in route for x in ('new MonitorCollector','postDelayed','getLongProperty','BatteryManager.EXTRA_LEVEL'))
print('POWER_BATTERY_EVENT_EXISTING_FRAMEWORK_ROUTE=PASS; PHYSICAL_MEASUREMENT=NOT_CLAIMED')
