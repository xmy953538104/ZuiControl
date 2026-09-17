"""Host contracts for exact-scope GPU proof; no production device operations."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'scripts/build'))
from GpuProofTransforms import gamepower, performance, FILTER_ENTRY
from BuildGpuFramework import canonical_defaults


class GpuProof(unittest.TestCase):
    def test_dex_default_equivalence_is_narrow(self):
        self.assertEqual(canonical_defaults(b'.field static x:Z = false\n'), b'.field static x:Z\n')
        self.assertEqual(canonical_defaults(b'.field static x:I = 0x0\n'), b'.field static x:I\n')
        self.assertEqual(canonical_defaults(b'.field static x:Ljava/lang/Object; = null\n'), b'.field static x:Ljava/lang/Object;\n')
        self.assertEqual(canonical_defaults(b'.method public whitelist f()V\n'), b'.method public f()V\n')
        for value in [b'.field static x:Z = true\n', b'.field static x:I = 0x1\n',
                      b'.field x:Z = false\n', b'.method public blacklist f()V\n',
                      b'    const-string v0, " whitelist "\n']:
            self.assertEqual(canonical_defaults(value), value)

    def test_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            log = tmp/'android/util/Log.java'
            log.parent.mkdir(parents=True)
            log.write_text('package android.util; public class Log { public static int w(String t,String s){return 0;} }')
            subprocess.run(['javac', '-d', str(tmp), str(log),
                str(ROOT/'framework_patch/src/framework_gpu/android/zui/GpuRequestFilter.java'),
                str(ROOT/'framework_patch/src/services/com/zui/server/control/GpuPolicyController.java'),
                str(ROOT/'framework_patch/src/services/com/zui/server/control/GpuRange.java'),
                str(Path(__file__).with_name('GpuProofTest.java'))], check=True)
            subprocess.run(['java', '-cp', str(tmp), 'com.zui.server.control.GpuProofTest'], check=True)

    def test_gamepower_exact_bytes(self):
        original = b'<Feature><Profiles>' + b''.join(
            b'<Profile'+a+b'><CPU>1,2</CPU><GPU_FREQ_MAX_Enable>1</GPU_FREQ_MAX_Enable><GPU_FREQ_MIN_Enable>0</GPU_FREQ_MIN_Enable></Profile>'
            for a in [b' target="cliffs"', b'']) + b'</Profiles></Feature>'
        out, n = gamepower(original)
        self.assertEqual(n, 2)
        self.assertEqual(out, original.replace(b'>1</GPU_FREQ_MAX_Enable>', b'>0</GPU_FREQ_MAX_Enable>'))
        self.assertEqual(gamepower(out), (out, 0))
        with self.assertRaises(ValueError): gamepower(original.replace(b'>1</GPU_FREQ_MAX_Enable>', b'>2</GPU_FREQ_MAX_Enable>', 1))

    def test_performance_exact_bytes(self):
        pair = b'<PerfLockConfig code = "0x4280C000" param = "903000"/><PerfLockConfig code = "0x42804000" param = "0"/>'
        items = b''.join(b'<Item name="'+n+b'">'+pair+b'<PerfLockConfig code="0x40800000" param="7"/></Item>' for n in [b'Full', b'GPUTest'])
        original = b'<Root>'+items+b'<Type name="GPU"><Freq level="1">9_5_-1</Freq><Freq level="2">-1_-1_-1</Freq></Type><CPU>900</CPU></Root>'
        out, report = performance(original)
        self.assertEqual(out, original.replace(pair, b'').replace(b'9_5_-1', b'-1_-1_-1'))
        self.assertEqual(report, dict(tuple_changes=1, independent_resources_removed=4))
        self.assertEqual(performance(out)[0], out)
        with self.assertRaises(ValueError): performance(original.replace(b'9_5_-1', b'BAD'))
        with self.assertRaises(ValueError): performance(original.replace(b'level="2"', b'level="1"'))

    def test_wrapper_edges(self):
        # Source-bound injection: non-target branches before array handling; no zero lock.
        self.assertLess(FILTER_ENTRY.index('if-eqz v0, :zui_gpu_original'), FILTER_ENTRY.index('->filter('))
        self.assertIn('if-eqz p2, :zui_gpu_no_acquire', FILTER_ENTRY)
        self.assertIn('if-nez v0, :zui_gpu_original', FILTER_ENTRY)
        self.assertIn('->perfLockRelease()I', FILTER_ENTRY)
        self.assertIn('const/4 v0, -0x1\n    return v0', FILTER_ENTRY)
        self.assertNotIn('getCallingUid', FILTER_ENTRY)


if __name__ == '__main__': unittest.main()
