from pathlib import Path
import sys,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts/rules'))
from ZUIOPT_rule_pack import parse_rules,dump_rules,merge
OLD=b'schema 2\nenabled true\nprofile Old 0-7\nthread Old C2 contains Work selector=rank:2 10 5-6\npackage exact org.example.old Old 100\n'
NEW=b'schema 3\nenabled true\nruntime_defaults 1 1\nprofile New 2-6\nruntime New -1 0 preset double\nthread New C2 contains Work selector=rank:1 10 7\npackage exact org.example.new New 100\n'
class RuntimeSchema(unittest.TestCase):
 def test_old_unchanged(self):
  old=parse_rules(OLD);self.assertNotIn('schema',old);self.assertNotIn('runtime',old['profiles']['Old']);self.assertEqual(old,parse_rules(dump_rules(old)))
 def test_new_roundtrip(self):
  c=parse_rules(NEW);self.assertEqual(c['mode'],1);self.assertEqual(c['rt'],1);self.assertEqual(c['profiles']['New']['runtime'],(-1,0,'preset','double'));self.assertEqual(c,parse_rules(dump_rules(c)))
 def test_invalid(self):
  for text in [NEW+b'runtime_defaults 0 0\n',NEW+b'runtime New 0 0 explicit none\n',OLD+b'runtime_defaults 0 0\n',NEW.replace(b'-1 0 preset',b'3 0 preset'),NEW.replace(b'preset double',b'explicit double'),NEW+b'opt 1\n']:
   with self.subTest(text=text),self.assertRaises(ValueError):parse_rules(text)
 def test_merge_inheritance_resolved_without_old_rule_change(self):
  c=parse_rules(merge(OLD,[],NEW));self.assertEqual(c['profiles']['p0000']['runtime'],(1,0,'preset','double'));self.assertEqual(c['profiles']['p0001']['mask'],255);self.assertEqual(c['profiles']['p0001']['rules'][0][3],2)
if __name__=='__main__':unittest.main()
