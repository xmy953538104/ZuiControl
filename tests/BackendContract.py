"""Exact R1 authorized delta reversal; historical engine hashes remain unchanged."""
from pathlib import Path
import hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[1]
MANIFEST=json.loads((ROOT/'tests/backend_abc_delta.json').read_text(encoding='utf-8'))

def reverse_text(path,text):
    row=next(r for r in MANIFEST['files'] if r['path']==path)
    for h in reversed(row['hunks']):
        assert text.count(h['after'])==1,('R1 unauthorized delta',path,h['after'][:80])
        text=text.replace(h['after'],h['before'],1)
    assert hashlib.sha256(text.encode()).hexdigest()==row['beforeSha256'],('R1 frozen base',path)
    return text

def entries(*scopes):
    paths=subprocess.check_output(['git','-C',str(ROOT),'ls-files','--cached','--others','--exclude-standard','--',*scopes],text=True).splitlines()
    modes={line.split('\t',1)[1]:line.split(' ',1)[0] for line in subprocess.check_output(['git','-C',str(ROOT),'ls-files','--stage','--',*scopes],text=True).splitlines()}
    result=[]
    for path in sorted(set(paths)):
        if not (ROOT/path).is_file():continue
        data=(ROOT/path).read_bytes()
        if b'\0' not in data:data=data.replace(b'\r\n',b'\n')
        oid=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
        result.append((modes.get(path,'100644')+' blob '+oid+'\t'+path).encode())
    return result

def reverse_entries(current,*scopes):
    result=list(current)
    for row in MANIFEST['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('R1 exact source bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    return result
