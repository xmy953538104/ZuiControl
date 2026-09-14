"""Parameter-only payload contract; no device required."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/build'))
from ApplyZuiControlPayload import patch_oem_touch_timer

KEY = b'ro.surface_flinger.set_touch_timer_ms='
IDLE_KEY = b'ro.surface_flinger.set_idle_timer_ms='
PATHS = ('system_a/system/build.prop', 'vendor_a/build.prop')
PROPERTIES = (b'ro.surface_flinger.set_idle_timer_ms=2000\n'
              b'ro.surface_flinger.set_refresh_timer_ms=200\n'
              b'ro.surface_flinger.set_launcher_timer_ms=30000\n'
              b'ro.surface_flinger.set_pen_timer_ms=10000\n'
              b'ro.surface_flinger.use_content_detection_for_refresh_rate=true\n'
              b'ro.config.lgsi.low_power_mode_require_fps=60\n'
              b'ro.config.lgsi.reading_mode_require_fps=30\n'
              b'# thermal and all unrelated bytes stay unchanged\n')


class OemTouchTimer(unittest.TestCase):
    def fixture(self, root, endings=b'\n'):
        original = {}
        for relative in PATHS:
            p = root / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            properties = PROPERTIES if relative == PATHS[0] else PROPERTIES.replace(IDLE_KEY+b'2000\n', b'')
            data = (b'# OEM\n' + KEY + b'2000\n' + properties).replace(b'\n', endings)
            p.write_bytes(data)
            original[relative] = data
        for relative in ('system_a/system/bin/surfaceflinger',
                         'system_a/system/lib64/libSurfaceFlingerProp.so'):
            p = root / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'unchanged native input\x00\xff')
            original[relative] = p.read_bytes()
        return original

    def test_exact_parameter_values_dry_run_and_idempotence(self):
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
                    if n == PATHS[0]:
                        expected = expected.replace(IDLE_KEY+b'2000', IDLE_KEY+b'7000')
                    self.assertEqual((root/n).read_bytes(), expected, n)
                self.assertTrue(all(row['new'] == '5000' for row in report['oem_touch_timer']))
                self.assertEqual(report['oem_idle_timer']['new'], '7000')
                patch_oem_touch_timer(root, False, report)
                self.assertTrue(all(not row['changed'] for row in report['oem_touch_timer']))
                self.assertFalse(report['oem_idle_timer']['changed'])

    def test_only_system_idle_changes_from_touch5000_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            for n in PATHS:
                p = root/n
                p.write_bytes(p.read_bytes().replace(KEY+b'2000', KEY+b'5000'))
            before = {n: (root/n).read_bytes() for n in PATHS}
            patch_oem_touch_timer(root, False, {})
            self.assertEqual((root/PATHS[0]).read_bytes(), before[PATHS[0]].replace(IDLE_KEY+b'2000', IDLE_KEY+b'7000'))
            self.assertEqual((root/PATHS[1]).read_bytes(), before[PATHS[1]])
            self.assertNotIn(IDLE_KEY, (root/PATHS[1]).read_bytes())

    def test_idle_authority_errors_reject_before_any_write(self):
        for relative, old, new in (
                (PATHS[0], IDLE_KEY+b'2000\n', b''),
                (PATHS[0], IDLE_KEY+b'2000\n', IDLE_KEY+b'3000\n'),
                (PATHS[0], IDLE_KEY+b'2000\n', (IDLE_KEY+b'2000\n')*2),
                (PATHS[1], b'# OEM\n', b'# OEM\n'+IDLE_KEY+b'2000\n'),
                (PATHS[1], b'# OEM\n', b'# OEM\n  '+IDLE_KEY+b'7000\n')):
            with self.subTest(path=relative, new=new), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.fixture(root)
                p = root/relative
                p.write_bytes(p.read_bytes().replace(old, new))
                before = {n: (root/n).read_bytes() for n in PATHS}
                with self.assertRaises(SystemExit):
                    patch_oem_touch_timer(root, False, {})
                self.assertEqual({n: (root/n).read_bytes() for n in PATHS}, before)

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
        # Accepted runtime trees are identical; no Java listener/timer, native or payload edits.
        expected = {'app': 'b8058f77ce699fdd7f6d9470c4329e6ed1a6dd36',
                    'framework_patch': 'e54609edf29df83d8ad9a2c5dc01f41b108bb53c',
                    'native': '6790c7cf6c634b28b0360911686d603bc8616917',
                    'payload': 'bdd42b192c8ac901d008d47892547b6e55fa8e10',
                    'upstream': '3dc065f74510060d3612ab3bf805ed305f2e447d'}
        for path, tree in expected.items():
            actual = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD:'+path], text=True).strip()
            self.assertEqual(actual, tree, path)
        dirty = subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain', '--', *expected], text=True)
        self.assertEqual(dirty, '')
        source = (ROOT/'scripts/build/ApplyZuiControlPayload.py').read_text(encoding='utf-8')
        self.assertIn('patch_oem_touch_timer(unpack, args.dry_run, report)', source)


if __name__ == '__main__':
    unittest.main(verbosity=2)
