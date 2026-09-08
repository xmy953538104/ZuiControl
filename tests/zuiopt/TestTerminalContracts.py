"""Permanent terminal scope/absence/reset/init contracts against real sources."""
from pathlib import Path
import hashlib
import json
import re
import unittest
from RuntimeAsoulAudit import audit_system

ROOT=Path(__file__).resolve().parents[2]
def read(name): return (ROOT/name).read_text(encoding='utf8')


class TerminalContracts(unittest.TestCase):
    def test_proven_algorithms_are_byte_identical(self):
        expected={
            'ZUIopt_core.h':'12766b596a1dd5fd7fbc8e6b83708b815e334a2a6d70bf8a6b89a201ac7f34df',
            'ZUIopt_owner.h':'148488a52090740f225c7acf95adca079451e22e32daf2878e2ff08f5b53cf3b',
            'ZUIopt_events.h':'61387f2fa149b8b5678b0c81bca21ea51c4cca0dfd7799eb48691acd482fb836',
            'ZUIopt_binder.h':'e9a821a1c58b27c6cc84c7dabe94225508bd573e5b2cb9c63bf7c61971782496',
            'ZUIopt_model.h':'34463bc9f179586206d966dc15c9f5b2c99112400f214d3bf353c469c274aebb',
            'ZUIopt_rules.h':'b3ce2f0737bfb948fa81cb80ec8bf7552a45ce7a751908b727e8b277bc790973',
        }
        for name,sha in expected.items():
            self.assertEqual(hashlib.sha256(read('native/zuiopt/'+name).encode()).hexdigest(),sha,name)

    def test_refresh_and_macro_power_service_unchanged(self):
        text=read('framework_patch/src/services/com/zui/server/control/ZuiControlService.java')
        text=re.sub(r'    private String schedulerHealthStateLines\(\).*?(?=    private static boolean isUperfMode)','',text,flags=re.S)
        text=re.sub(r'^.*private static final String PROP_ZUIOPT_(SERVICE|FAILED).*\n','',text,flags=re.M)
        self.assertEqual(hashlib.sha256(text.encode()).hexdigest(),'40bf756fe681be07809b41924d8003d3f2074997474acb19e1588811c2dc4764')
        self.assertIs(json.loads(read('payload/system/etc/zui_control/uperf-sm8650.json'))['modules']['sched']['enable'],False)

    def test_runtime_absence(self):
        self.assertEqual(audit_system(ROOT/'payload/system')['status'],'PASS')
        for name in ('payload/system/bin/zui_controld','payload/system/etc/zui_control/zui_scheduler_prepare.sh',
                'app/src/main/java/com/zui/zuicontrol/MainActivity.kt','app/src/main/java/com/zui/zuicontrol/ZuiControlContract.kt',
                'app/src/main/java/com/zui/zuicontrol/ZuioptRules.kt','framework_patch/src/services/com/zui/server/control/ZuiControlService.java'):
            self.assertNotRegex(read(name),r'(?i)asoul|a-soul|asopt|next_owner|current_owner|task_owner|zo_next|zo_owner')
        self.assertNotIn('/system/bin/AsoulOpt',read('payload/patches/plat_file_contexts_add.txt'))
        self.assertNotIn('task_owner',read('payload/patches/plat_property_contexts_add.txt'))
        self.assertNotIn('nextOwner',read('native/zuiopt/ZUIopt_store.h'))
        self.assertNotIn('AsoulOpt',read('native/zuiopt/ZUIopt_daemon.h'))

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
        body=store.split('void resetFailure()',1)[1].split('bool migrationDone()',1)[0]
        self.assertEqual(re.findall(r'root.remove\("([^"]+)"\)',body),['crashes.v1','failure.v1'])
        self.assertNotIn('root.remove("failure.v1")',store.split('void resetFailure()',1)[0])

    def test_migration_is_event_gated_and_verified_before_start(self):
        rc=read('payload/system/etc/init/zui_scheduler.rc')
        postfs=rc.split('on post-fs-data',1)[1].split('\non ',1)[0]
        self.assertLess(postfs.index('zuiopt_legacy_migration.sh prepare'),postfs.index('trigger zuiopt-migration-unlink'))
        self.assertLess(postfs.index('trigger zuiopt-migration-unlink'),postfs.index('trigger zuiopt-boot-state'))
        self.assertNotIn('on property:sys.zui_control.legacy_migration=',rc)
        self.assertIn('on zuiopt-migration-unlink && property:sys.zui_control.legacy_migration=UNLINK_VERIFIED\n    rm /data/vendor/asopt.conf',rc)
        boot=read('payload/system/etc/zuiopt/zuiopt_boot_state.sh')
        self.assertLess(boot.index('zuiopt_legacy_migration.sh finish'),boot.index('setprop sys.zui_control.zuiopt_failed 0'))
        failure=rc.split('on property:sys.zui_control.zuiopt_failed=1',1)[1].split('\nservice ',1)[0]
        self.assertIn('stop zui_zuiopt\n    start zui_zuiopt_recover',failure)
        self.assertEqual(re.findall(r'^    start (\S+)',failure,re.M),['zui_zuiopt_recover'])


if __name__=='__main__': unittest.main(verbosity=2)
