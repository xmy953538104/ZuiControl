package com.zui.server.control;

import java.util.*;
import static com.zui.server.control.PolicyJson.*;

/** Bounded analysis, owned by MonitorCollector. No timers, IO or scheduling mutations. */
final class ThreadAnalysis {
    static final long INTERVAL=3000, MAX_ACTIVE=600000, MAX_WALL=1800000;
    static final int MAX_TASKS=8192, MAX_IDENTITIES=32768, MAX_NAMES=512, RESULT_LIMIT=524288;
    static final class Process {
        final int pid; final long start; final List<MonitorSnapshot.Task> tasks;
        Process(int pid,long start,List<MonitorSnapshot.Task> tasks){this.pid=pid;this.start=start;this.tasks=tasks;}
        String key(){return pid+":"+start;}
    }
    static final class Group {
        final Set<String> identities=new HashSet<>(),segments=new HashSet<>();
        final List<Integer> concurrency=new ArrayList<>(),ranks=new ArrayList<>();
        int presence,valid,top1,top3;double sum,peak;
    }
    final int user;final String pkg,id,startRules;
    final long began,requested;
    final boolean recordBound;
    long sourceRecordId,sourceRecordWall,sourceRecordStartElapsed;
    String sourceRecordCompletion="",sourceRecordTerminalReason="";
    long active,lastClock,lastSample=-1,ended;
    int eligibleSamples,scans;
    String state="ARMED",error="",endRules="UNAVAILABLE";
    boolean collecting;
    final Map<String,Integer> interruptions=new TreeMap<>();
    final Map<String,Group> groups=new TreeMap<>();
    final Set<String> identities=new HashSet<>(),segments=new HashSet<>();
    final Map<String,MonitorSnapshot.Task> previous=new HashMap<>();
    final Set<String> priorProcesses=new HashSet<>();
    ThreadAnalysis(int user,String pkg,long requested,long now,String rules){
        this(user,pkg,requested,now,rules,false);
    }
    ThreadAnalysis(int user,String pkg,long requested,long now,String rules,boolean recordBound){
        require(user>=0&&pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"),"analysis target");
        require(recordBound?requested==MAX_WALL:requested==0||requested==120000||requested==300000||requested==600000,"analysis preset");
        this.user=user;this.pkg=pkg;this.requested=requested==0?MAX_ACTIVE:requested;this.recordBound=recordBound;
        began=lastClock=now;startRules=rules;id=UUID.randomUUID().toString();
    }
    void bindRecord(long record,long wall,long start){
        require(recordBound&&sourceRecordId==0&&record>0&&start==began,"analysis record identity");
        sourceRecordId=record;sourceRecordWall=wall;sourceRecordStartElapsed=start;
    }
    boolean live(){return ended==0&&!state.equals("FINISHED")&&!state.equals("TIMED_OUT")&&!state.equals("FAILED");}
    private void clock(long now){
        require(now>=lastClock,"analysis monotonic clock");
        if(collecting)active=Math.min(requested,active+now-lastClock);
        lastClock=now;
    }
    void scene(boolean foreground,boolean screen,long now){
        if(!live())return;clock(now);
        if(now-began>=MAX_WALL){finish("TIMED_OUT",now);return;}
        if(active>=requested){finish("FINISHED",now);return;}
        String next=!screen?"PAUSED_SCREEN_OFF":!foreground?"PAUSED_NOT_FOREGROUND":"RUNNING";
        if(!next.equals(state)&&next.startsWith("PAUSED"))interruptions.merge(next,1,Integer::sum);
        boolean nextCollecting=next.equals("RUNNING");
        if(nextCollecting!=collecting){previous.clear();priorProcesses.clear();lastSample=-1;}
        collecting=nextCollecting;state=next;
    }
    void finish(String terminal,long now){
        if(!live())return;clock(now);state=terminal;collecting=false;ended=now;previous.clear();
    }
    void fail(String message,long now){error=message.length()>160?message.substring(0,160):message;finish("FAILED",now);}
    boolean due(long now){return live()&&collecting&&(lastSample<0||now-lastSample>=INTERVAL);}
    void sample(List<Process> processes,long now,long hz){
        require(due(now)&&hz>0,"analysis not due");
        require(eligibleSamples<1024,"analysis sample bound");
        require(processes.size()<=64,"analysis process bound");
        long interval=lastSample<0?0:now-lastSample;
        Map<String,MonitorSnapshot.Task> next=new HashMap<>();
        Set<String> processKeys=new HashSet<>();Map<String,Integer> counts=new HashMap<>();
        Map<String,Double> cpus=new HashMap<>();Set<String> validNames=new HashSet<>();
        boolean restart=false;
        for(Process p:processes){
            require(p.pid>0&&p.start>0&&processKeys.add(p.key()),"analysis process identity");
            if(!priorProcesses.isEmpty()&&!priorProcesses.contains(p.key()))restart=true;
            require(segments.contains(p.key())||segments.size()<1024,"analysis segment bound");segments.add(p.key());
            for(MonitorSnapshot.Task t:p.tasks){
                require(t.name.length()<=64,"analysis name bound");
                String key=p.key()+":"+t.tid+":"+t.start;
                require(!next.containsKey(key)&&next.size()<MAX_TASKS,"analysis task bound/duplicate");next.put(key,t);
                require(identities.contains(key)||identities.size()<MAX_IDENTITIES,"analysis identity bound");identities.add(key);
                Group g=groups.get(t.name);
                if(g==null){require(groups.size()<MAX_NAMES,"analysis name count bound");g=new Group();groups.put(t.name,g);}
                g.identities.add(key);g.segments.add(p.key());counts.merge(t.name,1,Integer::sum);
                MonitorSnapshot.Task before=previous.get(key);
                if(before!=null&&t.ticks>=before.ticks&&interval>0){
                    double cpu=(t.ticks-before.ticks)*100000.0/hz/interval;
                    require(Double.isFinite(cpu)&&cpu>=0,"analysis CPU invalid");
                    cpus.merge(t.name,cpu,Double::sum);validNames.add(t.name);
                }
            }
        }
        require(segments.size()<=1024,"analysis segment bound");
        // A successful all-task enumeration is an eligible presence sample. First deltas stay unavailable.
        eligibleSamples++;scans++;
        List<String> ranked=new ArrayList<>(validNames);
        ranked.sort(Comparator.<String>comparingDouble(n->cpus.get(n)).reversed().thenComparing(n->n));
        for(Map.Entry<String,Integer> e:counts.entrySet()){
            Group g=groups.get(e.getKey());g.presence++;g.concurrency.add(e.getValue());
            if(validNames.contains(e.getKey())){double cpu=cpus.get(e.getKey());g.valid++;g.sum+=cpu;g.peak=Math.max(g.peak,cpu);}
        }
        for(int i=0;i<ranked.size();i++){Group g=groups.get(ranked.get(i));g.ranks.add(i+1);if(i==0)g.top1++;if(i<3)g.top3++;}
        if(restart){state="SEGMENTED_PROCESS_RESTART";interruptions.merge(state,1,Integer::sum);}
        previous.clear();previous.putAll(next);priorProcesses.clear();priorProcesses.addAll(processKeys);lastSample=now;
    }
    private static Object median(List<Integer> values){
        if(values.isEmpty())return null;List<Integer> sorted=new ArrayList<>(values);Collections.sort(sorted);int n=sorted.size();
        return n%2==1?(double)sorted.get(n/2):(sorted.get(n/2-1)+sorted.get(n/2))/2.0;
    }
    Map<String,Object> summary(long now){
        return map("schema",recordBound?2:1,"session",id,"user",user,"package",pkg,"state",state,"error",error,
            "acquisition",recordBound?"MONITOR_RECORDING":"STANDALONE_COMPATIBILITY",
            "sourceRecordId",sourceRecordId,"sourceRecordWall",sourceRecordWall,"sourceRecordStartElapsed",sourceRecordStartElapsed,
            "sourceRecordCompletion",sourceRecordCompletion,"sourceRecordTerminalReason",sourceRecordTerminalReason,
            "threadCoverage","ALL_QUALIFIED_TARGET_TASKS_OBSERVED",
            "wallElapsedMs",Math.max(0,(live()?now:ended)-began),"activeForegroundMs",active,
            "requestedActiveMs",requested,"hardWallMs",MAX_WALL,"eligibleSamples",eligibleSamples,
            "processSegmentCount",segments.size(),"uniqueIdentities",identities.size(),"uniqueNames",groups.size(),
            "interruptions",interruptions,"zuioptGenerationStart",startRules,"zuioptGenerationEnd",endRules,
            "cpuConvention","ONE_CORE_100_PERCENT","cpuAggregation","SUM_PER_NAME_OBSERVED_VALID_DELTAS",
            "rankAggregation","NAME_GROUP_CPU_SUM","concurrencyMedianScope","PRESENT_SAMPLES");
    }
    byte[] result(long now){
        Map<String,Object> out=summary(now);List<Object> rows=new ArrayList<>();out.put("threads",rows);
        for(Map.Entry<String,Group> entry:groups.entrySet()){
            Group g=entry.getValue();rows.add(map("name",entry.getKey(),"distinctIdentityCount",g.identities.size(),
                "sameNameConcurrencyMax",g.concurrency.isEmpty()?0:Collections.max(g.concurrency),"sameNameConcurrencyMedian",median(g.concurrency),
                "presenceSamples",g.presence,"eligibleSamples",eligibleSamples,"presencePct",eligibleSamples==0?null:g.presence*100.0/eligibleSamples,
                "avgCpuPct",g.valid==0?null:g.sum/g.valid,"peakCpuPct",g.valid==0?null:g.peak,"validCpuSamples",g.valid,
                "medianRank",median(g.ranks),"top1SharePct",eligibleSamples==0?null:g.top1*100.0/eligibleSamples,
                "top3SharePct",eligibleSamples==0?null:g.top3*100.0/eligibleSamples,"segmentCount",g.segments.size()));
        }
        byte[] data=bytes(out);require(data.length<=RESULT_LIMIT,"analysis result bound");return data;
    }
}
