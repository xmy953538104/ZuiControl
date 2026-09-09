"""Permanent terminal scope/absence/reset/init contracts against real sources."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import unittest
from RuntimePurityAudit import audit_system

ROOT=Path(__file__).resolve().parents[2]
def read(name): return (ROOT/name).read_text(encoding='utf8')


class TerminalContracts(unittest.TestCase):
    def test_reverse_verifier_loads_sibling_in_isolated_python(self):
        result=subprocess.run([sys.executable,'-I',str(ROOT/'tests/zuiopt/VerifyZUIoptPayload.py'),'--help'],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('--system-root',result.stdout)

    def test_final_verifier_literals_match_current_payload_and_health(self):
        # Exercise actual verifier assertions, not a second hand-maintained owner list.
        verifier=read('scripts/build/VerifyZuiControlFlashPackage.ps1')
        paths={'SchedulerRc':'etc/init/zui_scheduler.rc',
               'SchedulerPrepare':'etc/zui_control/zui_scheduler_prepare.sh',
               'Daemon':'bin/zui_controld','UperfService':'bin/zui_uperf_service',
               'UperfCrashGate':'etc/zui_control/zui_uperf_crash_gate.sh',
               'DaemonRc':'etc/init/zui_controld.rc','RefreshKillRc':'etc/init/zui_refresh_kill_switch.rc'}
        count=0
        for negative,name,needle in re.findall(r"Assert-(Not)?Contains \$(\w+) '([^']*)'",verifier):
            if name not in paths:continue
            text=read('payload/system/'+paths[name]);count+=1
            self.assertEqual(needle in text,not bool(negative),(name,needle))
        self.assertGreater(count,50)
        health=verifier.split('foreach ($stateMarker in @(',1)[1].split(')) { Assert-Contains',1)[0]
        java=read('framework_patch/src/services/com/zui/server/control/ZuiControlService.java')
        for needle in re.findall(r"'([^']+)'",health):self.assertIn(needle,java)

    def test_proven_algorithms_are_byte_identical(self):
        expected={
            # Scoped purity changes only: generic group safety and neutral provenance.
            'ZUIopt_core.h':'7d5ecbdd2ccc882740fd53059d1b93862bf1c78ff7317d08f843af0e9dd7f647',
            'ZUIopt_rules.h':'f50e258ccab3012d2173a05a99d5f22193311fe623a7156192946f06316b62a5',
            'ZUIopt_owner.h':'148488a52090740f225c7acf95adca079451e22e32daf2878e2ff08f5b53cf3b',
            'ZUIopt_model.h':'34463bc9f179586206d966dc15c9f5b2c99112400f214d3bf353c469c274aebb',
        }
        for name,sha in expected.items():
            self.assertEqual(hashlib.sha256(read('native/zuiopt/'+name).encode()).hexdigest(),sha,name)
        # The new scene queue/lifecycle shares these files; freeze the unchanged
        # authority and IProcessObserver implementations against the V53 bytes.
        events=read('native/zuiopt/ZUIopt_events.h').split('inline bool sameProcess(',1)[1]
        events=events.split('\n// Shared with event-loss fixtures;',1)[0]+'}\n'
        self.assertEqual(hashlib.sha256(events.encode()).hexdigest(),'462eea41086a097f85d6fb9a6bf0b66094f8b0f90f8659cf612631cf239f3a1a')
        observer=read('native/zuiopt/ZUIopt_binder.h').split('struct Observer {',1)[1]
        self.assertEqual(hashlib.sha256(observer.encode()).hexdigest(),'cc8f4702213ded4f9c47db9fa1cd88edbadf8c3a90944d310490e94a4742ed76')

    def test_refresh_and_macro_power_service_unchanged(self):
        text=read('framework_patch/src/services/com/zui/server/control/ZuiControlService.java')
        text=re.sub(r'    private String schedulerHealthStateLines\(\).*?(?=    private static boolean isUperfMode)','',text,flags=re.S)
        text=re.sub(r'^.*private static final String PROP_ZUIOPT_(SERVICE|FAILED).*\n','',text,flags=re.M)
        text=text.replace('import android.os.IBinder;\n','')
        text=text.replace('    private final ZuioptSceneAuthority mZuioptScene = new ZuioptSceneAuthority();\n','')
        text=re.sub(r'^\s*mZuioptScene.changed\(\);\n','',text,flags=re.M)
        text=re.sub(r'            if \(code >= ZuioptSceneAuthority.REGISTER.*?(?=            if \(code >= 1)', '',text,flags=re.S)
        self.assertEqual(hashlib.sha256(text.encode()).hexdigest(),'40bf756fe681be07809b41924d8003d3f2074997474acb19e1588811c2dc4764')
        self.assertIs(json.loads(read('payload/system/etc/zui_control/uperf-sm8650.json'))['modules']['sched']['enable'],False)

    def test_runtime_absence(self):
        self.assertEqual(audit_system(ROOT/'payload/system')['status'],'PASS')

    def test_reset_is_authenticated_and_next_boot_only(self):
        app=read('app/src/main/java/com/zui/zuicontrol/MainActivity.kt')
        self.assertIn('ZuioptRules.field(zuioptState, "failure") == "1"',app)
        self.assertIn('ZuioptRules.command(this@MainActivity, "reset")',app)
        self.assertIn('下次重启重新启用 ZUIopt',app)
        rules=read('app/src/main/java/com/zui/zuicontrol/ZuioptRules.kt')
        self.assertIn('CMD_RESET_ZUIOPT_FAILSAFE',rules)
        self.assertIn('ZuiControlRequest.awaitTerminalAck',rules)
        native=read('native/zuiopt/ZUIopt.cpp')
        reset=native.split('else if(command=="reset")',1)[1].split('\n',1)[0]
        self.assertIn('store.resetFailure()',reset)
        self.assertNotRegex(reset,r'reload|selected|setprop|signal')
        store=read('native/zuiopt/ZUIopt_store.h')
        body=store.split('void resetFailure()',1)[1].split('void failure()',1)[0]
        self.assertEqual(re.findall(r'root.remove\("([^"]+)"\)',body),['crashes.v1','failure.v1'])
        self.assertNotIn('root.remove("failure.v1")',store.split('void resetFailure()',1)[0])

    def test_boot_and_failure_have_one_owner(self):
        rc=read('payload/system/etc/init/zui_scheduler.rc')
        postfs=rc.split('on post-fs-data',1)[1].split('\n\non ',1)[0]
        self.assertEqual(postfs.count('trigger zuiopt-boot-state'),1)
        boot=read('payload/system/etc/zuiopt/zuiopt_boot_state.sh')
        self.assertEqual(boot.count('/system/bin/ZUIopt --boot'),1)
        self.assertNotIn('--migration',boot)
        failure=rc.split('on property:sys.zui_control.zuiopt_failed=1',1)[1].split('\nservice ',1)[0]
        self.assertIn('stop zui_zuiopt\n    start zui_zuiopt_recover',failure)
        self.assertEqual(re.findall(r'^    start (\S+)',failure,re.M),['zui_zuiopt_recover'])


if __name__=='__main__': unittest.main(verbosity=2)
