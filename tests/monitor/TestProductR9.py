"""Execute production in-flight rules sharing; Android presentation is a device gate."""
from pathlib import Path
import sys,unittest
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
sys.path.insert(0,str(ROOT/'tests/command_plane'))
from TestUtilityTruth import compile_run

class ProductR9(unittest.TestCase):
    def test_shared_read_completion_failure_and_later_identity(self):
        main=(APP/'MainActivity.kt').read_text('utf8')
        cache=main[main.index('    private data class LoadedRules'):main.index('    private companion object')].replace('private data class','data class')
        companion=main.split('    private companion object {',1)[1].split('    private var rulesRefreshPending',1)[0]
        code='''package com.zui.zuicontrol
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
class Context
object SystemClock { fun elapsedRealtimeNanos()=System.nanoTime() }
object OwnerRenderTrace { fun event(kind:String,value:String){} }
class ZuioptRuleModel { companion object { fun parseNormalized(text:String)=ZuioptRuleModel() } }
object ZuioptLibrary {
 data class Baseline(val generation:String,val rules:String)
 var reads=AtomicInteger()
 fun baseline(context:Context,state:String):Baseline {reads.incrementAndGet();return Baseline(ZuioptRules.field(state,"generation"),"upstream")}
}
object ZuioptRules {
 data class Snapshot(val generation:String,val text:String)
 var calls=AtomicInteger();var userReads=AtomicInteger();var enter=CountDownLatch(1);var release=CountDownLatch(1);var fail=false
 var generation="g"+"a".repeat(24)
 fun field(s:String,k:String)=s.lineSequence().firstOrNull{it.startsWith("$k=")}?.substringAfter('=') ?: ""
 fun state(context:Context):String {
  calls.incrementAndGet();enter.countDown();check(release.await(5,TimeUnit.SECONDS));if(fail)error("READ_FAILED")
  return listOf("generation" to generation,"canonical_sha256" to "c","user_sha256" to "u","user_size" to "123","effective_sha256" to "e","upstream_sha256" to "up","upstream_size" to "123","upstream_metadata_size" to "12").joinToString("\\n"){"${it.first}=${it.second}"}
 }
 fun userRules(context:Context,state:String):Snapshot {userReads.incrementAndGet();return Snapshot(field(state,"generation"),"rules")}
}
CACHE
object Loader {COMPANION
fun main() {
 val pool=Executors.newFixedThreadPool(2);val context=Context()
 try {
  val a=pool.submit(Callable{Loader.sharedRules(context){}})
  check(ZuioptRules.enter.await(5,TimeUnit.SECONDS))
  val entered=CountDownLatch(1)
  val b=pool.submit(Callable{entered.countDown();Loader.sharedRules(context){}})
  check(entered.await(5,TimeUnit.SECONDS))
  // Wait for the second worker to block inside the exact shared future, not a timing-only guess.
  val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
  while(Thread.getAllStackTraces().values.none{trace->trace.any{it.className=="com.zui.zuicontrol.Loader" && it.methodName=="sharedRules"} && trace.any{it.className=="java.util.concurrent.FutureTask" && it.methodName=="awaitDone"}}){check(System.nanoTime()<until);Thread.yield()}
  ZuioptRules.release.countDown()
  check(a.get(5,TimeUnit.SECONDS)===b.get(5,TimeUnit.SECONDS));check(ZuioptRules.calls.get()==1)
  check(ZuioptRules.userReads.get()==1 && ZuioptLibrary.reads.get()==1)
  Loader.sharedRules(context){};check(ZuioptRules.calls.get()==2);check(ZuioptRules.userReads.get()==1)
  ZuioptRules.generation="g"+"b".repeat(24)
  val fresh=Loader.sharedRules(context){};check(fresh.current.generation==ZuioptRules.generation)
  check(ZuioptRules.userReads.get()==2 && ZuioptLibrary.reads.get()==2)
  ZuioptRules.fail=true;check(runCatching{Loader.sharedRules(context){}}.exceptionOrNull()?.message=="READ_FAILED")
  check(Loader.rulesCache===fresh)
  ZuioptRules.fail=false;check(Loader.sharedRules(context){}.current.generation==fresh.current.generation)
  check(ZuioptRules.calls.get()==5 && ZuioptRules.userReads.get()==2)
  println("EXACT_RULE_SHARED_READ_PASS coalesced=2 native=1 later_identity=4 changed_generation=1 failure_retry=1 cached_bytes_preserved=true")
 }finally{pool.shutdownNow()}
}
'''.replace('\nCACHE\n','\n'+cache+'\n').replace('COMPANION',companion)
        compile_run({'Shared.kt':code},'com.zui.zuicontrol.SharedKt',[])
    def test_last_snapshot_is_read_only_until_fresh_qualification(self):
        main=(APP/'MainActivity.kt').read_text('utf8');load=main.split('private fun loadRules()',1)[1].split('private fun loadRecords()',1)[0]
        self.assertNotIn('snapshot=null',load);self.assertNotIn('model=null',load)
        self.assertIn('rulesQualified=false',load);self.assertIn('failed={error->',load)
        page=main.split('private fun render()',1)[1].split('private fun buildMaster()',1)[0]
        identity=page.split('val page=',1)[1].split('if(page!=shownPage)',1)[0]
        self.assertNotIn('ruleLoadStage',identity);self.assertNotIn('$rulesQualified',page)
        self.assertLess(page.index('restoreRulesPending()'),page.index('pageBindings.toList()'))
        self.assertIn('rulesPendingViews.putIfAbsent(v,v.isEnabled to v.alpha)',main)
        self.assertIn('view.isEnabled=saved.first;view.alpha=saved.second',main)
        self.assertIn('if(!rulesQualified)return',main.split('private fun mutateRule',1)[1].split('private fun moveRule',1)[0])
        self.assertIn('!rulesQualified',main.split('private fun saveDraft',1)[1].split('private fun addApp',1)[0])
        self.assertIn('tag="rules-retry"',main)
        self.assertIn('!it.dirty && it.generation!=fresh.current.generation',load)
        self.assertIn('rulesRefreshPending',load)

if __name__=='__main__':unittest.main()
