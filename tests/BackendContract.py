"""Exact R1 authorized delta reversal; historical engine hashes remain unchanged."""
from pathlib import Path
import hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[1]
PLAN3_R9=json.loads((ROOT/'tests/product_plan3_autonomous_delta.json').read_text(encoding='utf-8'))
R9_ALLOWED={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('MainActivity.kt','ZuioptRead.kt','ZuioptRules.kt','ZuioptLibrary.kt')}
R9_ALLOWED|={'framework_patch/src/services/com/zui/server/control/'+n for n in ('ZuiControlService.java','ZuioptSceneAuthority.java')}
R9_ALLOWED|={'native/zuiopt/'+n for n in ('ZUIopt_binder.h','ZUIopt_daemon.h','ZUIopt_scene.h','ZUIopt_store.h')}
assert set(PLAN3_R9['allowedPaths'])==R9_ALLOWED,'R9 explicit source boundary'
PLAN3_UX=json.loads((ROOT/'tests/product_plan3_device_ux_delta.json').read_text(encoding='utf-8'))
UX_ALLOWED={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('MainActivity.kt','ZuioptLibrary.kt','ZuioptRules.kt')}
UX_ALLOWED.add('framework_patch/src/services/com/zui/server/control/ZuiControlService.java')
assert set(PLAN3_UX['allowedPaths'])==UX_ALLOWED,'R8 explicit source boundary'
PLAN3_DOMAIN=json.loads((ROOT/'tests/product_plan3_domain_latency_delta.json').read_text(encoding='utf-8'))
DOMAIN_ALLOWED={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('BackendHealth.kt','MainActivity.kt','OwnerUi.kt','OwnerRenderTrace.kt','ZuiControlRequest.kt','FrontendGateway.kt')}
DOMAIN_ALLOWED|={'framework_patch/src/services/com/zui/server/control/'+n for n in ('PolicyCommand.java','PolicyRuntimePlan.java','ZuiControlService.java')}
DOMAIN_ALLOWED|={'native/command/Projection.h','payload/system/bin/zui_control_request'}
DOMAIN_ALLOWED.add('app/src/test/java/com/zui/zuicontrol/BackendHealthTest.kt')
DOMAIN_ALLOWED|={'app/src/main/res/'+n for n in ('animator/owner_ping_fade.xml','animator/owner_ping_scale.xml','drawable/owner_ping_halo.xml','drawable/owner_ping_vector.xml','interpolator/owner_ping_ease.xml')}
assert set(PLAN3_DOMAIN['allowedPaths'])==DOMAIN_ALLOWED,'R7 explicit source boundary'
PLAN3_OVERNIGHT=json.loads((ROOT/'tests/product_plan3_overnight_delta.json').read_text(encoding='utf-8'))
PLAN3_FINAL=json.loads((ROOT/'tests/product_plan3_final_integration_delta.json').read_text(encoding='utf-8'))
PLAN3_VERTICAL=json.loads((ROOT/'tests/product_plan3_vertical_slice_delta.json').read_text(encoding='utf-8'))
PLAN3_RESPONSIVENESS=json.loads((ROOT/'tests/product_plan3_responsiveness_delta.json').read_text(encoding='utf-8'))
PLAN3_RESUME=json.loads((ROOT/'tests/product_plan3_resume_delta.json').read_text(encoding='utf-8'))
PLAN3=json.loads((ROOT/'tests/product_plan3_functional_delta.json').read_text(encoding='utf-8'))
FIDELITY=json.loads((ROOT/'tests/frontend_fidelity_recovery_delta.json').read_text(encoding='utf-8'))
SYNC=json.loads((ROOT/'tests/frontend_visual_sync_delta.json').read_text(encoding='utf-8'))
PLAN2=json.loads((ROOT/'tests/frontend_owner_polish_delta.json').read_text(encoding='utf-8'))
VISUAL_CLOSURE=json.loads((ROOT/'tests/frontend_visual_closure_delta.json').read_text(encoding='utf-8'))
FINAL_UX=json.loads((ROOT/'tests/frontend_final_owner_ux_delta.json').read_text(encoding='utf-8'))
STATUS_CONTINUITY=json.loads((ROOT/'tests/frontend_status_continuity_delta.json').read_text(encoding='utf-8'))
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
    change=next((r for r in PLAN3_R9['files'] if r['path']==path),None)
    if change:
        assert path in R9_ALLOWED
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R9 exact authorized bytes',path)
        assert len(change['hunks'])==1 and change['hunks'][0]['after']==text
        text=change['hunks'][0]['before']
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R9 immutable R8 base',path)
    change=next((r for r in PLAN3_UX['files'] if r['path']==path),None)
    if change:
        assert change['path'] in UX_ALLOWED
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R8 exact authorized bytes',path)
        assert len(change['hunks'])==1 and change['hunks'][0]['after']==text
        text=change['hunks'][0]['before']
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R8 immutable R7 base',path)
    change=next((r for r in PLAN3_DOMAIN['files'] if r['path']==path),None)
    if change:
        assert change['path'] in PLAN3_DOMAIN['allowedPaths']
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R7 exact authorized bytes',path)
        assert len(change['hunks'])==1 and change['hunks'][0]['after']==text
        text=change['hunks'][0]['before']
        assert text is not None,('R7 added file has no historical text',path)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R7 immutable R6 base',path)
    change=next((r for r in PLAN3_OVERNIGHT['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R6 exact authorized bytes',path)
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('R6 authorized frontend delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R6 immutable R5 base',path)
    change=next((r for r in PLAN3_FINAL['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R5 exact presentation bytes',path)
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('R5 authorized presentation delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R5 immutable R4 base',path)
    change=next((r for r in PLAN3_VERTICAL['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R4 exact authorized bytes',path)
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('R4 authorized vertical slice delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R4 immutable R3 base',path)
    change=next((r for r in PLAN3_RESPONSIVENESS['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R3 exact authorized bytes',path)
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('R3 authorized responsiveness delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R3 immutable R2 base',path)
    change=next((r for r in PLAN3_RESUME['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R2 exact frontend bytes',path)
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('R2 authorized lifecycle delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R2 base identity',path)
    change=next((r for r in PLAN3['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('Plan3 exact authorized bytes',path)
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('Plan3 unauthorized delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('Plan3 exact R4 base',path)
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
    assert PLAN3_R9['baseHead']=='5f7754404efdcf378a380fc1e1a2a54dc851d704'
    for row in PLAN3_R9['files']:
        assert row['path'] in R9_ALLOWED
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['after'].encode())==1,('R9 exact authorized source',row['path'])
        result.remove(row['after'].encode())
        if row['before'] is not None:result.append(row['before'].encode())
    assert PLAN3_UX['baseHead']=='9c84b5535fa002f5740359c1e5351cd0922a7cea'
    for row in PLAN3_UX['files']:
        assert row['path'] in UX_ALLOWED
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['after'].encode())==1,('R8 exact authorized source',row['path'])
        result.remove(row['after'].encode());result.append(row['before'].encode())
    assert PLAN3_DOMAIN['baseHead']=='62dc044cf4ea7912a6855df27ff3d106602dd01f'
    for row in PLAN3_DOMAIN['files']:
        assert row['path'] in PLAN3_DOMAIN['allowedPaths'],('R7 unscoped source',row['path'])
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        if row['after'] is not None:
            assert result.count(row['after'].encode())==1,('R7 exact source bytes',row['path'])
            result.remove(row['after'].encode())
        else:assert not any(e.endswith(('\t'+row['path']).encode()) for e in result),('R7 deleted pulse resource',row['path'])
        if row['before'] is not None:result.append(row['before'].encode())
    assert PLAN3_OVERNIGHT['baseHead']=='8f3825fb4374bac892db96cb735f237aab23cb7c'
    allowed={'app/build.gradle.kts','app/src/androidTest/java/com/zui/zuicontrol/probe/ResourceProbe.java'}
    allowed|={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('BackendHealth.kt','MainActivity.kt','FrontendTransport.kt','ZuioptLibrary.kt','ZuioptRules.kt','FrontendPackages.kt','OwnerUi.kt','OwnerWindow.kt','OwnerEffects.kt')}
    allowed|={'app/src/main/res/'+n for n in ('drawable/owner_ping_vector.xml','drawable/owner_ping_halo.xml','animator/owner_ping_scale.xml','animator/owner_ping_fade.xml','interpolator/owner_ping_ease.xml')}
    allowed|={'app/src/test/java/com/zui/zuicontrol/'+n for n in ('BackendHealthTest.kt','FrontendTransportTest.kt')}
    for row in PLAN3_OVERNIGHT['files']:
        assert row['path'] in allowed,('R6 unscoped source',row['path'])
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['after'].encode())==1,('R6 exact authorized bytes',row['path'])
        result.remove(row['after'].encode())
        if row['before'] is not None:result.append(row['before'].encode())
    assert PLAN3_FINAL['baseHead']=='fb0cc54192e1769273a3199740347dd679b1811f'
    for row in PLAN3_FINAL['files']:
        assert row['path'] in {'app/src/main/java/com/zui/zuicontrol/MainActivity.kt','app/src/main/java/com/zui/zuicontrol/OwnerUi.kt'},('R5 unscoped source',row['path'])
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['after'].encode())==1,('R5 exact presentation bytes',row['path'])
        result.remove(row['after'].encode());result.append(row['before'].encode())
    assert PLAN3_VERTICAL['baseHead']=='1810a2e8ad1d0129cf5c75ae8817221bacc34737'
    allowed={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('FrontendSession.kt','MainActivity.kt','OwnerUi.kt','OwnerWindow.kt')}
    allowed|={'framework_patch/src/services/com/zui/server/control/'+n for n in ('UtilityTransport.java','ZuiControlService.java')}
    allowed.add('app/src/test/java/com/zui/zuicontrol/FrontendV3Test.kt')
    for row in PLAN3_VERTICAL['files']:
        assert row['path'] in allowed,('R4 unscoped source',row['path'])
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['after'].encode())==1,('R4 exact slice bytes',row['path'])
        result.remove(row['after'].encode());result.append(row['before'].encode())
    assert PLAN3_RESPONSIVENESS['baseHead']=='383649890092c4368ef4b571c09ba6ace8ba5ca6'
    backend={'framework_patch/src/services/com/zui/server/control/MonitorCollector.java','framework_patch/src/services/com/zui/server/control/ZuiControlService.java'}
    assert PLAN3_RESPONSIVENESS['BackendSourceChanged']==any(r['path'] in backend for r in PLAN3_RESPONSIVENESS['files'])
    for row in PLAN3_RESPONSIVENESS['files']:
        assert row['path'] in backend or (row['path'].startswith('app/') and Path(row['path']).name in {'ControlsState.kt','FrontendTransport.kt','FrontendPackages.kt','FrontendSession.kt','MainActivity.kt','OwnerUi.kt','OwnerWindow.kt','PerformanceMonitor.kt','ZuiControlClient.kt','ZuiControlQuickService.kt','FrontendV3Test.kt'})
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('R3 exact responsiveness bytes',row['path'])
        result.remove(after)
        if row['before'] is not None:result.append(row['before'].encode())
    assert PLAN3_RESUME['baseHead']=='a0c4b2e2b7e3cab2d59392cee7b85f142a0d3824'
    for row in PLAN3_RESUME['files']:
        assert row['path'] in {'app/src/main/java/com/zui/zuicontrol/MainActivity.kt','app/src/main/java/com/zui/zuicontrol/FrontendSession.kt','app/src/test/java/com/zui/zuicontrol/FrontendV3Test.kt'}
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('R2 exact frontend lifecycle bytes',row['path'])
        result.remove(after);result.append(row['before'].encode())
    assert PLAN3['baseHead']=='847d7f02f80a95f818930d5f0af0aa32256dba53'
    allowed={'app/src/main/java/com/zui/zuicontrol/MainActivity.kt','app/src/main/java/com/zui/zuicontrol/FrontendSession.kt','app/src/main/java/com/zui/zuicontrol/ZuiControlRequest.kt','app/src/test/java/com/zui/zuicontrol/FrontendV3Test.kt','framework_patch/src/services/com/zui/server/control/MonitorCollector.java','framework_patch/src/services/com/zui/server/control/ZuiControlService.java','framework_patch/src/services/com/zui/server/control/UtilityTransport.java'}
    for row in PLAN3['files']:
        assert row['path'] in allowed
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('Plan3 exact functional bytes',row['path'])
        result.remove(after);result.append(row['before'].encode())
    assert STATUS_CONTINUITY['baseHead']=='391826265eb2e6deba74c47426b793e2809733b4'
    for row in STATUS_CONTINUITY['files']:
        assert row['path'] in {'app/src/main/java/com/zui/zuicontrol/MainActivity.kt','app/src/main/java/com/zui/zuicontrol/OwnerWindow.kt'}
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        after=row['after'].encode();assert result.count(after)==1,('R4 exact status presentation bytes',row['path'])
        result.remove(after);result.append(row['before'].encode())
    assert FINAL_UX['baseHead']=='9fccabb7a356b372f51242ddfa3a3638b6718c6f'
    allowed={'MainActivity.kt','OwnerUi.kt','OwnerWindow.kt','OwnerEffects.kt','OwnerGeometry.kt','FrontendSession.kt','FrontendForeground.kt','OwnerCpuPicker.kt','OwnerRenderTrace.kt','GpuRangeBar.kt','RecordChart.kt','OwnerCpuSparklineView.kt','PerformanceRecordActivity.kt','FrontendV3Test.kt'}
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
