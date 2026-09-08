"""Linux root filesystem fixtures execute the actual deletion/rollback generators.

Product UI/ADB transport are mocked; these tests do not claim device qualification.
"""
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zlib
import retirement as R
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tests/zuiopt'))
from RuntimePurityAudit import inspect,patterns

CONFIG=b'# Shiroko A-SOUL: hard affinity + WALT per-task boost, no real-time policy.\nmode=0\nrt=0\nopt=0xDEADBEEF\n'

class PurePolicy(unittest.TestCase):
    def test_only_authorized_core_and_provenance_delta(self):
        repo=Path(__file__).resolve().parents[1]
        core=(repo/'native/zuiopt/ZUIopt_core.h').read_text()
        restored=core.replace('&&s.find("/ZUIopt")==s.npos','&&s.find("/ZUIopt")==s.npos&&s.find("/asopt")==s.npos').replace('!normalGroup("/../unsafe")','!normalGroup("/asopt/7c")')
        self.assertEqual(R.digest(restored.encode()),'12766b596a1dd5fd7fbc8e6b83708b815e334a2a6d70bf8a6b89a201ac7f34df')
        for name,expected in [('native/zuiopt/ZUIopt_rules.h','b3ce2f0737bfb948fa81cb80ec8bf7552a45ce7a751908b727e8b277bc790973'),('scripts/rules/ZUIOPT_rule_pack.py','68893bee028ddb0c2115b1877576528ac64fb1e810acbe70f9a6ff0b2ab591f1')]:
            text=(repo/name).read_text().replace('recovered_binary','asoul_binary').replace('recovered provenance','asoul provenance')
            self.assertEqual(R.digest(text.encode()),expected,name)
    def test_provenance_only_repack_preserves_execution(self):
        import types
        repo=Path(__file__).resolve().parents[1];sys.path.insert(0,str(repo/'scripts/rules'))
        import ZUIOPT_rule_pack as current
        old=types.ModuleType('proven_reference_parser')
        source=(repo/'scripts/rules/ZUIOPT_rule_pack.py').read_text().replace('recovered_binary','asoul_binary').replace('recovered provenance','asoul provenance')
        self.assertEqual(R.digest(source.encode()),'68893bee028ddb0c2115b1877576528ac64fb1e810acbe70f9a6ff0b2ab591f1')
        exec(compile(source,'<hash-bound-reference-parser>','exec'),old.__dict__)
        rules=(repo/'payload/system/etc/zuiopt/factory_rules.conf').read_bytes()
        manifest=old.manifest_for(rules,'recovered','asoul_binary',binary_sha='a'*64,evidence='STATIC_RECOVERED',priority=17)
        old_manifest,old_rules=old.unpack(old.pack_bytes(manifest,rules))
        new_manifest=dict(old_manifest,source_type='recovered_binary')
        new_manifest,new_rules=current.unpack(current.pack_bytes(new_manifest,old_rules))
        self.assertEqual(old_rules,new_rules)
        self.assertEqual({k:v for k,v in old_manifest.items() if k!='source_type'},{k:v for k,v in new_manifest.items() if k!='source_type'})
        self.assertEqual(old.parse_rules(old_rules),current.parse_rules(new_rules))
        for enabled in (False,True):
            self.assertEqual(old.merge(rules,[(old_manifest,old_rules)] if enabled else [],b''),current.merge(rules,[(new_manifest,new_rules)] if enabled else [],b''))
    def test_known_terms_and_generic_identifiers(self):
        p=patterns()
        for value in ('/system/bin/AsoulOpt','source_type=asoul_binary','start_asoul','asoulServiceState','/asopt','/asopt/7c','/data/vendor/asopt.conf','next_owner.v1','zuiopt_legacy_migration.sh'):
            self.assertTrue(inspect('fixture',value.encode(),p),value)
        for value in ('setHasOptionsMenu','HasOptimized','ZUIopt','Android default','getAndIncrementNextOwnerId','group_next_owner',' nextOwner:'):
            self.assertFalse(inspect('fixture',value.encode(),p),value)
    def test_utf16_string_pool(self):
        self.assertTrue(inspect('pool','start_asoul'.encode('utf-16le'),patterns()))
    def test_root_uses_child_receipt_not_su_status(self):
        import inspect as source
        body=source.getsource(R.Device.root)
        self.assertIn('su',body);self.assertIn("'-c'",body)
        self.assertIn('root child failed or incomplete output',body)
        self.assertNotIn("'0'",body)

