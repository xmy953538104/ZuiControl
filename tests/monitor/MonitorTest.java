package com.zui.server.control;
import java.util.*;

public final class MonitorTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static MonitorSnapshot.Task task(int id, long start, long ticks) {
        return new MonitorSnapshot.Task(id, "thread " + id, start, ticks);
    }
    public static void main(String[] args) {
        List<MonitorSnapshot.Task> before = Arrays.asList(task(1, 100, 20), task(2, 100, 40), task(3, 100, 90));
        List<MonitorSnapshot.Task> now = Arrays.asList(task(1, 100, 170), task(2, 200, 900), task(4, 100, 20));
        List<MonitorSnapshot.Row> rows = MonitorSnapshot.delta(before, now, 3000, 100, true);
        check(rows.size() == 3 && rows.get(0).task.tid == 1 && rows.get(0).cpu == 50);
        check(rows.get(1).cpu == -1 && rows.get(2).cpu == -1); // reused/new TID; disappeared3 absent
        check(MonitorSnapshot.delta(before, now, 3000, 100, false).get(0).cpu == -1);
        check(MonitorSnapshot.delta(before, now, 0, 100, true).get(0).cpu == -1);
        check(MonitorSnapshot.delta(before, Arrays.asList(task(1,100,1)),3000,100,true).get(0).cpu == -1);
        List<MonitorSnapshot.Task> population = new ArrayList<>();
        for (int i=0;i<30;i++) population.add(task(i+1,100,100));
        check(MonitorSnapshot.delta(population,population,3000,100,true).size()==15);
        String stat = "12 (name with ) spaces) S 0 0 0 0 0 0 0 0 0 0 15 5 0 0 0 0 0 0 123";
        MonitorSnapshot.Task parsed = MonitorSnapshot.Task.parse(stat);
        check(parsed != null && parsed.tid == 12 && parsed.ticks == 20 && parsed.start == 123);
        check(parsed.name.equals("name with ) spaces"));
        check(MonitorSnapshot.Task.parse("malformed") == null);
        check(MonitorSnapshot.Task.parse(stat.replace("15 5", "-1 5")) == null);
        long[] cpu = MonitorSnapshot.cpuTimes("cpu 100 20 30 400 50 10 10 0 100 20");
        check(cpu[0] == 620 && cpu[1] == 450);
        check(MonitorSnapshot.aggregateCpu(new long[]{100,50}, new long[]{200,100}) == 50);
        check(MonitorSnapshot.aggregateCpu(new long[]{100,50}, new long[]{90,40}) == -1);
        check(MonitorSnapshot.cpuTimes("cpu -1 0 0 0 0 0 0 0") == null);
        check(MonitorSnapshot.displayFps("fps: 59.9 duration:500000 frame_count:30") == 59.9);
        check(MonitorSnapshot.displayFps("fps: NaN") == -1);
        check(MonitorSnapshot.displayFps("fps: -1") == -1);
        check(MonitorSnapshot.displayFps("120Hz") == -1);
        MonitorLifecycle lifecycle = new MonitorLifecycle();
        check(lifecycle.select("0:game",true,false).isEmpty());
        lifecycle.setManual(true);
        check(lifecycle.select("0:home",false,false).isEmpty());
        check(lifecycle.select("0:game",true,false).equals("0:game"));
        check(lifecycle.select("0:other",true,false).isEmpty());
        check(lifecycle.select("0:game",false,false).isEmpty()); // screenoff/lock/unavailable
        check(lifecycle.select("0:game",true,false).equals("0:game"));
        lifecycle.setManual(false);
        check(lifecycle.select("0:game",true,false).isEmpty());
        check(lifecycle.select("0:game",true,true).equals("0:game"));
        check(lifecycle.select("0:game",false,true).isEmpty());
        System.out.println("MONITOR_MATH_LIFECYCLE=PASS");
    }
}
