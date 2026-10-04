package com.zui.server.control;
import java.util.*;
import java.lang.management.ManagementFactory;
import static com.zui.server.control.PolicyJson.*;

public final class RecordAnalysisBoundsTest {
 public static void main(String[] args)throws Exception {
  ThreadAnalysis a=new ThreadAnalysis(10,"org.game",ThreadAnalysis.MAX_WALL,100,"g-start",true);
  a.bindRecord(5,0,100);a.scene(true,true,100); // Wall clock is identity, not an availability flag.
  long cpu=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime(),wall=System.nanoTime();
  for(int scan=0;scan<600;scan++){
   long time=100+scan*3000L;List<ThreadAnalysis.Process> processes=new ArrayList<>();
   for(int process=0;process<64;process++){
    List<MonitorSnapshot.Task> tasks=new ArrayList<>();
    for(int t=0;t<128;t++){
     int identity=process*128+t;String name="\u0001".repeat(60)+String.format(java.util.Locale.ROOT,"%04d",identity%512);
     tasks.add(new MonitorSnapshot.Task(identity+1,name,1+Math.min(scan,3),scan*300L));
    }
    processes.add(new ThreadAnalysis.Process(process+1,1,tasks));
   }
   a.scene(true,true,time);a.sample(processes,time,100);
  }
  a.sourceRecordCompletion="COMPLETE";a.sourceRecordTerminalReason="DURATION_LIMIT";
  a.finish("FINISHED",100+ThreadAnalysis.MAX_WALL);byte[] result=a.result(100+ThreadAnalysis.MAX_WALL);
  require(a.eligibleSamples==600&&a.eligibleSamples<1024&&a.identities.size()==32768&&a.groups.size()==512,"30m safety bounds");
  require(result.length>262144&&result.length<=ThreadAnalysis.RESULT_LIMIT,"large escaped result within existing size limit");
  require(integer(object(parse(result,ThreadAnalysis.RESULT_LIMIT)).get("sourceRecordId"))==5,"bounded parser roundtrip");
  System.out.println("RECORD_ANALYSIS_30MIN_BOUNDS=PASS eligibleSamples="+a.eligibleSamples+" identities="+a.identities.size()+" names="+a.groups.size()+" resultBytes="+result.length+
   " cpuMs="+(ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime()-cpu)/1000000.0+" wallMs="+(System.nanoTime()-wall)/1000000.0);
 }
}