class FixtureDevice(R.Device):
    def __init__(self,root):
        self.local=root;self.running=True;self.fail_after_remove=False;self.fail_boundary=None;self.boot='same-boot';self.events=[]
    def path(self,name):return self.local/name.lstrip('/')
    def root(self,command):
        command=command.replace('/data',str(self.local/'data'))
        if self.fail_after_remove and command.startswith('set -eu'):
            self.fail_after_remove=False
            command=command.replace('; rm ','; false; rm ',1) if '; rm ' in command else command
        if self.fail_boundary is not None and command.startswith('set -eu'):
            pieces=command.split('; ');indices=[i for i,s in enumerate(pieces) if s.startswith(('rm ','rmdir '))]
            pieces.insert(indices[self.fail_boundary]+1,'false');command='; '.join(pieces);self.fail_boundary=None
        p=subprocess.run(['sh','-c','set -eu; '+command],capture_output=True,text=True)
        R.need(p.returncode==0,'fixture root failure '+p.stderr)
        # Preserve Android absolute targets observed through readlink.
        return p.stdout.replace(str(self.local/'data'),'/data')
    def identity(self):return dict(serial=R.SERIAL,model='TB321FU',build='16.1.11.072',boot_id=self.boot)
    def rules(self):
        root=self.path('/data/vendor/zui_control/zuiopt')
        rows={p.relative_to(root).as_posix():R.digest(p.read_bytes()) for p in root.rglob('*') if p.is_file() and p.name!='next_owner.v1'}
        return b'fixture-backup',rows
    def service_running(self):return self.running
    def product_toggle(self,enable):self.events.append(('authenticated_ui',enable));self.running=enable
    def released(self):R.need(not self.running,'mock tasks still managed')

