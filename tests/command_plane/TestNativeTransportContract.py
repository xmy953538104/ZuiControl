"""Execute exact production admission/transport guards with isolated user Settings."""
from pathlib import Path
import subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'framework_patch/src/services/com/zui/server/control'

class NativeTransportContract(unittest.TestCase):
    def test_production_transport_guards(self):
        source=(BASE/'ZuiControlService.java').read_text(encoding='utf8')
        start=source.index('    private UtilityTransport utilities(')
        methods=source[start:source.index('    private Profile makeProfile(',start)]
        start=source.index('    private void retireSensitiveSettings(')
        methods+='\n'+source[start:source.index('    private synchronized String policyCommand(',start)]
        validators=source[source.index('    private static boolean validRequestId('):source.index('    private static int parseInt(')]
        harness=(Path(__file__).with_name('UserTransportFixture.java')).read_text('utf8')
        harness=harness.replace('/* PRODUCTION METHODS */',methods).replace('/* VALIDATORS */',validators)
        start=source.index('    private synchronized String callerState(')
        harness=harness.replace('/* CALLER STATE */',source[start:source.index('    private synchronized String state(',start)])
        start=source.index('PolicyJson.require(mAppPolicies!=null&&mAppPolicies.current!=null&&inventory.containsKey(callerUser)')
        harness=harness.replace('/* INVENTORY GUARD */',source[start:source.index(';}',start)+1])
        with tempfile.TemporaryDirectory() as tmp:
            file=Path(tmp)/'UserTransportFixture.java';file.write_text(harness,encoding='utf8')
            names=['PolicyJson.java','GpuRange.java','AppPolicyStore.java','SettingsBackup.java','UperfConfigStore.java','RequestIdentity.java','UtilityTransport.java']
            subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*[str(BASE/n) for n in names],str(ROOT/'tests/gpu/AppPolicyFixture.java'),str(file)],check=True)
            subprocess.run(['java','-cp',tmp,'com.zui.server.control.UserTransportFixture'],check=True)
        root=source[source.index('if (code == 1012)'):source.index('if(code==1011)')]
        self.assertIn('data.enforceInterface(DESCRIPTOR)',root)
        self.assertIn('Binder.getCallingUid() != Process.ROOT_UID || (flags & IBinder.FLAG_ONEWAY) != 0',root)
        self.assertIn('data.dataAvail()!=0',root)
        self.assertIn('authenticatedPolicyIdentity(id,hash,sequence,user)',source)

if __name__=='__main__':unittest.main()
