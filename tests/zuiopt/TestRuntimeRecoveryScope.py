"""R2 reversal remains exact and rejects extra native bytes; old R1 is immutable."""
from pathlib import Path
import subprocess,sys,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import RECOVERY_R2,reverse_recovery_r2
class RuntimeRecoveryScope(unittest.TestCase):
    def test_each_bound_source_matches_base_and_rejects_mutation(self):
        for row in RECOVERY_R2['files']:
            with self.subTest(path=row['path']):
                current=(ROOT/row['path']).read_text(encoding='utf8')
                baseline=subprocess.check_output(['git','-C',str(ROOT),'show',RECOVERY_R2['baseHead']+':'+row['path']]).decode('utf8').replace('\r\n','\n')
                self.assertEqual(reverse_recovery_r2(row['path'],current),baseline)
                with self.assertRaisesRegex(AssertionError,'R2 exact authorized bytes'):
                    reverse_recovery_r2(row['path'],current+'\n// unauthorized\n')
    def test_historical_runtime_manifest_is_unchanged(self):
        historical=subprocess.check_output(['git','-C',str(ROOT),'show',RECOVERY_R2['baseHead']+':tests/zuiopt/runtime_delta.json'])
        self.assertEqual((ROOT/'tests/zuiopt/runtime_delta.json').read_bytes(),historical)
if __name__=='__main__':unittest.main(verbosity=2)