@unittest.skipUnless(os.name=='posix' and os.geteuid()==0,'Linux root UID/mode fixtures require CI')
class Transaction(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='zuiopt-retirement-');self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.device=FixtureDevice(self.root)
        for name,identity in R.ANCESTORS.items():
            p=self.device.path(name);p.mkdir(parents=True,exist_ok=True)
            uid,gid,mode=identity.split(':');os.chown(p,int(uid),int(gid));p.chmod(int(mode,8))
        directory=self.device.path(R.DIRECTORY);directory.mkdir();os.chown(directory,0,2000);directory.chmod(0o775)
        config=self.device.path(R.DIRECTORY+'/asopt.conf');config.write_bytes(CONFIG);os.chown(config,0,2000);config.chmod(0o644)
        self.device.path(R.LINK).symlink_to(config)
        selector=self.device.path(R.SELECTOR);body=b'ASOULOPT\n';selector.write_bytes(b'ZUIOPT_NEXT_OWNER_V1 '+str(zlib.crc32(body)).encode()+b'\n'+body);selector.chmod(0o600)
        rules=self.device.path('/data/vendor/zui_control/zuiopt/effective.conf');rules.write_bytes(b'schema 2\nenabled true\n');rules.chmod(0o600)
        for name in ('failure.v1','crashes.v1','owner_state.v1'):(rules.parent/name).write_bytes(b'preserved')
        other=self.device.path('/data/vendor/zui_control/uperf');other.mkdir();(other/'keep').write_bytes(b'unrelated')
        self.transaction=self.root/'host';self.transaction.mkdir()
    def review(self):
        d=self.device
        value=dict(identity=d.identity(),paths=d.inventory(),rules=d.rules()[1],was_running=d.service_running())
        R.persist(self.transaction/'inventory.json',value)
        return R.digest((self.transaction/'inventory.json').read_bytes())
    def test_exact_delete_and_rollback(self):
        before=self.device.inventory();rules=self.device.rules()[1]
        R.retire(self.device,self.transaction,self.review())
        self.assertTrue((self.transaction/'ready_for_flash.json').is_file())
        self.assertTrue(all(v is None for v in self.device.inventory().values()))
        R.rollback(self.device,self.transaction)
        self.assertEqual(self.device.inventory(),before);self.assertEqual(self.device.rules()[1],rules)
        self.assertEqual(self.device.events,[('authenticated_ui',False),('authenticated_ui',True)])
        self.assertEqual(self.device.path('/data/vendor/zui_control/uperf/keep').read_bytes(),b'unrelated')
    def test_failure_after_freeze_restores_mode_and_service(self):
        before=self.device.inventory();approved=self.review();self.device.fail_after_remove=True
        with self.assertRaises(RuntimeError):R.retire(self.device,self.transaction,approved)
        self.assertEqual(self.device.inventory(),before);self.assertTrue(self.device.running)
        self.assertTrue(list(self.transaction.glob('rollback_*.json')))
    def test_failure_after_each_mutation_restores_exact_objects(self):
        p=self.device.path(R.DIRECTORY+'/asopt.conf.tmp');p.write_bytes(CONFIG);os.chown(p,0,2000);p.chmod(0o644)
        for boundary in range(5):
            with self.subTest(boundary=boundary):
                self.transaction=self.root/('boundary-'+str(boundary));self.transaction.mkdir()
                before=self.device.inventory();approved=self.review();self.device.fail_boundary=boundary
                with self.assertRaises(RuntimeError):R.retire(self.device,self.transaction,approved)
                self.assertEqual(self.device.inventory(),before);self.assertTrue(self.device.running)
                self.assertTrue(list(self.transaction.glob('rollback_*.json')))
    def test_restore_rejects_foreign_bytes_without_chmod(self):
        before=self.device.inventory();p=self.device.path(R.DIRECTORY+'/asopt.conf')
        p.write_bytes(b'foreign');p.chmod(0o400)
        with self.assertRaises(RuntimeError):self.device.restore(before)
        self.assertEqual(p.read_bytes(),b'foreign');self.assertEqual(p.stat().st_mode & 0o777,0o400)
    def test_refuse_unknown_child(self):
        self.device.path(R.DIRECTORY+'/unknown').write_text('keep')
        with self.assertRaises(RuntimeError):self.device.inventory()
    def test_refuse_wrong_link(self):
        p=self.device.path(R.LINK);p.unlink();p.symlink_to('/tmp/foreign')
        with self.assertRaises(RuntimeError):self.device.inventory()
    def test_refuse_regular_link_path(self):
        p=self.device.path(R.LINK);p.unlink();p.write_text('keep')
        with self.assertRaises(RuntimeError):self.device.inventory()
    def test_refuse_config_hardlink(self):
        os.link(self.device.path(R.DIRECTORY+'/asopt.conf'),self.root/'external')
        with self.assertRaises(RuntimeError):self.device.inventory()
    def test_refuse_config_symlink(self):
        p=self.device.path(R.DIRECTORY+'/asopt.conf');p.unlink();p.symlink_to('/tmp/foreign')
        with self.assertRaises(RuntimeError):self.device.inventory()
    def test_refuse_wrong_bytes(self):
        self.device.path(R.DIRECTORY+'/asopt.conf').write_bytes(b'custom')
        with self.assertRaises(RuntimeError):self.device.inventory()
    def test_refuse_foreign_owner(self):
        os.chown(self.device.path(R.DIRECTORY),2000,2000)
        with self.assertRaises(RuntimeError):self.device.inventory()
    def test_refuse_bad_selector(self):
        self.device.path(R.SELECTOR).write_bytes(b'invalid')
        with self.assertRaises(RuntimeError):self.device.inventory()
    def test_refuse_changed_boot_rollback(self):
        R.retire(self.device,self.transaction,self.review());self.device.boot='changed'
        with self.assertRaises(RuntimeError):R.rollback(self.device,self.transaction)
    def test_refuse_tampered_backup(self):
        R.retire(self.device,self.transaction,self.review());(self.transaction/'backup.json').write_text('{}')
        with self.assertRaises(RuntimeError):R.rollback(self.device,self.transaction)
    def test_refuse_wrong_approval_before_stop(self):
        self.review()
        with self.assertRaises(RuntimeError):R.retire(self.device,self.transaction,'0'*64)
        self.assertTrue(self.device.running);self.assertEqual(self.device.events,[])
    def test_approved_temp(self):
        p=self.device.path(R.DIRECTORY+'/asopt.conf.tmp');p.write_bytes(CONFIG);os.chown(p,0,2000);p.chmod(0o644)
        self.test_exact_delete_and_rollback()

if __name__=='__main__':unittest.main(verbosity=2)
