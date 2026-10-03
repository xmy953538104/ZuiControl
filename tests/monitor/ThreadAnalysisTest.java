package com.zui.server.control;
import java.util.*;
import static com.zui.server.control.PolicyJson.*;
public final class ThreadAnalysisTest {
 static int checks;
 static void check(boolean b){if(!b)throw new AssertionError();checks++;}
 static ThreadAnalysis.Process process(int pid,long start,int tid,long tstart,long ticks){
  return new ThreadAnalysis.Process(pid,start,Arrays.asList(new MonitorSnapshot.Task(tid,"Pool",tstart,ticks)));
 }
 public static void main(String[] args)throws Exception {
  ThreadAnalysis a=new ThreadAnalysis(0,"org.example.game",120000,100,"g-start");
  check(a.state.equals("ARMED"));a.scene(true,true,100);check(a.due(100));
  a.sample(Arrays.asList(process(10,1,11,2,0),process(20,3,21,4,0)),100,100);
  a.sample(Arrays.asList(process(10,1,11,2,300),process(20,3,21,4,150)),3100,100);
  check(a.groups.get("Pool").sum==150&&a.groups.get("Pool").identities.size()==2);
  a.scene(false,true,4100);check(a.active==4000&&!a.due(8000));
  a.scene(true,true,10100);check(a.active==4000&&a.previous.isEmpty());
  a.sample(Arrays.asList(process(10,1,11,2,600)),10100,100);check(a.groups.get("Pool").valid==1);
  a.sample(Arrays.asList(process(10,1,11,99,900)),13100,100);check(a.groups.get("Pool").valid==1&&a.identities.size()==3);
  a.sample(Arrays.asList(process(10,55,11,100,1200)),16100,100);
  check(a.state.equals("SEGMENTED_PROCESS_RESTART")&&a.segments.size()==3&&a.groups.get("Pool").valid==1);
  a.scene(true,false,17100);long paused=a.active;check(a.state.equals("PAUSED_SCREEN_OFF"));
  a.scene(true,true,20100);check(a.active==paused);
  a.scene(true,true,200100);check(!a.live()&&a.state.equals("FINISHED")&&a.active==120000);
  a.endRules="g-end";Map<String,Object> result=object(parse(a.result(200100)));
  check(result.get("zuioptGenerationStart").equals("g-start")&&result.get("zuioptGenerationEnd").equals("g-end"));
  Map<String,Object> row=object(array(result.get("threads")).get(0));
  check(integer(row.get("distinctIdentityCount"))==4&&integer(row.get("sameNameConcurrencyMax"))==2);
  check(((Number)row.get("avgCpuPct")).doubleValue()==150);
  ThreadAnalysis b=new ThreadAnalysis(10,"org.example.game",0,100,"old");b.scene(false,false,100+ThreadAnalysis.MAX_WALL);
  check(b.state.equals("TIMED_OUT")&&b.active==0&&b.user==10);
  ThreadAnalysis c=new ThreadAnalysis(0,"org.example.game",300000,100,"old");c.scene(true,true,100);
  List<MonitorSnapshot.Task> many=new ArrayList<>();for(int i=0;i<8193;i++)many.add(new MonitorSnapshot.Task(i+1,"same",1,0));
  try{c.sample(Arrays.asList(new ThreadAnalysis.Process(1,1,many)),100,100);throw new AssertionError();}
  catch(IllegalArgumentException expected){c.fail(expected.getMessage(),100);}
  check(c.state.equals("FAILED")&&c.result(100).length<=ThreadAnalysis.RESULT_LIMIT);
  System.out.println("PASS thread analysis core checks="+checks);
 }
}
