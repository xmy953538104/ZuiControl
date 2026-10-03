"""Execute production collector analysis lifecycle with a deterministic host process/clock boundary."""
from pathlib import Path
import subprocess,sys,tempfile
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
namespace={'__file__':str(HERE/'TestPowerEdge.py')}
text=(HERE/'TestPowerEdge.py').read_text(encoding='utf8')
exec(compile(text[:text.index("stubs['com/zui/server/control/")],str(HERE/'TestPowerEdge.py'),'exec'),namespace)
stubs=namespace['stubs']
stubs['com/zui/server/control/AnalysisCollectorTest.java']='''package com.zui.server.control;
import java.util.*;import android.os.*;import android.content.*;
public final class AnalysisCollectorTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Client implements IBinder {
  public void linkToDeath(DeathRecipient d,int f){}public boolean unlinkToDeath(DeathRecipient d,int f){return true;}
  public boolean transact(int c,Parcel p,Parcel r,int f){return true;}
 }
 static class Collector extends MonitorCollector {
  int scans,separateScans;long generation=1;
  Collector(){super(new Context());facts=()->"g-bound";}
  int findPid(){return 1;}
  MonitorSnapshot.Task identity(int p){return new MonitorSnapshot.Task(p,"GameThread",generation,SystemClock.now/10);}
  List<MonitorSnapshot.Task> readTasks(int p){separateScans++;return Arrays.asList(identity(p));}
  List<ThreadAnalysis.Process> readAnalysisTasks(ThreadAnalysis a){scans++;return Arrays.asList(
   new ThreadAnalysis.Process(1,generation,Arrays.asList(new MonitorSnapshot.Task(2,"GameThread",generation,SystemClock.now/10))));}
 }
 static void tick(long time){SystemClock.now=time;Runnable r=Handler.pending.remove(0);Handler.delays.remove(0);r.run();}
 public static void main(String[] args)throws Exception {
  SystemClock.now=100;Collector c=new Collector();c.scene("org.game",0,true);
  String result=c.command("analysisStart",0,"{\\"package\\":\\"org.game\\",\\"activeMs\\":120000}");
  check(result.contains("RUNNING"),result);check(c.scans==0,"arming no scan");tick(100);tick(3100);
  check(c.scans==2&&c.analysisWrites==0,"memory only samples");
  c.scene("org.other",0,true);tick(4000);check(c.scans==2,"background pause");
  check(c.command("analysisState",0,"").contains("PAUSED_NOT_FOREGROUND"),"rebind state");
  c.scene("org.game",0,true);tick(10000);c.generation=2;tick(13000);check(c.scans==4,"resume segmented scan");
  android.os.PowerManager.interactive=false;c.scene("org.game",0,false);tick(14000);
  check(c.scans==4&&Handler.delays.get(0)>3000,"screen-off no polling");
  android.os.PowerManager.interactive=true;c.scene("org.game",0,true);tick(20000);
  check(c.command("analysisState",10,"").equals("{}"),"other user privacy");
  Map<String,Object> state=PolicyJson.object(PolicyJson.parse(c.command("analysisState",0,"").getBytes()));
  String session=PolicyJson.string(state.get("session"));
  result=c.command("analysisStop",0,PolicyJson.encode(PolicyJson.map("session",session)));
  check(result.contains("FINISHED")&&c.analysisWrites==1,"one final write");
  check(Handler.pending.isEmpty(),"idle has no analysis worker");
  check(c.command("analysisStop",0,PolicyJson.encode(PolicyJson.map("session",session))).startsWith("ok=0"),"stale stop no second finalize");
  check(c.analysisWrites==1,"no replay write");
  c.command("analysisStart",0,"{\\"package\\":\\"org.game\\",\\"activeMs\\":120000}");
  tick(20000);int startScans=c.scans;tick(140000);
  check(c.command("analysisState",0,"").contains("FINISHED")&&c.analysisWrites==2&&c.scans==startScans&&Handler.pending.isEmpty(),"active cap finalizes once without final scan");
  c.scene("org.other",0,true);c.command("analysisStart",0,"{\\"package\\":\\"org.game\\",\\"activeMs\\":0}");
  tick(140000+ThreadAnalysis.MAX_WALL);
  check(c.command("analysisState",0,"").contains("TIMED_OUT")&&c.analysisWrites==3&&Handler.pending.isEmpty(),"paused wall cap retires worker");
  c.scene("org.game",0,true);Client client=new Client();c.register(client);
  c.command("full",0,"");c.command("circle",0,"");
  c.command("analysisStart",0,"{\\"package\\":\\"org.game\\",\\"activeMs\\":120000}");
  check(c.command("recordStart",0,c.session.connectionEpoch+":"+c.session.targetEpoch+":9999999").startsWith("ok=1"),"parallel recording start");
  long begin=SystemClock.now;tick(begin);tick(begin+1000);tick(begin+3000);
  check(c.separateScans==0&&c.session.recording(),"recording shares all-task analysis enumeration");
  c.command("recordStop",0,"");c.unregister(client);
  check(c.command("analysisState",0,"").contains("RUNNING"),"UI detach does not cancel rebindable analysis");
  tick(begin+120000);check(Handler.pending.isEmpty(),"final analysis retires last worker");
  System.out.println("PASS analysis collector pause/rebind/segment/screen-off/stop/user/caps/shared-recording");
 }
}'''
with tempfile.TemporaryDirectory(prefix='analysis-collector-') as tmp:
 out=Path(tmp);files=[]
 for name,text in stubs.items():
  p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
 production=['MonitorCollector.java','MonitorSources.java','MonitorSnapshot.java','MonitorSession.java','MonitorLifecycle.java','ThreadAnalysis.java','PolicyJson.java']
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(namespace['namespace']['src']/n) for n in production]],check=True)
 subprocess.run(['java','-cp',tmp,'com.zui.server.control.AnalysisCollectorTest'],check=True)
