"""Execute the actual boot migration in an exclusive Linux fixture, never /data.

Run as root on the CI host to reproduce exact Android UID/GID/mode checks.
Only fixed production paths are substituted; no test switch enters the ROM.
"""
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / 'payload/system/etc/zuiopt/zuiopt_legacy_migration.sh').read_text()
CONFIG = b'# Shiroko A-SOUL: hard affinity + WALT per-task boost, no real-time policy.\nmode=0\nrt=0\nopt=0xDEADBEEF\n'


class Migration(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='zuiopt-migration-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.data = self.root / 'data'
        for rel, uid, gid, mode in (('', 1000, 1000, 0o771), ('vendor', 0, 0, 0o771),
                ('vendor/zui_control', 0, 0, 0o755), ('vendor/zui_control/asoul', 0, 2000, 0o775)):
            path = self.data / rel
            path.mkdir(); os.chown(path, uid, gid); path.chmod(mode)
        self.legacy = self.data / 'vendor/zui_control/asoul'
        self.config = self.legacy / 'asopt.conf'
        self.config.write_bytes(CONFIG); os.chown(self.config, 0, 2000); self.config.chmod(0o644)
        self.link = self.data / 'vendor/asopt.conf'
        self.link.symlink_to(self.config)
        self.marker = self.root / 'done'
        stub = self.root / 'store.sh'
        stub.write_text('#!/bin/sh\ncase "$1" in\n--migration-state) [ ! -f ' + shlex.quote(str(self.marker)) +
            ' ] && echo PENDING || echo DONE ;;\n--migration-done) touch ' + shlex.quote(str(self.marker)) + ' ;;\n*) exit 99 ;;\nesac\n')
        stub.chmod(0o700)
        self.prop = self.root / 'property'
        setters = 'setprop() { printf "%s" "$2" > ' + shlex.quote(str(self.prop)) + '; }\n'
        setters += 'getprop() { cat ' + shlex.quote(str(self.prop)) + '; }\n'
        self.script = self.root / 'migration.sh'
        self.script.write_text(setters + SCRIPT.replace('/system/bin/ZUIopt', shlex.quote(str(stub))).replace('/data', str(self.data)))
        self.other = self.data / 'vendor/zui_control/uperf'
        self.other.mkdir(); (self.other / 'keep').write_bytes(b'unrelated data')

    def run_migration(self, success):
        proc = subprocess.run(['sh', str(self.script), 'prepare'], capture_output=True, text=True, timeout=10)
        self.assertEqual(proc.returncode == 0, success, proc.stdout + proc.stderr)
        if not success:
            self.assertIn('LEGACY_ASOUL_PATH_UNEXPECTED', proc.stderr)
            self.assertFalse(self.marker.exists())
        else:
            # Execute init's one conditional unlink only after the actual helper
            # returns its verified state. No unlink after a failed preparation.
            if not self.marker.exists() and self.prop.read_text() == 'UNLINK_VERIFIED':
                self.assertTrue(self.link.is_symlink())
                self.assertEqual(self.link.readlink(), self.config)
                self.link.unlink()
            done = subprocess.run(['sh', str(self.script), 'finish'], capture_output=True, text=True, timeout=10)
            self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual((self.other / 'keep').read_bytes(), b'unrelated data')

    def test_known_layout_and_second_boot_no_actions(self):
        self.assertEqual(len(CONFIG), 103)
        self.assertEqual(hashlib.sha256(CONFIG).hexdigest(), '69a73f9bedb3a5f3e07d8f74d3ab9d18f8ab97ff48e02c74a378333fa3b1b75e')
        self.run_migration(True)
        self.assertFalse(self.legacy.exists()); self.assertFalse(self.link.is_symlink())
        stamp = self.marker.stat().st_mtime_ns
        self.run_migration(True); self.assertEqual(stamp, self.marker.stat().st_mtime_ns)

    def test_wrong_link_target(self):
        self.link.unlink(); self.link.symlink_to(self.other / 'keep')
        self.run_migration(False); self.assertTrue(self.link.is_symlink()); self.assertEqual(self.config.read_bytes(), CONFIG)

    def test_regular_external_path(self):
        self.link.unlink(); self.link.write_text('keep')
        self.run_migration(False); self.assertEqual(self.link.read_text(), 'keep')

    def test_unknown_child(self):
        (self.legacy / 'unknown').write_text('keep')
        self.run_migration(False); self.assertEqual(self.config.read_bytes(), CONFIG)
        self.assertEqual(self.legacy.stat().st_mode & 0o777, 0o775)

    def test_foreign_owner(self):
        os.chown(self.legacy, 2000, 2000)
        self.run_migration(False); self.assertEqual(self.config.read_bytes(), CONFIG)

    def test_unexpected_bytes(self):
        self.config.write_bytes(b'custom data')
        self.run_migration(False); self.assertEqual(self.config.read_bytes(), b'custom data')

    def test_config_symlink(self):
        self.config.unlink(); self.config.symlink_to(self.other / 'keep')
        self.run_migration(False); self.assertTrue(self.config.is_symlink())

    def test_config_hardlink(self):
        os.link(self.config, self.other / 'hardlink')
        self.run_migration(False); self.assertEqual(self.config.read_bytes(), CONFIG)

    def test_directory_symlink(self):
        self.config.unlink(); self.legacy.rmdir(); self.legacy.symlink_to(self.other, target_is_directory=True)
        self.run_migration(False); self.assertTrue(self.legacy.is_symlink())

    def test_approved_temp_and_interrupted_freeze(self):
        temp = self.legacy / 'asopt.conf.tmp'
        temp.write_bytes(CONFIG); os.chown(temp, 0, 2000); temp.chmod(0o644)
        self.legacy.chmod(0o700)
        self.run_migration(True); self.assertFalse(self.legacy.exists())

    def test_already_absent(self):
        self.config.unlink(); self.legacy.rmdir(); self.link.unlink()
        self.run_migration(True)


if __name__ == '__main__':
    if os.name != 'posix' or os.geteuid() != 0:
        raise SystemExit('Requires Linux root fixture; NOT_RUN is not PASS')
    unittest.main(verbosity=2)
