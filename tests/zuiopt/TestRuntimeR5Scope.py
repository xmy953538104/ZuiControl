"""Exact current R5 delta reverses to d54; historical overlays stay immutable."""
from pathlib import Path
import hashlib,subprocess,sys,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import RUNTIME_R5,reverse_runtime_r5,reverse_runtime_r5_entries,entries
class RuntimeR5Scope(unittest.TestCase):
 def test_reverse_bytes_and_entries(self):
  for row in RUNTIME_R5['files']:
   with self.subTest(path=row['path']):
    text=(ROOT/row['path']).read_text(encoding='utf8')
    old=subprocess.run(['git','show',RUNTIME_R5['baseHead']+':'+row['path']],cwd=ROOT,capture_output=True)
    self.assertEqual(reverse_runtime_r5(row['path'],text),old.stdout.decode('utf8').replace('\r\n','\n') if old.returncode==0 else '')
    with self.assertRaisesRegex(AssertionError,'(?:R[56]|RESIDENCY_R5|ADVICE_R4|POST_BASELINE|POWER_PORT_R3) exact authorized bytes'):reverse_runtime_r5(row['path'],text+'\n// outside scope\n')
  expected=subprocess.check_output(['git','ls-tree','-r',RUNTIME_R5['baseHead'],'--','native/zuiopt'],cwd=ROOT).splitlines()
  self.assertEqual(sorted(reverse_runtime_r5_entries(entries('native/zuiopt'),'native/zuiopt')),sorted(expected))
 def test_historical_overlays_unchanged(self):
  for p in (ROOT/'tests/zuiopt').glob('*delta.json'):
   if p.name=='runtime_r5_delta.json':continue
   old=subprocess.run(['git','show',RUNTIME_R5['baseHead']+':'+p.relative_to(ROOT).as_posix()],cwd=ROOT,capture_output=True)
   if old.returncode==0:self.assertEqual(p.read_text(encoding='utf8'),old.stdout.decode('utf8').replace('\r\n','\n'))
if __name__=='__main__':unittest.main(verbosity=2)
