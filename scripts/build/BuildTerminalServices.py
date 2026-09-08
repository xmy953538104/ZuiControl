"""Replace only the reviewed Golden services extension with exact current CI DEX.

All non-extension members (including existing framework hooks) are preserved.
The canonical release caller binds the CI download, source and this receipt.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
from PatchZuiControlFramework import compile_sources


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(repo, sdk, golden_dir, ci_dex, ci_run, output):
    assert not output.exists(), 'Fresh terminal framework output required'
    expected={'framework.jar':'b5f57d62546569b9bd9ba34d8757678da000c33f876358dd93a4dc747b8f1b32',
        'services.jar':'245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000'}
    for name,digest in expected.items():
        assert not (golden_dir/name).is_symlink() and sha(golden_dir/name)==digest
    git=shutil.which('git'); assert git
    source=subprocess.check_output([git,'-C',str(repo),'rev-parse','HEAD'],text=True).strip()
    assert not subprocess.check_output([git,'-C',str(repo),'status','--porcelain'])
    assert ci_dex.is_file() and not ci_dex.is_symlink() and ci_dex.read_bytes().startswith(b'dex\n')
    output.mkdir(parents=True)
    compiled=compile_sources(repo,repo,output,repo/'framework_patch/src/services','classes4.dex',sdk)
    assert sha(compiled)==sha(ci_dex), 'Current local/CI services compiler output mismatch'
    shutil.copy2(golden_dir/'framework.jar',output/'framework.jar')
    with zipfile.ZipFile(golden_dir/'services.jar') as old:
        assert len(old.namelist())==len(set(old.namelist())) and old.testzip() is None
        assert old.namelist().count('classes4.dex')==1
        with zipfile.ZipFile(output/'services.jar','x') as new:
            for item in old.infolist():
                new.writestr(item,ci_dex.read_bytes() if item.filename=='classes4.dex' else old.read(item.filename))
    def entry(path):return dict(path=str(path.resolve()),sha256=sha(path),size=path.stat().st_size)
    manifest=dict(schema='ZUIOPT_TERMINAL_FRAMEWORK_V1',source_commit=source,ci_run=ci_run,
        golden_services=entry(golden_dir/'services.jar'),ci_services_extension=entry(ci_dex),
        jars={name:entry(output/name) for name in expected},
        services_changed=True,framework_changed=False,changed_members=['classes4.dex'])
    receipt=output/'terminal_framework.json'
    receipt.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf8')
    from ApplyZuiControlPayload import terminal_framework
    terminal_framework(repo,receipt)
    print(json.dumps(dict(status='PASS_EXACT_CI_EXTENSION_ONLY',manifest=str(receipt),sha256=sha(receipt))))
    return receipt


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--sdk',type=Path,required=True);p.add_argument('--golden-dir',type=Path,required=True)
    p.add_argument('--ci-dex',type=Path,required=True);p.add_argument('--ci-run',type=int,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();build(Path(__file__).resolve().parents[2],a.sdk,a.golden_dir,a.ci_dex,a.ci_run,a.output)
