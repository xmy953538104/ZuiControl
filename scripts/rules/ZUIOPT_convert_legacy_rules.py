"""Deterministic STATIC_RECOVERED 27-profile/316-exact-package conversion; no binary reverse."""
from pathlib import Path
import argparse,csv,hashlib,json,re,time,tracemalloc

def convert(database,expected_counts=(27,316),require_selectors=False):
    rows=list(csv.DictReader(Path(database).open(encoding='utf8')))
    profiles={};packages={};provenance=[];selectors={}
    mask={'0x7c':('C0','2-6'),'0x1c':('C1','2-4'),'0x80':('C2','7')}
    for r in rows:
        p=r['package'];g=r['group'];assert re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+',p),p
        assert r['affinity_mask'] in mask and r['static_confidence']=='STATIC_CONFIRMED'
        pattern=r['thread_pattern'];order=int(r['thread_priority']);cls,cpus=mask[r['affinity_mask']]
        profile=profiles.setdefault(g,{})
        if order==0:assert pattern=='<NO_THREAD_MATCH>' and cpus=='2-6'
        else:
            assert r['thread_match_type'] in ('SUBSTRING_STRSTR','SUBSTRING_STRSTR;METADATA_KEY')
            rule=(pattern,cls,cpus)
            assert order not in profile or profile[order]==rule
            profile[order]=rule
            if require_selectors:
                selector=r.get('selector','');assert selector=='all' or re.fullmatch(r'rank:[1-9][0-9]*',selector),'new recovery needs explicit selector'
                assert selector=='all' or int(selector.split(':')[1])<=1024
                key=(g,cls);assert key not in selectors or selectors[key]==selector,'conflicting recovered selector'
                selectors[key]=selector
        packages.setdefault(p,set()).add((g,r['declaration_status']))
    assert 1<=len(profiles)<=64 and 1<=len(packages)<=512
    if expected_counts is not None: assert (len(profiles),len(packages))==expected_counts
    lines=['# ZUIopt generated compatibility database: STATIC_RECOVERED, not 316-device-PASS.','schema 2','enabled true','debug false']
    for g,rules in sorted(profiles.items()):
        lines+=['# '+g+' source=STATIC_RECOVERED','profile '+g+' 2-6']
        for order,(pattern,cls,cpus) in sorted(rules.items()):
            selector=selectors[(g,cls)] if require_selectors else 'all' if cls=='C0' else 'rank:2' if g=='G02' and cls=='C2' else 'rank:1'
            lines.append('thread '+g+' '+cls+' contains '+json.dumps(pattern)+' selector='+selector+' '+str(1000-order)+' '+cpus)
    profile_text='\n'.join(lines)+'\n'
    for n,(p,choices) in enumerate(sorted(packages.items())):
        active={g for g,status in choices if status=='ACTIVE_FOR_EXACT_INPUT'}
        if not active:
            active={status.removeprefix('SHADOWED_FOR_EXACT_INPUT_BY_') for _,status in choices}
        assert len(active)==1,(p,choices)
        g=next(iter(active));assert g in profiles
        lines.append('package exact '+p+' '+g+' '+str(10000-n))
        provenance.append(dict(package=p,match_type='exact',profile=g,priority=10000-n,status='STATIC_RECOVERED',source_declarations=';'.join(':'.join(c) for c in sorted(choices))))
    text='\n'.join(lines)+'\n'
    assert len(text.encode())<65536 and not re.search(r'\b(?:rt|opt|mode)\s*=',text)
    return text,profile_text,provenance

def selftest(database):
    a=convert(database);b=convert(database);assert a==b
    assert len(a[2])==316 and sum(l.startswith('profile ') for l in a[0].splitlines())==27
    assert next(r for r in a[2] if r['package']=='com.kurogame.mingchao')['profile']=='G07'
    assert next(r for r in a[2] if r['package']=='com.roblox.client')['profile']=='G05'
    assert next(r for r in a[2] if r['package']=='com.herogame.gplay.punishing.grayraven.tw')['profile']=='G01'
    return a

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('database',type=Path);parser.add_argument('output',type=Path);args=parser.parse_args()
    tracemalloc.start();start=time.perf_counter();text,profiles,rows=selftest(args.database);elapsed=time.perf_counter()-start;peak=tracemalloc.get_traced_memory()[1]
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'zuiopt_legacy_profiles.conf').write_text(text,encoding='utf8',newline='\n')
    (args.output/'12_ZUIOPT_27_PROFILES.conf').write_text(profiles,encoding='utf8',newline='\n')
    with (args.output/'13_ZUIOPT_316_PACKAGE_MAP.tsv').open('w',encoding='utf8',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]),delimiter='\t');w.writeheader();w.writerows(rows)
    receipt=dict(source_sha256=hashlib.sha256(args.database.read_bytes()).hexdigest(),config_sha256=hashlib.sha256(text.encode()).hexdigest(),profiles=27,mappings=316,bytes=len(text.encode()),two_conversions_seconds=elapsed,python_peak_bytes=peak,source_status='STATIC_RECOVERED',selftest='PASS')
    (args.output/'conversion_receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf8');print(json.dumps(receipt),flush=True)
