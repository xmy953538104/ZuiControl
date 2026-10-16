"""Exact response delta; old contracts and recovery manifests remain immutable."""
from pathlib import Path
import subprocess,sys,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests'))
from BackendContract import RUNTIME_R6,reverse_runtime_r6
class RuntimeR6Scope(unittest.TestCase):
 def test_current_and_negative_delta(self):
  for row in RUNTIME_R6['files']:
   with self.subTest(path=row['path']):
    text=(ROOT/row['path']).read_text(encoding='utf8')
    previous=subprocess.run(['git','show',RUNTIME_R6['baseHead']+':'+row['path']],cwd=ROOT,capture_output=True)
    self.assertEqual(reverse_runtime_r6(row['path'],text),previous.stdout.decode().replace('\r\n','\n') if previous.returncode==0 else '')
    with self.assertRaisesRegex(AssertionError,'(?:R6|RESIDENCY_R5|ADVICE_R4|POST_BASELINE|POWER_PORT_R3) exact authorized bytes'):reverse_runtime_r6(row['path'],text+'\n// outside scope\n')
 def test_historical_manifests(self):
  for path in (ROOT/'tests/zuiopt').glob('*delta.json'):
   if path.name=='runtime_r6_delta.json':continue
   old=subprocess.run(['git','show',RUNTIME_R6['baseHead']+':'+path.relative_to(ROOT).as_posix()],cwd=ROOT,capture_output=True)
   if old.returncode==0:self.assertEqual(path.read_text(encoding='utf8'),old.stdout.decode('utf8').replace('\r\n','\n'))
if __name__=='__main__':unittest.main(verbosity=2)
