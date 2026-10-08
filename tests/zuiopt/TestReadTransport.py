"""Execute the bounded Kotlin wire decoder and exact SystemServer observation race guard."""
from pathlib import Path
import sys,unittest,subprocess,tempfile,shutil
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tests/command_plane'))
from TestUtilityTruth import compile_run

class ReadTransport(unittest.TestCase):
    def test_exact_client_frame_and_compatibility(self):
        source=(ROOT/'app/src/main/java/com/zui/zuicontrol/ZuioptRead.kt').read_text('utf8')
        fixture=r'''package com.zui.zuicontrol
import android.zui.ZuiControlManager
import java.util.Base64
object ZuioptRules {
 fun field(s:String,k:String)=s.lineSequence().firstOrNull{it.startsWith("$k=")}?.substringAfter('=') ?: ""
 fun digest(b:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
}
fun main(){
 fun b64(s:String)=Base64.getEncoder().encodeToString(s.toByteArray())
 val user="mine";val up="upstream";val meta="{}";val g="g"+"a".repeat(24)
 fun state(u:String=user,p:String=up,m:String=meta)=listOf("generation=$g","canonical_sha256=${ZuioptRules.digest(u.toByteArray())}",
  "user_sha256=${ZuioptRules.digest(u.toByteArray())}","user_size=${u.toByteArray().size}","effective_sha256=${"b".repeat(64)}",
  "upstream_sha256=${ZuioptRules.digest(p.toByteArray())}","upstream_size=${p.toByteArray().size}",
  "upstream_metadata_size=${m.toByteArray().size}","upstream_metadata_sha256=${ZuioptRules.digest(m.toByteArray())}",
  "rules_read_transport=DAEMON_SNAPSHOT_V1").joinToString("\n",postfix="\n")
 fun frame(s:String=state(),u:String=user,p:String=up,m:String=meta)="ZUIOPT_READ_SNAPSHOT_V1\nstate=${b64(s)}\nuser=${b64(u)}\nupstream=${b64(p)}\nmetadata=${b64(m)}\n"
 fun reject(f:()->Any?){check(runCatching{f()}.isFailure)}
 val s=state();val good=frame();check(ZuioptRead.decodeSnapshot(good,s).user==user)
 val large="x".repeat(65536);val huge=frame(state(large,large," ".repeat(8192)),large,large," ".repeat(8192))
 check(huge.length<200000&&ZuioptRead.decodeSnapshot(huge,state(large,large," ".repeat(8192))).metadata.length==8192)
 for(bad in listOf(good+"\n",good.replace("user=","metadata="),good.replace("ZUIOPT_READ_SNAPSHOT_V1","ZUIOPT_READ_SNAPSHOT_V2"),
  good.replace("user=${b64(user)}","user=@@@@"),good.replace("user=${b64(user)}","user="),frame(u="wrong"),frame(m="{x}"),
  frame(s=s+"generation=$g\n"),good.replace("\n","\r\n"),good+"\u0000","x".repeat(200001)))reject{ZuioptRead.decodeSnapshot(bad,s)}
 reject{ZuioptRead.decodeSnapshot(good,s.replace(g,"g"+"c".repeat(24)))}
 reject{ZuioptRead.decodeSnapshot(good,s.replace("upstream_metadata_sha256=","missing="))}
 val invalid=byteArrayOf(0xc0.toByte(),0x80.toByte())
 val utfState=s.replace("user_size=4","user_size=2").replace(ZuioptRules.digest(user.toByteArray()),ZuioptRules.digest(invalid))
 reject{ZuioptRead.decodeSnapshot(frame(s=utfState).replace("user=${b64(user)}","user=${Base64.getEncoder().encodeToString(invalid)}"),utfState)}
 val manager=ZuiControlManager.instance;manager.caps="ok=1\n";check(ZuioptRead.state()==null&&manager.calls==0)
 manager.caps="ok=1\nrulesReadTransport=DAEMON_SNAPSHOT_V1\n";manager.reply="ok=0\nerror=rules_read_owner_absent"
 check(ZuioptRead.state()==null&&manager.calls==1)
 for(reply in listOf("ok=0\nerror=rules_read:busy","ok=0\nerror=rules_read_owner_absent\nextra=1","ok=1\nreadData=$s","ok=1\nresultCategory=AUTHORITATIVE_READ_ONLY\nreadData=bad")){
  manager.reply=reply;reject{ZuioptRead.state()}
 }
 manager.reply="ok=1\nresultCategory=AUTHORITATIVE_READ_ONLY\nreadData=$s";check(ZuioptRead.state()==s)
 manager.reply="ok=1\nresultCategory=AUTHORITATIVE_READ_ONLY\nreadData=$good";check(ZuioptRead.snapshot(s)?.upstream==up)
 manager.caps="ok=0\n";reject{ZuioptRead.state()};manager.caps="ok=1\nrulesReadTransport=unknown\n";reject{ZuioptRead.state()}
 manager.caps="ok=1\nrulesReadTransport=DAEMON_SNAPSHOT_V1\nrulesReadTransport=DAEMON_SNAPSHOT_V1\n";reject{ZuioptRead.state()}
 println("EXACT_RULE_READ_KOTLIN_FRAME_MAX_UTF8_HASH_GENERATION_DUPLICATE_LEGACY_ABSENCE_FAIL_CLOSED=PASS")
}
'''
        manager='''package android.zui
class ZuiControlManager {
 var caps="ok=1\\n";var reply="";var calls=0
 fun getCapabilities()=caps
 fun utility(action:String,arg:String):String{check(action=="rulesRead");calls++;return reply}
 companion object {val instance=ZuiControlManager();fun get():ZuiControlManager?=instance}
}'''
        compile_run({'Read.kt':source,'Manager.kt':manager,'ReadFixture.kt':fixture},'com.zui.zuicontrol.ReadFixtureKt',[])

    def test_source_contracts_run_before_native_payload_staging(self):
        workflow=(ROOT/'.github/workflows/build.yml').read_text('utf8')
        native=workflow.index('- name: Test and build ZUIopt production integration')
        self.assertLess(workflow.index('- name: Check scripts'),native)
        self.assertLess(workflow.index('- name: Test app'),native)
        self.assertLess(native,workflow.index('- name: Upload payload'))

    def test_exact_server_observation_interleavings(self):
        service=(ROOT/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java').read_text('utf8')
        members=service[service.index('    private volatile String mObservedRuleGeneration'):service.index('    private String requestRefused')]
        fixture=r'''package com.zui.server.control;
import java.util.*;import java.nio.charset.StandardCharsets;
class Binder {static int uid=10253;static int getCallingUid(){return uid;}static long clearCallingIdentity(){return 0;}static void restoreCallingIdentity(long t){}}
class ZuioptSceneAuthority {
 static class OwnerAbsentException extends IllegalStateException {}
 String reply;Runnable during=()->{};boolean absent;
 String readRules(String s){if(absent)throw new OwnerAbsentException();during.run();return reply;}
}
class AppPolicyStore {
 static class State {Map<Integer,Long> users=new HashMap<>();}
 static class Disk {byte[] admission="".getBytes();byte[] read(String f){if(!f.equals("control-admission.json"))throw new AssertionError();return admission;}}
 State current=new State();Disk disk=new Disk();
}
public class ReadFixture {
 AppPolicyStore mAppPolicies=new AppPolicyStore();ZuioptSceneAuthority mZuioptScene=new ZuioptSceneAuthority();
 Map<Integer,Long> policyUsers(){return Map.of(0,1L);}
 static String safe(String s){return s==null?"":s;}
MEMBERS
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] a){
  ReadFixture f=new ReadFixture();String old="generation=g"+"a".repeat(24)+"\nrules_read_transport=DAEMON_SNAPSHOT_V1\n";
  String newer=old.replace("a".repeat(24),"b".repeat(24));f.mZuioptScene.reply=old;
  check(f.rulesRead("state").startsWith("ok=1\n")&&f.mObservedRuleGeneration.equals("g"+"a".repeat(24)));
  f.invalidateRulesObservation();f.mAppPolicies.disk.admission="{\"terminal\":false}".getBytes();
  check(f.rulesRead("state").startsWith("ok=1\n")&&f.mObservedRuleGeneration.equals("UNAVAILABLE"));
  f.mAppPolicies.disk.admission="{\"terminal\":true}".getBytes();
  f.mZuioptScene.during=()->{f.invalidateRulesObservation();f.observeRules(newer);};
  check(f.rulesRead("state").startsWith("ok=1\n")&&f.mObservedRuleGeneration.equals("g"+"b".repeat(24)));
  f.mZuioptScene.during=()->f.invalidateRulesObservation();
  check(f.rulesRead("state").startsWith("ok=1\n")&&f.mObservedRuleGeneration.equals("UNAVAILABLE"));
  f.mZuioptScene.during=()->{};f.mAppPolicies.disk.admission="bad".getBytes();
  check(f.rulesRead("state").startsWith("ok=0\n")&&f.mObservedRuleGeneration.equals("UNAVAILABLE"));
  f.mAppPolicies.disk.admission="{\"terminal\":true}".getBytes();f.mZuioptScene.reply=newer;
  check(f.rulesRead("state").startsWith("ok=1\n")&&f.mObservedRuleGeneration.equals("g"+"b".repeat(24)));
  f.mZuioptScene.absent=true;check(f.rulesRead("state").equals("ok=0\nerror=rules_read_owner_absent"));
  f.mZuioptScene.absent=false;f.mAppPolicies.current.users.put(0,2L);check(f.rulesRead("state").startsWith("ok=0\n"));
  check(rulesWriter("zo_commit")&&rulesWriter("sb_restore")&&!rulesWriter("zo_state")&&!rulesWriter("policy"));
  System.out.println("EXACT_SERVER_RULES_OBSERVATION_ADMITTED_WINDOW_ACK_EPOCH_USER_INVENTORY=PASS");
 }
}'''.replace('MEMBERS',members)
        with tempfile.TemporaryDirectory(prefix='rules-read-observation-') as temporary:
            root=Path(temporary);p=root/'com/zui/server/control';p.mkdir(parents=True)
            (p/'ReadFixture.java').write_text(fixture,encoding='utf8')
            shutil.copyfile(ROOT/'framework_patch/src/services/com/zui/server/control/PolicyJson.java',p/'PolicyJson.java')
            subprocess.run(['javac','-encoding','UTF-8','-d',str(root),*map(str,p.glob('*.java'))],check=True,timeout=60)
            subprocess.run(['java','-cp',str(root),'com.zui.server.control.ReadFixture'],check=True,timeout=20)

if __name__=='__main__':unittest.main(verbosity=2)
