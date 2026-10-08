"""V3 boundaries plus exact production notification renderer with counting Android boundaries.
This tests RemoteViews actions; it does not claim a SystemUI pixel/device render.
"""
from pathlib import Path
import os,re,shutil,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
def read(name):return (APP/name).read_text(encoding='utf-8')

class FrontendV3(unittest.TestCase):
    def test_shared_command_reads_use_existing_session_queue(self):
        main=read('MainActivity.kt');session=read('FrontendSession.kt')
        load=main.split('private fun load() {',1)[1].split('private fun observeGlobals()',1)[0]
        self.assertIn('"tune"->{loadState();loadPolicies();loadCapabilities()}',load);self.assertNotIn('Thread {',load)
        self.assertIn('"monitor"->loadRecords()',load)
        self.assertIn('"settings"->when(session.settingsModule)',load)
        self.assertIn('"thread"->{loadState();loadInventory();loadRules()}',load)
        self.assertNotIn('ZuioptLibrary.baseline',load)
        rules=main.split('private fun loadRules()',1)[1].split('private fun loadRecords()',1)[0]
        self.assertNotIn('shared=true',rules);self.assertIn('cached?.matches(rs)==true',rules)
        self.assertIn('FrontendTransport.commandRead {',read('ZuioptRules.kt'))
        self.assertIn('FrontendTransport.commandRead {',read('ZuioptLibrary.kt'))
        self.assertIn('ZuioptLibrary.baseline(applicationContext,rs)',rules)
        self.assertIn('session.read { runCatching { ZuiControlRequest.recoverPending(appContext) } }',main)
        reader=session.split('fun read(task:()->Unit)',1)[1].split('private fun finishClose()',1)[0]
        self.assertIn('if (closing) return',reader);self.assertIn('executor.execute',reader)
        self.assertIn('= FrontendTransport.commands',session)
        self.assertIn('FrontendTransport.reads.execute',session)
        self.assertNotIn('session.work',main.split('private fun readRecord',1)[1].split('private fun monitorPage',1)[0])
    def test_one_monitor_owner_no_mock_sampler_or_hidden_network_poll(self):
        main=read('MainActivity.kt');gateway=read('FrontendGateway.kt');session=read('FrontendSession.kt')
        self.assertNotIn('monitor("register"',main)
        self.assertIn('MonitorPresentation.observe(monitorChanged)',main)
        self.assertIn('MonitorPresentation.remove(monitorChanged)',main)
        self.assertIn('MonitorPresentation.publish(next)',read('PerformanceMonitor.kt'))
        clock=main.split('private val recordClock',1)[1].split('private var visible',1)[0]
        self.assertIn('bindMonitor()',clock);self.assertNotIn('render()',clock);self.assertNotIn('command(',clock)
        for forbidden in ('DemoBackend','Math.random','Random(', 'analysisStart','analysisStop', 'WebView'):
            self.assertNotIn(forbidden,main+gateway+session)
        self.assertNotIn('setGlobalGpuRange',main+gateway+session)
        self.assertIn('"defaultGpuBatch"',gateway)
        self.assertIn('ThreadAnalysisClient.read(packageName)',gateway)
        remote=read('RuleUpstreamFetcher.kt')
        self.assertIn('const val GITHUB_BASE = ""',remote);self.assertIn('const val GITEE_BASE = ""',remote)
        for forbidden in ('Timer','postDelayed','Thread.sleep'):
            self.assertNotIn(forbidden,remote)
        self.assertIn('check(a == null || b == null || a == b)',remote)
        self.assertIn('latest.verify(bytes, revisions)',remote)
        self.assertIn('ZuioptLibrary.preview',main)
        self.assertNotIn('saveGpuDefaultsAtomic',main) # centralized session operation, never per-slider saves
    def test_record_analysis_identity_empty_state_and_cpu_choice(self):
        main=read('MainActivity.kt')
        for field in ('sourceRecordId','sourceRecordWall','wallElapsedMs','eligibleSamples','processSegmentCount','distinctIdentityCount','sameNameConcurrencyMax','presencePct','avgCpuPct','peakCpuPct','medianRank','top1SharePct','top3SharePct','presenceSamples'):
            self.assertIn('"'+field+'"',main)
        analysis=main.split('private fun analysisCard()',1)[1].split('private fun readRecord',1)[0]
        self.assertIn('暂无可用于线程分析的监测记录',analysis)
        self.assertIn('emptySet()',analysis);self.assertNotIn('recordStart',analysis)
        self.assertIn('session.gateway.stopRecord()',main)
        self.assertNotIn('EditText',main.split('private fun rawView()',1)[1].split('private fun mergeAppOpt',1)[0])
    def test_exact_notification_rebind(self):
        helper=read('NotificationQuickControlHelper.kt')
        ids=sorted(set(re.findall(r'R.id.(\w+)',helper)))
        drawable=sorted(set(re.findall(r'R.drawable.(\w+)',helper)))
        stubs={
          'PendingIntent.kt':'package android.app\nclass PendingIntent(val action:String)\n',
          'Color.kt':'package android.graphics\nobject Color { const val WHITE=-1; fun rgb(r:Int,g:Int,b:Int)=(255 shl 24) or (r shl 16) or (g shl 8) or b }\n',
          'Text.kt':'''package android.text
object Spanned { const val SPAN_EXCLUSIVE_EXCLUSIVE=33 }
class SpannableString(val value:String):CharSequence by value {
 override fun toString()=value
 fun setSpan(span:Any,start:Int,end:Int,flags:Int){check(start in 0..end && end<=length)}
}
''',
          'Spans.kt':'package android.text.style\nclass ForegroundColorSpan(val color:Int)\nclass RelativeSizeSpan(val proportion:Float)\n',
          'View.kt':'package android.view\nobject View { const val VISIBLE=0;const val GONE=8 }\n',
          'RemoteViews.kt':'''package android.widget
import android.app.PendingIntent
class RemoteViews {
 val properties=mutableMapOf<Pair<Int,String>,Any>()
 fun setInt(id:Int,method:String,value:Int){properties[id to method]=value}
 fun setBoolean(id:Int,method:String,value:Boolean){properties[id to method]=value}
 fun setViewVisibility(id:Int,value:Int){properties[id to "visibility"]=value}
 fun setImageViewResource(id:Int,value:Int){properties[id to "image"]=value}
 fun setContentDescription(id:Int,value:String){properties[id to "description"]=value}
 fun setOnClickPendingIntent(id:Int,value:PendingIntent){properties[id to "click"]=value}
 fun setTextViewText(id:Int,value:CharSequence){properties[id to "text"]=value.toString()}
 fun setTextColor(id:Int,value:Int){properties[id to "textColor"]=value}
}
''',
          'R.kt':'package com.zui.zuicontrol\nobject R { object id {\n'+''.join('const val '+n+'='+str(i+1)+'\n' for i,n in enumerate(ids))+'}\nobject drawable {\n'+''.join('const val '+n+'='+str(i+100)+'\n' for i,n in enumerate(drawable))+'}\nobject color {const val mode_powersave=1;const val mode_balance=2;const val mode_performance=3;const val mode_fast=4} }\nobject ZuiControlContract { val rates=listOf(60,90,120,144,165) }\n'
        }
        harness='''package com.zui.zuicontrol
import android.app.PendingIntent
import android.graphics.Color
import android.widget.RemoteViews
fun main() {
 val views=RemoteViews();val helper=NotificationQuickControlHelper
 val rates=listOf(R.id.refresh_60,R.id.refresh_90,R.id.refresh_120,R.id.refresh_144,R.id.refresh_165)
 val modes=listOf(R.id.mode_powersave,R.id.mode_balance,R.id.mode_performance,R.id.mode_fast)
 fun show(rate:Int,tier:UperfMode,enabled:Boolean,active:Boolean,quiet:Double,power:Double) {
  helper.updateRemoteViews(views,NotificationQuickControlHelper.Snapshot("org.test.app",rate,tier,active,true,enabled,quiet,power),
   PendingIntent("monitor"),{PendingIntent("hz:$it")},{PendingIntent("mode:${it.id}")})
 }
 for(tier in UperfMode.entries) for(rate in ZuiControlContract.rates) {
  show(rate,tier,true,true,40.1,4.2)
  check(views.properties[modes[tier.ordinal] to "textColor"]==Color.WHITE)
  check(views.properties[R.id.mode_fast to "text"]=="快速")
  rates.forEachIndexed { i,id -> check((views.properties[id to "textColor"]==Color.WHITE)==(ZuiControlContract.rates[i]==rate)) }
  modes.forEachIndexed { i,id -> check((views.properties[id to "textColor"]==Color.WHITE)==(i==tier.ordinal)) }
  check(views.properties[R.id.monitor_indicator to "visibility"]==0)
  show(rate,tier,false,false,-1.0,-1.0)
  modes.forEach { id ->
   check(views.properties[id to "setEnabled"]==false)
   check(views.properties[id to "setBackgroundResource"]==R.drawable.notify_rate_normal)
   check(views.properties[id to "textColor"]!=Color.WHITE)
   check((views.properties[id to "description"] as String).contains("不可配置"))
  }
  check(views.properties[R.id.monitor_indicator to "visibility"]==8)
  check(views.properties[R.id.notification_quiet to "text"]=="--°C")
  check(views.properties[R.id.notification_power to "text"]=="-- W")
  show(rate,tier,true,true,41.2,5.6)
  check(views.properties[R.id.notification_quiet to "text"]=="41.2°C")
  check(views.properties[R.id.notification_power to "text"]=="5.6 W")
  check(views.properties[modes[tier.ordinal] to "setEnabled"]==true)
 }
 for(dark in listOf(false,true)) {
  helper.updateRemoteViews(views,NotificationQuickControlHelper.Snapshot("org.test.app",120,UperfMode.BALANCE,false,true,true,40.1,-1.0,dark),PendingIntent("monitor"),{PendingIntent("hz:$it")},{PendingIntent("mode:${it.id}")})
  check(views.properties[R.id.notification_quiet to "textColor"]==if(dark)Color.rgb(232,237,246)else Color.rgb(15,23,42))
  check(views.properties[R.id.notification_power to "textColor"]==if(dark)Color.rgb(108,120,144)else Color.rgb(142,155,174))
  check((views.properties[R.id.notification_power to "description"] as String).contains("暂不可用"))
  check((views.properties[R.id.mode_fast to "click"] as PendingIntent).action=="mode:fast")
 }
 println("EXACT_RENDERER_REBIND_20_COMBINATIONS_60_TRANSITIONS=PASS")
}
'''
        cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
        compilers=list((cache/'org.jetbrains.kotlin/kotlin-compiler-embeddable').glob('*/*/*.jar'));assert len(compilers)==1
        version=compilers[0].parent.parent.name
        jars=list((cache/'org.jetbrains.kotlin').glob('*/'+version+'/*/*.jar'))+list((cache/'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
        annotations=list((cache/'org.jetbrains/annotations/13.0').glob('*/*.jar'));jars+=annotations
        stdlib=next((cache/'org.jetbrains.kotlin/kotlin-stdlib'/version).glob('*/*.jar'))
        java=str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')) if 'JAVA_HOME' in os.environ else shutil.which('java')
        with tempfile.TemporaryDirectory(prefix='zui-v3-renderer-') as directory:
            tmp=Path(directory)
            for name,text in stubs.items():(tmp/name).write_text(text,encoding='utf-8')
            (tmp/'Harness.kt').write_text(harness,encoding='utf-8')
            subprocess.run([java,'-cp',os.pathsep.join(map(str,jars)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',os.pathsep.join(map(str,[stdlib,*annotations])),'-d',str(tmp/'classes'),str(APP/'NotificationQuickControlHelper.kt'),str(APP/'UperfMode.kt'),*map(str,tmp.glob('*.kt'))],check=True,timeout=60,capture_output=True)
            r=subprocess.run([java,'-cp',os.pathsep.join(map(str,[tmp/'classes',stdlib])),'com.zui.zuicontrol.HarnessKt'],check=True,timeout=20,capture_output=True,text=True)
            self.assertIn('60_TRANSITIONS=PASS',r.stdout);print(r.stdout.strip())

if __name__=='__main__':unittest.main(verbosity=2)
