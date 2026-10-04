"""Exact R1 authorized delta reversal; historical engine hashes remain unchanged."""
from pathlib import Path
import hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[1]
FRONTEND_INTEGRATION=json.loads((ROOT/'tests/backend_frontend_integration_delta.json').read_text(encoding='utf-8'))
FRONTEND_DELTA=json.loads((ROOT/'tests/backend_frontend_delta.json').read_text(encoding='utf-8'))
REAL_WIRE=json.loads((ROOT/'tests/command_plane/utility_wire_delta.json').read_text(encoding='utf-8'))
RESIDUAL=json.loads((ROOT/'tests/command_plane/residual_fix_delta.json').read_text(encoding='utf-8'))
USER_DOMAIN=json.loads((ROOT/'tests/command_plane/user_identity_delta.json').read_text(encoding='utf-8'))
RECOVERY=json.loads((ROOT/'tests/command_plane/policy_recovery_delta.json').read_text(encoding='utf-8'))
OPTION_B=json.loads((ROOT/'tests/command_plane/option_b_delta.json').read_text(encoding='utf-8'))
HARDENING=json.loads((ROOT/'tests/backend_hardening_delta.json').read_text(encoding='utf-8'))
CLOSURE=json.loads((ROOT/'tests/backend_monitor_closure_delta.json').read_text(encoding='utf-8'))
BOUNDARY=json.loads((ROOT/'tests/backend_property_boundary_delta.json').read_text(encoding='utf-8'))
BINDER=json.loads((ROOT/'tests/backend_binder_delta.json').read_text(encoding='utf-8'))
BOOTSTRAP=json.loads((ROOT/'tests/backend_bootstrap_delta.json').read_text(encoding='utf-8'))
INTEGRATION=json.loads((ROOT/'tests/backend_monitor_settings_delta.json').read_text(encoding='utf-8'))
MANIFEST=json.loads((ROOT/'tests/backend_abc_delta.json').read_text(encoding='utf-8'))

def reverse_text(path,text):
    change=next((r for r in FRONTEND_INTEGRATION['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('V84 exact authorized bytes',path)
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('V84 unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('V84 accepted V83 base',path)
    change=next((r for r in FRONTEND_DELTA['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('V83 unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('V83 frozen V82 base',path)
    change=next((r for r in REAL_WIRE['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('utility wire unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('utility wire V81 base',path)
    change=next((r for r in RESIDUAL['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('residual fix unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('residual fix V80 base',path)
    change=next((r for r in USER_DOMAIN['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('user identity unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('user identity base',path)
    change=next((r for r in RECOVERY['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('policy recovery unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('policy recovery base',path)
    change=next((r for r in OPTION_B['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('Option B unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('Option B frozen base',path)
    change=next((r for r in HARDENING['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('hardening unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('hardening frozen base',path)
    change=next((r for r in CLOSURE['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('monitor closure unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('monitor closure frozen base',path)
    change=next((r for r in BOUNDARY['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('property boundary unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('property boundary frozen base',path)
    change=next((r for r in BINDER['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('Binder unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('Binder frozen base',path)
    change=next((r for r in BOOTSTRAP['files'] if r['path']==path),None)
    if change:
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('bootstrap unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('bootstrap frozen base',path)
    current=next((r for r in INTEGRATION['files'] if r['path']==path),None)
    if current:
        for h in reversed(current['hunks']):
            assert text.count(h['after'])==1,('integration unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==current['beforeSha256'],('integration frozen base',path)
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
    for row in FRONTEND_INTEGRATION['files']+FRONTEND_DELTA['files']+REAL_WIRE['files']+RESIDUAL['files']+USER_DOMAIN['files']+RECOVERY['files']+OPTION_B['files']+HARDENING['files']+CLOSURE['files']+BOUNDARY['files']+BINDER['files']+BOOTSTRAP['files']+INTEGRATION['files']+MANIFEST['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('R1 exact source bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    return result
