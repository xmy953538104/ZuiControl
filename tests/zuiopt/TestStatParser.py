"""Compile the real parser slice portably; native ownership suites remain separate.

Usage: python TestStatParser.py --compiler clang++ --output <new-evidence-dir>
Benchmark is a separate explicit invocation after all required correctness gates.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--compiler',required=True)
    ap.add_argument('--output',type=Path,required=True);args=ap.parse_args()
    repo=Path(__file__).resolve().parents[2]
    source=(repo/'native/zuiopt/ZUIopt_core.h').read_text(encoding='utf-8')
    start=source.index('struct Identity {');end=source.index('inline Identity identity(',start)
    parser=source[start:end]
    assert parser.count('inline Identity statIdentity(')==1
    args.output.mkdir(parents=True,exist_ok=False)
    header=args.output/'ZUIoptStatParserUnderTest.h'
    header.write_text('#include <array>\n#include <cstdint>\n#include <locale>\n#include <string>\nnamespace ZUIopt {\n'+parser+'}\n',encoding='utf-8')
    binary=args.output/('stat-parser.exe' if __import__('os').name=='nt' else 'stat-parser')
    commands=[[args.compiler,'--version'],[args.compiler,'-std=c++17','-O2','-Wall','-Wextra','-Werror','-I',str(args.output),str(repo/'tests/zuiopt/ZUIoptStatParserTest.cpp'),'-o',str(binary)],[str(binary)]]
    receipts=[]
    for command in commands:
        result=subprocess.run(command,capture_output=True,text=True,timeout=120)
        receipts.append(dict(argv=command,returncode=result.returncode,stdout=result.stdout,stderr=result.stderr))
        print(result.stdout,end='',flush=True);print(result.stderr,end='',flush=True)
        (args.output/'receipts.json').write_text(json.dumps(dict(source_sha256=hashlib.sha256(source.encode()).hexdigest(),parser_sha256=hashlib.sha256(parser.encode()).hexdigest(),commands=receipts),indent=2),encoding='utf-8')
        if result.returncode:raise SystemExit(result.returncode)

if __name__=='__main__':main()
