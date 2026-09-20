"""Execute exact production migration/mutation SQL on SQLite, including abrupt interruption."""
from pathlib import Path
import os,re,sqlite3,subprocess,sys,tempfile
ROOT=Path(__file__).resolve().parents[2]
src=(ROOT/'framework_patch/src/services/com/zui/server/control/MonitorStore.java').read_text(encoding='utf8')
sql=re.findall(r'(?:next|db)\.execSQL\("([^"\n]+)"',src)
def statement(prefix):
    found=[s for s in sql if s.startswith(prefix)]
    assert len(found)==1,(prefix,found)
    return found[0]
def migrate(db):
    if db.execute('PRAGMA user_version').fetchone()[0]==2:return
    legacy=bool(db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='record_meta'").fetchone())
    if legacy:db.execute(statement('ALTER TABLE record_meta'))
    db.execute(statement('CREATE TABLE record_meta '))
    for prefix in (['INSERT INTO record_meta SELECT','DROP TABLE','ALTER TABLE scalar','ALTER TABLE thread'] if legacy else ['CREATE TABLE scalar','CREATE TABLE thread']):db.execute(statement(prefix))
    for s in sql:
        if s.startswith('CREATE INDEX'):db.execute(s)
    db.execute('PRAGMA user_version=2')
def remove(db,pkg,user=0):
    for s in sql:
        if s.startswith('DELETE FROM'):db.execute(s,(user,pkg))
def start(db,pkg,wall=100,user=0):
    migrate(db);remove(db,pkg,user)
    db.execute(statement('INSERT INTO record_meta(package'),(pkg,pkg,user,42,100,wall,200))
    return db.execute('select last_insert_rowid()').fetchone()[0]
def rows(db):
    return {n:db.execute('select * from '+n+' order by rowid').fetchall() for n in ('record_meta','scalar_samples','thread_samples')}
if len(sys.argv)>1 and sys.argv[1]=='--interrupt':
    db=sqlite3.connect(sys.argv[2]);db.execute('begin immediate');start(db,'app.a',999);os._exit(17)
with tempfile.TemporaryDirectory(prefix='zui-r6-record-') as tmp:
    path=Path(tmp)/'monitor.db';db=sqlite3.connect(path)
    # Exact R5 schema and a retained completed recording with TID/process reuse.
    db.execute('CREATE TABLE record_meta (id INTEGER PRIMARY KEY CHECK(id=1), package TEXT, label TEXT, user INTEGER, pid INTEGER, generation INTEGER, wall INTEGER, elapsed INTEGER, ended INTEGER DEFAULT 0)')
    db.execute('CREATE TABLE scalar_samples (t INTEGER, fps REAL, power REAL, quiet REAL)')
    db.execute('CREATE TABLE thread_samples (t INTEGER, identity TEXT, name TEXT, cpu REAL)')
    db.execute("INSERT INTO record_meta VALUES(1,'app.a','A',0,42,100,10,200,4000)")
    db.executemany('INSERT INTO scalar_samples VALUES(?,?,?,?)',[(1000,60,4,38),(2000,40,None,39),(3000,80,6,40)])
    db.executemany('INSERT INTO thread_samples VALUES(?,?,?,?)',[(1000,k,'GameThread',50) for k in ('42:100:9:11','42:100:9:12','42:101:9:12')]);db.commit()
    before=rows(db)
    db.execute('BEGIN');migrate(db);db.rollback()
    assert rows(db)==before and db.execute('pragma user_version').fetchone()==(0,)
    with db:migrate(db)
    after=rows(db)
    assert after['record_meta']==before['record_meta']
    for table in ('scalar_samples','thread_samples'):
        assert [r[:-1] for r in after[table]]==before[table] and all(r[-1]==1 for r in after[table])
    assert db.execute('select count(distinct identity) from thread_samples').fetchone()==(3,)
    stats=db.execute('SELECT MIN(fps),AVG(fps),MAX(fps),MIN(power),AVG(power),MAX(power),MIN(quiet),AVG(quiet),MAX(quiet) FROM scalar_samples WHERE record_id=1').fetchone()
    assert stats==(40,60,80,4,5,6,38,39,40)
    with db:
        b=start(db,'app.b',200)
        db.execute(statement('INSERT INTO scalar_samples'),(1000,90,None,41,b))
    assert db.execute('select count(*) from record_meta').fetchone()==(2,)
    before_interrupt=rows(db)
    crash=subprocess.run([sys.executable,__file__,'--interrupt',str(path)])
    assert crash.returncode==17
    assert rows(db)==before_interrupt
    b_before=[r for r in rows(db)['scalar_samples'] if r[-1]==b]
    with db:a=start(db,'app.a',300)
    assert db.execute("SELECT wall FROM record_meta WHERE package='app.a'").fetchone()==(300,)
    assert db.execute('select count(*) from record_meta').fetchone()==(2,)
    assert [r for r in rows(db)['scalar_samples'] if r[-1]==b]==b_before
    # User separation also survives replacement/deletion of the same package name.
    with db:other=start(db,'app.a',400,user=10)
    with db:remove(db,'app.a')
    assert db.execute('select user,package from record_meta order by user').fetchall()==[(0,'app.b'),(10,'app.a')]
    assert db.execute('select * from scalar_samples').fetchall()==b_before
    assert db.execute('pragma integrity_check').fetchone()==('ok',)
    db.close()
    # No file mutation from merely opening the records page.
    raw=path.read_bytes()
    with sqlite3.connect(f'file:{path.as_posix()}?mode=ro',uri=True) as read:assert read.execute('select count(*) from record_meta').fetchone()==(2,)
    read.close()
    assert path.read_bytes()==raw
assert 'SQLiteDatabase.OPEN_READONLY' in src.split('String list(',1)[1]
print('PER_APP_LATEST_MIGRATION_PRESERVATION_REPLACEMENT_DELETE_CRASH_ROLLBACK_USER_ISOLATION_STATS_GENERATIONS=PASS')
