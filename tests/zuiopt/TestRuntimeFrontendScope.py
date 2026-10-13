"""Exact Owner C/B overlay; unchanged scheduler and prior evidence stay immutable."""
from pathlib import Path
import subprocess,sys,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import FRONTEND_RUNTIME_R2,LIBRARY_INHERITANCE_R2,reverse_library_inheritance_r2,reverse_frontend_runtime_r2,reverse_frontend_runtime_entries,entries
class FrontendRuntimeScope(unittest.TestCase):
    def test_source_reversal_and_negative_mutation(self):
        for row in FRONTEND_RUNTIME_R2['files']:
            with self.subTest(path=row['path']):
                source=(ROOT/row['path']).read_text(encoding='utf8');old=subprocess.check_output(['git','-C',str(ROOT),'show',FRONTEND_RUNTIME_R2['baseHead']+':'+row['path']]).decode('utf8').replace('\r\n','\n')
                self.assertEqual(old,reverse_frontend_runtime_r2(row['path'],source))
                with self.assertRaisesRegex(AssertionError,'R2 (?:C/B|library inheritance) exact authorized bytes'):reverse_frontend_runtime_r2(row['path'],source+'\n// outside Owner delta\n')
        actual=entries('native/zuiopt');old=subprocess.check_output(['git','-C',str(ROOT),'ls-tree','-r',FRONTEND_RUNTIME_R2['baseHead'],'--','native/zuiopt']).splitlines()
        self.assertEqual(sorted(old),sorted(reverse_frontend_runtime_entries(actual,'native/zuiopt')))
    def test_execution_and_historical_manifests_unchanged(self):
        paths=['native/zuiopt/'+name for name in FRONTEND_RUNTIME_R2['unchanged_native_execution_files']]
        paths+=['tests/zuiopt/'+name for name in ('runtime_delta.json','runtime_recovery_r2_delta.json','runtime_quarantine_r2_delta.json')]
        for path in paths:
            self.assertEqual(reverse_library_inheritance_r2(path,(ROOT/path).read_text(encoding='utf8')),subprocess.check_output(['git','-C',str(ROOT),'show',FRONTEND_RUNTIME_R2['baseHead']+':'+path]).decode('utf8').replace('\r\n','\n'))
    def test_library_inheritance_overlay_preserves_prior_receipts(self):
        for row in LIBRARY_INHERITANCE_R2['files']+LIBRARY_INHERITANCE_R2['testFiles']+LIBRARY_INHERITANCE_R2['supportFiles']:
            with self.subTest(path=row['path']):
                source=(ROOT/row['path']).read_text(encoding='utf8')
                old=subprocess.check_output(['git','-C',str(ROOT),'show',LIBRARY_INHERITANCE_R2['baseHead']+':'+row['path']]).decode('utf8').replace('\r\n','\n')
                self.assertEqual(old,reverse_library_inheritance_r2(row['path'],source))
                with self.assertRaisesRegex(AssertionError,'R2 library inheritance exact authorized bytes'):reverse_library_inheritance_r2(row['path'],source+'\n// unauthorized\n')
        path='tests/zuiopt/runtime_frontend_r2_delta.json'
        self.assertEqual((ROOT/path).read_text(encoding='utf8'),subprocess.check_output(['git','-C',str(ROOT),'show',LIBRARY_INHERITANCE_R2['baseHead']+':'+path]).decode('utf8').replace('\r\n','\n'))
if __name__=='__main__':unittest.main(verbosity=2)
