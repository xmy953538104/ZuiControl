"""Execute the exact in-memory cache predicate; rendering/latency remain device gates."""
from pathlib import Path
import sys,unittest
ROOT=Path(__file__).resolve().parents[2];APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
sys.path.insert(0,str(ROOT/'tests/command_plane'))
from TestUtilityTruth import compile_run

class ProductR8(unittest.TestCase):
    def test_exact_cache_and_mismatch(self):
        main=(APP/'MainActivity.kt').read_text('utf8')
        cache=main[main.index('    private data class LoadedRules'):main.index('    private companion object')].replace('private data class','data class')
        source='''package com.zui.zuicontrol
object ZuioptRules{data class Snapshot(val generation:String,val text:String);fun field(s:String,k:String)=s.lineSequence().firstOrNull{it.startsWith("$k=")}?.substringAfter('=') ?: ""}
object ZuioptLibrary{data class Baseline(val generation:String)}
class ZuioptRuleModel
CACHE
fun main(){
 val g="g"+"a".repeat(24);val keys=listOf("generation","canonical_sha256","user_sha256","user_size","effective_sha256","upstream_sha256","upstream_size","upstream_metadata_size")
 val fields=keys.associateWith{if(it=="generation")g else if(it.endsWith("size"))"123" else "b".repeat(64)}
 fun state(f:Map<String,String>)=f.entries.joinToString("\\n"){"${it.key}=${it.value}"}
 val native=state(fields);val c=LoadedRules(native,ZuioptRules.Snapshot(g,"rules"),ZuioptRuleModel(),ZuioptLibrary.Baseline(g),ZuioptRuleModel())
 check(c.matches(native));check(c.matches(native+"\\nfailure=1")) // Health is always bound from fresh state, not cached.
 for(k in keys){check(!c.matches(state(fields+(k to "changed"))));check(!c.matches(state(fields-k)))}
 check(!c.copy(current=ZuioptRules.Snapshot("other","rules")).matches(native));check(!c.copy(baseline=ZuioptLibrary.Baseline("other")).matches(native))
 println("EXACT_NATIVE_RULE_CACHE_PASS mismatches=18")
}'''.replace('\nCACHE\n','\n'+cache+'\n')
        compile_run({'Cache.kt':source},'com.zui.zuicontrol.CacheKt',[])
    def test_progress_and_stationary_page_contract(self):
        main=(APP/'MainActivity.kt').read_text('utf8');rules=(APP/'ZuioptRules.kt').read_text('utf8');up=(APP/'ZuioptLibrary.kt').read_text('utf8')
        loader=main.split('private fun loadRules()',1)[1].split('private fun loadRecords()',1)[0]
        fetch=main.split('fun sharedRules(',1)[1].split('private var rulesRefreshPending',1)[0]
        self.assertEqual(fetch.count('ZuioptRules.state(context)'),1)
        self.assertIn('ZuioptRules.userRules(context,rs)',fetch);self.assertIn('ZuioptLibrary.baseline(context,rs)',fetch)
        self.assertLess(fetch.index('progress("生效规则已读取'),fetch.index('val up=bulk?.let'))
        self.assertIn('rulesQualified=false',loader);self.assertIn('rulesQualified=true',loader)
        self.assertIn('if(session.section=="thread" && !rulesQualified)ownerPending()',main)
        self.assertIn('if(!rulesQualified)return',main.split('private fun openRule',1)[1].split('private fun threadApp',1)[0])
        self.assertIn('if(model==null)emptyList() else installed.mapNotNull',main)
        page=main.split('private fun render()',1)[1].split('private fun buildMaster()',1)[0]
        for forbidden in ('next.alpha=0','next.translationY','next.animate()', 'previous.animate()'):self.assertNotIn(forbidden,page)
        self.assertIn('pageHost.removeViewAt(i)',page);self.assertIn('OwnerRenderTrace.event("PAGE_CHANGED",page)',page)
        self.assertIn('check(digest(data) == field(state, "user_sha256"))',rules)
        self.assertIn('part[0]==generation&&part[1]==out.size().toString()',up)
        self.assertIn('check(ZuioptRules.digest(bytes)==hash)',up)
        self.assertIn('RULES_USER_PARSED',fetch);self.assertIn('RULES_UPSTREAM_PARSED',fetch)

if __name__=='__main__':unittest.main()
