"""Verify reverse-extracted artifacts against explicit CI/source/CIL intended identities."""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,re,sys

# The pinned Windows embeddable interpreter omits the script directory from sys.path.
# Resolve only our explicit source sibling, without changing the immutable interpreter.
sys.path.insert(0,str(Path(__file__).resolve().parent))
from RuntimePurityAudit import audit_system

REQUIRED={
 '/system/bin/ZUIopt','/system/bin/zui_controld',
 '/system/etc/zuiopt/factory_rules.conf','/system/etc/zuiopt/zuiopt_boot_state.sh',
 '/system/etc/zuiopt/crash_gate.sh','/system/etc/init/zui_scheduler.rc',
 '/system/etc/selinux/plat_sepolicy.cil','/system/etc/selinux/plat_file_contexts',
 '/system/etc/selinux/plat_property_contexts','/system/framework/framework.jar',
 '/system/framework/services.jar','/system/priv-app/ZuiControlV59/ZuiControl.apk',
}
GOLDEN={'framework.jar':'b5f57d62546569b9bd9ba34d8757678da000c33f876358dd93a4dc747b8f1b32',
        'services.jar':'245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000'}
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def verify(system,manifest_path,contexts,fs_config):
    manifest=json.loads(manifest_path.read_text(encoding='utf8'))
    assert re.fullmatch('[0-9a-f]{40}',manifest['source_commit'])
    assert re.fullmatch('[0-9]+',str(manifest['ci_run']))
    expected=manifest['files'];assert REQUIRED<=set(expected)
    for name,entry in expected.items():
        path=PurePosixPath(name)
        assert path.is_absolute() and path.parts[1]=='system' and '..' not in path.parts
        target=system.joinpath(*path.parts[2:])
        assert target.is_file() and not target.is_symlink(),name
        assert sha(target)==entry['sha256'] and target.stat().st_size==entry['size'],name
    assert sha(system/'framework/framework.jar')==GOLDEN['framework.jar']
    assert sha(system/'framework/services.jar')!=GOLDEN['services.jar']
    assert manifest['services_changed'] is True
    absence=audit_system(system)
    assert absence['status']=='PASS' and not absence['semantic_hits'],absence
    binary=(system/'bin/ZUIopt').read_bytes()
    for token in (b'--selftest',b'--check-config',b'/data/local/tmp',b'/data/adb',b'sched_setscheduler',b'/proc/sys/walt',b'/sys/class/kgsl'):
        assert token not in binary,token
    factory=(system/'etc/zuiopt/factory_rules.conf').read_text()
    assert len(re.findall(r'^profile ',factory,re.M))==27
    assert len(re.findall(r'^package ',factory,re.M))==316
    rc=(system/'etc/init/zui_scheduler.rc').read_text()
    scaffold='    mkdir /dev/cpuset/ZUIopt 0755 root root'
    assert rc.count(scaffold)==1 and rc.index(scaffold)<rc.index('zuiopt_boot_state.sh')
    policy=(system/'etc/selinux/plat_sepolicy.cil').read_text()
    assert not re.search(r'\(allow zuiopt self \(capability \([^)]*\bdac_(override|read_search)\b',policy)
    assert '(allow zuiopt self (capability (sys_nice)))' in policy
    action='';starts=0
    for line in rc.splitlines():
        if line.startswith('on '): action=line
        if line.strip() == 'start zui_zuiopt':
            assert 'property:sys.zui_control.scheduler_active=1' in action
            assert 'property:sys.zui_control.zuiopt_failed=0' in action
            starts+=1
    assert starts==1
    boot=(system/'etc/zuiopt/zuiopt_boot_state.sh').read_text()
    assert '|| state=FAILSAFE' in boot and 'READY_TO_START) setprop sys.zui_control.zuiopt_failed 0' in boot
    for helper in ('zuiopt_boot_state.sh','crash_gate.sh'):
        text=(system/'etc/zuiopt'/helper).read_text()
        assert '/data/local/tmp' not in text and 'while ' not in text
    inode_contexts={line.split()[0]:line.split()[-1] for line in contexts.read_text().splitlines() if line.strip()}
    inode_modes={line.split()[0]:line.split()[1:4] for line in fs_config.read_text().splitlines() if line.strip()}
    for relative,context,mode in (
        ('bin/ZUIopt','zuiopt_exec','0755'),
        ('etc/zuiopt/factory_rules.conf','zuiopt_config_file','0644'),
        ('etc/zuiopt/zuiopt_boot_state.sh','zuiopt_config_file','0644'),
        ('etc/zuiopt/crash_gate.sh','zuiopt_config_file','0644'),
    ):
        name='system_a/system/'+relative
        assert inode_contexts['/'+name.replace('.',r'\.')]=='u:object_r:'+context+':s0',name
        assert inode_modes[name]==['0','0',mode],name
    return dict(status='PASS',expected_manifest_sha256=sha(manifest_path),verified_files=len(expected),default_owner='ZUIOPT',failsafe_target='ANDROID_DEFAULT',runtime_absence=absence,dual_owner_boot_path='ABSENT',framework_changed=False,services_jar_changed=True,runtime_avc='POST_FLASH_NOT_TESTED')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--system-root',type=Path,required=True);parser.add_argument('--expected',type=Path,required=True);parser.add_argument('--contexts',type=Path,required=True);parser.add_argument('--fs-config',type=Path,required=True);parser.add_argument('--receipt',type=Path,required=True);args=parser.parse_args()
    result=verify(args.system_root,args.expected,args.contexts,args.fs_config)
    args.receipt.write_text(json.dumps(result,indent=2)+'\n',encoding='utf8');print(json.dumps(result))
