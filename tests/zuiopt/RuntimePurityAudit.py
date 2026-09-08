"""Strict semantic product audit. Policy is host-only; no runtime exceptions.

Opaque raw substring collisions are recorded by a separate OEM inheritance audit.
Archive members and UTF-16 string pools are inspected without rewriting contents.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import zipfile

ROOT=Path(__file__).resolve().parents[2]
DEFAULT_POLICY=ROOT/'host_retirement/purity_policy.json'

def patterns(policy=DEFAULT_POLICY):
    rules=json.loads(Path(policy).read_text(encoding='utf8'))
    return [re.compile(row.encode('ascii'),re.I) for row in rules['semantic_patterns']]

def inspect(name,data,rules):
    result=[]
    samples=[('bytes',data.replace(b'\\.',b'.'))]
    samples += [('utf16le',m.group().decode('utf-16le').encode('ascii'))
                for m in re.finditer(rb'(?:[\x20-\x7e]\x00){4,}',data)]
    for encoding,sample in samples:
        for pattern in rules:
            for match in pattern.finditer(sample):
                result.append(dict(path=name,encoding=encoding,token=match.group().decode('ascii'),
                                   sample_offset=match.start()))
    return result

def audit_system(system,policy=DEFAULT_POLICY):
    rules=patterns(policy);hits=[];files=0;members=0
    for path in sorted(Path(system).rglob('*')):
        name=path.relative_to(system).as_posix()
        hits += inspect(name+' [path]',name.encode('utf8'),rules)
        if path.is_symlink():
            hits += inspect(name+' [link]',os.readlink(path).encode('utf8'),rules)
            continue
        if not path.is_file():continue
        files+=1
        if path.suffix.lower() in ('.apk','.jar','.zip'):
            with zipfile.ZipFile(path) as archive:
                assert archive.testzip() is None,name
                assert len(archive.namelist())==len(set(archive.namelist())),name
                for member in archive.infolist():
                    hits += inspect(name+'!'+member.filename+' [path]',member.filename.encode('utf8'),rules)
                    if member.is_dir():continue
                    members+=1
                    hits += inspect(name+'!'+member.filename,archive.read(member),rules)
        else:
            with path.open('rb') as stream:
                tail=b''
                while chunk:=stream.read(1024*1024):
                    hits += inspect(name,tail+chunk,rules)
                    tail=chunk[-256:]
    return dict(status='PASS' if not hits else 'HOLD_PROJECT_LEGACY_SEMANTICS',
                semantic_hits=hits,semantic_hit_count=len(hits),files_scanned=files,
                archive_members_scanned=members,policy_sha256=hashlib.sha256(Path(policy).read_bytes()).hexdigest())

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--system',type=Path,required=True);p.add_argument('--policy',type=Path,default=DEFAULT_POLICY);p.add_argument('--receipt',type=Path);a=p.parse_args()
    result=audit_system(a.system,a.policy)
    if a.receipt:a.receipt.write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
    print(json.dumps(result));raise SystemExit(0 if result['status']=='PASS' else 1)
