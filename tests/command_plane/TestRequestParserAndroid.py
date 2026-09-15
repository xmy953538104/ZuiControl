#!/usr/bin/env python3
"""Real Android parser-only gate. Never source or invoke the command plane."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import uuid
import TestRequestParser as parser


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--adb',required=True)
    ap.add_argument('--serial',required=True)
    ap.add_argument('--source',type=Path,default=parser.ROOT/'payload/system/bin/zui_controld')
    ap.add_argument('--receipt-dir',type=Path,required=True)
    ap.add_argument('--freeze-old',action='store_true')
    ap.add_argument('--contract',type=Path)
    args=ap.parse_args()
    args.receipt_dir.mkdir(parents=True,exist_ok=True)
    def save(name,data):
        with (args.receipt_dir/name).open('x',encoding='utf8') as f:
            json.dump(data,f,ensure_ascii=False,indent=2)
    def adb(name,argv):
        p=subprocess.run([args.adb,'-s',args.serial,*argv],capture_output=True,timeout=90)
        (args.receipt_dir/(name+'.stdout')).write_bytes(p.stdout)
        save(name+'.json',dict(argv=argv,returncode=p.returncode,stderr=p.stderr.decode('utf8','replace')))
        assert p.returncode==0,(name,p.stderr)
        return p.stdout
    def shell(name,script):
        marker='ANDROID_GATE_RC_'+uuid.uuid4().hex+'='
        wire='/data/local/tmp/zui_parser_wire_'+uuid.uuid4().hex
        # su text transport may translate LF to CRLF. Hex preserves exact bytes.
        wrapped='test ! -e '+wire+' || exit 99; ( '+script+'\n ) >'+wire+' 2>&1; result=$?; '
        wrapped+='od -An -v -tx1 '+wire+'; rm -f '+wire+'; printf "\\n'+marker+'%s\\n" "$result"'
        # Match the workspace's verified exec-out/su transport; check child status.
        out=adb(name,['exec-out','su','-c',shlex.join(['/system/bin/sh','-c',wrapped])])
        body,rc=out.rsplit(('\n'+marker).encode(),1)
        decoded=bytes.fromhex(body.decode('ascii'))
        assert rc.strip()==b'0',(name,rc,decoded[-2000:])
        return decoded
    source=args.source.read_text()
    old=(parser.HERE/'RequestParserOld.sh').read_text()
    for n in ('request_field','request_field_count'):
        assert parser.function(source,n)==parser.function(old,n),'FROZEN_HELPER_CHANGED'
    exact='' if args.freeze_old else parser.function(source,'parse_request_fields')
    definitions=old+'\n'+exact+'\n'+r'''
case "$1" in old_parse_request_fields|parse_request_fields) ;; *) exit 99 ;; esac
"$1" "$2"
printf '%s\0' "$request_parse_id" "$request_parse_cmd" "$request_parse_pkg" "$request_parse_mode" "$request_parse_count"
'''
    assert 'oneshot_request()' not in definitions and 'settings ' not in definitions
    structured,generated=parser.corpus()
    records=['1789406724610_366364749078_set_uperf_mode|set_uperf_mode|||powersave']+structured+generated[:11]
    assert len(records)==512
    frozen=None
    if args.contract:
        frozen=json.loads(args.contract.read_text(encoding='utf8'))
        assert frozen['authority']=='REAL_ANDROID_OLD_BEHAVIOR'
        assert frozen['old_parser_sha256']==hashlib.sha256(old.encode()).hexdigest()
        assert [r['input'] for r in frozen['rows']]==records
    assert args.freeze_old or frozen is not None,'Freeze real Android OLD first'
    contract_rows=[]
    mismatches=[]
    remote='/data/local/tmp/zui_parser_gate_'+uuid.uuid4().hex
    paths=[]
    matched=0
    identity=shell('shell_identity','printf "%s\\n" "$KSH_VERSION"; command -v timeout').decode()
    assert 'MIRBSD KSH R59' in identity and '/system/bin/timeout' in identity,identity
    shell('mkdir','set -e\nmkdir -m 0700 '+shlex.quote(remote)+'\nchown 2000:2000 '+shlex.quote(remote))
    try:
        local=args.receipt_dir/'parser.sh'
        local.write_bytes(definitions.encode())
        paths.append(remote+'/parser.sh')
        adb('push_parser',['push',str(local),paths[-1]])
        assert shell('verify_parser','sha256sum '+shlex.quote(paths[-1])).decode().split()[0]==hashlib.sha256(local.read_bytes()).hexdigest()
        for start in range(0,len(records),32):
            script='set -e\n'
            batch=records[start:start+32]
            for value in batch:
                if '\r' in value:
                    octets=''.join('\\%03o'%b for b in value.encode())
                    script+="request_input=$(printf '"+octets+"x'); request_input=${request_input%x}\n"
                else:script+='request_input='+shlex.quote(value)+'\n'
                for op in (('old_parse_request_fields',) if args.freeze_old else ('parse_request_fields',)):
                    script+='timeout 5 /system/bin/sh '+shlex.quote(paths[0])+' '+op+' "$request_input"\n'
            local=args.receipt_dir/('batch_%03d.sh'%start)
            local.write_bytes(script.encode())
            paths.append(remote+'/'+local.name)
            adb('push_%03d'%start,['push',str(local),paths[-1]])
            out=shell('batch_%03d'%start,'set -e\n/system/bin/sh '+shlex.quote(paths[-1]))
            values=out.split(b'\0')
            assert values.pop()==b'' and len(values)==len(batch)*5
            for i,value in enumerate(batch):
                actual=values[i*5:i*5+5]
                if args.freeze_old:
                    contract_rows.append(dict(input=value,**dict(zip(('id','command','package','mode','count'),[v.decode('utf8') for v in actual]))))
                    matched+=1
                    continue
                expected=[frozen['rows'][start+i][k].encode() for k in ('id','command','package','mode','count')]
                if expected!=actual:
                    mismatches.append(dict(index=start+i,input=value,old=[x.hex() for x in expected],new=[x.hex() for x in actual]))
                else:matched+=1
            print('ANDROID_MKSH_MATCH',matched,'/512',flush=True)
    finally:
        # Exact files in the unique directory created by this invocation; no recursion.
        shell('cleanup','set -e\nrm -f '+' '.join(shlex.quote(p) for p in paths)+'\nrmdir '+shlex.quote(remote)+'\ntest ! -e '+shlex.quote(remote))
    if args.freeze_old:
        assert contract_rows[5]==dict(input='a',id='a',command='',package='',mode='',count='1')
        save('ANDROID_OLD_PARSER_CONTRACT.json',dict(schema=1,authority='REAL_ANDROID_OLD_BEHAVIOR',android_sh=identity.splitlines()[0],old_parser_sha256=hashlib.sha256(old.encode()).hexdigest(),rows=contract_rows))
    if mismatches:save('MISMATCHES.json',mismatches)
    result=dict(status='FAIL' if mismatches else 'PASS',android_sh='/system/bin/sh',android_sh_version=identity.splitlines()[0],
        case_count=512,match_count=matched,mismatch_count=len(mismatches),per_parse_timeout_seconds=5,
        source_path=str(args.source.resolve()),source_sha256=hashlib.sha256(args.source.read_bytes()).hexdigest(),
        parser_sha256=hashlib.sha256(exact.encode()).hexdigest(),
        mode='freeze_old' if args.freeze_old else 'compare_contract',
        exact_production_source=not args.freeze_old and args.source.resolve()==(parser.ROOT/'payload/system/bin/zui_controld').resolve(),
        corpus_sha256=hashlib.sha256(json.dumps(records,ensure_ascii=False).encode()).hexdigest(),
        command_plane_executed=False,temporary_device_files_cleaned=True)
    save('RESULT.json',result)
    print(json.dumps(result),flush=True)
    if mismatches:raise SystemExit(1)


if __name__=='__main__':main()
