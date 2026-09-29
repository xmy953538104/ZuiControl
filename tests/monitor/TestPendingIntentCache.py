"""Execute the exact production PendingIntent cache with a counting Android boundary."""
from pathlib import Path
import os,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
quick=(APP/'ZuiControlQuickService.kt').read_text('utf8')
cache_method=quick[quick.index('    private var pendingIntentCreations'):quick.index('    private fun snapshot():')]
harness='''package com.zui.zuicontrol
class Intent(val context:Any,val type:Class<*>) {var action="";fun setAction(value:String):Intent{action=value;return this}}
class PendingIntent(val request:Int,val intent:Intent,val flags:Int){companion object{
 const val FLAG_IMMUTABLE=1;const val FLAG_UPDATE_CURRENT=2;var creations=0
 fun getService(context:Any,request:Int,intent:Intent,flags:Int):PendingIntent{creations++;return PendingIntent(request,intent,flags)}
}}
class ZuiControlQuickService {
'''+cache_method+'''
 fun action(name:String,code:Int)=pending(name,code)
}
fun main(){
 val first=ZuiControlQuickService();val a=first.action("refresh",1)
 repeat(1000){check(first.action("refresh",1)===a)}
 check(PendingIntent.creations==1);check(a.intent.action=="refresh"&&a.request==1&&a.flags==3)
 check(first.action("refresh",2)!==a);check(first.action("uperf",1)!==a)
 check(PendingIntent.creations==3)
 val second=ZuiControlQuickService();check(second.action("refresh",1)!==a)
 check(PendingIntent.creations==4)
 println("PENDING_INTENT_EXACT_CACHE_IDENTITY_FLAGS_ACTION_LIFETIME=PASS")
}
'''
cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
compilers=list((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable').glob('*/*/*.jar'))
assert len(compilers)==1,'Run Gradle first: '+str(compilers)
version=compilers[0].parent.parent.name
jars=list((cache/'org.jetbrains.kotlin').glob('*/'+version+'/*/*.jar'))
jars+=list((cache/'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
annotations=list((cache/'org.jetbrains/annotations/13.0').glob('*/*.jar'));jars+=annotations
stdlib=next((cache/'org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar'))
java=str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')) if 'JAVA_HOME' in os.environ else shutil.which('java')
with tempfile.TemporaryDirectory(prefix='zui-pending-') as tmp:
    tmp=Path(tmp);src=tmp/'PendingTest.kt';src.write_text(harness,encoding='utf8')
    subprocess.run([java,'-cp',os.pathsep.join(map(str,jars)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib','-no-reflect','-classpath',os.pathsep.join(map(str,[stdlib,*annotations])),
        '-d',str(tmp/'classes'),str(src)],check=True,timeout=60)
    subprocess.run([java,'-cp',os.pathsep.join(map(str,[tmp/'classes',stdlib])),
        'com.zui.zuicontrol.PendingTestKt'],check=True,timeout=20)
