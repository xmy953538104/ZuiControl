package com.zui.server.control;
import java.util.*;
import android.os.*;
import android.content.*;
import static com.zui.server.control.PolicyJson.*;

public final class RecordAnalysisTest {
 static MonitorSources sources;
 static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 static class Client implements IBinder {
  public void linkToDeath(DeathRecipient d,int f){}public boolean unlinkToDeath(DeathRecipient d,int f){return true;}
  public boolean transact(int c,Parcel p,Parcel r,int f){return true;}
 }
 static class EnumerationCollector extends MonitorCollector {
  Map<Integer,Integer> enumerations=new TreeMap<>();
  EnumerationCollector(){super(new Context(),sources);}
  int findPid(){return 1;}
  MonitorSnapshot.Task identity(int pid){return new MonitorSnapshot.Task(pid,"Task"+pid,1,SystemClock.now/10);}
  List<MonitorSnapshot.Task> readTasks(int pid){enumerations.merge(pid,1,Integer::sum);return Arrays.asList(identity(pid));}
 }
 static android.app.ActivityManager.RunningAppProcessInfo process(int pid,int user,String... packages){
  android.app.ActivityManager.RunningAppProcessInfo p=new android.app.ActivityManager.RunningAppProcessInfo();
  p.pid=pid;p.uid=user*100000+10001;p.pkgList=packages;p.processName=packages[0];return p;
 }
 static class Collector extends MonitorCollector {
  int fullScans,separateScans;long childGeneration=2,mainGeneration=1,scanDelay;boolean sourceFailure;
  Collector(){super(new Context(),sources);facts=()->"g-record";}
  int findPid(){return 1;}
  MonitorSnapshot.Task identity(int pid){return new MonitorSnapshot.Task(pid,"Main",mainGeneration,SystemClock.now/10);}
  List<MonitorSnapshot.Task> readTasks(int pid){separateScans++;throw new AssertionError("duplicate Recording enumeration");}
  List<ThreadAnalysis.Process> readAnalysisTasks(ThreadAnalysis a){
   fullScans++;if(sourceFailure)throw new IllegalStateException("task source unavailable");List<MonitorSnapshot.Task> main=new ArrayList<>();
   SystemClock.now+=scanDelay;
   for(int i=0;i<40;i++)main.add(new MonitorSnapshot.Task(i+10,"Task"+i,1,SystemClock.now/10+i));
   return Arrays.asList(new ThreadAnalysis.Process(1,mainGeneration,main),new ThreadAnalysis.Process(2,childGeneration,
    Arrays.asList(new MonitorSnapshot.Task(99,"Child",childGeneration,SystemClock.now/5))));
  }
 }
 static void tick(long time){SystemClock.now=time;Handler.delays.remove(0);Handler.pending.remove(0).run();}
 static Map<String,Object> result()throws Exception{return object(parse(MonitorStore.lastAnalysis,ThreadAnalysis.RESULT_LIMIT));}
 static long gesture;
 static void start(MonitorCollector c){
  c.scene("org.game",0,true,true,"",1,true);if(c.session.mode!=MonitorSession.FULL)c.command("full",0,"");c.command("circle",0,"");
  String reply=c.command("recordStart",0,c.session.connectionEpoch+":"+c.session.targetEpoch+":"+(++gesture));
  check(reply.startsWith("ok=1"),reply);
 }
 public static void main(String[] args)throws Exception {
  java.nio.file.Path root=java.nio.file.Files.createTempDirectory("record-analysis-sources-");
  java.nio.file.Path zone=java.nio.file.Files.createDirectory(root.resolve("thermal_zone1")),fps=root.resolve("fps");
  java.nio.file.Files.writeString(zone.resolve("type"),"quiet-therm");java.nio.file.Files.writeString(zone.resolve("temp"),"41000");
  java.nio.file.Files.writeString(fps,"fps: 59.9 duration:1000000 frame_count:60");sources=new MonitorSources(fps.toString(),root.toFile());
  SystemClock.now=100;Collector c=new Collector();Client client=new Client();c.register(client);start(c);
  long begin=SystemClock.now;int workers=HandlerThread.created;
  check(c.command("analysisStart",0,"{\"package\":\"org.game\",\"activeMs\":120000}").startsWith("ok=0"),"Recording owns the analysis");
  tick(begin);tick(begin+1000);tick(begin+2000);tick(begin+3000);c.childGeneration=3;tick(begin+6000);tick(begin+9000);
  check(c.fullScans==4&&c.separateScans==0&&c.analysisWrites==0,"3s shared enumeration, no analysis sample writes");
  check(HandlerThread.created==workers&&HandlerThread.active==1,"single worker");
  c.command("recordStop",0,"");Map<String,Object> first=result();
  check(integer(first.get("sourceRecordId"))==MonitorStore.starts&&integer(first.get("sourceRecordStartElapsed"))==begin,"exact record identity");
  check(first.get("acquisition").equals("MONITOR_RECORDING")&&first.get("sourceRecordTerminalReason").equals("EXPLICIT_STOP"),"source completion contract");
  check(integer(first.get("uniqueNames"))==41&&array(first.get("threads")).size()==41,"all tasks, more than Top15");
  check(integer(first.get("processSegmentCount"))==3&&object(first.get("interruptions")).containsKey("SEGMENTED_PROCESS_RESTART"),"child restart segmentation");
  check(c.state().contains("monitorThreadRows=60")&&c.analysisWrites==1,"unchanged Top15 primary persistence, one final summary");
  long firstId=integer(first.get("sourceRecordId"));
  for(String terminal:new String[]{"deadline","screen","scene","mainRestart","task","disable","permission"}){
   SystemClock.now+=100;start(c);long started=SystemClock.now;int scans=c.fullScans;tick(started);
   if(terminal.equals("deadline"))tick(started+MonitorSession.MAX_RECORD_MS+1000);
   if(terminal.equals("screen")){PowerManager.interactive=false;tick(started+1000);PowerManager.interactive=true;}
   if(terminal.equals("scene"))c.scene("org.other",0,true);
   if(terminal.equals("mainRestart")){c.mainGeneration++;tick(started+1000);}
   if(terminal.equals("task"))c.scene("org.game",0,true,true,"",2,true);
   if(terminal.equals("disable"))c.command("off",0,"");
   if(terminal.equals("permission")){c.command("permissionLost",0,"");c.command("permissionGranted",0,"");}
   Map<String,Object> out=result();
   check(!c.session.recording()&&integer(out.get("sourceRecordId"))==MonitorStore.starts&&integer(out.get("sourceRecordId"))>firstId,"latest replacement source");
   check(out.get("sourceRecordTerminalReason").equals(MonitorStore.terminal),"record terminal matches analysis "+terminal);
   check(out.get("sourceRecordCompletion").equals(MonitorStore.incomplete?"INCOMPLETE":"COMPLETE"),"completion matches "+terminal);
   check(c.fullScans==scans+1&&c.separateScans==0,"no terminal or deadline enumeration");
   check(integer(out.get("wallElapsedMs"))<=MonitorSession.MAX_RECORD_MS,"late wake stays bounded");
  }
  if(args.length>0)java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),MonitorStore.lastAnalysis);
  SystemClock.now+=100;start(c);tick(SystemClock.now);c.sourceFailure=true;tick(SystemClock.now+3000);
  check(!c.session.recording()&&MonitorStore.incomplete&&result().get("sourceRecordTerminalReason").equals("ANALYSIS_SOURCE_FAILURE"),"task source failure finalizes linked incomplete record");
  check(c.state().contains("monitorError=IllegalStateException:analysis source:"),"failed pipeline health is visible until actual recovery");
  c.sourceFailure=false;tick(SystemClock.now+1000);check(c.state().contains("monitorError=\n"),"completed scalar sample proves recovery");
  SystemClock.now+=100;start(c);long slowStart=SystemClock.now;c.scanDelay=1000;
  tick(slowStart+MonitorSession.MAX_RECORD_MS-500);c.scanDelay=0;
  check(!c.session.recording()&&MonitorStore.terminal.equals("DURATION_LIMIT")&&c.state().contains("monitorScalarRows=0"),"slow task read cannot persist a post-deadline scalar");
  c.unregister(client);check(HandlerThread.active==0&&Handler.pending.isEmpty(),"no detached record analysis lifetime");
  for(boolean omitted:new boolean[]{false,true}){
   EnumerationCollector actual=new EnumerationCollector();actual.register(new Client());
   android.app.ActivityManager.processes=Arrays.asList(process(1,0,omitted?new String[]{"org.game","org.shared"}:new String[]{"org.game"}),
     process(2,0,"org.game"),process(3,10,"org.game"),process(4,0,"org.other"));
   SystemClock.now+=100;start(actual);long began=SystemClock.now;tick(began);tick(began+3000);actual.command("recordStop",0,"");
   check(actual.enumerations.equals(new TreeMap<>(Map.of(1,2,2,2))),"production process qualification and one scan per PID "+omitted);
   check(actual.state().contains("monitorThreadRows=2"),"primary Top15 persists even when pkgList is ambiguous");
   check(integer(result().get("uniqueNames"))==(omitted?1:2),"qualified analysis preserves exclusion authority");
   actual.register(null);
  }
  System.out.println("RECORD_LINKED_ANALYSIS=PASS one-snapshot/Top15/all-task/multiprocess/child-restart/terminal/latest/source/one-worker");
  java.nio.file.Files.delete(zone.resolve("type"));java.nio.file.Files.delete(zone.resolve("temp"));java.nio.file.Files.delete(zone);java.nio.file.Files.delete(fps);java.nio.file.Files.delete(root);
 }
}
