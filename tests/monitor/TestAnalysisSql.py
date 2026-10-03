"""Actual production SQLite statements: scoped replacement, quota and crash atomicity."""
from pathlib import Path
import json,os,re,sqlite3,subprocess,sys,tempfile
ROOT=Path(__file__).resolve().parents[2]
source=(ROOT/'framework_patch/src/services/com/zui/server/control/MonitorStore.java').read_text('utf8')
body=source.split('void saveAnalysis(',1)[1].split('private static boolean hasAnalysis',1)[0]
create,insert=re.findall(r'next.execSQL\("([^"\n]+)"',body)
quota=re.search(r'next.rawQuery\("([^"\n]+)"',body)[1]
assert 'c.getLong(0)>=16' in body and 'c.getLong(1)+result.length>8*1024*1024' in body
read=source.split('String analysis(',1)[1].split('void removeUser(',1)[0]
select=re.search(r'read.rawQuery\("(SELECT result[^"\n]+)"',read)[1]
delete=re.search(r'read.execSQL\("(DELETE[^"\n]+)"',read)[1]
def save(db,user,pkg,value):
    db.execute(create)
    count,size=db.execute(quota,(user,pkg)).fetchone()
    if count>=16 or size+len(value.encode())>8*1024*1024:raise ValueError('quota')
    db.execute(insert,(user,pkg,value))
if len(sys.argv)>1:
    db=sqlite3.connect(sys.argv[1]);db.execute('BEGIN IMMEDIATE')
    save(db,0,'org.game','{"new":"uncommitted"}');os._exit(17)
with tempfile.TemporaryDirectory(prefix='analysis-sql-') as folder:
    path=Path(folder)/'monitor.db';db=sqlite3.connect(path)
    db.execute('CREATE TABLE retained_records(id INTEGER PRIMARY KEY, data TEXT)')
    db.execute("INSERT INTO retained_records VALUES(1,'owner-record')");db.commit()
    with db:
        save(db,0,'org.game','{"session":"first"}')
        save(db,10,'org.game','{"session":"other-user"}')
        save(db,0,'org.other','{"session":"other-app"}')
    before=db.execute('SELECT * FROM analysis_results ORDER BY user,package').fetchall()
    assert subprocess.run([sys.executable,__file__,str(path)]).returncode==17
    assert db.execute('SELECT * FROM analysis_results ORDER BY user,package').fetchall()==before
    with db:save(db,0,'org.game','{"session":"second"}')
    assert db.execute(select,(10,'org.game')).fetchone()==('{"session":"other-user"}',)
    assert db.execute(select,(0,'org.game')).fetchone()==('{"session":"second"}',)
    with db:
        for i in range(13):save(db,0,'org.slot'+str(i),'{}')
    try:
        with db:save(db,0,'org.extra','{}')
        raise AssertionError('unbounded rows')
    except ValueError:pass
    with db:save(db,0,'org.game','{"session":"replacement-at-cap"}')
    assert db.execute('SELECT COUNT(*) FROM analysis_results').fetchone()==(16,)
    with db:db.execute(delete,(10,'org.game'))
    assert db.execute(select,(0,'org.game')).fetchone()==('{"session":"replacement-at-cap"}',)
    assert db.execute('SELECT * FROM retained_records').fetchall()==[(1,'owner-record')]
    assert db.execute('PRAGMA integrity_check').fetchone()==('ok',)
    db.close()
print('ANALYSIS_SQL_REPLACE_QUOTA_CRASH_USER_RECORD_PRESERVATION=PASS')
