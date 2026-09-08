"""Version/owner/build prose must track the active source, not historical names."""
import argparse
from pathlib import Path
import re

def verify_repository_docs(root):
    version=re.search(r'versionCode\s*=\s*(\d+)',(root/'app/build.gradle.kts').read_text())[1]
    paths=['README.md','AGENTS.md','payload/README.txt']
    for relative in paths:
        text=(root/relative).read_text(encoding='utf8')
        assert 'ZuiTaskOpt' not in text,relative
        assert not re.search(r'\b(?:App V|ZuiControlV)(\d+)\b',text) or all(v==version for v in re.findall(r'\b(?:App V|ZuiControlV)(\d+)\b',text)),relative
        assert 'asoulOpt is the sole per-task' not in text,relative
        assert 'scripts/WorkShell.ps1' not in text,relative
    payload=(root/'payload/README.txt').read_text(encoding='utf8')
    assert f'App V{version} target' in payload and f'ZuiControlV{version}/ZuiControl.apk' in payload
    assert 'sole per-task owner' in payload and 'ANDROID_DEFAULT_FAILSAFE' in payload
    assert 'Init creates /dev/cpuset/ZUIopt 0755 root root' in payload
    assert 'classes4.dex is rebuilt' in payload

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[2]);args=parser.parse_args()
    verify_repository_docs(args.repo)
    print('STALE_CANONICAL_DOC_TEST=PASS')
