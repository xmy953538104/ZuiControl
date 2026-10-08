"""Execute production V84 adapters against producer bytes and counted platform boundaries.

JSON fixture uses production PolicyJson, not invented successful wire/analysis data.
Android Binder, SQLite bindings and rendering remain merged-ROM device gates.
"""
from pathlib import Path
import base64, json, os, re, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/java/com/zui/zuicontrol'
SERVICES = ROOT / 'framework_patch/src/services/com/zui/server/control'
sys.path.insert(0, str(ROOT / 'tests/command_plane'))
from UtilityWireContract import app_wire
sys.path.insert(0, str(ROOT / 'scripts/rules'))
from PublishRules import build

manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
android = '{http://schemas.android.com/apk/res/android}'
assert all(a.get(android+'screenOrientation') == 'sensorLandscape' for a in manifest.findall('./application/activity'))
for filename in ('MainActivity.kt', 'FrontendGateway.kt', 'FrontendSession.kt', 'PerformanceRecordActivity.kt'):
    text = (APP / filename).read_text('utf8')
    assert all(bad not in text for bad in ('recordStart', 'analysisStart', 'analysisStop', 'FakeFrontendGateway', 'DemoBackend', 'Math.random'))
backend_scopes = ['framework_patch', 'native', 'payload/system', 'payload/patches']
# Current Owner Plan3 deltas must reverse exactly to the immutable integration
# baseline before the earlier freeze proofs are inherited.
sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import entries,PLAN3,PLAN3_RESPONSIVENESS,PLAN3_VERTICAL,PLAN3_DOMAIN
current=entries(*backend_scopes)
for row in PLAN3_DOMAIN['files']:
    assert row['path'] in PLAN3_DOMAIN['allowedPaths']
    if any(row['path'].startswith(scope+'/') for scope in backend_scopes):
        if row['after'] is not None:
            assert current.count(row['after'].encode())==1
            current.remove(row['after'].encode())
        else:assert not any(r.endswith(('\t'+row['path']).encode()) for r in current)
        if row['before'] is not None:current.append(row['before'].encode())
for row in PLAN3_VERTICAL['files']+PLAN3_RESPONSIVENESS['files']+PLAN3['files']:
    if any(row['path'].startswith(scope+'/') for scope in backend_scopes):
        assert current.count(row['after'].encode())==1
        current.remove(row['after'].encode());current.append(row['before'].encode())
assert sorted(current)==sorted(subprocess.check_output(['git','ls-tree','-r','6d896f29812dab653e2e88c6ee989c972a343c97','--',*backend_scopes],cwd=ROOT).splitlines())
# BuildZUIopt deliberately replaces its tracked seed with the current CI ELF.
# Current authorized runtime edits have already been byte-bound and reversed above.
generated = subprocess.check_output(['git', 'diff', '--name-only', 'HEAD', '--', *backend_scopes], cwd=ROOT, text=True).splitlines()
assert set(generated) <= {'payload/system/bin/ZUIopt'} | {r['path'] for r in PLAN3_DOMAIN['files']+PLAN3_VERTICAL['files']+PLAN3_RESPONSIVENESS['files']+PLAN3['files'] if any(r['path'].startswith(scope+'/') for scope in backend_scopes)}, generated

