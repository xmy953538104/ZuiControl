"""Exact App request lifecycle and SAF export slices; fakes only platform boundaries."""
from pathlib import Path
import os,subprocess,tempfile,sys
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
def compile_run(files,main,args):
    cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
    compiler=list((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable').glob('*/*/*.jar'))
    assert len(compiler)==1,'Resolve exact existing Kotlin compiler first'
    version=compiler[0].parent.parent.name
    jars=list((cache/'org.jetbrains.kotlin').glob('*/'+version+'/*/*.jar'))
    jars+=list((cache/'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
    annotations=list((cache/'org.jetbrains/annotations/13.0').glob('*/*.jar'));jars+=annotations
    stdlib=next((cache/'org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar'))
    with tempfile.TemporaryDirectory(prefix='utility-truth-') as tmp:
        tmp=Path(tmp);sources=[]
        for name,text in files.items():
            p=tmp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');sources.append(str(p))
        subprocess.run(['java','-cp',os.pathsep.join(map(str,jars)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib','-no-reflect','-classpath',os.pathsep.join(map(str,[stdlib,*annotations])),
            '-d',str(tmp/'classes'),*sources],check=True,timeout=60)
        subprocess.run(['java','-cp',os.pathsep.join(map(str,[tmp/'classes',stdlib])),main,*args],check=True,timeout=20)

def saf(case):
    source=(APP/'MainActivity.kt').read_text('utf8')
    body=source[source.index('        if (requestCode != REQUEST_EXPORT_LOG) return'):source.index('    private fun addFloatingButton(')]
    # Includes the exact final onActivityResult branch and closing function brace.
    harness='''package com.zui.zuicontrol
import java.io.*
class Resolver(val mode:String){var bytes=ByteArrayOutputStream();fun openOutputStream(uri:String):OutputStream? =
 if(mode=="null")null else if(mode=="throw")object:OutputStream(){override fun write(b:Int){throw IOException("write failure")}} else bytes}
class Export(val mode:String){val contentResolver=Resolver(mode);var pendingExportText=if(mode=="empty")"" else "CURRENT_EXPORT";var message=""
 val REQUEST_EXPORT_LOG=1
 fun toast(s:String){message=s}
 fun result(requestCode:Int,uri:String){
'''+body+'''}
fun main(args:Array<String>){val c=args.single();val e=Export(c);e.result(1,"fixture")
 if(c=="ok")check(e.message=="日志已导出"&&e.contentResolver.bytes.toString()=="CURRENT_EXPORT")
 else check(e.message!="日志已导出"){"false success: $c"}
 println("SAF_$c=PASS")}
'''
    compile_run({'Saf.kt':harness},'com.zui.zuicontrol.SafKt',[case])

def rejection():
    files={
    'Context.kt':'''package android.content
class ContentResolver
class Prefs {val values=mutableMapOf<String,String>();fun getString(k:String,d:String?):String?=values[k]?:d;fun edit()=Editor(this)
 class Editor(val p:Prefs){val pending=p.values.toMutableMap();fun putString(k:String,v:String):Editor{pending[k]=v;return this}
 fun clear():Editor{pending.clear();return this};fun commit():Boolean{p.values.clear();p.values.putAll(pending);return true}}}
class Context {val contentResolver=ContentResolver();val prefs=Prefs();fun createDeviceProtectedStorageContext()=this
 fun getSharedPreferences(n:String,m:Int)=prefs;companion object{const val MODE_PRIVATE=0}}
''',
    'Settings.kt':'''package android.provider
object Settings{object System{val data=mutableMapOf<String,String>();fun getString(r:android.content.ContentResolver,k:String)=data[k]
 fun putString(r:android.content.ContentResolver,k:String,v:String):Boolean{data[k]=v;return true}}}''',
    'Clock.kt':'''package android.os
object SystemClock{var n=1000L;fun elapsedRealtime()=n++;fun elapsedRealtimeNanos()=n++}''',
    'Log.kt':'''package android.util
object Log{fun i(t:String,s:String)=0}''',
    'Client.kt':'''package com.zui.zuicontrol
object ZuiControlClient{data class Reply(val ok:Boolean,val text:String);var refusal="settings_primary_user_required";var kicks=0
 fun sendPolicy(c:android.content.Context,a:String,p:String,s:String,mode:String="")="unused"
 fun stateValue(s:String,k:String)=s.lineSequence().firstOrNull{it.startsWith("$k=")}?.substringAfter('=')
 fun notifyControlRequest(id:String,sha:String):Reply{kicks++;return if(refusal.isEmpty())Reply(true,"ok=1") else Reply(false,"ok=0\\nerror=$refusal\\nresultCategory=DEFINITIVE_NOT_ADMITTED")}}
fun main(){for(reason in listOf("settings_primary_user_required","policy_store_unavailable","policy_user_mismatch","request_busy","request_transport_bound","request_payload_mismatch")){
 val c=android.content.Context();ZuiControlClient.refusal=reason
 val rejected=runCatching{ZuiControlRequest.send(c,"sb_export")}.isFailure
 check(rejected&&c.prefs.values.isEmpty()){ "definitive refusal retained pending: $reason" }
 ZuiControlClient.refusal="";check(ZuiControlRequest.send(c,"status").isNotEmpty())
}
println("UTILITY_DEFINITIVE_REJECTION_NEXT_REQUEST=PASS")}
''',
    'ZuiControlRequest.kt':(APP/'ZuiControlRequest.kt').read_text('utf8'),
    'ZuiControlContract.kt':(APP/'ZuiControlContract.kt').read_text('utf8')}
    compile_run(files,'com.zui.zuicontrol.ClientKt',[])

if __name__=='__main__':
    if sys.argv[1]=='rejection':rejection()
    else:saf(sys.argv[1])
