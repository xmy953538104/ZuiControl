"""Exact R1 authorized delta reversal; historical engine hashes remain unchanged."""
from pathlib import Path
import hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[1]
RUNTIME=json.loads((ROOT/'tests/zuiopt/runtime_delta.json').read_text(encoding='utf-8'))
RUNTIME_ALLOWED={'native/zuiopt/'+n for n in ('ZUIopt.cpp','ZUIopt_core.h','ZUIopt_daemon.h','ZUIopt_library.h','ZUIopt_lifecycle.h','ZUIopt_owner.h','ZUIopt_rules.h','ZUIopt_runtime.h','ZUIopt_store.h')}
RUNTIME_ALLOWED.add('payload/patches/plat_sepolicy_zui_control.cil')
RUNTIME_ALLOWED.add('scripts/rules/ZUIOPT_rule_pack.py')
RUNTIME_ALLOWED.add('scripts/build/ApplyZuiControlPayload.py')
assert RUNTIME['phase']=='ZUIOPT_RUNTIME_EVOLUTION_ADCB_R1' and RUNTIME['baseHead']=='358ffe118c0e82e937d58e4e619ef25e04f48089'
assert set(RUNTIME['allowedPaths'])==RUNTIME_ALLOWED
RECOVERY_R2=json.loads((ROOT/'tests/zuiopt/runtime_recovery_r2_delta.json').read_text(encoding='utf-8'))
RECOVERY_R2_ALLOWED={'native/zuiopt/'+n for n in ('ZUIopt_daemon.h','ZUIopt_owner.h','ZUIopt_runtime.h','ZUIopt_lifecycle.h')}
assert RECOVERY_R2['phase']=='DEVICE_LOCAL_TEST_AND_RUNTIME_RECOVERY_R2' and RECOVERY_R2['baseHead']=='7a3620ac42481459480f49cc9e517ce059eaeeaf'
assert set(RECOVERY_R2['allowedPaths'])==RECOVERY_R2_ALLOWED and {row['path'] for row in RECOVERY_R2['files']}==RECOVERY_R2_ALLOWED
QUARANTINE_R2=json.loads((ROOT/'tests/zuiopt/runtime_quarantine_r2_delta.json').read_text(encoding='utf8'))
FRONTEND_RUNTIME_R2=json.loads((ROOT/'tests/zuiopt/runtime_frontend_r2_delta.json').read_text(encoding='utf8'))
FRONTEND_RUNTIME_ALLOWED={'app/src/main/java/com/zui/zuicontrol/'+name for name in ('FrontendSession.kt','MainActivity.kt','ZuioptRead.kt','ZuioptRuleModel.kt')}
FRONTEND_RUNTIME_ALLOWED|={'framework_patch/src/services/com/zui/server/control/'+name for name in ('ZuiControlService.java','ZuioptSceneAuthority.java')}
FRONTEND_RUNTIME_ALLOWED|={'native/zuiopt/'+name for name in ('ZUIopt_scene.h','ZUIopt_store.h')}
assert set(FRONTEND_RUNTIME_R2['allowedPaths'])==FRONTEND_RUNTIME_ALLOWED,'R2 C/B explicit source scope'
assert {r['path'] for r in FRONTEND_RUNTIME_R2['testFiles']}=={'app/src/test/java/com/zui/zuicontrol/ZuioptRuleModelTest.kt'},'R2 explicit App unit regression scope'
LIBRARY_INHERITANCE_R2=json.loads((ROOT/'tests/zuiopt/runtime_library_inheritance_r2_delta.json').read_text(encoding='utf8'))
assert LIBRARY_INHERITANCE_R2['baseHead']=='270e33ebd244625b012e4bba052dc7067d5dcb25' and LIBRARY_INHERITANCE_R2['phase']=='R2_LIBRARY_INHERITANCE_REPAIR'
assert set(LIBRARY_INHERITANCE_R2['allowedPaths'])=={'native/zuiopt/ZUIopt_library.h','app/src/main/java/com/zui/zuicontrol/MainActivity.kt','app/src/main/java/com/zui/zuicontrol/ZuioptRuleModel.kt'}
assert {r['path'] for r in LIBRARY_INHERITANCE_R2['testFiles']}=={'app/src/test/java/com/zui/zuicontrol/ZuioptRuleModelTest.kt'}
assert {r['path'] for r in LIBRARY_INHERITANCE_R2['supportFiles']}=={'app/build.gradle.kts','.github/workflows/build.yml'}
RUNTIME_R3=json.loads((ROOT/'tests/zuiopt/runtime_r3_delta.json').read_text(encoding='utf8'))
RUNTIME_R3_ALLOWED={'native/zuiopt/'+name for name in ('ZUIopt_daemon.h','ZUIopt_owner.h','ZUIopt_rules.h','ZUIopt_runtime.h','ZUIopt_store.h','ZUIopt_sha256.h','ZUIopt_lifecycle.h')}
RUNTIME_R3_ALLOWED|={'app/src/main/java/com/zui/zuicontrol/'+name for name in ('ZuioptRead.kt','FrontendGateway.kt','RuleUpstreamFetcher.kt','MainActivity.kt')}
RUNTIME_R3_ALLOWED|={'scripts/rules/'+name for name in ('PublishRules.py','PublishRulesGit.py','ZUIOPT_rule_pack.py')}
RUNTIME_R3_ALLOWED|={'native/zuiopt/ZUIopt.cpp','native/zuiopt/ZUIopt_scene.h','framework_patch/src/services/com/zui/server/control/ZuiControlService.java'}
assert RUNTIME_R3['baseHead']=='4ccc7620cab5927cbcc602fd8f10a38491df7bb4' and RUNTIME_R3['phase']=='R3_CI_QUARANTINE_WALT_AND_SCHEMA3_CLOSURE'
assert set(RUNTIME_R3['allowedPaths'])==RUNTIME_R3_ALLOWED

