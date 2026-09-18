package com.zui.server.control;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One observer-only collector. No shell, control writes, independent view scanners or OFF timer. */
final class MonitorCollector {
    static final String CALLBACK = "android.zui.IMonitorSnapshot";
    private final Context context;
    private IBinder callback;
    private IBinder.DeathRecipient death;
    private HandlerThread thread;
    private Handler handler;
    private long epoch, samples, reads;
    private String target = "", lastSnapshot = "{}", error = "";
    private int targetUser;
    private boolean expanded;
    private List<MonitorSnapshot.Task> previous = Collections.emptyList();
    private int previousPid;
    private long previousStart, previousTime;
    private long[] previousCpu;
    private final long hz = Os.sysconf(OsConstants._SC_CLK_TCK);

    MonitorCollector(Context context) { this.context = context; }

    synchronized void register(IBinder binder) throws android.os.RemoteException {
        stop();
        if (callback != null && death != null) callback.unlinkToDeath(death, 0);
        callback = binder;
        lastSnapshot = "{}";
        death = null;
        if (binder != null) {
            death = () -> {
                synchronized (MonitorCollector.this) {
                    if (callback == binder) { stop(); callback = null; death = null; }
                }
            };
            binder.linkToDeath(death, 0);
        }
    }

    synchronized void select(String pkg, int user, boolean showThreads) {
        if (pkg.equals(target) && user == targetUser && expanded == showThreads
                && (pkg.isEmpty() || thread != null)) return;
        stop();
        expanded = showThreads;
        if (pkg.isEmpty() || callback == null) return;
        target = pkg; targetUser = user;
        previous = Collections.emptyList(); previousPid = 0; previousStart = 0;
        previousTime = 0; previousCpu = null; error = "";
        thread = new HandlerThread("ZuiMonitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        final long ticket = epoch;
        handler.post(() -> sample(ticket));
    }

    synchronized void stop() {
        epoch++;
        target = "";
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        handler = null; thread = null;
        previous = Collections.emptyList(); previousCpu = null;
        deliver("{\"active\":false}");
    }

    synchronized String state() {
        return "\nmonitorActive=" + (thread != null) + "\nmonitorTarget=" + target
                + "\nmonitorSamples=" + samples + "\nmonitorReads=" + reads
                + "\nmonitorIntervalMs=" + MonitorSnapshot.INTERVAL_MS
                + "\nmonitorTimer=" + (handler != null) + "\nmonitorError=" + error;
    }

    synchronized String snapshot() { return lastSnapshot; }

    private void sample(long ticket) {
        // Serialize stop with bounded proc reads; no old generation can enqueue after stop.
        synchronized (this) {
            if (ticket != epoch || handler == null || target.isEmpty()) return;
            try {
                KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                if (keyguard == null || keyguard.isKeyguardLocked()) { stop(); return; }
                long now = SystemClock.elapsedRealtime();
                int pid = findPid();
                MonitorSnapshot.Task process = pid > 0 ? MonitorSnapshot.Task.parse(read("/proc/" + pid + "/stat")) : null;
                List<MonitorSnapshot.Task> tasks = new ArrayList<>();
                if (process != null) {
                    File[] entries = new File("/proc/" + pid + "/task").listFiles();
                    if (entries == null) throw new java.io.IOException("task_directory_unreadable");
                    if (entries.length > 8192) throw new java.io.IOException("task_population_limit");
                    for (File entry : entries) {
                        if (!entry.getName().matches("[0-9]+")) continue;
                        MonitorSnapshot.Task task = MonitorSnapshot.Task.parse(read(entry + "/stat"));
                        if (task != null) tasks.add(task);
                    }
                    MonitorSnapshot.Task after = MonitorSnapshot.Task.parse(read("/proc/" + pid + "/stat"));
                    if (after == null || after.start != process.start) { process = null; tasks.clear(); }
                }
                boolean same = process != null && previousPid == pid && previousStart == process.start;
                List<MonitorSnapshot.Row> rows = MonitorSnapshot.delta(previous, tasks,
                        now - previousTime, hz, same);
                long[] cpu = MonitorSnapshot.cpuTimes(read("/proc/stat"));
                JSONObject data = new JSONObject();
                data.put("active", true).put("package", target).put("pid", process == null ? 0 : pid)
                        .put("elapsedMs", now).put("expanded", expanded).put("sample", ++samples)
                        .put("cpu", MonitorSnapshot.aggregateCpu(previousCpu, cpu))
                        .put("fps", MonitorSnapshot.displayFps(read("/sys/class/drm/sde-crtc-0/measured_fps")))
                        .put("fpsSource", "display_measured_fps_not_game_present")
                        .put("gpuMHz", numeric("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", 1000000))
                        .put("gpuPercent", numeric("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", 1));
                Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                data.put("batteryC", battery != null && battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)
                        ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0 : -1);
                JSONArray jsonRows = new JSONArray();
                for (MonitorSnapshot.Row row : rows) {
                    jsonRows.put(new JSONObject().put("tid", row.task.tid)
                            .put("name", row.task.name).put("cpu", row.cpu));
                }
                data.put("threads", jsonRows).put("population", tasks.size())
                        .put("threadStatus", process == null ? "process_unavailable" : tasks.isEmpty() ? "threads_unreadable" : "ok")
                        .put("reads", reads);
                previous = tasks; previousPid = pid;
                previousStart = process == null ? 0 : process.start;
                previousTime = now; previousCpu = cpu;
                lastSnapshot = data.toString();
                deliver(lastSnapshot);
            } catch (Exception failure) {
                error = failure.getClass().getSimpleName() + ":" + failure.getMessage();
                stop();
            }
            if (ticket == epoch && handler != null) handler.postDelayed(() -> sample(ticket), MonitorSnapshot.INTERVAL_MS);
        }
    }

    private int findPid() {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (target.equals(p.processName) && p.uid / 100000 == targetUser) return p.pid;
        }
        return 0;
    }

    private String read(String path) {
        reads++;
        try (BufferedReader reader = new BufferedReader(new FileReader(path), 1024)) {
            // Every chosen source is one line; bound malformed input without readLine's unlimited allocation.
            char[] text = new char[4096];
            int n = reader.read(text);
            if (n <= 0) return "";
            String s = new String(text, 0, n);
            int newline = s.indexOf('\n');
            return newline < 0 ? s : s.substring(0, newline);
        } catch (java.io.IOException unavailable) { return ""; }
    }

    private double numeric(String path, double divisor) {
        try {
            double n = Double.parseDouble(read(path).trim().replace("%", "")) / divisor;
            return Double.isFinite(n) && n >= 0 ? n : -1;
        } catch (RuntimeException unavailable) { return -1; }
    }

    private void deliver(String snapshot) {
        if (callback == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CALLBACK); data.writeString(snapshot);
            callback.transact(1, data, null, IBinder.FLAG_ONEWAY);
        } catch (android.os.RemoteException dead) {
            callback = null;
            // Death recipient will cancel sampling; also fail closed if it has not run yet.
            if (handler != null) { handler.removeCallbacksAndMessages(null); thread.quitSafely(); }
            handler = null; thread = null; target = ""; epoch++;
        } finally { data.recycle(); }
    }
}
