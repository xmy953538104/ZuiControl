"""Source fences for the reviewed V21 integration; no device or image writes."""
from pathlib import Path
import hashlib
import re
import unittest

ROOT=Path(__file__).resolve().parents[2]
def read(path): return (ROOT/path).read_text(encoding='utf-8')

class ProductionContracts(unittest.TestCase):
    def test_each_owner_start_is_xor_guarded(self):
        rc=read('payload/system/etc/init/zui_scheduler.rc')
        action='';starts=[]
        for line in rc.splitlines():
            if line.startswith('on '): action=line
            if line.strip() in ('start zui_asoulopt','start zui_zuiopt'):
                owner='ASOULOPT' if 'asoulopt' in line else 'ZUIOPT'
                self.assertIn('property:ro.zui_control.task_owner='+owner,action)
                if owner=='ZUIOPT': self.assertIn('property:sys.zui_control.zuiopt_failed=0',action)
                starts.append((action,line))
        self.assertEqual(len(starts),3)
        self.assertIn('    trigger zui-task-owner-start',rc)
        self.assertIn('    restart_period 5',rc)
        self.assertNotRegex(rc,r'^\s+critical(?:\s|$)')
        crash=read('payload/system/etc/zuiopt/crash_gate.sh')
        self.assertNotIn('start zui_asoulopt',crash)
        self.assertNotIn('while ',crash)
        boot=read('payload/system/etc/zuiopt/boot_owner.sh')
        self.assertIn('|| owner=ASOULOPT',boot)
        self.assertIn('[ -z "$(getprop ro.zui_control.task_owner)" ] || exit 0',boot)
        self.assertNotIn('boot_owner.sh',rc[rc.index('on property:zui_control.scheduler=restart'):])

    def test_bound_transport_and_native_paths(self):
        daemon=read('payload/system/bin/zui_controld')
        for command in ('state','owner','next','read','begin','chunk','commit','abort','enable','disable','rollback'):
            self.assertIn('zo_'+command,daemon)
        self.assertIn('[ "${#3}" -le 10924 ]',daemon)
        self.assertIn('bounded_transport=1',daemon)
        binary=read('native/zuiopt/ZUIopt.cpp')
        for forbidden in ('--selftest','--check-config','/data/local/tmp','sched_setscheduler','SCHED_FIFO','SCHED_RR'):
            self.assertNotIn(forbidden,binary)
        self.assertIn('pidfd_send_signal',binary)
        self.assertIn('root identity required',binary)
        store=read('native/zuiopt/ZUIopt_store.h')
        for token in ('O_NOFOLLOW','st_nlink==1','flock(lockFd,LOCK_EX)','600000','PACK_COUNT','sha256(data)==f[3]','root.put("effective.conf",effective)'):
            self.assertIn(token,store)

    def test_factory_and_retained_binary_identity(self):
        factory=ROOT/'payload/system/etc/zuiopt/factory_rules.conf'
        self.assertEqual(hashlib.sha256(factory.read_bytes()).hexdigest(),'1ac23f379482649608b44860eac0be165ba6b71022eee140735edbc362970f65')
        self.assertEqual(len(re.findall(r'^profile ',factory.read_text(),re.M)),27)
        self.assertEqual(len(re.findall(r'^package ',factory.read_text(),re.M)),316)
        self.assertEqual(hashlib.sha256((ROOT/'payload/system/bin/AsoulOpt').read_bytes()).hexdigest(),'7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86')

    def test_new_policy_has_no_forbidden_owner_scope(self):
        policy=read('payload/patches/plat_sepolicy_zui_control.cil')
        zuiopt=policy[policy.index(';; ZUIopt direct owner.'):]
        self.assertNotIn('(typepermissive ',zuiopt)
        self.assertNotIn('unconfined', '\n'.join(x for x in zuiopt.splitlines() if not x.startswith(';;')))
        for target in ('proc_type','fs_type','sysfs_type','property_type','domain','surfaceflinger_exec','vendor_sysfs_kgsl','zui_scheduler_proc'):
            self.assertNotIn('(allow zuiopt '+target+' ',zuiopt)
        self.assertNotIn('(allow priv_app zuiopt_data_file',zuiopt)
        self.assertIn('(type zuiopt)',zuiopt)

if __name__=='__main__': unittest.main(verbosity=2)
