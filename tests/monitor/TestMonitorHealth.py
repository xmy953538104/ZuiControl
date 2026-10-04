"""Production collector error-domain transitions; host I/O faults, no Android paths."""
from pathlib import Path
import subprocess,tempfile
HERE=Path(__file__).resolve().parent
namespace={'__file__':str(HERE/'TestPowerEdge.py')}
text=(HERE/'TestPowerEdge.py').read_text(encoding='utf8')
exec(compile(text[:text.index("stubs['com/zui/server/control/")],str(HERE/'TestPowerEdge.py'),'exec'),namespace)
stubs=namespace['stubs']
stubs['com/zui/server/control/MonitorStore.java']=stubs['com/zui/server/control/MonitorStore.java'].replace(
 'String read(int u,String key){return "{}";}',
 'String read(int u,String key){if(!key.startsWith("{"))throw new IllegalArgumentException(key.equals("com.zui.calculator")?"thread_identity":key);return "{}";}')
stubs['com/zui/server/control/HealthTest.java']='''package com.zui.server.control;
import android.os.*;import android.content.*;import java.nio.file.*;
public class HealthTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static String field(MonitorCollector c,String key){for(String line:c.state().split("\\n"))if(line.startsWith(key+"="))return line.substring(key.length()+1);throw new AssertionError(key);}
 static void healthy(MonitorCollector c){check(field(c,"monitorError").isEmpty(),c.state());}
 static class Client implements IBinder {public void linkToDeath(DeathRecipient d,int f){} public boolean unlinkToDeath(DeathRecipient d,int f){return true;} public boolean transact(int n,Parcel p,Parcel r,int f){return true;}}
 static class Collector extends MonitorCollector {
  Collector(MonitorSources s){super(new Context(),s);} int findPid(){return 42;}
  MonitorSnapshot.Task identity(int p){return new MonitorSnapshot.Task(p,"GameThread",1,0);}
 }
 static long gesture;
 static void start(Collector c){c.command("circle",0,"");check(c.command("recordStart",0,c.session.connectionEpoch+":"+c.session.targetEpoch+":"+(++gesture)).startsWith("ok=1"),"start");}
 public static void main(String[] args)throws Exception {
  Path root=Paths.get(args[0]),zone=Files.createDirectory(root.resolve("thermal_zone123")),fps=root.resolve("fps");
  Files.write(zone.resolve("type"),"quiet-therm".getBytes());Files.write(zone.resolve("temp"),"41000".getBytes());Files.write(fps,"fps: 59.9 duration:1000000 frame_count:60".getBytes());
  MonitorSources source=new MonitorSources(fps.toString(),root.toFile());Collector c=new Collector(source);
  check(c.command("recordRead",0,"com.zui.calculator").equals("ok=0\\nerror=IllegalArgumentException:thread_identity"),"original bad request rejected");
  healthy(c);check(field(c,"monitorFinalizeError").isEmpty(),"not finalize");check(Handler.pending.isEmpty(),"OFF request starts no worker");
  String diagnostic=field(c,"monitorLastRequestError");c.command("recordRead",0,"{}");check(field(c,"monitorLastRequestError").equals(diagnostic),"successful read retains diagnostic");
  c.command("recordRead",0,new String(new char[1000]).replace('\\0','x')+"\\nspoof=1\\r");
  check(field(c,"monitorLastRequestError").length()==160&&!c.state().contains("\\nspoof="),"bounded single-line diagnostic");healthy(c);
  c.scene("org.game",0,true);Client client=new Client();c.register(client);Handler.next();healthy(c);
  Context.failBattery=true;Handler.next();check(field(c,"monitorError").contains("battery fixture"),"runtime fault visible");
  c.command("recordRead",0,"{}");check(!field(c,"monitorError").isEmpty(),"read cannot recover pipeline");
  c.command("recordRead",0,"com.zui.calculator");check(field(c,"monitorError").contains("battery fixture"),"reject cannot overwrite runtime");
  Context.failBattery=false;Handler.next();healthy(c);
  Files.delete(zone.resolve("temp"));Handler.next();check(field(c,"monitorError").startsWith("quiet:"),"quiet source failure visible");
  Files.write(zone.resolve("temp"),"41000".getBytes());Handler.next();check(!field(c,"monitorError").isEmpty(),"latched source not recovered by read");
  c.command("full",0,"");check(!field(c,"monitorError").isEmpty(),"enable alone not recovery");Handler.next();healthy(c);
  Files.write(fps,"broken".getBytes());Handler.next();check(field(c,"monitorError").contains("MALFORMED"),"malformed demanded FPS visible");
  Files.write(fps,"fps: 0.0 duration:1000000 frame_count:0".getBytes());Handler.next();healthy(c);
  start(c);MonitorStore.failFinish=true;c.command("recordStop",0,"");String finalize=field(c,"monitorFinalizeError");check(!finalize.isEmpty(),"finalization failure");healthy(c);
  MonitorStore.failFinish=false;c.command("recordRead",0,"{}");Handler.next();check(field(c,"monitorFinalizeError").equals(finalize),"read/sample cannot clear finalization failure");
  c.command("recordStop",0,"");check(field(c,"monitorFinalizeError").equals(finalize),"idle stop not proof");
  start(c);check(field(c,"monitorFinalizeError").equals(finalize),"start not proof");c.command("recordStop",0,"");check(field(c,"monitorFinalizeError").isEmpty(),"successful real finalization proves recovery");
  c.command("off",0,"");c.unregister(client);healthy(c);check(field(c,"monitorMode").equals("0")&&field(c,"monitorRecordState").equals("IDLE")&&Handler.pending.isEmpty(),"OFF retires all work");
  System.out.println("MONITOR_HEALTH_DOMAINS=PASS request/runtime/source/recovery/finalize/OFF");
 }
}'''
with tempfile.TemporaryDirectory(prefix='monitor-health-') as tmp:
 out=Path(tmp);files=[]
 for name,text in stubs.items():
  p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
 production=['MonitorCollector.java','MonitorSources.java','MonitorSnapshot.java','MonitorSession.java','MonitorLifecycle.java','ThreadAnalysis.java','PolicyJson.java']
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(namespace['namespace']['src']/n) for n in production]],check=True)
 fixture=out/'sources';fixture.mkdir()
 subprocess.run(['java','-cp',tmp,'com.zui.server.control.HealthTest',str(fixture)],check=True)
