"""Pinned Candidate1 scan model + real owner functions, portable host qualification.

No device, root, ROM, or new runtime dependency. Linux ownership/release suites
separately test full native POSIX integration. Model counts are logical operations,
not a hardware syscall trace; benchmark uses identical synthetic file-backed reads.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def between(text, start, end):
    return text[text.index(start):text.index(end, text.index(start))]


def sources():
    owner = (ROOT/'native/zuiopt/ZUIopt_owner.h').read_text(encoding='utf8')
    core = (ROOT/'native/zuiopt/ZUIopt_core.h').read_text(encoding='utf8')
    daemon = (ROOT/'native/zuiopt/ZUIopt_daemon.h').read_text(encoding='utf8')
    baseline = (ROOT/'tests/zuiopt/fixtures/PhysicalRevokeCandidate1.h').read_text(encoding='utf8')
    candidate = between(owner, '    bool physicalRevoke(', '    CoherenceResult verifyCoherence(')
    # Replace ONLY the authorized function. This freezes every other owner byte,
    # including Journal, prepare, apply, release/recovery and pre-write live checks.
    assert digest(owner.replace(candidate, baseline, 1)) == '35f42c416f50c8c6f5a7d3fdd6966b81dc36cb947d2d80299a9f11600e05dac0'
    assert digest(core) == 'bf977859edad2520fc0a8ac2f308b90becadd2c70bf99ee0525bbfde0344e21d'
    assert digest(daemon) == '0af1ac4ee1d4a7d1752cc213e96c33a7352900137abd0c8303277c6d75cc09b4'
    prefix = '        if(!p.writable())return p.revoked();\n        if(!t.owned||!t.appliedMask||!same(p,tid,t))return false;\n'
    assert prefix in candidate
    assert candidate[candidate.index('        auto g=group(tid);'):] == baseline[baseline.index('        auto g=group(tid);'):]
    assert owner.count('ObservedIdentity') == 2
    assert 'const ObservedIdentity observed{p.pid,p.uid,tid,p.generation,t.generation};' in candidate
    assert not any(word in candidate for word in ('static ', 'thread_local', 'new ', 'open(', 'dirfd', 'push_back', 'journal.commit', 'std::function'))
    for field in ('pid','user','tid','processStart','threadStart'):
        assert 'r.'+field+'==observed.'+field in candidate
    assert 'journal.boot==journal.currentBootId()' in candidate
    assert all('ObservedIdentity' not in text for text in (core, daemon))
    # Real production definitions, not reimplemented ownership/epoch semantics.
    definitions = between(core, 'struct StaleAuthorityScan', 'using Mask=')
    definitions += 'using Mask=uint64_t;\n'
    definitions += between(core, 'struct Identity {', 'inline Identity identity(')
    definitions += between(core, 'inline bool normalGroup(', 'inline int uid(')
    definitions += between(core, 'struct Counters {', 'inline void beginAcquisition(')
    definitions += between(core, 'inline bool forceCoherence(', '// Shared by the real reactor')
    definitions += between(owner, 'struct OwnerRecord {', 'inline uint32_t checksum(')
    same = between(owner, '    bool same(const ProcessState&', '    std::set<std::string> groups(')
    jsame = between(owner, '    bool same(const OwnerRecord&', '    bool inherited(')
    apply = between(owner, '    void apply(', '    // A physical mismatch')
    print('SCAN_LOCAL_LIFETIME_CONTRACT=PASS;OUTSIDE_PHYSICAL_REVOKE_BYTE_IDENTICAL=PASS;FRESH_FENCE_SOURCE=PASS', flush=True)
    return dict(definitions=definitions, same=same, jsame=jsame, apply=apply,
                baseline=baseline, candidate=candidate,
                hashes={name:digest(text) for name,text in [('owner',owner),('core',core),('daemon',daemon),('baseline_probe',baseline),('candidate_probe',candidate)]})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--compiler', required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    data = sources()
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output/'definitions.h').write_text(data['definitions'], encoding='utf8')
    (args.output/'journal_same.h').write_text(data['jsame'], encoding='utf8')
    for kind in ('baseline','candidate'):
        (args.output/(kind+'.h')).write_text(data['same']+data['apply']+data[kind], encoding='utf8')
    binary = args.output/('scan-observation.exe' if os.name=='nt' else 'scan-observation')
    commands = [[args.compiler,'--version'],
        [args.compiler,'-std=c++17','-O2','-Wall','-Wextra','-Werror','-I',str(args.output),
         str(ROOT/'tests/zuiopt/ZUIoptScanObservationTest.cpp'),'-o',str(binary)],
        [str(binary),str(args.output)]]
    receipts = []
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True, timeout=180)
        receipts.append(dict(argv=command, returncode=result.returncode, stdout=result.stdout, stderr=result.stderr))
        print(result.stdout,end='',flush=True)
        print(result.stderr,end='',flush=True)
        (args.output/'receipts.json').write_text(json.dumps(dict(hashes=data['hashes'],commands=receipts),indent=2),encoding='utf8')
        if result.returncode:
            raise SystemExit(result.returncode)
    rows = re.findall(r'BENCH N=(\d+) ROUND=(\d+) BASE_US=([\d.]+) CAND_US=([\d.]+)',result.stdout)
    summary = {}
    for n in (64,128,256,320):
        samples = [(int(r),float(a),float(b)) for size,r,a,b in rows if int(size)==n]
        assert len(samples)==7 and len({r for r,_,_ in samples})==7
        a,b = [sorted(row[i] for row in samples) for i in (1,2)]
        ma,mb = statistics.median(a),statistics.median(b)
        pa,pb = a[math.ceil(.95*len(a))-1],b[math.ceil(.95*len(b))-1]
        summary[n] = dict(rounds=7,scans_per_round=3,rank_reads_per_scan=1,
            baseline_median_us=ma,candidate_median_us=mb,baseline_p95_us=pa,candidate_p95_us=pb,
            median_delta_percent=100*(mb/ma-1),p95_delta_percent=100*(pb/pa-1))
    (args.output/'benchmark.json').write_text(json.dumps(dict(
        fixture='pinned mature scan model with real owner functions and synthetic file backing',
        p95_method='nearest-rank over 7 round means; each round averages 3 scans',
        HOST_SCAN_BENCHMARK_IS_NOT_DEVICE_CPU_CLAIM=True,results=summary),indent=2),encoding='utf8')


if __name__ == '__main__':
    main()
