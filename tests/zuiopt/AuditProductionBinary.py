"""Audit actual NDK readelf/strings output, not the source file names."""
from pathlib import Path
import json
import sys

root=Path(sys.argv[1])
elf=(root/'elf.txt').read_text(encoding='utf-8')
strings=(root/'strings.txt').read_text(encoding='utf-8')
assert 'AArch64' in elf and 'DYN' in elf and 'GNU_RELRO' in elf
assert 'BIND_NOW' in elf or 'NOW' in elf
for token in ('.debug_', '.symtab', 'sched_setscheduler', 'sched_setparam'):
    assert token not in elf, token
for token in ('--selftest','--check-config','/data/local/tmp','/data/adb','SCHED_FIFO','SCHED_RR','sched_setscheduler','/sys/class/kgsl','/proc/sys/walt','ZUIOPT_SELFTEST','CONFIG_PASS','FIXTURE_FAIL','injected before commit'):
    assert token not in strings,token
needed={'libc.so','libm.so','libdl.so','libbinder_ndk.so','libz.so'}
for library in __import__('re').findall(r'Shared library: \[([^\]]+)\]',elf):
    assert library in needed,library
assert 'sched_setaffinity' in elf
(root/'binary_audit.json').write_text(json.dumps(dict(status='PASS',no_rt=True,no_walt=True,no_opt=True,no_gpu=True,no_test_entry=True,no_debug_symbols=True),indent=2)+'\n')
print('PRODUCTION_ELF_SYMBOLS_STRINGS=PASS')
