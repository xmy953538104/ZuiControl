"""Current R3 bytes reverse to 4ccc; historical receipts remain immutable."""
from pathlib import Path
import hashlib,json,subprocess,sys,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import RUNTIME_R3,reverse_runtime_r3,reverse_runtime_r3_entries,entries
class RuntimeR3Scope(unittest.TestCase):
 def test_current_exact_overlay_and_mutation(self):
  for row in RUNTIME_R3['files']+RUNTIME_R3['testFiles']+RUNTIME_R3['supportFiles']:
   with self.subTest(path=row['path']):
    text=(ROOT/row['path']).read_text(encoding='utf8');self.assertEqual(hashlib.sha256(text.encode()).hexdigest(),row['afterSha256'])
    old=subprocess.run(['git','show',RUNTIME_R3['baseHead']+':'+row['path']],cwd=ROOT,capture_output=True)
    self.assertEqual(reverse_runtime_r3(row['path'],text),old.stdout.decode('utf8').replace('\r\n','\n') if old.returncode==0 else '')
    with self.assertRaisesRegex(AssertionError,'R3 exact authorized bytes'):reverse_runtime_r3(row['path'],text+'\n// outside scope\n')
 def test_native_entries_and_historical_manifests(self):
  expected=subprocess.check_output(['git','ls-tree','-r',RUNTIME_R3['baseHead'],'--','native/zuiopt'],cwd=ROOT).splitlines()
  self.assertEqual(sorted(reverse_runtime_r3_entries(entries('native/zuiopt'),'native/zuiopt')),sorted(expected))
  for path in ('runtime_delta.json','runtime_recovery_r2_delta.json','runtime_quarantine_r2_delta.json','runtime_frontend_r2_delta.json','runtime_library_inheritance_r2_delta.json'):
   old=subprocess.check_output(['git','show',RUNTIME_R3['baseHead']+':tests/zuiopt/'+path],cwd=ROOT)
   self.assertEqual((ROOT/'tests/zuiopt'/path).read_text(encoding='utf8'),old.decode('utf8').replace('\r\n','\n'))
 def test_ci_denominators_and_storage_boundary(self):
  coverage=json.loads((ROOT/'tests/zuiopt/ci_storage_coverage.json').read_text())
  self.assertEqual(coverage['authority']['total'],coverage['authority']['shards']*coverage['authority']['cases_per_shard'])
  self.assertEqual(coverage['lease']['total'],coverage['lease']['shards']*coverage['lease']['cases_per_shard'])
  workflow=(ROOT/'.github/workflows/build.yml').read_text()
  self.assertIn('--storage=disk --deterministic-only',workflow);self.assertIn('--storage=ram --stress-only --shard=',workflow)
  owner=(ROOT/'native/zuiopt/ZUIopt_owner.h').read_text();self.assertIn('fsync(fd)==0',owner);self.assertIn('fsync(dirFd)==0',owner)
if __name__=='__main__':unittest.main(verbosity=2)
