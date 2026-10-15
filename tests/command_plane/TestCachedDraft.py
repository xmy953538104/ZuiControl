"""Current local-edit functions retain the draft identity and original CAS base."""
from pathlib import Path
from TestUtilityTruth import compile_run

ROOT=Path(__file__).resolve().parents[2]
source=(ROOT/'app/src/main/java/com/zui/zuicontrol/MainActivity.kt').read_text('utf8')
open_rule=source.split('    private fun openRule(',1)[1].split('    private fun ordinaryTemplateDialog',1)[0]
mutate=source.split('    private fun mutateRule(',1)[1].split('    private fun moveRule',1)[0]
assert 'if(session.section=="thread" && !rulesQualified)' in source.split('private fun saveDraft',1)[1].split('private fun addApp',1)[0]
assert 'expectedGeneration = rule.generation' in source
assert 'if(v.tag=="owner-local-draft")return' in source
assert 'session.ruleDraft!==d' in source.split('private fun editRule',1)[1].split('private fun loadAnalysis',1)[0]
harness='''package com.zui.zuicontrol
class ZuioptRuleModel(val shared:Boolean=false){data class Profile(val mask:Set<Int>,val rows:List<Int>);data class Mapping(val matchKind:String,val packageName:String,val profile:String)
 val mappings=if(shared)listOf(Mapping("prefix","app","shared")) else listOf(Mapping("exact","app","own"));fun appProfile(pkg:String)=Profile(setOf(0),emptyList())}
class RuleDraft(val packageName:String,val base:ZuioptRuleModel,val generation:Long,val original:ZuioptRuleModel.Profile?,var profile:ZuioptRuleModel.Profile){var cloneConfirmed=false}
class Snapshot(val generation:Long)
class Session{var busy=false;var workFeature="";var ruleDraft:RuleDraft?=null}
class Fixture{val session=Session();var model:ZuioptRuleModel?=null;var snapshot:Snapshot?=null;var analysisPage=false;var pending:(()->Unit)?=null
 private val localRulesAvailable get()=model!=null&&snapshot!=null
 fun confirm(title:String,body:String,action:()->Unit){pending=action}
 fun open(pkg:String)=openRule(pkg)
 fun edit(d:RuleDraft,next:()->Unit)=mutateRule(d,next)
 private fun openRule('''+open_rule+''' private fun mutateRule('''+mutate+'''}
fun main(args:Array<String>){val f=Fixture();f.open("app");check(f.session.ruleDraft==null)
 f.model=ZuioptRuleModel();f.snapshot=Snapshot(7);f.open("app");val old=checkNotNull(f.session.ruleDraft);var changes=0
 f.edit(old){changes++};check(changes==1&&old.generation==7L)
 f.open("other");f.edit(old){error("late control edited another draft")};check(changes==1)
 val current=checkNotNull(f.session.ruleDraft);f.session.busy=true;f.session.workFeature="rules";f.edit(current){error("in-flight transaction mutated")}
 f.session.busy=false;f.model=ZuioptRuleModel(true);f.open("app");val shared=checkNotNull(f.session.ruleDraft)
 f.edit(shared){changes++};check(changes==1&&f.pending!=null);f.open("other");f.pending!!.invoke();check(changes==1&&!shared.cloneConfirmed)
 f.open("app");val accepted=checkNotNull(f.session.ruleDraft);f.edit(accepted){changes++};f.pending!!.invoke();check(changes==2&&accepted.cloneConfirmed&&accepted.generation==7L)
 println("CACHED_LOCAL_DRAFT_IDENTITY_AND_CAS_BASE=PASS")}
'''
if __name__=='__main__':compile_run({'Draft.kt':harness},'com.zui.zuicontrol.DraftKt',[])
