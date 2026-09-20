package com.zui.server.control;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.List;

/** One latest record per user/package. Display and reads never open a writable DB. */
final class MonitorStore {
    private final File file = new File(Environment.getDataDirectory(), "system/zui_control/monitor.db");
    private SQLiteDatabase db;
    long writes, scalarRows, threadRows;
    long beginElapsed, recordId;
    boolean active;

    // Migration is inside the explicit start/delete transaction. Failed replacement rolls
    // back migration too; the original R5 record remains readable until the first write.
    private static void schema(SQLiteDatabase next) {
        if (next.getVersion() > 2) throw new IllegalStateException("record_schema_newer");
        if (next.getVersion() == 2) return;
        boolean legacy;
        try (Cursor c=next.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='record_meta'",null)) {
            legacy=c.moveToFirst();
        }
        if (legacy) next.execSQL("ALTER TABLE record_meta RENAME TO record_meta_r5");
        next.execSQL("CREATE TABLE record_meta (id INTEGER PRIMARY KEY, package TEXT NOT NULL, label TEXT, user INTEGER NOT NULL, pid INTEGER, generation INTEGER, wall INTEGER, elapsed INTEGER, ended INTEGER DEFAULT 0, UNIQUE(user,package))");
        if (legacy) {
            next.execSQL("INSERT INTO record_meta SELECT * FROM record_meta_r5");
            next.execSQL("DROP TABLE record_meta_r5");
            next.execSQL("ALTER TABLE scalar_samples ADD COLUMN record_id INTEGER NOT NULL DEFAULT 1");
            next.execSQL("ALTER TABLE thread_samples ADD COLUMN record_id INTEGER NOT NULL DEFAULT 1");
        } else {
            next.execSQL("CREATE TABLE scalar_samples (t INTEGER, fps REAL, power REAL, quiet REAL, record_id INTEGER NOT NULL)");
            next.execSQL("CREATE TABLE thread_samples (t INTEGER, identity TEXT, name TEXT, cpu REAL, record_id INTEGER NOT NULL)");
        }
        next.execSQL("CREATE INDEX scalar_record_time ON scalar_samples(record_id,t)");
        next.execSQL("CREATE INDEX thread_record_identity_time ON thread_samples(record_id,identity,t)");
        next.setVersion(2);
    }
    private static void remove(SQLiteDatabase next, int user, String pkg) {
        Object[] args={user,pkg};
        next.execSQL("DELETE FROM thread_samples WHERE record_id IN (SELECT id FROM record_meta WHERE user=? AND package=?)",args);
        next.execSQL("DELETE FROM scalar_samples WHERE record_id IN (SELECT id FROM record_meta WHERE user=? AND package=?)",args);
        next.execSQL("DELETE FROM record_meta WHERE user=? AND package=?",args);
    }
    void start(String pkg, String label, int user, int pid, long generation, long elapsed) {
        if (active) throw new IllegalStateException("already_recording");
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
            throw new IllegalStateException("record_directory");
        SQLiteDatabase next = SQLiteDatabase.openOrCreateDatabase(file, null);
        long nextId;
        try {
            next.beginTransaction();
            try {
                schema(next);
                remove(next,user,pkg);
                next.execSQL("INSERT INTO record_meta(package,label,user,pid,generation,wall,elapsed) VALUES(?,?,?,?,?,?,?)",
                        new Object[]{pkg,label,user,pid,generation,System.currentTimeMillis(),elapsed});
                try(Cursor c=next.rawQuery("SELECT last_insert_rowid()",null)){c.moveToFirst();nextId=c.getLong(0);}
                next.setTransactionSuccessful();
            } finally { next.endTransaction(); }
        } catch (RuntimeException e) { next.close(); throw e; }
        db=next; recordId=nextId; active=true; beginElapsed=elapsed; writes++; scalarRows=threadRows=0;
    }
    void append(long now, double fps, double power, double quiet, int pid, long generation,
            List<MonitorSnapshot.Row> threads) {
        if (!active || db == null) throw new IllegalStateException("not_recording");
        int n=Math.min(15,threads.size());
        db.beginTransaction();
        try {
            db.execSQL("INSERT INTO scalar_samples(t,fps,power,quiet,record_id) VALUES(?,?,?,?,?)",
                    new Object[]{now-beginElapsed,valid(fps),valid(power),valid(quiet),recordId});
            for(int i=0;i<n;i++) {
                MonitorSnapshot.Row row=threads.get(i);
                String key=pid+":"+generation+":"+row.task.tid+":"+row.task.start;
                db.execSQL("INSERT INTO thread_samples(t,identity,name,cpu,record_id) VALUES(?,?,?,?,?)",
                        new Object[]{now-beginElapsed,key,row.task.name,valid(row.cpu),recordId});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        scalarRows++; threadRows+=n; writes+=1+n;
    }
    private static Object valid(double n) { return Double.isFinite(n) && n>=0 ? n : null; }
    void finish(long now) {
        if(!active)return;
        db.execSQL("UPDATE record_meta SET ended=? WHERE id=?",new Object[]{now-beginElapsed,recordId});
        writes++; abandon();
    }
    void abandon() { active=false; if(db!=null)db.close(); db=null; }

    String delete(int user,String pkg) {
        if(active)throw new IllegalStateException("stop_recording_before_delete");
        if(pkg==null || !pkg.matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+"))throw new IllegalArgumentException("record_package");
        if(!file.exists())return "ok=1";
        try(SQLiteDatabase next=SQLiteDatabase.openOrCreateDatabase(file,null)) {
            next.beginTransaction();
            try {schema(next);remove(next,user,pkg);next.setTransactionSuccessful();}
            finally {next.endTransaction();}
        }
        writes++;return "ok=1";
    }
    String list(int user) throws Exception {
        JSONObject result=new JSONObject();JSONArray records=new JSONArray();result.put("records",records);
        if(!file.exists())return result.toString();
        try(SQLiteDatabase read=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            String filter=read.getVersion()==2 ? " WHERE record_id=m.id" : "";
            try(Cursor c=read.rawQuery("SELECT package,label,wall,MAX(ended,COALESCE((SELECT MAX(t) FROM scalar_samples"+filter+"),0)),(SELECT AVG(fps) FROM scalar_samples"+filter+"),ended,id FROM record_meta m WHERE user=? ORDER BY wall DESC",new String[]{String.valueOf(user)})) {
                while(c.moveToNext())records.put(new JSONObject().put("package",c.getString(0)).put("label",c.getString(1))
                    .put("wall",c.getLong(2)).put("duration",c.getLong(3)).put("avgFps",c.isNull(4)?JSONObject.NULL:c.getDouble(4))
                    .put("complete",c.getLong(5)>0).put("active",active&&recordId==c.getLong(6)));
            }
        }
        return result.toString();
    }
    String read(int user,String argument) throws Exception {
        if(!file.exists())return "{}";
        JSONObject request=argument.startsWith("{") ? new JSONObject(argument) : new JSONObject().put("thread",argument);
        String pkg=request.optString("package","");String threadKey=request.optString("thread","");
        if(!threadKey.isEmpty() && (threadKey.length()>100 || !threadKey.matches("[0-9]+:[0-9]+:[0-9]+:[0-9]+")))
            throw new IllegalArgumentException("thread_identity");
        try(SQLiteDatabase read=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            JSONObject result=new JSONObject();long duration,id;
            String[] args=pkg.isEmpty()?new String[]{String.valueOf(user)}:new String[]{String.valueOf(user),pkg};
            try(Cursor c=read.rawQuery("SELECT package,label,pid,generation,wall,ended,id FROM record_meta WHERE user=?"+
                    (pkg.isEmpty()?"":" AND package=?")+" ORDER BY wall DESC LIMIT 1",args)) {
                if(!c.moveToFirst())return "{}";
                result.put("package",c.getString(0)).put("label",c.getString(1)).put("pid",c.getInt(2))
                    .put("generation",c.getLong(3)).put("wall",c.getLong(4)).put("complete",c.getLong(5)>0);
                duration=c.getLong(5);id=c.getLong(6);
            }
            String filter=read.getVersion()==2 ? "record_id="+id : "1=1";
            try(Cursor c=read.rawQuery("SELECT COALESCE(MAX(t),0),COUNT(*) FROM scalar_samples WHERE "+filter,null)) {
                c.moveToFirst();duration=Math.max(duration,c.getLong(0));result.put("samples",c.getLong(1));
            }
            result.put("duration",duration).put("active",active&&recordId==id);
            long bucket=Math.max(1000,(duration+599)/600);
            if(!threadKey.isEmpty()) {
                result.put("detail",rows(read,"SELECT MIN(t),AVG(cpu) FROM thread_samples WHERE "+filter+" AND identity=? GROUP BY t/"+bucket+" ORDER BY MIN(t)",new String[]{threadKey},2));
            } else if(request.optBoolean("threads",false)) {
                result.put("threads",rows(read,"SELECT identity,MAX(name),AVG(cpu),MAX(cpu),COUNT(cpu) FROM thread_samples WHERE "+filter+" GROUP BY identity ORDER BY SUM(cpu) DESC LIMIT 50",null,5));
            } else {
                result.put("scalars",rows(read,"SELECT MIN(t),AVG(fps),AVG(power),AVG(quiet) FROM scalar_samples WHERE "+filter+" GROUP BY t/"+bucket+" ORDER BY MIN(t)",null,4));
                result.put("stats",rows(read,"SELECT MIN(fps),AVG(fps),MAX(fps),MIN(power),AVG(power),MAX(power),MIN(quiet),AVG(quiet),MAX(quiet) FROM scalar_samples WHERE "+filter,null,9));
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
