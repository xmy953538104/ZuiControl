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
    long beginElapsed, recordId, recordWall;
    boolean active;

    // Same DB owner, final analysis only. No write occurs for analysis samples or state reads.
    void saveAnalysis(int user,String pkg,byte[] result) {
        if(result.length>ThreadAnalysis.RESULT_LIMIT)throw new IllegalArgumentException("analysis result bound");
        java.util.Map<String,Object> summary;
        try{summary=PolicyJson.object(PolicyJson.parse(result,ThreadAnalysis.RESULT_LIMIT));}catch(Exception e){throw new IllegalArgumentException("analysis result",e);}
        PolicyJson.require(PolicyJson.integer(summary.get("user"))==user&&pkg.equals(summary.get("package")),"analysis result target");
        if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IllegalStateException("record_directory");
        try(SQLiteDatabase next=SQLiteDatabase.openOrCreateDatabase(file,null)){
            next.beginTransaction();
            try{
                if(PolicyJson.integer(summary.get("sourceRecordId"))>0)
                    PolicyJson.require(matchesRecord(next,user,pkg,summary),"analysis source record changed");
                next.execSQL("CREATE TABLE IF NOT EXISTS analysis_results (user INTEGER NOT NULL, package TEXT NOT NULL, result TEXT NOT NULL, PRIMARY KEY(user,package))");
                try(Cursor c=next.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(CAST(result AS BLOB))),0) FROM analysis_results WHERE NOT (user=? AND package=?)",new String[]{String.valueOf(user),pkg})){
                    c.moveToFirst();if(c.getLong(0)>=16||c.getLong(1)+result.length>8*1024*1024)throw new IllegalStateException("analysis storage full; delete a result");
                }
                next.execSQL("INSERT OR REPLACE INTO analysis_results(user,package,result) VALUES(?,?,?)",new Object[]{user,pkg,new String(result,java.nio.charset.StandardCharsets.UTF_8)});
                next.setTransactionSuccessful();
            }finally{next.endTransaction();}
        }
    }
    private static boolean hasAnalysis(SQLiteDatabase database){
        try(Cursor c=database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='analysis_results'",null)){return c.moveToFirst();}
    }
    private static boolean matchesRecord(SQLiteDatabase database,int user,String pkg,java.util.Map<String,Object> result){
        try(Cursor c=database.rawQuery("SELECT id,wall,elapsed,completion,terminal_reason FROM record_meta WHERE user=? AND package=?",new String[]{String.valueOf(user),pkg})){
            return c.moveToFirst()&&c.getLong(0)==PolicyJson.integer(result.get("sourceRecordId"))
                &&c.getLong(1)==PolicyJson.integer(result.get("sourceRecordWall"))
                &&c.getLong(2)==PolicyJson.integer(result.get("sourceRecordStartElapsed"))
                &&c.getString(3).equals(result.get("sourceRecordCompletion"))
                &&c.getString(4).equals(result.get("sourceRecordTerminalReason"));
        }
    }
    String analysis(int user,String pkg,int offset,String digest,boolean delete)throws Exception {
        if(!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")||offset<0)throw new IllegalArgumentException("analysis read identity");
        if(!file.exists())return "{}";
        try(SQLiteDatabase read=SQLiteDatabase.openDatabase(file.getPath(),null,delete?SQLiteDatabase.OPEN_READWRITE:SQLiteDatabase.OPEN_READONLY)){
            if(!hasAnalysis(read))return "{}";
            if(delete){read.execSQL("DELETE FROM analysis_results WHERE user=? AND package=?",new Object[]{user,pkg});return "ok=1";}
            try(Cursor c=read.rawQuery("SELECT result FROM analysis_results WHERE user=? AND package=?",new String[]{String.valueOf(user),pkg})){
                if(!c.moveToFirst())return "{}";byte[] data=c.getString(0).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.util.Map<String,Object> summary=PolicyJson.object(PolicyJson.parse(data,ThreadAnalysis.RESULT_LIMIT));
                // Old standalone summaries are readable for compatibility, but never claim a source record.
                if(summary.containsKey("sourceRecordId")&&PolicyJson.integer(summary.get("sourceRecordId"))>0
                        &&!matchesRecord(read,user,pkg,summary))return "{}";
                String hash=PolicyJson.hash(data);PolicyJson.require(data.length<=ThreadAnalysis.RESULT_LIMIT&&offset<=data.length&&(offset==0||hash.equals(digest)),"analysis read changed");
                return PolicyJson.encode(PolicyJson.map("hash",hash,"size",data.length,"offset",offset,"data",java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(data,offset,Math.min(offset+8192,data.length)))));
            }
        }
    }

    void removeUser(int user) {
        if(user<=0)throw new IllegalArgumentException("cannot retire primary records");
        if(!file.exists())return;
        try(SQLiteDatabase next=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            next.beginTransaction();
            try{
                schema(next);
                Object[] args={user};
                if(hasAnalysis(next))next.execSQL("DELETE FROM analysis_results WHERE user=?",args);
                next.execSQL("DELETE FROM thread_samples WHERE record_id IN (SELECT id FROM record_meta WHERE user=?)",args);
                next.execSQL("DELETE FROM scalar_samples WHERE record_id IN (SELECT id FROM record_meta WHERE user=?)",args);
                next.execSQL("DELETE FROM record_meta WHERE user=?",args);
                next.setTransactionSuccessful();
            }finally{next.endTransaction();}
        }
    }

    // Migration is inside the explicit start/delete transaction. Failed replacement rolls
    // back migration too; the original R5 record remains readable until the first write.
    private static void schema(SQLiteDatabase next) {
        if (next.getVersion() > 4) throw new IllegalStateException("record_schema_newer");
        if (next.getVersion() == 4) return;
        if (next.getVersion() == 3) { snapshotUpgrade(next); return; }
        if (next.getVersion() == 2) { upgrade(next); return; }
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
        upgrade(next);
    }
    private static void upgrade(SQLiteDatabase next) {
        next.execSQL("ALTER TABLE record_meta ADD COLUMN terminal_reason TEXT NOT NULL DEFAULT ''");
        next.execSQL("ALTER TABLE record_meta ADD COLUMN end_elapsed INTEGER NOT NULL DEFAULT 0");
        next.execSQL("ALTER TABLE record_meta ADD COLUMN completion TEXT NOT NULL DEFAULT 'INCOMPLETE'");
        next.execSQL("ALTER TABLE scalar_samples ADD COLUMN source_validity TEXT NOT NULL DEFAULT 'LEGACY_UNQUALIFIED_FPS'");
        next.execSQL("UPDATE record_meta SET completion=CASE WHEN ended>0 THEN 'COMPLETE' ELSE 'INCOMPLETE' END, terminal_reason=CASE WHEN ended>0 THEN 'LEGACY_END' ELSE 'CRASH_RECOVERY' END, end_elapsed=elapsed+ended");
        next.execSQL("ALTER TABLE record_meta ADD COLUMN task_id INTEGER NOT NULL DEFAULT -1");
        next.execSQL("ALTER TABLE record_meta ADD COLUMN target_epoch INTEGER NOT NULL DEFAULT 0");
        next.execSQL("ALTER TABLE thread_samples ADD COLUMN interval_start INTEGER NOT NULL DEFAULT -1");
        next.execSQL("ALTER TABLE thread_samples ADD COLUMN interval_end INTEGER NOT NULL DEFAULT -1");
        next.execSQL("ALTER TABLE thread_samples ADD COLUMN cpu_convention TEXT NOT NULL DEFAULT 'ONE_CORE_100_PERCENT'");
        next.setVersion(3);
        snapshotUpgrade(next);
    }
    private static void snapshotUpgrade(SQLiteDatabase next){
        next.execSQL("ALTER TABLE record_meta ADD COLUMN policy_snapshot TEXT NOT NULL DEFAULT '{}'");
        next.setVersion(4);
    }
    private static void remove(SQLiteDatabase next, int user, String pkg) {
        Object[] args={user,pkg};
        if(hasAnalysis(next))next.execSQL("DELETE FROM analysis_results WHERE user=? AND package=?",args);
        next.execSQL("DELETE FROM thread_samples WHERE record_id IN (SELECT id FROM record_meta WHERE user=? AND package=?)",args);
        next.execSQL("DELETE FROM scalar_samples WHERE record_id IN (SELECT id FROM record_meta WHERE user=? AND package=?)",args);
        next.execSQL("DELETE FROM record_meta WHERE user=? AND package=?",args);
    }
    void start(String pkg, String label, int user, int pid, long generation, long elapsed,int taskId,long targetEpoch) {
        start(pkg,label,user,pid,generation,elapsed,taskId,targetEpoch,"{}");
    }
    void start(String pkg, String label, int user, int pid, long generation, long elapsed,int taskId,long targetEpoch,String policySnapshot) {
        if(policySnapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException("record policy snapshot bound");
        if (active) throw new IllegalStateException("already_recording");
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
            throw new IllegalStateException("record_directory");
        SQLiteDatabase next = SQLiteDatabase.openOrCreateDatabase(file, null);
        long nextId,wall=System.currentTimeMillis();
        try {
            next.beginTransaction();
            try {
                schema(next);
                remove(next,user,pkg);
                next.execSQL("INSERT INTO record_meta(package,label,user,pid,generation,wall,elapsed,terminal_reason,task_id,target_epoch) VALUES(?,?,?,?,?,?,?,'CRASH_RECOVERY',?,?)",
                        new Object[]{pkg,label,user,pid,generation,wall,elapsed,taskId,targetEpoch});
                try(Cursor c=next.rawQuery("SELECT last_insert_rowid()",null)){c.moveToFirst();nextId=c.getLong(0);}
                next.execSQL("UPDATE record_meta SET policy_snapshot=? WHERE id=?",new Object[]{policySnapshot,nextId});
                next.setTransactionSuccessful();
            } finally { next.endTransaction(); }
        } catch (RuntimeException e) { next.close(); throw e; }
        db=next; recordId=nextId; recordWall=wall; active=true; beginElapsed=elapsed; writes++; scalarRows=threadRows=0;
    }
    void append(long now, double fps, double power, double quiet, int pid, long generation,
            List<MonitorSnapshot.Row> threads, String fpsValidity) {
        if (!active || db == null) throw new IllegalStateException("not_recording");
        int n=Math.min(15,threads.size());
        db.beginTransaction();
        try {
            db.execSQL("INSERT INTO scalar_samples(t,fps,power,quiet,record_id,source_validity) VALUES(?,?,?,?,?,?)",
                    new Object[]{now-beginElapsed,valid(fps),valid(power),valid(quiet),recordId,
                        "fps="+fpsValidity+";fpsSource=DISPLAY_MEASURED_FPS"+";consumption="+(power<0?"UNAVAILABLE":"VALID")+";quiet="+(quiet<0?"UNAVAILABLE":"VALID")});
            for(int i=0;i<n;i++) {
                MonitorSnapshot.Row row=threads.get(i);
                String key=pid+":"+generation+":"+row.task.tid+":"+row.task.start;
                db.execSQL("INSERT INTO thread_samples(t,identity,name,cpu,record_id,interval_start,interval_end) VALUES(?,?,?,?,?,?,?)",
                        new Object[]{now-beginElapsed,key,row.task.name,valid(row.cpu),recordId,Math.max(0,now-beginElapsed-row.intervalMs),now-beginElapsed});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        scalarRows++; threadRows+=n; writes+=1+n;
    }
    private static Object valid(double n) { return Double.isFinite(n) && n>=0 ? n : null; }
    void finish(long now,String reason,boolean incomplete) {
        if(!active)return;
        db.execSQL("UPDATE record_meta SET ended=?,end_elapsed=?,terminal_reason=?,completion=? WHERE id=?",
                new Object[]{Math.max(0,now-beginElapsed),now,reason,incomplete?"INCOMPLETE":"COMPLETE",recordId});
        writes++; abandon();
    }
    void abandon() { active=false; SQLiteDatabase closing=db; db=null; if(closing!=null)closing.close(); }

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
            String filter=read.getVersion()>=2 ? " WHERE record_id=m.id" : "";
            try(Cursor c=read.rawQuery("SELECT package,label,wall,MAX(ended,COALESCE((SELECT MAX(t) FROM scalar_samples"+filter+"),0)),(SELECT AVG(fps) FROM scalar_samples"+filter+"),ended,id"+(read.getVersion()>=3?",completion,terminal_reason,end_elapsed":"")+" FROM record_meta m WHERE user=? ORDER BY wall DESC",new String[]{String.valueOf(user)})) {
                while(c.moveToNext())records.put(new JSONObject().put("package",c.getString(0)).put("label",c.getString(1))
                    .put("wall",c.getLong(2)).put("duration",c.getLong(3)).put("avgFps",c.isNull(4)?JSONObject.NULL:c.getDouble(4))
                    .put("complete",read.getVersion()>=3?"COMPLETE".equals(c.getString(7)):c.getLong(5)>0)
                    .put("terminalReason",read.getVersion()>=3?c.getString(8):"LEGACY")
                    .put("endElapsed",read.getVersion()>=3?c.getLong(9):0).put("active",active&&recordId==c.getLong(6)));
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
            try(Cursor c=read.rawQuery("SELECT package,label,pid,generation,wall,ended,id"+(read.getVersion()>=3?",completion,terminal_reason,end_elapsed":"")+(read.getVersion()>=4?",policy_snapshot":"")+" FROM record_meta WHERE user=?"+
                    (pkg.isEmpty()?"":" AND package=?")+" ORDER BY wall DESC LIMIT 1",args)) {
                if(!c.moveToFirst())return "{}";
                result.put("recordId",c.getLong(6)).put("user",user).put("package",c.getString(0)).put("label",c.getString(1)).put("pid",c.getInt(2))
                    .put("generation",c.getLong(3)).put("wall",c.getLong(4))
                    .put("complete",read.getVersion()>=3?"COMPLETE".equals(c.getString(7)):c.getLong(5)>0)
                    .put("terminalReason",read.getVersion()>=3?c.getString(8):"LEGACY")
                    .put("endElapsed",read.getVersion()>=3?c.getLong(9):0)
                    .put("policySnapshot",read.getVersion()>=4?new JSONObject(c.getString(10)):JSONObject.NULL)
                    .put("threadCoverage","TOP15_OBSERVED_ONLY");
                duration=c.getLong(5);id=c.getLong(6);
            }
            try(Cursor c=read.rawQuery("SELECT elapsed FROM record_meta WHERE id=?",new String[]{String.valueOf(id)})){
                if(c.moveToFirst())result.put("startElapsed",c.getLong(0));
            }
            String filter=read.getVersion()>=2 ? "record_id="+id : "1=1";
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