stubs = {
    'Context.kt': 'package android.content\nclass Context\n',
    'JSON.kt': '''package org.json
import com.zui.server.control.JsonBridge
class JSONObject(private val data:MutableMap<String,Any?> = linkedMapOf()) {
 constructor(text:String):this((JsonBridge.parse(text) as Map<String,Any?>).toMutableMap())
 fun put(k:String,v:Any?):JSONObject {data[k]=when(v){is JSONObject->v.data;is JSONArray->v.data;else->v};return this}
 fun get(k:String):Any=checkNotNull(data[k])
 fun getInt(k:String)=(get(k) as Number).toInt()
 fun getLong(k:String)=(get(k) as Number).toLong()
 fun getDouble(k:String)=(get(k) as Number).toDouble()
 fun getString(k:String)=get(k) as String
 fun getBoolean(k:String)=get(k) as Boolean
 fun optString(k:String)=data[k] as? String ?: ""
 fun has(k:String)=data.containsKey(k)
 fun isNull(k:String)=data[k]==null
 fun length()=data.size
 fun keys()=data.keys.iterator()
 fun getJSONObject(k:String)=JSONObject((get(k) as Map<String,Any?>).toMutableMap())
 fun getJSONArray(k:String)=JSONArray(get(k) as List<Any?>)
 override fun toString()=JsonBridge.encode(data)
}
class JSONArray(val data:List<Any?>) {
 fun length()=data.size
 fun getJSONObject(i:Int)=JSONObject(JsonBridge.encode(data[i]))
}
''',
    'Boundary.kt': '''package com.zui.zuicontrol
import android.content.Context
import org.json.JSONObject
object ZuiControlClient {
 data class Reply(val ok:Boolean,val text:String)
 class AppPolicyDraft(val packageName:String="")
 class AppRow(val draft:AppPolicyDraft)
 class AppPolicies(val apps:List<AppRow>)
 fun appPolicies()=AppPolicies(emptyList())
 fun currentUserId()=0
 fun stateText()="ok=1\\npolicyGeneration=1\\n"+GpuDefaultsDraft.modes.joinToString("\\n"){val r=GpuRanges.default(it);"gpuGlobal=0|$it|${r.min}|${r.max}"}
 fun saveAppPolicy(c:Context,d:AppPolicyDraft)=Reply(true,"")
 fun sendPolicy(c:Context,a:String,p:String,s:String,value:Int,mode:String)="global"
 fun stateValue(s:String,k:String)=s.lineSequence().firstOrNull{it.startsWith("$k=")}?.substringAfter('=')
 fun replyIsOk(s:String)=s.lineSequence().firstOrNull()?.trim()=="ok=1"
}
object ZuiControlRequest {
 data class Ack(val succeeded:Boolean,val detail:String)
 var calls=0;var acks=0;var succeed=true;var wire=""
 fun send(c:Context,cmd:String,mode:String):String {calls++;wire=buildRequestText("batch",cmd,"",mode);return "batch"}
 fun awaitTerminalAck(c:Context,id:String):Ack {check(id=="batch");acks++;return Ack(succeed,"terminal")}
 ENCODER
}
object ThreadAnalysisClient {var result=JSONObject();fun read(p:String)=result}
object PerformanceMonitor {
 var record="{}";val actions=mutableListOf<String>()
 fun command(a:String,arg:String=""):String {actions+=a;return if(a=="recordRead")record else "ok=1"}
}
object ZuioptLibrary {data class Baseline(val metadata:JSONObject,val hash:String)}
object ZuioptRules {fun boundedRead(s:java.io.InputStream,n:Int):ByteArray=s.readBytes().also{require(it.size<=n)}}
''',
    'Harness.kt': '''package com.zui.zuicontrol
import android.content.Context
import org.json.JSONObject
import java.io.File
fun main(args:Array<String>) {
 val gateway=V84FrontendGateway(Context())
 val ranges=GpuDefaultsDraft.modes.map(GpuRanges::default)
 val state="ok=1\\npolicyGeneration=1\\n"+GpuDefaultsDraft.modes.zip(ranges).joinToString("\\n"){(m,r)->"gpuGlobal=0|$m|${r.min}|${r.max}"}
 val draft=GpuDefaultsDraft.fromState(state,0);check(draft.expectedGeneration==1L && draft.original.values.toSet()==ranges.toSet())
 fun rejects(f:()->Unit){try{f();error("accepted invalid input")}catch(e:IllegalArgumentException){}catch(e:IllegalStateException){check(e.message!="accepted invalid input")}}
 rejects {GpuDefaultsDraft.fromState(state.replace("gpuGlobal=0|fast", "gpuGlobal=10|fast"),0)}
 rejects {GpuDefaultsDraft.fromState(state+"\\ngpuGlobal=0|fast|629|903",0)}
 for(success in listOf(false,true)) {
  ZuiControlRequest.succeed=success
  check(gateway.saveGpuDefaultsAtomic(ranges[0],ranges[1],ranges[2],ranges[3],1).ok==success)
 }
 check(ZuiControlRequest.calls==2 && ZuiControlRequest.acks==2)
 File(args[2]).writeText(ZuiControlRequest.wire)
 val a=JSONObject(File(args[0]).readText());val r=JSONObject(File(args[1]).readText())
 ThreadAnalysisClient.result=a;PerformanceMonitor.record=r.toString()
 check(gateway.readRecordLinkedThreadAnalysis(a.getString("package"))===a)
 for((left,right) in listOf("sourceRecordId" to "recordId", "sourceRecordWall" to "wall", "sourceRecordStartElapsed" to "startElapsed",
  "user" to "user", "package" to "package", "sourceRecordCompletion" to "complete", "sourceRecordTerminalReason" to "terminalReason")) {
  val changed=JSONObject(r.toString());val v=changed.get(right)
  changed.put(right,when(v){is Boolean->!v;is Number->v.toLong()+1;else->"OTHER"})
  rejects {V84FrontendGateway.validateRecordRelation(a,changed,0,a.getString("package"))}
 }
 val zero=JSONObject(a.toString()).put("sourceRecordWall",0);val zeroRecord=JSONObject(r.toString()).put("wall",0)
 V84FrontendGateway.validateRecordRelation(zero,zeroRecord,0,a.getString("package"))
 val nulls=JSONObject(a.toString());val rows=nulls.getJSONArray("threads");check(rows.length()>0)
 // Preserve valid unknown metrics from the native producer, never replace them with zero.
 val row=rows.getJSONObject(0).put("avgCpuPct",null).put("medianRank",null)
 nulls.put("threads",org.json.JSONArray(listOf(JsonBridgeValue(row))))
 V84FrontendGateway.validateRecordRelation(nulls,r,0,a.getString("package"))
 for(changed in listOf(JSONObject(a.toString()).put("schema",1),JSONObject(a.toString()).put("acquisition","STANDALONE_COMPATIBILITY"),JSONObject(a.toString()).put("state","FAILED"))) {
  rejects {V84FrontendGateway.validateRecordRelation(changed,r,0,a.getString("package"))}
 }
 gateway.stopRecord();check(PerformanceMonitor.actions.last()=="recordStop")
 val manifest=File(args[3]).readBytes();val bytes=File(args[4]).readBytes();val hash=RuleUpstreamFetcher.hash(bytes)
 val baseline=ZuioptLibrary.Baseline(JSONObject().put("sourceEvidence","publisher-revision:1"),"0".repeat(64))
 var fetches=0
 val upstream=RuleUpstreamFetcher("https://qualification.invalid", "") {url,limit->fetches++;if(url.endsWith("latest.json")){check(limit==2048);manifest}else{check(limit==65536);bytes}}
 val latest=checkNotNull(upstream.check(baseline));check(latest.rulesSchema==2 && latest.sha256==hash)
 check(upstream.download(latest,baseline).contentEquals(bytes));check(fetches==3)
 rejects {upstream.check(baseline.copy(metadata=JSONObject().put("sourceEvidence","revision:1")))}
 val obsolete=RuleUpstreamFetcher("https://qualification.invalid", "") {_,_->manifest.toString(Charsets.UTF_8).replace("rulesSchema","ruleSchema").toByteArray()}
 rejects {obsolete.check(baseline)}
 val unset=RuleUpstreamFetcher("", "") {_,_->error("unconfigured network")}
 rejects {unset.check(baseline)}
 println("V84_REAL_ADAPTER_BATCH_ACK_COMPOSITE_RELATION_NULL_LEGACY_STOP_PUBLISHER_SINGLE_SOURCE=PASS")
}
fun JsonBridgeValue(j:JSONObject)=com.zui.server.control.JsonBridge.parse(j.toString())
'''
}
request = (APP / 'ZuiControlRequest.kt').read_text('utf8')
encoder = request[request.index('    internal fun buildRequestText('):request.index('    internal fun hasPendingRequest(')]
stubs['Boundary.kt'] = stubs['Boundary.kt'].replace('ENCODER', encoder)

cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
compiler = next((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable').glob('*/*/*.jar'))
version = compiler.parent.parent.name
jars = list((cache/'org.jetbrains.kotlin').glob('*/'+version+'/*/*.jar')) + list((cache/'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
annotations = list((cache/'org.jetbrains/annotations/13.0').glob('*/*.jar'))
stdlib = next((cache/'org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar'))
with tempfile.TemporaryDirectory(prefix='zui-final-adapters-') as directory:
    tmp = Path(directory)
    subprocess.run([sys.executable, str(ROOT/'tests/monitor/TestAnalysisCollector.py'), str(tmp/'analysis.json')], check=True)
    a = json.loads((tmp/'analysis.json').read_text('utf8'))
    r = dict(recordId=a['sourceRecordId'], wall=a['sourceRecordWall'], startElapsed=a['sourceRecordStartElapsed'], user=a['user'], package=a['package'], complete=a['sourceRecordCompletion']=='COMPLETE', terminalReason=a['sourceRecordTerminalReason'], active=False)
    (tmp/'record.json').write_text(json.dumps(r), encoding='utf8')
    metadata, rules = build((ROOT/'payload/system/etc/zuiopt/factory_rules.conf').read_bytes(), 'canonical', 2, 'qualification-fixture', 'v84', '2026-10-04', 'a'*40)
    (tmp/'latest.json').write_bytes(metadata); (tmp/'rules.conf').write_bytes(rules)
    (tmp/'JsonBridge.java').write_text('''package com.zui.server.control;
public class JsonBridge {
 public static Object parse(String s)throws Exception{return PolicyJson.parse(s.getBytes(java.nio.charset.StandardCharsets.UTF_8),524288);}
 public static String encode(Object v){return PolicyJson.encode(v);}
 public static void main(String[] args)throws Exception {
  var p=PolicyJson.object(parse(java.nio.file.Files.readString(java.nio.file.Path.of(args[0]))));
  PolicyJson.keys(p,"action","userId","generation","ranges");
  var old=GpuDefaultsBatchTest.initial();
  var next=AppPolicyStore.defaultGpuBatch(old,PolicyJson.integer(p.get("generation")),PolicyJson.intValue(p.get("userId")),p.get("ranges"));
  if(next.generation!=old.generation+1 || !next.apps.equals(old.apps))throw new AssertionError("batch generation/rows");
  for(String mode:AppPolicyStore.MODES){var pair=PolicyJson.object(PolicyJson.object(p.get("ranges")).get(mode));
   if(next.range(0,mode).minMHz!=PolicyJson.intValue(pair.get("min")) || next.range(0,mode).maxMHz!=PolicyJson.intValue(pair.get("max")))throw new AssertionError(mode);}
  System.out.println("KOTLIN_GPU_PAYLOAD_TO_PRODUCTION_BACKEND_BUILDER=PASS");
 }
}''', encoding='utf8')
    java_sources=[SERVICES/name for name in ('PolicyJson.java','GpuRange.java','AppPolicyStore.java','SettingsBackup.java','UperfConfigStore.java')]
    java_sources += [ROOT/'tests/gpu'/name for name in ('AppPolicyFixture.java','AtomicPolicyTest.java','FactoryResetTest.java','GpuDefaultsBatchTest.java')]
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(tmp/'classes'), *map(str,java_sources), str(tmp/'JsonBridge.java')], check=True)
    for name, text in stubs.items(): (tmp/name).write_text(text, encoding='utf8')
    cmd = ['java','-cp',os.pathsep.join(map(str,jars+annotations)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',os.pathsep.join(map(str,[tmp/'classes',stdlib,*annotations])),'-d',str(tmp/'classes')]
    cmd += [str(APP/name) for name in ('FrontendGateway.kt','FrontendState.kt','GpuRanges.kt','PackageNames.kt','RuleUpstreamFetcher.kt')]+list(map(str,tmp.glob('*.kt')))
    subprocess.run(cmd, check=True, timeout=60)
    subprocess.run(['java','-cp',os.pathsep.join(map(str,[tmp/'classes',stdlib])),'com.zui.zuicontrol.HarnessKt',str(tmp/'analysis.json'),str(tmp/'record.json'),str(tmp/'wire.txt'),str(tmp/'latest.json'),str(tmp/'rules.conf')],check=True,timeout=30)
    wire = (tmp/'wire.txt').read_text('utf8');payload = json.loads(base64.b64decode(wire.split('|')[4]))
    assert wire == app_wire('batch','policy','',wire.split('|')[4])
    assert set(payload)=={'action','userId','generation','ranges'} and payload['action']=='defaultGpuBatch'
    assert set(payload['ranges'])=={'powersave','balance','performance','fast'}
    assert all(set(pair)=={'min','max'} for pair in payload['ranges'].values())
    (tmp/'gpu.json').write_text(json.dumps(payload),encoding='utf8')
    subprocess.run(['java','-cp',str(tmp/'classes'),'com.zui.server.control.JsonBridge',str(tmp/'gpu.json')],check=True)
print('INTEGRATION_NEGATIVE_ORIENTATION_REAL_PRODUCER_CONSUMER_WIRE=PASS')