RUNTIME_R6=json.loads((ROOT/'tests/zuiopt/runtime_r6_delta.json').read_text(encoding='utf8'))
assert RUNTIME_R6['baseHead']=='d0282ffad66bdb3dcb53c9fadbfe783114d7dd7c' and RUNTIME_R6['phase']=='ZUIOPT_R6_RESPONSE_AND_FINAL_RELEASE'

RESPONSE_BATTERY=json.loads((ROOT/'tests/response_battery_r1_delta.json').read_text(encoding='utf8'))
RESPONSE_ALLOWED={'.github/workflows/build.yml','scripts/build/PatchRenderVoteOwner.py'}
RESPONSE_ALLOWED|={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('MainActivity.kt','LivePower.kt','PerformanceMonitor.kt','NotificationQuickControlHelper.kt','ZuiControlQuickService.kt','ControlsState.kt','ZuiControlRequest.kt','FrontendPackages.kt')}
RESPONSE_ALLOWED|={'app/src/test/java/com/zui/zuicontrol/'+n for n in ('LivePowerTest.kt','NotificationMetricTest.kt')}
RESPONSE_ALLOWED|={'framework_patch/src/services/com/zui/server/control/'+n for n in ('MonitorCollector.java','MonitorSources.java','MonitorPower.java','RenderVoteOwner.java','ZuiControlService.java','ControlsCallbacks.java')}
RESPONSE_ALLOWED|={'framework_patch/stubs/android/os/UEventObserver.java','payload/patches/vendor_sepolicy_zui_scheduler.cil','native/command/Adapter.cpp','native/command/Rules.h'}
RESPONSE_ALLOWED.add('app/src/androidTest/java/com/zui/zuicontrol/probe/ResourceProbe.java')
RESPONSE_ALLOWED.add('app/src/main/java/com/zui/zuicontrol/ResponseTrace.kt')
RESPONSE_ALLOWED|={'tests/zuiopt/'+n for n in ('TestRuntimeR3Scope.py','TestRuntimeR5Scope.py','TestRuntimeR6Scope.py','TestRuntimeFrontendScope.py','TestRuntimeRecoveryScope.py')}
RESPONSE_ALLOWED|={'tests/monitor/TestFrontendV3.py','tests/monitor/TestFinalIntegration.py','tests/monitor/TestProductR9.py'}
assert RESPONSE_BATTERY['phase']=='ZUICONTROL_POST_BASELINE_RESPONSE_AND_BATTERY_R1'
assert RESPONSE_BATTERY['baseHead']=='3565f3d4ecf66bce0f8bac2ca93d9ae180745e1c'
assert set(RESPONSE_BATTERY['allowedPaths'])==RESPONSE_ALLOWED=={r['path'] for r in RESPONSE_BATTERY['files']}

