package com.zui.server.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only sample math. CPU percentages use one logical core = 100%. */
final class MonitorSnapshot {
    static final long INTERVAL_MS = 3000;
    static final int TOP_N = 15;

    static final class Task {
        final int tid;
        final String name;
        final long start, ticks;
        Task(int tid, String name, long start, long ticks) {
            this.tid = tid; this.name = name; this.start = start; this.ticks = ticks;
        }
        static Task parse(String line) {
            try {
                int open = line.indexOf('('), close = line.lastIndexOf(')');
                if (open <= 0 || close <= open) return null;
                int tid = Integer.parseInt(line.substring(0, open).trim());
                String[] fields = line.substring(close + 1).trim().split("\\s+");
                if (tid <= 0 || fields.length < 20) return null;
                long user = Long.parseLong(fields[11]), sys = Long.parseLong(fields[12]);
                long start = Long.parseLong(fields[19]);
                if (user < 0 || sys < 0 || start < 0 || user > Long.MAX_VALUE - sys) return null;
                return new Task(tid, line.substring(open + 1, close), start, user + sys);
            } catch (RuntimeException malformed) { return null; }
        }
    }

    static final class Row {
        final Task task;
        final double cpu;
        final long intervalMs;
        Row(Task task, double cpu,long intervalMs) { this.task = task; this.cpu = cpu;this.intervalMs=intervalMs; }
    }

    static List<Row> delta(List<Task> previous, List<Task> current, long elapsedMs,
            long ticksPerSecond, boolean sameProcess) {
        Map<Integer, Task> old = new HashMap<>();
        if (sameProcess) for (Task t : previous) old.put(t.tid, t);
        List<Row> rows = new ArrayList<>();
        for (Task task : current) {
            Task before = old.get(task.tid);
            double cpu = -1;
            if (before != null && before.start == task.start && task.ticks >= before.ticks
                    && elapsedMs > 0 && ticksPerSecond > 0) {
                cpu = (task.ticks - before.ticks) * 100000.0 / ticksPerSecond / elapsedMs;
            }
            rows.add(new Row(task, cpu,elapsedMs));
        }
        rows.sort(Comparator.comparingDouble((Row r) -> r.cpu).reversed()
                .thenComparingInt(r -> r.task.tid));
        return Collections.unmodifiableList(new ArrayList<>(rows.subList(0, Math.min(TOP_N, rows.size()))));
    }

    static long[] cpuTimes(String firstLine) {
        try {
            String[] f = firstLine.trim().split("\\s+");
            if (f.length < 9 || !"cpu".equals(f[0])) return null;
            long total = 0;
            // guest/guest_nice already included in user/nice: do not double-count them.
            for (int i = 1; i <= 8; i++) {
                long v = Long.parseLong(f[i]);
                if (v < 0 || total > Long.MAX_VALUE - v) return null;
                total += v;
            }
            return new long[] {total, Long.parseLong(f[4]) + Long.parseLong(f[5])};
        } catch (RuntimeException malformed) { return null; }
    }

    static double aggregateCpu(long[] old, long[] now) {
        if (old == null || now == null || now[0] <= old[0] || now[1] < old[1]) return -1;
        long total = now[0] - old[0], idle = now[1] - old[1];
        return idle > total ? -1 : 100.0 * (total - idle) / total;
    }

    static double displayFps(String line) {
        try {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 2 || !"fps:".equals(fields[0])) return -1;
            double value = Double.parseDouble(fields[1]);
            return Double.isFinite(value) && value >= 0 ? value : -1;
        } catch (RuntimeException malformed) { return -1; }
    }
}
