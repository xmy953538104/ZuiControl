package com.zui.server.control;
import java.util.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;

/** Accelerated Linux host comparison: actual production /proc reader, same snapshot consumers. */
public final class RecordAnalysisBenchmark {
 static long reads()throws Exception {
  for(String line:Files.readAllLines(Paths.get("/proc/self/io")))if(line.startsWith("syscr:"))return Long.parseLong(line.substring(6).trim());
  throw new IllegalStateException("Linux syscr counter unavailable");
 }
 public static void main(String[] args)throws Exception {
  if(!Files.isDirectory(Paths.get("/proc/self/task"))){System.out.println("RECORD_ANALYSIS_PROC_BENCHMARK=N_A non-Linux host; CPU bound test available");return;}
  MonitorCollector collector=new MonitorCollector(new android.content.Context());int pid=(int)ProcessHandle.current().pid();
  long start=collector.identity(pid).start;int samples=200;
  android.app.ActivityManager.RunningAppProcessInfo process=new android.app.ActivityManager.RunningAppProcessInfo();
  process.pid=pid;process.uid=10001;process.processName="org.host";process.pkgList=new String[]{"org.host"};
  android.app.ActivityManager.processes=Arrays.asList(process);
  collector.session.scene("org.host",0,true,true);collector.session.mode=MonitorSession.FULL;collector.session.started(100,pid,start);
  ThreadAnalysis warmAnalysis=new ThreadAnalysis(0,"org.host",ThreadAnalysis.MAX_WALL,100,"g-host",true);warmAnalysis.bindRecord(1,1,100);warmAnalysis.scene(true,true,100);
  List<MonitorSnapshot.Task> warmPrevious=Collections.emptyList();
  for(int warm=0;warm<30;warm++){
   List<ThreadAnalysis.Process> snapshot=collector.readAnalysisTasks(warmAnalysis);List<MonitorSnapshot.Task> tasks=snapshot.get(0).tasks;long now=100+warm*3000L;
   warmAnalysis.scene(true,true,now);warmAnalysis.sample(snapshot,now,100);
   MonitorSnapshot.delta(warmPrevious,tasks,3000,100,warm>0);warmPrevious=tasks;
  }
  for(int run=0;run<3;run++)for(boolean linked:new boolean[]{false,true}){
   ThreadAnalysis a=new ThreadAnalysis(0,"org.host",ThreadAnalysis.MAX_WALL,100,"g-host",true);a.bindRecord(1,1,100);a.scene(true,true,100);
   List<MonitorSnapshot.Task> previous=Collections.emptyList();int taskCount=0;
   long beforeReads=reads(),cpu=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime(),wall=System.nanoTime();
   for(int scan=0;scan<samples;scan++){
    // Exactly one production directory/read path in each case. Both consumers see these bytes.
    collector.identity(pid); // Existing Recording terminal process check, in both cases.
    List<ThreadAnalysis.Process> target=linked?collector.readAnalysisTasks(a):Collections.emptyList();
    List<MonitorSnapshot.Task> snapshot=linked?target.get(0).tasks:collector.readTasks(pid);taskCount=snapshot.size();
    collector.identity(pid); // Existing capture identity check, in both cases.
    long now=100+scan*3000L;
    if(linked){a.scene(true,true,now);a.sample(target,now,100);}
    collector.identity(pid); // Existing post-snapshot identity check, in both cases.
    MonitorSnapshot.delta(previous,snapshot,3000,100,scan>0);previous=snapshot;
   }
   double cpuMs=(ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime()-cpu)/1000000.0;
   double wallMs=(System.nanoTime()-wall)/1000000.0;long syscr=reads()-beforeReads;
   System.out.println("RECORD_ANALYSIS_PROC_BENCHMARK run="+run+" mode="+(linked?"RECORD_LINKED":"TOP15_BASELINE")+
    " samples="+samples+" directoryEnumerations="+samples+" observedTasks="+taskCount+" cpuMs="+cpuMs+" wallMs="+wallMs+" readSyscalls="+syscr);
  }
 }
}