POWER_PORT_R2=json.loads((ROOT/'tests/power_port_r2_delta.json').read_text(encoding='utf8'))
assert POWER_PORT_R2['phase']=='ZUICONTROL_POWER_PORT_R2_ACTUAL_EVIDENCE_AND_FIX'
assert POWER_PORT_R2['baseHead']=='1f67fc0f1476e728a6a9e9fe8eac6062fb3b83d1'
assert set(POWER_PORT_R2['allowedPaths'])=={'framework_patch/src/services/com/zui/server/control/'+n for n in ('MonitorCollector.java','MonitorPower.java')}
assert set(POWER_PORT_R2['allowedPaths'])=={r['path'] for r in POWER_PORT_R2['files']}
POWER_PORT_R3=json.loads((ROOT/'tests/power_port_r3_delta.json').read_text(encoding='utf8'))
assert POWER_PORT_R3['phase']=='ZUICONTROL_POWER_PORT_R3_ON_DEMAND_AND_DELTA_REVIEW'
assert POWER_PORT_R3['baseHead']=='64af33c72f2ca3a4655ecb16d4b2e771d58e5bfb'
POWER_PORT_R3_ALLOWED={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('BatteryWindow.kt','FrontendSession.kt','MainActivity.kt','PerformanceMonitor.kt','ZuiControlQuickService.kt')}
POWER_PORT_R3_ALLOWED|={'framework_patch/src/services/com/zui/server/control/'+n for n in ('MonitorCollector.java','MonitorPower.java')}
POWER_PORT_R3_ALLOWED.add('app/src/test/java/com/zui/zuicontrol/BatteryWindowTest.kt')
POWER_PORT_R3_ALLOWED|={'tests/zuiopt/'+n for n in ('TestRuntimeR5Scope.py','TestRuntimeR6Scope.py')}
POWER_PORT_R3_ALLOWED.add('tests/monitor/TestGpuDraftLifecycle.py')
assert set(POWER_PORT_R3['allowedPaths'])==POWER_PORT_R3_ALLOWED=={r['path'] for r in POWER_PORT_R3['files']}
ADVICE_R4=json.loads((ROOT/'tests/advice_closeout_r4_delta.json').read_text(encoding='utf8'))
ADVICE_R4_ALLOWED={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('ControlsState.kt','MainActivity.kt','NotificationQuickControlHelper.kt','OwnerPageHost.kt','OwnerUi.kt','OwnerWindow.kt','QuickControlState.kt','QuickControlTrace.kt','ZuiControlQuickService.kt','ZuiControlRequest.kt')}
ADVICE_R4_ALLOWED|={'app/src/main/res/'+n for n in ('drawable/notify_pending.xml','drawable/notify_monitor_pending.xml','layout/notification_quick_control.xml','values/styles.xml')}
ADVICE_R4_ALLOWED.add('app/src/test/java/com/zui/zuicontrol/QuickControlStateTest.kt')
ADVICE_R4_ALLOWED|={'tests/monitor/TestFrontendV3.py','tests/monitor/TestNotificationRenderer.py','tests/device/ProductSmoke.java'}
ADVICE_R4_ALLOWED|={'tests/zuiopt/'+n for n in ('TestRuntimeFrontendScope.py','TestRuntimeR3Scope.py','TestRuntimeR5Scope.py','TestRuntimeR6Scope.py')}
ADVICE_R4_ALLOWED|={'tests/monitor/TestPlan2.py','app/src/androidTest/java/com/zui/zuicontrol/probe/ResourceProbe.java'}
ADVICE_R4_ALLOWED|=set(['tests/monitor/'+n for n in ('TestProductR6.py','TestProductR7.py','TestProductR8.py')])
ADVICE_R4_ALLOWED.add('tests/refresh/TestRefreshStateMachine.py')
ADVICE_R4_ALLOWED.add('tests/monitor/TestOwnerPageHost.py')
assert ADVICE_R4['phase']=='ZUICONTROL_FINAL_ADVICE_CLOSEOUT_R4' and ADVICE_R4['baseHead']=='c64aa686829ca0420d7b523eb28c2ce2e71c6c7f'
assert set(ADVICE_R4['allowedPaths'])==ADVICE_R4_ALLOWED=={r['path'] for r in ADVICE_R4['files']}
RESIDENCY_R5=json.loads((ROOT/'tests/native_residency_r5_delta.json').read_text(encoding='utf8'))
RESIDENCY_R5_ALLOWED={'app/build.gradle.kts','.github/workflows/build.yml','tests/monitor/TestProbeIsolation.py'}
RESIDENCY_R5_ALLOWED|={'app/src/androidTest/java/com/zui/zuicontrol/probe/'+n for n in ('ResourceProbe.java','AckFaultGate.java')}
RESIDENCY_R5_ALLOWED|={'tests/zuiopt/'+n for n in ('TestRuntimeFrontendScope.py','TestRuntimeR5Scope.py','TestRuntimeR6Scope.py')}
assert RESIDENCY_R5['phase']=='ZUICONTROL_R5_NATIVE_RESIDENCY_CALLBACK_AND_POWER_CLOSEOUT' and RESIDENCY_R5['baseHead']=='00be2b28ce856d6b59555f1dd475caea3e1b53ec'
assert set(RESIDENCY_R5['allowedPaths'])==RESIDENCY_R5_ALLOWED=={r['path'] for r in RESIDENCY_R5['files']}

