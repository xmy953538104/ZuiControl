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
  ThreadAnalysis warmAnalysis=new ThreadAnalysis(0,"org.host",ThreadAnalysis.MAX_WALL,100,"g-host",true);warmAnalysis.bindRecord(1,1,100);warmAnalysis.scene(true,true,100);
  List<MonitorSnapshot.Task> warmPrevious=Collections.emptyList();
  for(int warm=0;warm<30;warm++){
   List<MonitorSnapshot.Task> tasks=collector.readTasks(pid);long now=100+warm*3000L;
   warmAnalysis.scene(true,true,now);warmAnalysis.sample(Arrays.asList(new ThreadAnalysis.Process(pid,start,tasks)),now,100);
   MonitorSnapshot.delta(warmPrevious,tasks,3000,100,warm>0);warmPrevious=tasks;
  }
  for(int run=0;run<3;run++)for(boolean linked:new boolean[]{false,true}){
   ThreadAnalysis a=new ThreadAnalysis(0,"org.host",ThreadAnalysis.MAX_WALL,100,"g-host",true);a.bindRecord(1,1,100);a.scene(true,true,100);
   List<MonitorSnapshot.Task> previous=Collections.emptyList();int taskCount=0;
   long beforeReads=reads(),cpu=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime(),wall=System.nanoTime();
   for(int scan=0;scan<samples;scan++){
    // Exactly one production directory/read path in each case. Both consumers see these bytes.
    List<MonitorSnapshot.Task> snapshot=collector.readTasks(pid);taskCount=snapshot.size();
    long now=100+scan*3000L;
    if(linked){a.scene(true,true,now);a.sample(Arrays.asList(new ThreadAnalysis.Process(pid,start,snapshot)),now,100);}
    MonitorSnapshot.delta(previous,snapshot,3000,100,scan>0);previous=snapshot;
   }
   double cpuMs=(ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime()-cpu)/1000000.0;
   double wallMs=(System.nanoTime()-wall)/1000000.0;long syscr=reads()-beforeReads;
   System.out.println("RECORD_ANALYSIS_PROC_BENCHMARK run="+run+" mode="+(linked?"RECORD_LINKED":"TOP15_BASELINE")+
    " samples="+samples+" directoryEnumerations="+samples+" observedTasks="+taskCount+" cpuMs="+cpuMs+" wallMs="+wallMs+" readSyscalls="+syscr);
  }
 }
}
