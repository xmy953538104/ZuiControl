"""Diagnostic profile separation and one-shot exact-identity fault selection."""
from pathlib import Path
import os,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
gradle=(ROOT/'app/build.gradle.kts').read_text(encoding='utf-8')
release=gradle.split('getByName("release") {',1)[1].split('getByName("debug")',1)[0]
profile=gradle.split('create("nativeProfile") {',1)[1].split('\n        }',1)[0]
assert 'isDebuggable = false' in release and 'isProfileable = false' in release
assert 'initWith(getByName("release"))' in profile and 'isProfileable = true' in profile
assert 'androidTestCompileOnly(project(":framework-stubs"))' in gradle
workflow=(ROOT/'.github/workflows/build.yml').read_text(encoding='utf-8')
stage=workflow.split('- name: Stage payload APK',1)[1]
assert 'app/build/outputs/apk/release/app-release.apk' in stage and 'nativeProfile' not in stage
assert 'app/build/outputs/apk/nativeProfile/app-nativeProfile.apk' in workflow
probe=(ROOT/'app/src/androidTest/java/com/zui/zuicontrol/probe/ResourceProbe.java').read_text(encoding='utf-8')
assert 'startActivitySync(launch)' in probe and 'Binder.getCallingUid()!=1000' in probe
assert 'data.setDataPosition(position)' in probe and 'delegate.transact(code,data,reply,flags)' in probe
assert 'ACK_TARGET_ARMED id=' in probe and 'PRIMARY_FAILURE mode=' in probe and 'CLEANUP_FAILURE mode=' in probe
assert 'controlsOwner.controls(true,probeCallback)' in probe and 'getMethod("controls"' not in probe
assert 'Pending fault requires actual HOME scene' in probe
source=ROOT/'app/src/androidTest/java/com/zui/zuicontrol/probe/AckFaultGate.java'
with tempfile.TemporaryDirectory(prefix='zui-r5-ack-gate-') as directory:
    path=Path(directory);shutil.copyfile(source,path/source.name)
    (path/'GateTest.java').write_text('''package com.zui.zuicontrol.probe;
public class GateTest {public static void main(String[] args){
 AckFaultGate gate=new AckFaultGate();String sha="a".repeat(64);if(gate.take("id",sha)!=0)throw new AssertionError();
 for(int action:new int[]{1,2}){gate.arm("id",sha,action);
  if(gate.take("wrong",sha)!=0||gate.take("id","b".repeat(64))!=0||gate.consumed())throw new AssertionError();
  if(gate.take("id",sha)!=action||!gate.consumed()||gate.take("id",sha)!=0)throw new AssertionError();}
 for(int invalid:new int[]{-1,0,3}){try{gate.arm("id",sha,invalid);throw new AssertionError();}catch(IllegalArgumentException expected){}}
 System.out.println("EXACT_ID_HASH_ONE_FAULT_THEN_PASS_THROUGH=PASS");}}
''',encoding='utf-8')
    subprocess.run(['javac','-d',str(path),str(path/source.name),str(path/'GateTest.java')],check=True)
    subprocess.run(['java','-cp',str(path),'com.zui.zuicontrol.probe.GateTest'],check=True)
print('TEST_PROFILE_NEVER_STAGED_IN_PRODUCTION_PAYLOAD=PASS')
