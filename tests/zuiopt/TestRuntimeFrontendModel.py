"""Execute exact production Kotlin model: lossless Schema3 and local App isolation."""
from pathlib import Path
import sys,unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tests/command_plane'))
from TestUtilityTruth import compile_run
class FrontendRuntimeModel(unittest.TestCase):
    def test_actual_parser_serializer_and_provenance(self):
        source=(ROOT/'app/src/main/java/com/zui/zuicontrol/ZuioptRuleModel.kt').read_text(encoding='utf8')
        fixture=r'''package com.zui.zuicontrol
fun main(){
 val text="schema 3\nenabled true\ndebug false\nruntime_defaults 1 1\nprofile Shared 2,3,4,5,6\nruntime Shared -1 -1 preset double\nthread Shared C2 contains \"Thread-\" selector=rank:1 100000 7\npackage exact org.first.app Shared 100000\npackage exact org.second.app Shared 99999\n"
 val original=ZuioptRuleModel.parseNormalized(text);check(original.normalized()==text)
 val changed=original.editApp("org.first.app"){it.copy(runtime=it.runtime.copy(mode=2,rt=0))}
 check(changed.appProfile("org.second.app")==original.appProfile("org.second.app"));check(changed.appProfile("org.first.app")!!.runtime.rt==0)
 check(changed.mode==1&&changed.rt==1);check(original.normalized()==text)
 check(changed.provenance("org.first.app",original)=="USER_MODIFIED");check(changed.provenance("org.second.app",original)=="UPSTREAM")
 val concrete=original.copy(profiles=original.profiles.mapValues{it.value.copy(runtime=it.value.runtime.copy(mode=1,rt=1))})
 check(concrete.provenance("org.first.app",original)=="USER_MODIFIED")
 check(original.copy(mode=2,rt=0).provenance("org.first.app",original)=="UPSTREAM")
 val manualProfile=original.appProfile("org.first.app")!!
 val manual=ZuioptRuleModel(true,mapOf("P" to manualProfile),listOf(ZuioptRuleModel.Mapping("exact","org.first.app","P",100000)))
 check(ZuioptRuleModel.parseNormalized(manual.normalized()).appProfile("org.first.app")!!.runtime.mode==-1)
 check(ZuioptRuleModel.parseNormalized(manual.normalized()).appProfile("org.first.app")!!.runtime.rt==-1)
 check(ZuioptRuleModel.parseNormalized(changed.normalized()).appProfile("org.first.app")!!.runtime.mode==2)
 for(bad in listOf(text.replace("schema 3","schema 2"),text.replace("runtime_defaults 1 1","runtime_defaults 3 1"),text.replace("preset double","explicit double"),text.replace("preset double","preset unknown"),text.replace("runtime Shared -1 -1 preset double","runtime Shared -1 -1 preset double\nruntime Shared 0 0 preset none"))){check(runCatching{ZuioptRuleModel.parseNormalized(bad)}.isFailure)}
 val legacy="schema 2\nenabled true\ndebug false\nprofile Shared 2,3,4,5,6\nthread Shared C2 contains \"Thread-\" selector=rank:1 100000 7\npackage exact org.first.app Shared 100000\n"
 check(ZuioptRuleModel.parseNormalized(legacy).normalized()==legacy)
 val upgrade=ZuioptRuleModel.parseNormalized(legacy).editApp("org.first.app"){it.copy(runtime=ZuioptRuleModel.Runtime(1,1,"preset","dummy"))}
 check(upgrade.normalized().startsWith("schema 3\n"));check(ZuioptRuleModel.parseNormalized(upgrade.normalized()).appProfile("org.first.app")!!.runtime.marker=="dummy")
 println("SCHEMA3_FRONTEND_RUNTIME_LOCAL_DRAFT_INHERITANCE_PROVENANCE_SHARED_APP_AND_LEGACY=PASS")
}
'''
        compile_run({'Model.kt':source,'Fixture.kt':fixture},'com.zui.zuicontrol.FixtureKt',[])
if __name__=='__main__':unittest.main()
