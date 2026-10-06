"""Exact R1 authorized delta reversal; historical engine hashes remain unchanged."""
from pathlib import Path
import hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[1]
FIDELITY=json.loads((ROOT/'tests/frontend_fidelity_recovery_delta.json').read_text(encoding='utf-8'))
SYNC=json.loads((ROOT/'tests/frontend_visual_sync_delta.json').read_text(encoding='utf-8'))
PLAN2=json.loads((ROOT/'tests/frontend_owner_polish_delta.json').read_text(encoding='utf-8'))
VISUAL_CLOSURE=json.loads((ROOT/'tests/frontend_visual_closure_delta.json').read_text(encoding='utf-8'))
FINAL_UX=json.loads((ROOT/'tests/frontend_final_owner_ux_delta.json').read_text(encoding='utf-8'))
IDENTITY=json.loads((ROOT/'tests/integration_identity_delta.json').read_text(encoding='utf-8'))
FINAL_FRONTEND=json.loads((ROOT/'tests/final_frontend_merge_delta.json').read_text(encoding='utf-8'))
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
    change=next((r for r in IDENTITY['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('V84 exact identity bytes',path)
        text=change['hunks'][0]['before']
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
    assert FINAL_UX['baseHead']=='9fccabb7a356b372f51242ddfa3a3638b6718c6f'
    allowed={'MainActivity.kt','OwnerUi.kt','OwnerEffects.kt','OwnerGeometry.kt','FrontendSession.kt','FrontendForeground.kt','OwnerCpuPicker.kt','OwnerRenderTrace.kt','GpuRangeBar.kt','RecordChart.kt','OwnerCpuSparklineView.kt','PerformanceRecordActivity.kt','FrontendV3Test.kt'}
    for row in FINAL_UX['files']:
        assert row['path'].startswith('app/') and (Path(row['path']).name in allowed or row['path'] in {'app/src/main/res/drawable/owner_appopt.xml','app/src/main/res/drawable/owner_undo.xml','app/src/main/res/drawable/owner_thread_list.xml','app/src/main/res/drawable/owner_grip.xml','app/src/main/res/drawable/owner_export.xml'})
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('R3 exact Owner frontend bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    assert VISUAL_CLOSURE['baseHead']=='aeda10226cab96a62eee48081ecbd882bffab71a'
    allowed={'MainActivity.kt','OwnerUi.kt','OwnerWindow.kt','OwnerTypography.kt','OwnerGeometry.kt','OwnerCpuSparklineView.kt','PerformanceRecordActivity.kt','RecordChart.kt','OwnerVisualClosureTest.kt'}
    for row in VISUAL_CLOSURE['files']:
        assert row['path'].startswith('app/') and Path(row['path']).name in allowed
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('Plan2 closure exact frontend bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    # Reverse only the current Owner's explicit frontend/interaction delta.
    assert PLAN2['baseHead']=='d7c99926ee55b54af3d53129644693aff81fd887'
    allowed={'FrontendGateway.kt','FrontendSession.kt','FrontendState.kt','MainActivity.kt','GpuRangeBar.kt','OwnerUi.kt','OwnerWindow.kt','OwnerTypography.kt','OwnerEffects.kt','PerformanceRecordActivity.kt','RecordChart.kt','FrontendV3Test.kt'}
    for row in PLAN2['files']:
        assert row['path'].startswith('app/') and Path(row['path']).name in allowed
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('Plan2 exact frontend bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    # Owner R2 presentation delta reverses to the immutable R1 receipt first.
    assert SYNC['baseHead']=='e46691dee3a6ee988f641aacb41a4b2aa2352ca4'
    allowed={'MainActivity.kt','OwnerUi.kt','OwnerWindow.kt','GpuRangeBar.kt','RecordChart.kt','PerformanceRecordActivity.kt','UiControls.kt','NotificationQuickControlHelper.kt','ZuiControlQuickService.kt'}
    for row in SYNC['files']:
        assert row['path'].startswith('app/') and (Path(row['path']).name in allowed or (row['path'].startswith('app/src/main/res/drawable/notify_') or row['path'] in {'app/src/main/res/drawable/owner_grip.xml','app/src/main/res/drawable/owner_export.xml','app/src/main/res/drawable/owner_code.xml','app/src/main/res/drawable/owner_backup.xml','app/src/main/res/drawable/owner_restore.xml','app/src/main/res/drawable/owner_reset.xml','app/src/main/res/drawable/owner_restart.xml','app/src/main/res/drawable/owner_logs.xml','app/src/main/res/drawable/owner_help.xml'}))
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('V84 R2 exact presentation bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    # New Owner-authorized Phase 1 App port reverses to the exact 04 integration.
    # Never repin the original merge/input or historical Backend manifests.
    assert FIDELITY['baseHead']=='04d4f962315b2cf051f8a3be0af952513e8e6928'
    allowed={'FrontendGateway.kt','FrontendSession.kt','GpuRangeBar.kt','MainActivity.kt','OwnerUi.kt','FrontendV3Test.kt'}
    for row in FIDELITY['files']:
        assert row['path'].startswith('app/') and (Path(row['path']).name in allowed or row['path'].startswith('app/src/main/res/drawable/owner_'))
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('V84 Phase 1 exact frontend bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    # Reverse the exact final App merge before replaying unchanged Backend freezes.
    for row in FINAL_FRONTEND['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        if row['after'] is None:
            assert not any(e.endswith(('\t'+row['path']).encode()) for e in result),('V84 deleted frontend file',row['path'])
        else:
            after=row['after'].encode();assert result.count(after)==1,('V84 exact final frontend bytes',row['path'])
            result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    for row in IDENTITY['files']+FRONTEND_INTEGRATION['files']+FRONTEND_DELTA['files']+REAL_WIRE['files']+RESIDUAL['files']+USER_DOMAIN['files']+RECOVERY['files']+OPTION_B['files']+HARDENING['files']+CLOSURE['files']+BOUNDARY['files']+BINDER['files']+BOOTSTRAP['files']+INTEGRATION['files']+MANIFEST['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('R1 exact source bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    return result
