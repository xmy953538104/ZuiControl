"""Explicit legacy reader stays outside all normal rules/backup paths."""
from pathlib import Path
import sys,unittest,json
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts/rules'))
from MigrateSchema2Backup import migrate,migrate_rules,settings_zip,canonical,NAMES
from ZUIOPT_rule_pack import digest,parse_rules
OLD=b'schema 2\nenabled true\nprofile P 2-6\nthread P C2 exact Render selector=rank:2 20 7\npackage contains com.example P 101\n'
class MigrationTests(unittest.TestCase):
 def test_format_only_inheritance_and_explicit_masks(self):
  new=migrate_rules(OLD);c=parse_rules(new);self.assertEqual(c['profiles']['P']['mask'],124);self.assertEqual(c['profiles']['P']['runtime'],(-1,-1,'explicit','none'));self.assertEqual(c['profiles']['P']['rules'][0][3:],(2,20,128));self.assertEqual(migrate_rules(new),new)
  with self.assertRaises(ValueError):parse_rules(OLD)
  with self.assertRaises(ValueError):migrate_rules(OLD+b'runtime_defaults 1 1\n')
 def test_settings_hashes_preserve_other_owners_and_provenance(self):
  entries=dict(zip(NAMES[1:],[b'{"generation":44}',OLD,b'{"circle_x":0.25}']))
  m=dict(kind='zui.settings.backup',schemaVersion=1,zuioptSchema=2,targetSoC='SM8650',configGeneration={'rules':'g'+'1'*24},entries=[dict(name=n,bytes=len(entries[n]),sha256=digest(entries[n])) for n in NAMES[1:]])
  m['manifestHash']=digest(canonical(m));entries['manifest.json']=canonical(m);old=settings_zip(entries)
  new,receipt=migrate(old);self.assertNotEqual(new,old);self.assertEqual(migrate(new)[0],new);self.assertTrue(receipt['policy_and_preferences_unchanged'])
  import io,zipfile
  with zipfile.ZipFile(io.BytesIO(new)) as z:
   for n in ['policy.json','preferences.json']:self.assertEqual(z.read(n),entries[n])
   mm=json.loads(z.read('manifest.json'));self.assertEqual(mm['zuioptSchema'],3);self.assertEqual(mm['configGeneration'],m['configGeneration'])
  bad=dict(entries);bad['zuiopt.canonical.conf']=OLD.replace(b'2-6',b'0-7')
  with self.assertRaises(ValueError):migrate(settings_zip(bad))
  with self.assertRaises(ValueError):migrate(old+b'extra')
if __name__=='__main__':unittest.main(verbosity=2)
