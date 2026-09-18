package com.zui.server.control;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.List;

/** One transactional slot. Display/arming never opens a writable database. */
final class MonitorStore {
    private final File file = new File(Environment.getDataDirectory(), "system/zui_control/monitor.db");
    private SQLiteDatabase db;
    long writes, scalarRows, threadRows;
    long beginElapsed;
    boolean active;

    void start(String pkg, String label, int user, int pid, long generation, long elapsed) {
        if (active) throw new IllegalStateException("already_recording");
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
            throw new IllegalStateException("record_directory");
        SQLiteDatabase next = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            next.beginTransaction();
            try {
                next.execSQL("CREATE TABLE IF NOT EXISTS record_meta (id INTEGER PRIMARY KEY CHECK(id=1), package TEXT, label TEXT, user INTEGER, pid INTEGER, generation INTEGER, wall INTEGER, elapsed INTEGER, ended INTEGER DEFAULT 0)");
                next.execSQL("CREATE TABLE IF NOT EXISTS scalar_samples (t INTEGER, fps REAL, power REAL, quiet REAL)");
                next.execSQL("CREATE TABLE IF NOT EXISTS thread_samples (t INTEGER, identity TEXT, name TEXT, cpu REAL)");
                next.execSQL("DELETE FROM thread_samples"); next.execSQL("DELETE FROM scalar_samples"); next.execSQL("DELETE FROM record_meta");
                next.execSQL("INSERT INTO record_meta(id,package,label,user,pid,generation,wall,elapsed) VALUES(1,?,?,?,?,?,?,?)",
                        new Object[]{pkg,label,user,pid,generation,System.currentTimeMillis(),elapsed});
                next.setTransactionSuccessful();
            } finally { next.endTransaction(); }
        } catch (RuntimeException e) { next.close(); throw e; }
        db = next; active = true; beginElapsed = elapsed; writes++; scalarRows = threadRows = 0;
    }
    void append(long now, double fps, double power, double quiet, int pid, long generation,
            List<MonitorSnapshot.Row> threads) {
        if (!active || db == null) throw new IllegalStateException("not_recording");
        int n = Math.min(15, threads.size());
        db.beginTransaction();
        try {
            db.execSQL("INSERT INTO scalar_samples VALUES(?,?,?,?)", new Object[]{now-beginElapsed,valid(fps),valid(power),valid(quiet)});
            for (int i=0;i<n;i++) {
                MonitorSnapshot.Row row=threads.get(i);
                String key=pid+":"+generation+":"+row.task.tid+":"+row.task.start;
                db.execSQL("INSERT INTO thread_samples VALUES(?,?,?,?)",new Object[]{now-beginElapsed,key,row.task.name,valid(row.cpu)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        scalarRows++; threadRows+=n; writes+=1+n;
    }
    private static Object valid(double n) { return Double.isFinite(n) && n>=0 ? n : null; }
    void finish(long now) {
        if (!active) return;
        db.execSQL("UPDATE record_meta SET ended=? WHERE id=1",new Object[]{now-beginElapsed});
        writes++; abandon();
    }
    void abandon() { active=false; if(db!=null)db.close(); db=null; }

    String read(int user, String threadKey) throws Exception {
        if (!file.exists()) return "{}";
        // Read-only open neither creates the file nor changes an interrupted recording to completed.
        try (SQLiteDatabase read=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            JSONObject result=new JSONObject(); long duration;
            try(Cursor c=read.rawQuery("SELECT package,label,user,pid,generation,wall,elapsed,ended FROM record_meta WHERE id=1",null)) {
                if(!c.moveToFirst() || c.getInt(2)!=user)return "{}";
                result.put("package",c.getString(0)).put("label",c.getString(1)).put("pid",c.getInt(3))
                        .put("generation",c.getLong(4)).put("wall",c.getLong(5)).put("complete",c.getLong(7)>0);
                duration=c.getLong(7);
            }
            try(Cursor c=read.rawQuery("SELECT COALESCE(MAX(t),0),COUNT(*) FROM scalar_samples",null)) {
                c.moveToFirst(); duration=Math.max(duration,c.getLong(0));result.put("samples",c.getLong(1));
            }
            result.put("duration",duration).put("active",active);
            long bucket=Math.max(1000,(duration+599)/600);
            if(threadKey.isEmpty()) {
                result.put("scalars",rows(read,"SELECT MIN(t),AVG(fps),AVG(power),AVG(quiet) FROM scalar_samples GROUP BY t/"+bucket+" ORDER BY MIN(t)",null,4));
                result.put("threads",rows(read,"SELECT identity,MAX(name),AVG(cpu),MAX(cpu),COUNT(cpu) FROM thread_samples GROUP BY identity ORDER BY SUM(cpu) DESC LIMIT 50",null,5));
            } else {
                if(threadKey.length()>100 || !threadKey.matches("[0-9]+:[0-9]+:[0-9]+:[0-9]+"))throw new IllegalArgumentException("thread_identity");
                result.put("detail",rows(read,"SELECT MIN(t),AVG(cpu) FROM thread_samples WHERE identity=? GROUP BY t/"+bucket+" ORDER BY MIN(t)",new String[]{threadKey},2));
            }
            return result.toString();
        }
    }
    private static JSONArray rows(SQLiteDatabase db,String sql,String[] args,int columns) throws Exception {
        JSONArray result=new JSONArray();
        try(Cursor c=db.rawQuery(sql,args)) {
            while(c.moveToNext()) {
                JSONArray row=new JSONArray();
                for(int i=0;i<columns;i++)row.put(c.isNull(i)?JSONObject.NULL:
                        c.getType(i)==Cursor.FIELD_TYPE_STRING?c.getString(i):c.getDouble(i));
                result.put(row);
            }
        }
        return result;
    }
}
