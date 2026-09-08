"""Reject active retired owners in source payload or actual extracted system.

Migration path literals are narrowly classified, not erased or called owners.
APK/JAR DEX members are inspected uncompressed, not only ZIP-container strings.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

FORBIDDEN = (b'asoulopt', b'a-soul', b'zui_asoulopt', b'default_asopt.conf',
    b'/data/vendor/asopt.conf', b'start_asoul', b'stop_asoul', b'ro.zui_control.task_owner', b'next_owner=')
ABSENT = ('bin/AsoulOpt', 'etc/zui_control/default_asopt.conf', 'etc/zuiopt/boot_owner.sh', 'etc/init/zui_asoulopt.rc')


def audit_system(system):
    for path in ABSENT:
        assert not (system/path).exists() and not (system/path).is_symlink(), path
    hits = []
    def inspect(name, data):
        normalized = data.lower().replace(b'\\.', b'.')
        found = [token.decode() for token in FORBIDDEN if token in normalized]
        if not found:
            return
        kind = None
        if name == 'etc/zuiopt/zuiopt_legacy_migration.sh':
            # Cleanup script cannot contain any runtime binary/service/selector.
            assert all(token == '/data/vendor/asopt.conf' for token in found), (name, found)
            assert b'readlink "$legacy_link"' in data and b'legacy_sha=' in data and b'rm -rf' not in data
            kind = 'EXACT_PATH_MIGRATION_ONLY'
        elif name == 'etc/init/zui_scheduler.rc':
            assert found == ['/data/vendor/asopt.conf'], (name, found)
            assert re.search(rb'on zuiopt-migration-unlink && property:sys.zui_control.legacy_migration=UNLINK_VERIFIED\r?\n    rm /data/vendor/asopt.conf\r?\n', data)
            kind = 'VERIFIED_MIGRATION_INIT_UNLINK_ONLY'
        elif name == 'etc/selinux/plat_file_contexts':
            assert found == ['/data/vendor/asopt.conf'], (name, found)
            kind = 'LEGACY_LINK_LABEL_FOR_SAFE_MIGRATION_ONLY'
        assert kind, 'ACTIVE_RUNTIME_ASOUL: ' + name + ': ' + str(found)
        hits.append(dict(path=name,classification=kind,tokens=found,sha256=hashlib.sha256(data).hexdigest()))
    files = 0
    for path in sorted(system.rglob('*')):
        if not path.is_file() or path.is_symlink():
            continue
        name = path.relative_to(system).as_posix()
        files += 1
        if path.suffix in ('.apk','.jar'):
            with zipfile.ZipFile(path) as archive:
                for member in archive.namelist():
                    if member.endswith(('.dex','.xml','.arsc')):
                        inspect(name+'!'+member, archive.read(member))
        else:
            # Overlap retains literals across chunk boundaries without loading
            # whole device binaries into memory. Small matching scripts get a full audit.
            with path.open('rb') as stream:
                tail=b''
                while True:
                    chunk=stream.read(1024*1024)
                    if not chunk: break
                    sample=tail+chunk
                    if any(token in sample.lower().replace(b'\\.',b'.') for token in FORBIDDEN):
                        inspect(name,path.read_bytes()); break
                    tail=sample[-128:]
    return dict(status='PASS',active_runtime_asoul='ABSENT',files_scanned=files,classified_hits=hits)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--system',type=Path,required=True); parser.add_argument('--receipt',type=Path)
    args=parser.parse_args(); result=audit_system(args.system)
    if args.receipt: args.receipt.write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
    print(json.dumps(result))
