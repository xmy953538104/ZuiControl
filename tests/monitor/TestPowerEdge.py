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
 public Handler(Object o){}public boolean post(Runnable r){pending.add(r);delays.add(0L);return true;}
 public boolean postDelayed(Runnable r,long d){pending.add(r);delays.add(d);return true;}
 public void removeCallbacksAndMessages(Object o){pending.clear();delays.clear();}
 public static long next(){long d=delays.remove(0);SystemClock.now+=d;pending.remove(0).run();return d;}}
'''
stubs['com/zui/server/control/PowerEdgeTest.java']='''package com.zui.server.control;
import android.os.*;import android.content.*;
public class PowerEdgeTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Client implements IBinder {String last="";
 public void linkToDeath(DeathRecipient d,int f){}public boolean unlinkToDeath(DeathRecipient d,int f){return true;}
 public boolean transact(int c,Parcel p,Parcel r,int f){last=p.text;return true;}}
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
 System.out.println("POWER_EDGE_COLLECTOR=PASS settled/stale/replaced/one-confirm/OFF/FULL/no-scan/no-write");
 }}'''
with tempfile.TemporaryDirectory(prefix='monitor-power-edge-') as tmp:
 files=[]
 for name,text in stubs.items():
  p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
 production=['MonitorCollector.java','MonitorSources.java','MonitorSnapshot.java','MonitorSession.java','MonitorLifecycle.java','ThreadAnalysis.java','PolicyJson.java']
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(namespace['src']/n) for n in production]],check=True)
 subprocess.run(['java','-cp',tmp,'com.zui.server.control.PowerEdgeTest'],check=True)
