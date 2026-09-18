"""Parameter-only payload contract; no device required."""
from pathlib import Path
import hashlib
import json
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
        # Product R2 only authorizes the named GPU model/UI/service files.
        # TerminalContracts reverses exact service deltas to the old hash.
        expected = {'native': '6790c7cf6c634b28b0360911686d603bc8616917',
                    'payload': 'bdd42b192c8ac901d008d47892547b6e55fa8e10',
                    'upstream': '3dc065f74510060d3612ab3bf805ed305f2e447d'}
        for path, tree in expected.items():
            actual = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD:'+path], text=True).strip()
            self.assertEqual(actual, tree, path)
        dirty = subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain', '--', *expected], text=True)
        self.assertEqual(dirty, '')
        allowed = {'app/src/main/java/com/zui/zuicontrol/MainActivity.kt',
                   'app/src/main/java/com/zui/zuicontrol/ZuiControlClient.kt',
                   'app/src/main/java/com/zui/zuicontrol/GpuRanges.kt',
                   'app/src/main/java/com/zui/zuicontrol/GpuRangeBar.kt',
                   'app/src/test/java/com/zui/zuicontrol/GpuRangesTest.kt',
                   'framework_patch/src/framework/android/zui/ZuiControlManager.java',
                   'framework_patch/src/services/com/zui/server/control/GpuRange.java',
                   'framework_patch/src/services/com/zui/server/control/GpuPolicyController.java',
                   'framework_patch/src/services/com/zui/server/control/ZuiControlService.java'}
        entries = subprocess.check_output(['git','-C',str(ROOT),'ls-tree','-r','HEAD','--','app','framework_patch']).splitlines()
        # Reverse the exact Owner-authorized monitor tree delta; keep the old hash.
        monitor = json.loads((ROOT/'tests/monitor/production_delta.json').read_text(encoding='utf-8'))
        for delta in monitor['tree']:
            self.assertEqual(entries.count(delta['after'].encode()), 1, delta['path'])
            entries.remove(delta['after'].encode())
            if delta['before'] is not None:
                entries.append(delta['before'].encode())
        entries.sort(key=lambda line: line.split(b'\t',1)[1])
        frozen = [line for line in entries if line.split(b'\t',1)[1].decode() not in allowed]
        self.assertEqual(hashlib.sha256(b'\n'.join(frozen)).hexdigest(),
                         '038e79b1749067c66557a1db9dac1f7f841a554bd7dbb481a3b0ccac1ba9a11d')
        changed = subprocess.check_output(['git','-C',str(ROOT),'diff','--name-only',
            'HEAD','--','app','framework_patch'],text=True).splitlines()
        untracked = subprocess.check_output(['git','-C',str(ROOT),'ls-files','--others',
            '--exclude-standard','--','app','framework_patch'],text=True).splitlines()
        self.assertLessEqual(set(changed+untracked), allowed | {d['path'] for d in monitor['tree']})
        source = (ROOT/'scripts/build/ApplyZuiControlPayload.py').read_text(encoding='utf-8')
        self.assertIn('patch_oem_touch_timer(unpack, args.dry_run, report)', source)


if __name__ == '__main__':
    unittest.main(verbosity=2)
