"""Exact production prepare script with temporary paths and process-boundary fixtures."""
from pathlib import Path
import os,shutil,subprocess,tempfile,sys
ROOT=Path(__file__).resolve().parents[3]
source=(ROOT/'payload/system/etc/zui_control/zui_scheduler_prepare.sh').read_text('utf8')
bash=os.environ.get('BASH') or shutil.which('bash')
assert bash
cases=sys.argv[1:] or ['post_valid','virgin_valid','virgin_corrupt','canonical_corrupt','interrupted','screen_off']
for case in cases:
    with tempfile.TemporaryDirectory(prefix='post-cutover-') as tmp:
        root=Path(tmp);data=root/'data';u=data/'uperf';u.mkdir(parents=True);bin=root/'bin';bin.mkdir()
        factory=root/'factory';factory.write_text('example.app fast\n')
        if case not in ['virgin_valid','interrupted']:(u/'cur_powermode.txt').write_text('INVALID\n')
        if case not in ['virgin_valid','interrupted']:(u/'perapp_powermode.txt').write_text('example.app fast\n')
        if case in ['post_valid','canonical_corrupt','screen_off']:
            (u/'policy-projection').mkdir();(u/'policy-projection/active.json').write_text('{"canonical":"fixture"}')
        if case=='interrupted':(u/'cur_powermode.txt').write_text('balance\n')
        fixtures={'setprop':'echo "$*" >> "$FIXTURE/events"','sync':'exit 0','restorecon_recursive':'exit 0','settings':'echo null',
        'app_process':'''echo "$3" >> "$FIXTURE/calls"
if [ "$3" = bootstrap ]; then
 [ "$CASE" != canonical_corrupt ] || exit 12
 if [ -f "$FIXTURE/data/uperf/policy-projection/active.json" ]; then printf '%s\n' "$CANONICAL_EFFECTIVE" > "$FIXTURE/data/uperf/effective_powermode.txt"; fi
fi
if [ "$3" = uperf-startup ]; then echo qualified > "$FIXTURE/data/uperf/.validated_runtime.sha256"; fi
'''}
        for n,b in fixtures.items():p=bin/n;p.write_text('#!/bin/sh\n'+b+'\n');p.chmod(0o755)
        hostroot=subprocess.check_output([bash,'-c','cd "$1" && pwd','fixture',str(root)],text=True).strip()
        body=source.replace('/data/vendor/zui_control',hostroot+'/data').replace('/system/bin/app_process','app_process')
        body=body.replace('/system/etc/zui_control/default_uperf_perapp.txt',hostroot+'/factory')
        script=root/'prepare.sh';script.write_text('export PATH="'+hostroot+'/bin:$PATH"\n'+body)
        env=dict(os.environ,FIXTURE=hostroot,CASE=case,CANONICAL_EFFECTIVE='powersave' if case=='screen_off' else 'fast')
        result=subprocess.run([bash,str(script),'powersave' if case=='screen_off' else 'fast'],env=env,capture_output=True)
        expected=case not in ['virgin_corrupt','canonical_corrupt']
        assert (result.returncode==0)==expected,(case,result.returncode,(data/'log/bootstrap.log').read_text())
        if case in ['post_valid','screen_off']:
            assert (u/'cur_powermode.txt').read_text()=='INVALID\n','must not rewrite legacy evidence'
            assert (u/'effective_powermode.txt').read_text().strip()==env['CANONICAL_EFFECTIVE']
        print('POST_CUTOVER_'+case+'=PASS')
