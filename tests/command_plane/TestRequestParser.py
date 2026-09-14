#!/usr/bin/env python3
"""Frozen cut/awk differential, deterministic corpus, optional Linux benchmark."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import shlex
import shutil
import statistics
import subprocess
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def function(text, name):
    return re.search(r'^' + name + r'\(\) \{\n.*?^\}', text, re.M | re.S)[0]


def corpus():
    seeds = ['', '|', '||||', '|||||', 'a', 'a|b', 'a|b|c', 'a|b|c|d',
             'a|b|c|d|e', 'a|b|c|d|e|f', '|b||p|fast', 'a|||p|',
             'id|set_uperf_mode|||powersave', 'id|set_uperf_mode|||balance',
             'id|set_uperf_mode|||performance', 'id|set_uperf_mode|||fast',
             'id|done|set_uperf_mode|', 'id|failed|set_uperf_mode|bad hash',
             'bad id|set_uperf_mode|||balance', 'same_id|status|||',
             'id|zo_chunk||pkg|' + 'a' * 10924, 'id|cmd||a b|x\\y',
             '''id|cmd||'quoted'|"double"''', 'id|cmd||$(literal)|`literal`',
             '\n', '\r\n', 'a\nb', 'a|b|||c\nx|y', '\na|b|||c',
             'id|cmd|||mode\n', 'id|cmd|||mode\r\n', 'id|cmd|||mode\n\n']
    structured = list(seeds)
    endings = ['', '\n', '\r\n', '\n\n']
    while len(structured) < 500:
        i = len(structured)
        fields = ['', 'id', 'same_id', 'bad id', 'x' * 65, 'a' * 64,
                  'not_a_sha', 'a b', '\\x', "'\"", '中文']
        values = [fields[(i + j * 3) % len(fields)] for j in range(i % 9)]
        structured.append('|'.join(values) + endings[i % 4])
    rng = random.Random(0x225701)
    generated = []
    alphabet = "abXYZ019 _-.\\'\"$`()[];:*?\t\r中文"
    for i in range(20000):
        values = [''.join(rng.choice(alphabet) for _ in range(rng.randrange(0, 30)))
                  for _ in range(rng.randrange(0, 12))]
        if values and i % 211 == 0:
            values[-1] += 'long' * 2731
        value = '|'.join(values)
        if i % 127 == 0:
            value += '\n' + '|'.join(reversed(values))
        if i % 131 == 0:
            value += '\n\n'
        generated.append(value)
    return structured, generated


def run(args, data, timeout=600):
    p = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError((args, p.returncode, p.stderr.decode('utf8', 'replace')[-2000:]))
    return p.stdout


def shell_parse(shell, definitions, operation, records):
    script = definitions + '\n'
    for value in records:
        # Git Bash normalizes literal CRLF in script input, even inside quotes.
        # Transport CR-bearing fixtures as exact bytes, not physical CRLF source.
        if '\r' in value:
            octets = ''.join('\\%03o' % b for b in value.encode())
            script += "request_input=$(printf '" + octets + "x'); request_input=${request_input%x}\n"
        else:
            script += 'request_input=' + shlex.quote(value) + '\n'
        script += operation + ' "$request_input"\n'
        script += "printf '%s\\0' \"$request_parse_id\" \"$request_parse_cmd\" \"$request_parse_pkg\" \"$request_parse_mode\" \"$request_parse_count\"\n"
    out = run([shell], script.encode())
    values = out.split(b'\0')
    assert values.pop() == b'' and len(values) == len(records) * 5
    return [tuple(values[i:i+5]) for i in range(0, len(values), 5)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--receipt-dir', type=Path, required=True)
    ap.add_argument('--benchmark', action='store_true')
    ap.add_argument('--shell', default=shutil.which('sh'))
    args = ap.parse_args()
    args.receipt_dir.mkdir(parents=True, exist_ok=True)
    def save(name, data):
        with (args.receipt_dir / name).open('x', encoding='utf8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    if platform.system() != 'Linux':
        save('platform.json', dict(status='HOLD_REQUIRES_LINUX',platform=platform.platform(),
             reason='MINGW pipeline CRLF translation is not a Linux/Android parser oracle'))
        raise SystemExit('Linux qualification required; no MINGW semantics substituted')
    source = (ROOT / 'payload/system/bin/zui_controld').read_text()
    old = (HERE / 'RequestParserOld.sh').read_text()
    for name in ('request_field', 'request_field_count'):
        assert function(source, name) == function(old, name), 'FROZEN_HELPER_CHANGED'
    definitions = '\n'.join(function(source, n) for n in
                            ('request_field', 'request_field_count', 'parse_request_fields'))
    structured, generated = corpus()
    records = structured + generated
    for start in range(0, len(records), 500):
        batch = records[start:start+500]
        print('DIFFERENTIAL_BATCH',start,'/',len(records),flush=True)
        reference = shell_parse(args.shell, old, 'old_parse_request_fields', batch)
        actual = shell_parse(args.shell, definitions, 'parse_request_fields', batch)
        for offset, (expected, result) in enumerate(zip(reference, actual)):
            if expected != result:
                index = start + offset
                save('differential.json', dict(status='FAIL',matches=index,mismatches=1,
                     index=index,input=records[index],old=[x.hex() for x in expected],new=[x.hex() for x in result]))
                raise SystemExit('STOP: parser semantic mismatch; frozen expectations unchanged')
        print('MATCH',min(start+500,len(records)),'/',len(records),flush=True)
    save('differential.json', dict(status='PASS',structured_cases=500,generated_cases=20000,
         match_count=len(records),mismatch_count=0,seed='0x225701',platform=platform.platform(),
         oracle='frozen literal OLD shell4cut+1awk invoked for EVERY case; no emulated or batched reference',
         source_sha256=hashlib.sha256(source.encode()).hexdigest(),
         corpus_sha256=hashlib.sha256(json.dumps(records,ensure_ascii=False).encode()).hexdigest()))
    if not args.benchmark:
        return
    rounds=[]
    inputs=['bench_'+str(i)+'|set_uperf_mode|||'+m for i in range(50)
            for m in ('powersave','balance','performance','fast')]
    expected = shell_parse(args.shell, old, 'old_parse_request_fields', inputs)
    for i in range(10):
        row=dict(round=i+1,order=['old','new'] if i%2==0 else ['new','old'])
        for model in row['order']:
            begin=time.perf_counter()
            result=shell_parse(args.shell,old if model=='old' else definitions,
                               'old_parse_request_fields' if model=='old' else 'parse_request_fields',inputs)
            row[model+'_seconds']=time.perf_counter()-begin
            assert result==expected
        rounds.append(row)
        print('BENCHMARK',row,flush=True)
    old_times=[r['old_seconds'] for r in rounds];new_times=[r['new_seconds'] for r in rounds]
    om=statistics.median(old_times);nm=statistics.median(new_times)
    save('benchmark.json',dict(status='PASS' if nm<om else 'FAIL_NEW_SLOWER',rounds=rounds,
         iterations_per_round=200,old_median=om,new_median=nm,
         old_p95=sorted(old_times)[math.ceil(.95*len(old_times))-1],
         new_p95=sorted(new_times)[math.ceil(.95*len(new_times))-1],speedup=om/nm,
         external_execs_per_record_old=5,external_execs_per_record_new=0,
         multiline_fallback_external_execs=5,platform=platform.platform(),
         caveat='whole batch shell launch and result output included equally; no device latency prediction'))
    assert nm<om,'STOP: new parser benchmark slower'


if __name__=='__main__':
    main()
