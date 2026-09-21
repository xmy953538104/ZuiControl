"""Package path/version must advance together; reject mixed cache identities."""
from pathlib import Path
import sys,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/build'))
from ApplyZuiControlPayload import APP_APK_PATH,copy_payload

class PackageIdentity(unittest.TestCase):
    def test_identity_bindings(self):
        self.assertEqual(APP_APK_PATH,'system/priv-app/ZuiControlV67/ZuiControl.apk')
        gradle=(ROOT/'app/build.gradle.kts').read_text(encoding='utf8')
        self.assertIn('versionCode = 67',gradle)
        self.assertIn('versionName = "0.21.30"',gradle)
        for name in ('.github/workflows/build.yml','scripts/build/BuildZuiControl.ps1',
                     'scripts/build/VerifyZuiControlFlashPackage.ps1','tests/zuiopt/VerifyZUIoptPayload.py'):
            self.assertIn('ZuiControlV67',(ROOT/name).read_text(encoding='utf8'))
        self.assertIn('android.permission.SYSTEM_ALERT_WINDOW',
                      (ROOT/'app/src/main/AndroidManifest.xml').read_text(encoding='utf8'))
    def test_single_identity_and_stale_rejection_before_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);payload=root/'payload';unpack=root/'unpack'
            apk=payload/APP_APK_PATH;apk.parent.mkdir(parents=True);apk.write_bytes(b'fixture')
            for base in (payload/'system/priv-app',unpack/'system_a/system/priv-app'):
                old=base/'ZuiControlV60';old.mkdir(parents=True)
                with self.assertRaises(SystemExit):copy_payload(payload,unpack,False,{})
                self.assertFalse((unpack/'system_a'/APP_APK_PATH).exists())
                old.rmdir()
            report={};copy_payload(payload,unpack,True,report)
            self.assertFalse((unpack/'system_a'/APP_APK_PATH).exists())
            copy_payload(payload,unpack,False,{})
            self.assertEqual((unpack/'system_a'/APP_APK_PATH).read_bytes(),b'fixture')

if __name__=='__main__':unittest.main(verbosity=2)
