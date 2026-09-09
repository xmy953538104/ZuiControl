"""Every active product source path and string pool must satisfy the host policy."""
from pathlib import Path
import sys
from RuntimePurityAudit import patterns, inspect

ROOT=Path(__file__).resolve().parents[2]
rules=patterns();hits=[];count=0
for name in ('AGENTS.md','README.md'):
    hits+=inspect(name,(ROOT/name).read_bytes(),rules);count+=1
for scope in ('app','framework_patch','native','payload','scripts/build','scripts/rules','.github/workflows','tests'):
    for p in (ROOT/scope).rglob('*'):
        if not p.is_file() or any(x in p.parts for x in ('build','.gradle','__pycache__')) and not p.is_relative_to(ROOT/'scripts/build'):
            continue
        if p.suffix in ('.pyc','.class'):continue
        name=p.relative_to(ROOT).as_posix();count+=1
        hits+=inspect(name+' [path]',name.encode(),rules)
        hits+=inspect(name,p.read_bytes(),rules)
if hits:raise AssertionError(hits)
print('ACTIVE_PRODUCTION_SEMANTIC_PURITY=PASS; FILES='+str(count))