# Exact local R1 build/fixture changes are separate from the public runtime ancestry.
BASELINE_LOCAL={}
for path,before,after in (
    ('app/build.gradle.kts','            signingConfig = signingConfigs.getByName("release")\n',
     '            // The baseline receiver compiles unsigned and verifies an authorized CI signature separately.\n            if (!providers.gradleProperty("zuicontrol.unsignedBaseline").isPresent) {\n                signingConfig = signingConfigs.getByName("release")\n            }\n'),
    ('host_retirement/TestRetirement.py',"serial=R.SERIAL,model='TB321FU'","serial='FIXTURE_DEVICE_SERIAL',model='TB321FU'")):
    original=subprocess.check_output(['git','-C',str(ROOT),'show','3565f3d4ecf66bce0f8bac2ca93d9ae180745e1c:'+path]).decode().replace('\r\n','\n')
    assert original.count(before)==1
    local=original.replace(before,after,1)
    BASELINE_LOCAL[path]=(original,local)

def reverse_response_battery(path,text):
    delta=next((r for r in RESIDENCY_R5['files'] if r['path']==path),None)
    if delta:
        assert hashlib.sha256(text.encode()).hexdigest()==delta['afterSha256'],('RESIDENCY_R5 exact authorized bytes',path)
        for h in reversed(delta['hunks']):
            assert text.count(h['after'])==1,('RESIDENCY_R5 exact hunk',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==delta['beforeSha256'],('RESIDENCY_R5 preserved 00be source',path)
    baseline=BASELINE_LOCAL.get(path)
    if baseline and text==baseline[1]:text=baseline[0]
    delta=next((r for r in ADVICE_R4['files'] if r['path']==path),None)
    if delta:
        assert hashlib.sha256(text.encode()).hexdigest()==delta['afterSha256'],('ADVICE_R4 exact authorized bytes',path)
        for h in reversed(delta['hunks']):
            assert text.count(h['after'])==1,('ADVICE_R4 exact hunk',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==delta['beforeSha256'],('ADVICE_R4 preserved c64 source',path)
    delta=next((r for r in POWER_PORT_R3['files'] if r['path']==path),None)
    if delta:
        assert hashlib.sha256(text.encode()).hexdigest()==delta['afterSha256'],('POWER_PORT_R3 exact authorized bytes',path)
        for h in reversed(delta['hunks']):
            assert text.count(h['after'])==1,('POWER_PORT_R3 exact hunk',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==delta['beforeSha256'],('POWER_PORT_R3 preserved R2 base',path)
    delta=next((r for r in POWER_PORT_R2['files'] if r['path']==path),None)
    if delta:
        assert hashlib.sha256(text.encode()).hexdigest()==delta['afterSha256'],('POWER_PORT_R2 exact authorized bytes',path)
        for h in reversed(delta['hunks']):
            assert text.count(h['after'])==1,('POWER_PORT_R2 exact hunk',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==delta['beforeSha256'],('POWER_PORT_R2 preserved R1 base',path)
    row=next((r for r in RESPONSE_BATTERY['files'] if r['path']==path),None)
    if row is None:return text
    assert hashlib.sha256(text.encode()).hexdigest()==row['afterSha256'],('POST_BASELINE exact authorized bytes',path)
    for h in reversed(row['hunks']):
        assert text.count(h['after'])==1,('POST_BASELINE exact hunk',path)
        text=text.replace(h['after'],h['before'],1)
    assert hashlib.sha256(text.encode()).hexdigest()==row['beforeSha256'],('POST_BASELINE immutable business base',path)
    return text

def reverse_response_battery_entries(current,*scopes):
    result=list(current)
    for row in RESIDENCY_R5['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('RESIDENCY_R5 exact authorized source',row['path'])
        result.remove(row['afterEntry'].encode())
        if row['beforeEntry'] is not None:result.append(row['beforeEntry'].encode())
    for path,(original,local) in BASELINE_LOCAL.items():
        if not any(path.startswith(scope+'/') for scope in scopes):continue
        def baseline_entry(value):
            data=value.encode();oid=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
            return ('100644 blob '+oid+'\t'+path).encode()
        old,new=baseline_entry(original),baseline_entry(local)
        if new in result:result.remove(new);result.append(old)
    for row in ADVICE_R4['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('ADVICE_R4 exact authorized source',row['path'])
        result.remove(row['afterEntry'].encode())
        if row['beforeEntry'] is not None:result.append(row['beforeEntry'].encode())
    for row in POWER_PORT_R3['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('POWER_PORT_R3 exact authorized source',row['path'])
        result.remove(row['afterEntry'].encode())
        if row['beforeEntry'] is not None:result.append(row['beforeEntry'].encode())
    for row in POWER_PORT_R2['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('POWER_PORT_R2 exact authorized source',row['path'])
        result.remove(row['afterEntry'].encode());result.append(row['beforeEntry'].encode())
    for row in RESPONSE_BATTERY['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('POST_BASELINE exact authorized source',row['path'])
        result.remove(row['afterEntry'].encode())
        if row['beforeEntry'] is not None:result.append(row['beforeEntry'].encode())
    return result
def reverse_runtime_r6(path,text):
    text=reverse_response_battery(path,text)
    row=next((r for r in RUNTIME_R6['files'] if r['path']==path),None)
    if not row:return text
    assert hashlib.sha256(text.encode()).hexdigest()==row['afterSha256'],('R6 exact authorized bytes',path)
    if row['beforeEntry'] is None:return ''
    for h in reversed(row['hunks']):
        assert text.count(h['after'])==1,('R6 exact source hunk',path)
        text=text.replace(h['after'],h['before'],1)
    assert hashlib.sha256(text.encode()).hexdigest()==row['beforeSha256'],('R6 immutable base',path)
    return text
def reverse_runtime_r6_entries(current,*scopes):
    result=reverse_response_battery_entries(current,*scopes)
    for row in RUNTIME_R6['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('R6 exact authorized source',row['path'])
        result.remove(row['afterEntry'].encode())
        if row['beforeEntry'] is not None:result.append(row['beforeEntry'].encode())
    return result

RUNTIME_R5=json.loads((ROOT/'tests/zuiopt/runtime_r5_delta.json').read_text(encoding='utf8'))
RUNTIME_R5_ALLOWED=set(RUNTIME_R5['allowedPaths'])
assert RUNTIME_R5['baseHead']=='d54bbee0215989b3f4a2737aff30d315f590eaa3' and RUNTIME_R5['phase']=='ZUIOPT_R5_SCHEMA3_LIVE_CLOUD_INTEGRATION_AND_COMPLETION'
def reverse_runtime_r5(path,text):
    text=reverse_runtime_r6(path,text)
    row=next((r for r in RUNTIME_R5['files'] if r['path']==path),None)
    if not row:return text
    assert hashlib.sha256(text.encode()).hexdigest()==row['afterSha256'],('R5 exact authorized bytes',path)
    if row['beforeEntry'] is None:return ''
    for h in reversed(row['hunks']):
        assert text.count(h['after'])==1,('R5 exact source hunk',path)
        text=text.replace(h['after'],h['before'],1)
    assert hashlib.sha256(text.encode()).hexdigest()==row['beforeSha256'],('R5 immutable base',path)
    return text
def reverse_runtime_r5_entries(current,*scopes):
    result=reverse_runtime_r6_entries(current,*scopes)
    for row in RUNTIME_R5['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('R5 exact authorized source',row['path'])
        result.remove(row['afterEntry'].encode())
        if row['beforeEntry'] is not None:result.append(row['beforeEntry'].encode())
    return result

def reverse_runtime_r3(path,text):
    text=reverse_runtime_r5(path,text)
    row=next((r for r in RUNTIME_R3['files']+RUNTIME_R3['testFiles']+RUNTIME_R3['supportFiles'] if r['path']==path),None)
    if not row:return text
    assert hashlib.sha256(text.encode()).hexdigest()==row['afterSha256'],('R3 exact authorized bytes',path)
    if row['beforeEntry'] is None:return ''
    for h in reversed(row['hunks']):
        assert text.count(h['after'])==1,('R3 exact source hunk',path)
        text=text.replace(h['after'],h['before'],1)
    assert hashlib.sha256(text.encode()).hexdigest()==row['beforeSha256'],('R3 immutable base',path)
    return text

def reverse_runtime_r3_entries(current,*scopes):
    result=reverse_runtime_r5_entries(current,*scopes)
    for row in RUNTIME_R3['files']+RUNTIME_R3['testFiles']+RUNTIME_R3['supportFiles']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('R3 exact authorized source',row['path'])
        result.remove(row['afterEntry'].encode())
        if row['beforeEntry'] is not None:result.append(row['beforeEntry'].encode())
    return result

def reverse_library_inheritance_r2(path,text):
    text=reverse_runtime_r3(path,text)
    row=next((r for r in LIBRARY_INHERITANCE_R2['files']+LIBRARY_INHERITANCE_R2['testFiles']+LIBRARY_INHERITANCE_R2['supportFiles'] if r['path']==path),None)
    if row:
        assert hashlib.sha256(text.encode()).hexdigest()==row['afterSha256'],('R2 library inheritance exact authorized bytes',path)
        for h in reversed(row['hunks']):
            assert text.count(h['after'])==1,('R2 library inheritance exact hunk',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==row['beforeSha256'],('R2 library inheritance immutable base',path)
    return text

def reverse_frontend_runtime_r2(path,text):
    text=reverse_library_inheritance_r2(path,text)
    row=next((r for r in FRONTEND_RUNTIME_R2['files']+FRONTEND_RUNTIME_R2['testFiles'] if r['path']==path),None)
    if row:
        assert hashlib.sha256(text.encode()).hexdigest()==row['afterSha256'],('R2 C/B exact authorized bytes',path)
        for h in reversed(row['hunks']):
            assert text.count(h['after'])==1,('R2 C/B exact authorized hunk',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==row['beforeSha256'],('R2 C/B immutable source base',path)
    return text
def reverse_frontend_runtime_entries(current,*scopes):
    result=reverse_runtime_r3_entries(current,*scopes)
    for row in LIBRARY_INHERITANCE_R2['files']+LIBRARY_INHERITANCE_R2['testFiles']+LIBRARY_INHERITANCE_R2['supportFiles']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('R2 library inheritance exact source entry',row['path'])
        result.remove(row['afterEntry'].encode());result.append(row['beforeEntry'].encode())
    for row in FRONTEND_RUNTIME_R2['files']+FRONTEND_RUNTIME_R2['testFiles']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('R2 C/B exact source entry',row['path'])
        result.remove(row['afterEntry'].encode());result.append(row['beforeEntry'].encode())
    return result
assert QUARANTINE_R2['phase']=='R2_QUARANTINE_ACQUISITION_GUARD' and QUARANTINE_R2['baseHead']=='22191e73a072b2e0bca847cff0d26167d88e7359'
assert QUARANTINE_R2['allowedPaths']==['native/zuiopt/ZUIopt_owner.h'] and len(QUARANTINE_R2['files'])==1
assert QUARANTINE_R2['files'][0]['path']=='native/zuiopt/ZUIopt_owner.h'
assert QUARANTINE_R2['files'][0]['beforeSha256']==next(row['afterSha256'] for row in RECOVERY_R2['files'] if row['path']=='native/zuiopt/ZUIopt_owner.h')

def reverse_quarantine_r2(path,text):
    change=QUARANTINE_R2['files'][0]
    if path==change['path']:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R2 quarantine exact bytes',path)
        for hunk in reversed(change['hunks']):
            assert text.count(hunk['after'])==1,('R2 quarantine exact guard',path)
            text=text.replace(hunk['after'],hunk['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R2 quarantine immutable base',path)
    return text

def reverse_recovery_r2(path,text):
    text=reverse_frontend_runtime_r2(path,text)
    text=reverse_quarantine_r2(path,text)
    change=next((r for r in RECOVERY_R2['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R2 exact authorized bytes',path)
        assert hashlib.sha256(change['before'].encode()).hexdigest()==change['beforeSha256'],('R2 immutable base bytes',path)
        text=change['before']
    return text

def reverse_recovery_entries(current,*scopes):
    result=reverse_frontend_runtime_entries(current,*scopes)
    for row in QUARANTINE_R2['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['afterEntry'].encode())==1,('R2 quarantine exact source',row['path'])
        result.remove(row['afterEntry'].encode());result.append(row['beforeEntry'].encode())
    for row in RECOVERY_R2['files']:
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        def entry(text):
            data=text.encode();oid=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
            return ('100644 blob '+oid+'\t'+row['path']).encode()
        after=entry(row['after']);before=entry(row['before'])
        assert result.count(after)==1,('R2 exact authorized source',row['path'])
        result.remove(after);result.append(before)
    return result

def reverse_runtime_entries(current,*scopes):
    result=reverse_recovery_entries(current,*scopes)
    for row in RUNTIME['files']:
        assert row['path'] in RUNTIME_ALLOWED
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['after'].encode())==1,('ADCB exact authorized source',row['path'])
        result.remove(row['after'].encode())
        if row['before'] is not None:result.append(row['before'].encode())
    return result

PLAN3_R10=json.loads((ROOT/'tests/product_plan3_resource_frame_delta.json').read_text(encoding='utf-8'))
R10_ALLOWED={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('MainActivity.kt','OwnerWindow.kt')}
assert set(PLAN3_R10['allowedPaths'])==R10_ALLOWED,'R10 explicit presentation-only source boundary'
PLAN3_R9=json.loads((ROOT/'tests/product_plan3_autonomous_delta.json').read_text(encoding='utf-8'))
R9_ALLOWED={'app/src/main/java/com/zui/zuicontrol/'+n for n in ('MainActivity.kt','ZuioptRead.kt','ZuioptRules.kt','ZuioptLibrary.kt')}
R9_ALLOWED|={'framework_patch/src/services/com/zui/server/control/'+n for n in ('ZuiControlService.java','ZuioptSceneAuthority.java')}
R9_ALLOWED|={'native/zuiopt/'+n for n in ('ZUIopt_binder.h','ZUIopt_daemon.h','ZUIopt_lifecycle.h','ZUIopt_scene.h','ZUIopt_store.h')}
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

def reverse_runtime_text(path,text):
    text=reverse_recovery_r2(path,text)
    change=next((r for r in RUNTIME['files'] if r['path']==path),None)
    if change:
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('ADCB exact authorized bytes',path)
        text=subprocess.check_output(['git','-C',str(ROOT),'show',RUNTIME['baseHead']+':'+path]).decode().replace('\r\n','\n')
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('ADCB immutable base',path)
    return text

def reverse_text(path,text):
    text=reverse_runtime_text(path,text)
    change=next((r for r in PLAN3_R10['files'] if r['path']==path),None)
    if change:
        assert path in R10_ALLOWED
        assert hashlib.sha256(text.encode()).hexdigest()==change['afterSha256'],('R10 exact authorized bytes',path)
        for h in reversed(change['hunks']):
            assert text.count(h['after'])==1,('R10 authorized inset timing delta',path)
            text=text.replace(h['after'],h['before'],1)
        assert hashlib.sha256(text.encode()).hexdigest()==change['beforeSha256'],('R10 immutable R9 base',path)
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
    result=reverse_runtime_entries(current,*scopes)
    assert PLAN3_R10['baseHead']=='159a4144cceb68873cd48274d9fe737720ff229b'
    for row in PLAN3_R10['files']:
        assert row['path'] in R10_ALLOWED
        if not any(row['path'].startswith(scope+'/') for scope in scopes):continue
        assert result.count(row['after'].encode())==1,('R10 exact authorized source',row['path'])
        result.remove(row['after'].encode());result.append(row['before'].encode())
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
