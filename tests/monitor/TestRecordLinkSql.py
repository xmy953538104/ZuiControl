"""Real collector result bytes against production relation/replacement/deletion SQL on SQLite.

Platform SQLite bindings remain an Android merged-candidate gate; no handwritten
summary is used as the successful producer input here.
"""
from pathlib import Path
import json,re,sqlite3,subprocess,sys,tempfile

HERE=Path(__file__).resolve().parent
namespace={'__file__':str(HERE/'TestRecordSql.py')}
text=(HERE/'TestRecordSql.py').read_text('utf8')
exec(compile(text[:text.index("if len(sys.argv)>1")],str(HERE/'TestRecordSql.py'),'exec'),namespace)
source=namespace['src'];statement=namespace['statement'];remove=namespace['remove'];migrate=namespace['migrate']
relation=source.split('private static boolean matchesRecord(',1)[1].split('String analysis(',1)[0]
query=re.search(r'rawQuery\("([^"]+)"',relation)[1]
assert all(name in relation for name in ('sourceRecordId','sourceRecordWall','sourceRecordStartElapsed','sourceRecordCompletion','sourceRecordTerminalReason'))
assert 'matchesRecord(next,user,pkg,summary)' in source and '!matchesRecord(read,user,pkg,summary)' in source
save=source.split('void saveAnalysis(',1)[1].split('private static boolean hasAnalysis',1)[0]
create,insert=re.findall(r'next.execSQL\("([^"\n]+)"',save)
with tempfile.TemporaryDirectory(prefix='record-relation-') as tmp:
    result_path=Path(tmp)/'result.json'
    subprocess.run([sys.executable,str(HERE/'TestAnalysisCollector.py'),str(result_path)],check=True)
    result=result_path.read_bytes();value=json.loads(result);db=sqlite3.connect(Path(tmp)/'monitor.db')
    with db:
        migrate(db);db.execute(create)
        # Preserve lower IDs to obtain the producer's exact source ID via the actual insert SQL.
        for index in range(value['sourceRecordId']):
            pkg=value['package'] if index+1==value['sourceRecordId'] else 'org.slot'+str(index)
            db.execute(statement('INSERT INTO record_meta(package'),(pkg,pkg,value['user'],42,1,value['sourceRecordWall'],value['sourceRecordStartElapsed'],1,1))
        record=db.execute('select last_insert_rowid()').fetchone()[0]
        assert record==value['sourceRecordId']
        db.execute(statement('UPDATE record_meta SET ended='),(value['wallElapsedMs'],value['sourceRecordStartElapsed']+value['wallElapsedMs'],value['sourceRecordTerminalReason'],value['sourceRecordCompletion'],record))
        actual=db.execute(query,(value['user'],value['package'])).fetchone()
        expected=tuple(value[key] for key in ('sourceRecordId','sourceRecordWall','sourceRecordStartElapsed','sourceRecordCompletion','sourceRecordTerminalReason'))
        assert actual==expected
        db.execute(insert,(value['user'],value['package'],result.decode()))
        db.execute(insert,(10,value['package'],'{"unrelated":"other user"}'))
    for key in ('sourceRecordId','sourceRecordWall','sourceRecordStartElapsed','sourceRecordCompletion','sourceRecordTerminalReason'):
        stale=dict(value);stale[key]=stale[key]+1 if isinstance(stale[key],int) else 'OTHER'
        assert actual!=tuple(stale[name] for name in ('sourceRecordId','sourceRecordWall','sourceRecordStartElapsed','sourceRecordCompletion','sourceRecordTerminalReason'))
    assert db.execute(query,(10,value['package'])).fetchone() is None
    before=db.execute('select * from analysis_results order by user').fetchall()
    db.execute('begin');remove(db,value['package'],value['user']);db.rollback()
    assert db.execute('select * from analysis_results order by user').fetchall()==before
    with db:remove(db,value['package'],value['user'])
    assert db.execute(query,(value['user'],value['package'])).fetchone() is None
    assert db.execute('select user from analysis_results').fetchall()==[(10,)]
    with db:
        db.execute(statement('INSERT INTO record_meta(package'),(value['package'],value['package'],0,42,1,value['sourceRecordWall']+1,value['sourceRecordStartElapsed']+1,1,1))
    assert db.execute(query,(0,value['package'])).fetchone()!=expected
    assert db.execute('select user from analysis_results').fetchall()==[(10,)] # replacement cannot resurrect previous result
    assert db.execute('pragma integrity_check').fetchone()==('ok',)
    db.close()
print('RECORD_ANALYSIS_SQL_PRODUCER_RELATION_REPLACEMENT_DELETE_TRANSACTION_USER=PASS')
