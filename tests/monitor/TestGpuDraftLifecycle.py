"""Actual session Bundle codec with counted host platform/owner boundaries.

Gson is the already installed AGP dependency. This is host restoration/model proof,
not Android parcel/process or actual Backend proof; those require device gates.
"""
from pathlib import Path
import os,subprocess,tempfile,shutil
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
compiler=next((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable').glob('*/*/*.jar'))
version=compiler.parent.parent.name
jars=list((cache/'org.jetbrains.kotlin').glob('*/'+version+'/*/*.jar'))+list((cache/'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
stdlib=next((cache/'org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar'))
gson=next((cache/'com.google.code.gson/gson').glob('*/*/*.jar'))
annotations=list((cache/'org.jetbrains/annotations/13.0').glob('*/*.jar'))
java=str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')) if 'JAVA_HOME' in os.environ else shutil.which('java')
sources={
'Platform.kt':'''package android.os
class Bundle {
 private val data=mutableMapOf<String,Any>()
 fun putInt(k:String,v:Int){data[k]=v};fun getInt(k:String,d:Int=0)=data[k] as? Int ?: d
 fun putLong(k:String,v:Long){data[k]=v};fun getLong(k:String)=data[k] as? Long ?: 0L
 fun putString(k:String,v:String){data[k]=v};fun getString(k:String)=data[k] as? String
 fun getString(k:String,d:String)=getString(k) ?: d
 fun putBoolean(k:String,v:Boolean){data[k]=v};fun getBoolean(k:String)=data[k] as? Boolean ?: false
 fun putByteArray(k:String,v:ByteArray){data[k]=v};fun getByteArray(k:String)=data[k] as? ByteArray
}
class Looper {companion object{fun getMainLooper()=Looper()}}
class Handler(looper:Looper){fun post(task:()->Unit){task()}}
''',
'Json.kt':'''package org.json
import com.google.gson.*
private fun element(v:Any?):JsonElement=when(v){is JSONObject->v.value;is JSONArray->v.value;else->Gson().toJsonTree(v)}
class JSONObject(val value:JsonObject=JsonObject()) {
 constructor(text:String):this(JsonParser.parseString(text).asJsonObject)
 constructor(map:Map<*,*>):this(){map.forEach{(k,v)->value.add(k.toString(),element(v))}}
 fun put(k:String,v:Any?):JSONObject{value.add(k,element(v));return this}
 fun getString(k:String)=value[k].asString;fun getInt(k:String)=value[k].asInt
 fun getLong(k:String)=value[k].asLong;fun getBoolean(k:String)=value[k].asBoolean
 fun has(k:String)=value.has(k);fun getJSONArray(k:String)=JSONArray(value[k].asJsonArray)
 override fun toString()=value.toString()
}
class JSONArray(val value:JsonArray=JsonArray()) {
 constructor(items:List<*>):this(){items.forEach{value.add(element(it))}}
 fun length()=value.size();fun getInt(i:Int)=value[i].asInt
 fun getJSONObject(i:Int)=JSONObject(value[i].asJsonObject)
}
''',
'Owners.kt':'''package com.zui.zuicontrol
object PackageNames{fun isValid(s:String)=s.contains('.')}
object ZuiControlClient {
 enum class GpuPolicy{CUSTOM,DEFAULT_FOR_MODE}
 data class AppPolicyDraft(val packageName:String,val refreshHz:Int,val uperfMode:String,val gpuPolicy:GpuPolicy,val expectedGeneration:Long,val gpuMinMHz:Int?=null,val gpuMaxMHz:Int?=null)
 data class AppPolicyRead(val draft:AppPolicyDraft,val effectiveMinMHz:Int,val effectiveMaxMHz:Int)
 data class AppPolicies(val userId:Int,val generation:Long,val apps:List<AppPolicyRead>)
 data class Reply(val ok:Boolean,val text:String)
 fun replyIsOk(s:String)=s.lineSequence().firstOrNull()=="ok=1"
 fun stateValue(s:String,k:String)=s.lineSequence().firstOrNull{it.startsWith(k+"=")}?.substringAfter('=')
}
interface FrontendGateway {
 fun readGpuDefaults(user:Int):GpuDefaultsDraft
 fun saveGpuDefaultsAtomic(a:GpuRanges.Range,b:GpuRanges.Range,c:GpuRanges.Range,d:GpuRanges.Range,g:Long):ZuiControlClient.Reply
 fun saveAppPolicy(d:ZuiControlClient.AppPolicyDraft):ZuiControlClient.Reply
 fun readAppPolicy(pkg:String):ZuiControlClient.AppPolicyDraft?
}
object SettingsBackup{data class Inspection(val transaction:String,val hash:String,val summary:org.json.JSONObject)}
object RuleUpstreamFetcher{class Latest}
object ZuioptLibrary{class Preview;class Provenance;class Decision}
data class ZuioptRuleModel(val profiles:Map<String,Profile> = emptyMap(),val mappings:List<Mapping> = emptyList()) {
 data class Profile(val generalMask:Set<Int>,val rules:List<Rule>)
 data class Rule(val competitionClass:String,val matchKind:String,val pattern:String,val selector:String,val priority:Int,val cpuMask:Set<Int>)
 data class Mapping(val kind:String,val pattern:String,val profile:String,val priority:Int)
 fun appProfile(s:String):Profile?=error("unused");fun editApp(s:String,f:()->Profile)=error("unused") as ZuioptRuleModel
 fun normalized():String=error("unused")
 companion object{fun parseNormalized(s:String)=error("unused") as ZuioptRuleModel}
}
''',
'Harness.kt':'''package com.zui.zuicontrol
import android.os.Bundle
import java.util.concurrent.Executors
fun main(){
 var writes=0
 val boundary=object:FrontendGateway{
  override fun readGpuDefaults(user:Int):GpuDefaultsDraft=error("not a read gate")
  override fun saveGpuDefaultsAtomic(a:GpuRanges.Range,b:GpuRanges.Range,c:GpuRanges.Range,d:GpuRanges.Range,g:Long):ZuiControlClient.Reply{writes++;error("forbidden")}
  override fun saveAppPolicy(d:ZuiControlClient.AppPolicyDraft):ZuiControlClient.Reply{writes++;error("forbidden")}
  override fun readAppPolicy(pkg:String):ZuiControlClient.AppPolicyDraft?=error("unused")
 }
 val first=FrontendSession(boundary,0,Executors.newSingleThreadExecutor()){}
 val restored=FrontendSession(boundary,0,Executors.newSingleThreadExecutor()){}
 val otherUser=FrontendSession(boundary,1,Executors.newSingleThreadExecutor()){}
 try{
  first.changeContext("settings",1)
  first.gpuDraft=GpuDefaultsDraft(458,GpuDefaultsDraft.modes.associateWith{GpuRanges.Range(231,903)})
  first.gpuDraft!!.set("powersave",GpuRanges.Range(231,422));first.gpuDraft!!.set("fast",GpuRanges.Range(629,903))
  val saved=Bundle();first.save(saved)
  check(saved.getString("gpu")!!.contains("powersave")) // Actual serialized ranges, not a retained object reference.
  restored.restore(saved)
  check(restored.section=="settings" && restored.settingsModule==1 && restored.gpuDraft!!.dirty)
  check(restored.gpuDraft!==first.gpuDraft && restored.gpuDraft!!.expectedGeneration==458L)
  check(restored.gpuDraft!!.ranges==first.gpuDraft!!.ranges && restored.gpuDraft!!.original==first.gpuDraft!!.original)
  check(!restored.changeContext("settings") && !restored.changeContext("settings",1))
  restored.returnHome();check(restored.gpuDraft!!.dirty)
  otherUser.restore(saved);check(otherUser.gpuDraft==null)
  check(writes==0)
  println("ACTUAL_SESSION_BUNDLE_GPU_RESTORATION_HOST_BOUNDARY=PASS;WRITE_COUNT=0;OTHER_USER_REJECTED=true")
 }finally{first.close();restored.close();otherUser.close()}
}
'''}
with tempfile.TemporaryDirectory(prefix='zui-gpu-lifecycle-') as directory:
 p=Path(directory)
 for name,text in sources.items():(p/name).write_text(text,encoding='utf8')
 cp=os.pathsep.join(map(str,[stdlib,gson,*annotations]))
 cmd=[java,'-cp',os.pathsep.join(map(str,[*jars,*annotations])),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(p/'classes'),*[str(APP/n) for n in ('FrontendSession.kt','FrontendTransport.kt','FrontendState.kt','GpuRanges.kt')],*map(str,p.glob('*.kt'))]
 result=subprocess.run(cmd,capture_output=True,text=True,timeout=60)
 assert result.returncode==0,result.stdout+result.stderr
 result=subprocess.run([java,'-cp',os.pathsep.join([str(p/'classes'),cp]),'com.zui.zuicontrol.HarnessKt'],capture_output=True,text=True,timeout=20)
 assert result.returncode==0,result.stdout+result.stderr
 print(result.stdout.strip())
