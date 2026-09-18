"""Execute exact production schema and replacement SQL using stdlib SQLite."""
import re,sqlite3,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
source=(root/'framework_patch/src/services/com/zui/server/control/MonitorStore.java').read_text(encoding='utf8')
ddl=re.findall(r'next.execSQL\("(CREATE TABLE[^"\n]+)"\)',source)
deletes=re.findall(r'next.execSQL\("(DELETE FROM[^"\n]+)"\)',source)
insert=re.search(r'next.execSQL\("(INSERT INTO record_meta[^"\n]+)"',source)[1]
assert len(ddl)==3 and len(deletes)==3
with tempfile.TemporaryDirectory() as tmp:
 path=Path(tmp)/'record.db'
 assert not path.exists() # display/arming do not instantiate production writable store
 db=sqlite3.connect(path)
 def begin(pkg,fail=False):
  with db:
   for sql in ddl+deletes:db.execute(sql)
   db.execute(insert,(pkg,pkg,0,42,100,123,200))
   if fail:raise RuntimeError('injected_start_failure')
 begin('one')
 try:begin('two',True)
 except RuntimeError:pass
 assert db.execute('select package from record_meta').fetchone()==('one',)
 db.close();db=sqlite3.connect(path)
 assert db.execute('select ended from record_meta').fetchone()==(0,) # incomplete survives close/reopen
 begin('two')
 assert db.execute('select count(*),package from record_meta').fetchone()==(1,'two')
 with db:
  for key in ('42:100:9:11','42:100:9:12','42:101:9:12'):
   db.execute('insert into thread_samples values(?,?,?,?)',(1000,key,'GameThread',50))
 assert db.execute('select count(distinct identity) from thread_samples').fetchone()==(3,)
 db.close()
print('SINGLE_SLOT_TRANSACTION_ROLLBACK_INCOMPLETE_AND_GENERATIONS=PASS')
