"""Build the production binary with the existing pinned NDK; never installs it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    suffix = '.exe' if os.name == 'nt' else ''
    tools = args.ndk / 'toolchains/llvm/prebuilt' / host
    source = repo / 'native/zuiopt/ZUIopt.cpp'
    version = (args.ndk / 'source.properties').read_text()
    assert 'Pkg.Revision = 27.2.12479018' in version, version
    cmd = [str(tools / ('bin/clang++' + suffix)), '--target=aarch64-linux-android34',
           '--sysroot=' + str(tools / 'sysroot'), '-std=c++17', '-Os',
           '-DZUIOPT_PRODUCTION=1', '-fPIE', '-pie', '-static-libstdc++',
           '-fstack-protector-strong', '-D_FORTIFY_SOURCE=2', '-ffunction-sections',
           '-fdata-sections', '-fno-ident', '-Wall', '-Wextra', '-Werror',
           '-Wl,-z,relro,-z,now,--build-id=none,--gc-sections', str(source),
           '-lbinder_ndk', '-ldl', '-lz', '-o', str(args.output)]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(cmd, check=True)
    strip = [str(tools / ('bin/llvm-strip' + suffix)), '--strip-all', str(args.output)]
    subprocess.run(strip, check=True)
    receipt = dict(command=cmd, strip=strip,
                   sha256=hashlib.sha256(args.output.read_bytes()).hexdigest(),
                   size=args.output.stat().st_size,
                   sources={str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in sorted(source.parent.glob('*')) if p.is_file()})
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(status='BUILT_NOT_INSTALLED', sha256=receipt['sha256'], size=receipt['size'])))

if __name__ == '__main__':
    main()
