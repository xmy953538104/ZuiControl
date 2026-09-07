"""Runnable bounded P1.1 schema/import/transaction checks (stdlib only)."""
import io, json, tempfile, unittest, warnings, zipfile
from unittest.mock import patch
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts/rules'))
from ZUIOPT_rule_pack import *

BASE=b'schema 2\nenabled true\nprofile G 2-6\npackage exact org.example.game G 100\n'
OPEN=b'schema 2\nenabled true\nprofile G 2-7\nthread G ALL glob "Job.worker[AB]*" selector=all 30 2-4\nthread G R1 prefix Top selector=rank:1 20 7\nthread G R2 contains Runner selector=rank:2 10 5-6\npackage exact org.example.game G 100\n'

class Rules(unittest.TestCase):
    def test_future_recovered_selector_required(self):
        from ZUIOPT_convert_legacy_rules import convert
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'recovered.csv'
            rows=[dict(package='org.example.future',group='F01',affinity_mask='0x7c',static_confidence='STATIC_CONFIRMED',thread_pattern='<NO_THREAD_MATCH>',thread_priority='0',thread_match_type='SUBSTRING_STRSTR',declaration_status='ACTIVE_FOR_EXACT_INPUT',selector='all'),dict(package='org.example.future',group='F01',affinity_mask='0x80',static_confidence='STATIC_CONFIRMED',thread_pattern='Runner',thread_priority='1',thread_match_type='SUBSTRING_STRSTR',declaration_status='ACTIVE_FOR_EXACT_INPUT',selector='rank:2')]
            def save():
                with path.open('w',newline='') as f: w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
            save();text=convert(path,expected_counts=None,require_selectors=True)[0]
            self.assertEqual(parse_rules(text)['profiles']['F01']['rules'][0][3],2)
            rows[1]['selector']='';save()
            with self.assertRaises(AssertionError): convert(path,expected_counts=None,require_selectors=True)
    def test_roundtrip(self): self.assertEqual(parse_rules(OPEN),parse_rules(dump_rules(parse_rules(OPEN))))
    def test_source_parity(self):
        for source in ('asoul_binary','appopt','zuiopt_native','user_export'):
            m=manifest_for(OPEN,'test',source,binary_sha='a'*64,evidence='STATIC_RECOVERED')
            self.assertEqual(unpack(pack_bytes(m,OPEN)),(m,OPEN))
        expected=parse_rules(OPEN)['profiles']['G']
        for data in (merge(OPEN,[],b''),merge(BASE,[(manifest_for(OPEN,'test'),OPEN)],b''),merge(BASE,[],OPEN)):
            c=parse_rules(data);self.assertEqual(c['profiles'][c['packages'][0][2]],expected)
    def test_glob_valid(self):
        for p in ('*','?','a?b','[abc]','[a-z]','Job.worker*','[0-9][AB]?','x**y'): validate_glob(p)
    def test_glob_invalid(self):
        for p in ('','[','[]','[z-a]','[a-]','[!a]','[a','a]','\\*','a'*65):
            with self.subTest(p=p),self.assertRaises(ValueError): validate_glob(p)
    def test_rules_bounds(self):
        cases=[b'',b'#'*65537,BASE.replace(b'2-6',b'0-8'),BASE.replace(b'schema 2',b'schema 1'),BASE+b'profile G 1\n',BASE+b'package exact org.other G 100\n',BASE+b'package exact org.example.game G 99\n',OPEN.replace(b'selector=all',b'selector=rank:0'),OPEN.replace(b'selector=all',b'selector=rank:1025'),OPEN.replace(b'selector=all',b'auto'),OPEN.replace(b' 20 7',b' 30 7'),OPEN.replace(b' R2 ',b' R1 '),BASE+b'unknown true\n']
        cases+=[b'schema 2\nenabled true\n'+b''.join(f'profile G{n} 2-6\n'.encode() for n in range(65))]
        cases+=[BASE+b''.join(f'thread G R{n} exact t{n} selector=all {n} 7\n'.encode() for n in range(33))]
        cases+=[BASE+b''.join(f'package exact org.a{n} G {n+101}\n'.encode() for n in range(512))]
        for data in cases:
            with self.subTest(data=data[:100]),self.assertRaises(ValueError): parse_rules(data)
    def test_pack_deterministic(self):
        m=manifest_for(OPEN,'native');self.assertEqual(pack_bytes(m,OPEN),pack_bytes(m,OPEN))
    def test_pack_invalid(self):
        m=manifest_for(OPEN,'native')
        for k,v in [('schema_version',2),('schema_version',True),('pack_id','../bad'),('pack_version','next'),('source_type','guess'),('evidence_level','DEVICE_PROVEN'),('target_soc','OTHER'),('target_topology','0-3'),('package_count',10),('rules_sha256','0'*64),('pack_priority',1000001)]:
            bad=dict(m);bad[k]=v
            with self.subTest(key=k),self.assertRaises(ValueError): validate_manifest(bad,OPEN)
        for names in [('manifest.json','../rules.conf'),('manifest.json','/rules.conf'),('manifest.json','rules.conf','rules.conf'),('manifest.json','manifest.json')]:
            out=io.BytesIO()
            with warnings.catch_warnings():
                warnings.simplefilter('ignore')
                with zipfile.ZipFile(out,'w') as z:
                    for name in names: z.writestr(name,b'irrelevant')
            with self.assertRaises(ValueError): unpack(out.getvalue())
        with self.assertRaises(ValueError): unpack(b'0'*(MAX_PACK+1))
        with self.assertRaises(ValueError): strict_json(b'{"pack_id":"a","pack_id":"b"}')
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED) as z:
            z.writestr('manifest.json',json.dumps(m));z.writestr('rules.conf',b'0'*(MAX_RULES+1))
        with self.assertRaises(ValueError): unpack(out.getvalue())
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w') as z:
            z.writestr('manifest.json',json.dumps(m));info=zipfile.ZipInfo('rules.conf');info.external_attr=0o120777<<16;z.writestr(info,OPEN)
        with self.assertRaises(ValueError): unpack(out.getvalue())
    def test_appopt(self):
        data=appopt('org.example.game=0-6\norg.example.game{UnityMain}=7\norg.example.game{Job.worker*}=4-6\norg.example.game{Render?[a-z]}=2-4','appopt')
        m,r=unpack(data);self.assertEqual(m['source_type'],'appopt');c=parse_rules(r)
        self.assertEqual(len(c['profiles']['A000']['rules']),3);self.assertTrue(all(row[3]==0 for row in c['profiles']['A000']['rules']))
        for text in ('org.example.game{X}=7','org.example.game=0-8','org.example.game=0-7\nunknown extension','org.example.game=0-7\norg.example.game{[}=7','org.example.game=0-7\norg.example.game=2-6'):
            with self.assertRaises(ValueError): appopt(text,'appopt')
    def test_priority_layering(self):
        a=(manifest_for(OPEN,'a',priority=1),OPEN);b=(manifest_for(BASE,'b',priority=2),BASE)
        self.assertEqual(merge(BASE,[a,b],b''),merge(BASE,[b,a],b''))
        self.assertEqual(next(iter(parse_rules(merge(BASE,[a,b],b''))['profiles'].values()))['mask'],mask('2-6'))
        self.assertEqual(next(iter(parse_rules(merge(BASE,[a,b],OPEN))['profiles'].values()))['mask'],mask('2-7'))
        b[0]['pack_priority']=1
        with self.assertRaises(ValueError): merge(BASE,[a,b],b'')
        prefix=BASE.replace(b'package exact org.example.game',b'package prefix org.')
        with self.assertRaises(ValueError): merge(BASE,[a,(manifest_for(prefix,'b',priority=1),prefix)],b'')
        self.assertEqual(merge(BASE,[a,(manifest_for(OPEN,'c',priority=1),OPEN)],b''),merge(BASE,[(manifest_for(OPEN,'c',priority=1),OPEN),a],b''))
    def test_atomic_lkg_rollback_and_toggle(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=Store(Path(tmp)/'store',BASE);initial=store.apply('init');data=pack_bytes(manifest_for(OPEN,'test'),OPEN)
            disabled=store.apply('import',data);self.assertEqual(parse_rules(initial),parse_rules(disabled))
            enabled=store.apply('enable',pack_id='test');self.assertEqual(next(iter(parse_rules(enabled)['profiles'].values()))['mask'],mask('2-7'))
            with self.assertRaises(zipfile.BadZipFile): store.apply('import',b'bad')
            self.assertEqual(store.current()[1],enabled)
            user=store.apply('user',BASE);self.assertEqual(next(iter(parse_rules(user)['profiles'].values()))['mask'],mask('2-6'))
            self.assertEqual(store.apply('rollback'),enabled)
            before=store.current()[1]
            with patch.object(store,'activate',side_effect=OSError('injected pre-commit failure')):
                with self.assertRaises(OSError): store.apply('disable',pack_id='test')
            self.assertEqual(store.current()[1],before)
            after=store.apply('disable',pack_id='test');self.assertEqual(next(iter(parse_rules(after)['profiles'].values()))['mask'],mask('2-6'))
            self.assertLessEqual(len(list((store.root/'generations').iterdir())),2)

if __name__=='__main__': unittest.main(verbosity=2)
