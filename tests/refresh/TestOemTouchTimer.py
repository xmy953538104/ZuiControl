"""Parameter-only payload contract; no device required."""
from pathlib import Path
import hashlib
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/build'))
from ApplyZuiControlPayload import patch_oem_touch_timer

KEY = b'ro.surface_flinger.set_touch_timer_ms='
PATHS = ('system_a/system/build.prop', 'vendor_a/build.prop')
PROPERTIES = (b'ro.surface_flinger.set_idle_timer_ms=2000\n'
              b'ro.surface_flinger.set_refresh_timer_ms=200\n'
              b'ro.surface_flinger.set_launcher_timer_ms=30000\n'
              b'ro.surface_flinger.set_pen_timer_ms=10000\n'
              b'ro.surface_flinger.use_content_detection_for_refresh_rate=true\n'
              b'ro.config.lgsi.low_power_mode_require_fps=60\n'
              b'ro.config.lgsi.reading_mode_require_fps=30\n'
              b'# thermal and all unrelated bytes stay unchanged\n')


def protected_payload(tree):
    # Only the authorized command parser is qualified independently. Preserve
    # every other path, blob and file mode from the accepted payload inventory.
    rows = tree.splitlines()
    parser = [row for row in rows if row.split(b'\t', 1)[1] == b'system/bin/zui_controld']
    assert len(parser) == 1 and parser[0].startswith(b'100644 blob ')
    return hashlib.sha256(b'\n'.join(row for row in rows if row != parser[0]) + b'\n').hexdigest()


class OemTouchTimer(unittest.TestCase):
    def fixture(self, root, endings=b'\n'):
        original = {}
        for relative in PATHS:
            p = root / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            data = (b'# OEM\n' + KEY + b'2000\n' + PROPERTIES).replace(b'\n', endings)
            p.write_bytes(data)
            original[relative] = data
        for relative in ('system_a/system/bin/surfaceflinger',
                         'system_a/system/lib64/libSurfaceFlingerProp.so'):
            p = root / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'unchanged native input\x00\xff')
            original[relative] = p.read_bytes()
        return original

    def test_exact_two_values_dry_run_and_idempotence(self):
        for endings in (b'\n', b'\r\n'):
            with self.subTest(endings=endings), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                before = self.fixture(root, endings)
                report = {}
                patch_oem_touch_timer(root, True, report)
                self.assertEqual({n: (root/n).read_bytes() for n in before}, before)
                self.assertEqual(len(report['oem_touch_timer']), 2)
                patch_oem_touch_timer(root, False, report)
                for n, data in before.items():
                    expected = data.replace(KEY+b'2000', KEY+b'5000') if n in PATHS else data
                    self.assertEqual((root/n).read_bytes(), expected, n)
                self.assertTrue(all(row['new'] == '5000' for row in report['oem_touch_timer']))
                patch_oem_touch_timer(root, False, report)
                self.assertTrue(all(not row['changed'] for row in report['oem_touch_timer']))

    def test_missing_duplicate_or_unexpected_input_fails_before_any_write(self):
        for bad in (None, b'', KEY+b'2000\n'+KEY+b'2000\n', KEY+b'3000\n'):
            for relative in PATHS:
                with self.subTest(bad=bad, path=relative), tempfile.TemporaryDirectory() as temp:
                    root = Path(temp)
                    self.fixture(root)
                    p = root/relative
                    if bad is None:
                        p.unlink()
                    else:
                        p.write_bytes(bad)
                    before = {n: (root/n).read_bytes() for n in PATHS if (root/n).exists()}
                    with self.assertRaises(SystemExit):
                        patch_oem_touch_timer(root, False, {})
                    self.assertEqual({n: (root/n).read_bytes() for n in before}, before)

    def test_half_applied_input_finishes_both_sources(self):
        for relative in PATHS:
            with self.subTest(path=relative), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.fixture(root)
                p = root/relative
                p.write_bytes(p.read_bytes().replace(KEY+b'2000', KEY+b'5000'))
                patch_oem_touch_timer(root, False, {})
                for n in PATHS:
                    self.assertIn(KEY+b'5000\n', (root/n).read_bytes())
                    self.assertNotIn(KEY+b'2000', (root/n).read_bytes())

    def test_no_new_runtime_policy_or_native_inputs(self):
        # The parser has its own differential/transaction gate. Other production
        # inputs retain their accepted identities; no new Refresh implementation.
        expected = {'app': 'b8058f77ce699fdd7f6d9470c4329e6ed1a6dd36',
                    'framework_patch': 'e54609edf29df83d8ad9a2c5dc01f41b108bb53c',
                    'native': '6790c7cf6c634b28b0360911686d603bc8616917',
                    'upstream': '3dc065f74510060d3612ab3bf805ed305f2e447d'}
        for path, tree in expected.items():
            actual = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD:'+path], text=True).strip()
            self.assertEqual(actual, tree, path)
        payload = subprocess.check_output(['git', '-C', str(ROOT), 'ls-tree', '-r', 'HEAD:payload'])
        self.assertEqual(protected_payload(payload),
                         '5a4c3bdf03c638495e299e62e78965376fa418f20497d3d215bc6e1623d451e0')
        dirty = subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain', '--', *expected, 'payload'], text=True)
        self.assertEqual(dirty, '')
        source = (ROOT/'scripts/build/ApplyZuiControlPayload.py').read_text(encoding='utf-8')
        self.assertIn('patch_oem_touch_timer(unpack, args.dry_run, report)', source)

    def test_payload_scope_guard_rejects_other_mutations(self):
        parser = b'100644 blob ' + b'a'*40 + b'\tsystem/bin/zui_controld'
        protected = b'100644 blob ' + b'b'*40 + b'\tsystem/etc/init/zui_control.rc'
        baseline = parser + b'\n' + protected + b'\n'
        fingerprint = protected_payload(baseline)
        self.assertEqual(protected_payload(baseline.replace(b'a'*40, b'c'*40)), fingerprint)
        # Blob, mode, path, deletion and addition remain protected. Similar names
        # must not inherit the one exact-path exception.
        for changed in (baseline.replace(b'b'*40, b'c'*40),
                        parser+b'\n'+protected.replace(b'100644', b'100755')+b'\n',
                        baseline.replace(b'zui_control.rc', b'other.rc'),
                        parser+b'\n',
                        baseline+protected.replace(b'zui_control.rc', b'zui_controld.extra')+b'\n'):
            with self.subTest(changed=changed):
                self.assertNotEqual(protected_payload(changed), fingerprint)
        for changed in (protected+b'\n', baseline+parser+b'\n',
                        baseline.replace(parser, parser.replace(b'100644', b'120000'))):
            with self.subTest(invalid_parser=changed), self.assertRaises(AssertionError):
                protected_payload(changed)


if __name__ == '__main__':
    unittest.main(verbosity=2)
