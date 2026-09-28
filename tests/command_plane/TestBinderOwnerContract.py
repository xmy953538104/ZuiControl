"""Execute the exact production guards with V73-observed Binder flags 0x12."""
from pathlib import Path
import re, subprocess, tempfile, unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'framework_patch/src/services/com/zui/server/control'

class BinderOwnerContract(unittest.TestCase):
    def test_actual_java_guards(self):
        service = (BASE / 'ZuiControlService.java').read_text(encoding='utf8')
        callback = (BASE / 'PolicyCommand.java').read_text(encoding='utf8')
        guards = re.findall(r'if\s*\([^\n]+throw new SecurityException\("root (?:policy|settings) owner required"\);', service)
        self.assertEqual(len(guards), 2)
        projection = re.search(r'require\(Binder.getCallingUid\(\)[^\n]+"system policy authority required"\);', callback)[0]
        methods = '\n'.join('static void outer%d(int flags) {%s}' % (i, g) for i, g in enumerate(guards))
        source = '''public class OwnerFixture {
          static class Binder { static int uid; static int getCallingUid(){return uid;} }
          static class IBinder { static final int FLAG_ONEWAY=1; }
          static class Process { static final int ROOT_UID=0,SYSTEM_UID=1000; }
          static void require(boolean b,String message){if(!b)throw new SecurityException(message);}
          METHODS
          static void callback(int code,int flags){PROJECTION}
          public static void main(String[] args){
            int count=0;
            for(int uid:new int[]{0,1000,2000,10001})
              for(int flags:new int[]{0,2,16,18,1,19})
                for(int channel=0;channel<3;channel++)
                  for(int code:new int[]{1,2}){
                    Binder.uid=uid; boolean actual=true;
                    try {if(channel==0)outer0(flags);else if(channel==1)outer1(flags);else callback(code,flags);}
                    catch(SecurityException expected){actual=false;}
                    boolean accepted=(uid==(channel==2?1000:0))&&(flags&1)==0&&(channel!=2||code==1);
                    if(actual!=accepted)throw new AssertionError(uid+":"+flags+":"+channel+":"+code);
                    count++;
                  }
            System.out.println("BINDER_OWNER_GUARDS_PASS cases="+count);
          }
        }'''.replace('METHODS', methods).replace('PROJECTION', projection).replace('android.os.Process.', 'Process.')
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / 'OwnerFixture.java'; file.write_text(source, encoding='utf8')
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', tmp, str(file)], check=True)
            subprocess.run(['java', '-cp', tmp, 'OwnerFixture'], check=True)
        for error in ('root policy owner required', 'root settings owner required'):
            start=service.index('data.enforceInterface(DESCRIPTOR);', service.index('protected boolean onTransact'))
            self.assertIn(error, service[start:service.index('if (code >= ZuioptSceneAuthority.REGISTER', start)])
        self.assertIn('data.dataAvail() != 0 || owner == null', service)
        self.assertIn('data.dataAvail()!=0||owner==null', service)
        self.assertIn('data.enforceInterface(CALLBACK)', callback)
        self.assertIn('data.dataAvail() == 0, "projection trailing data"', callback)

    def test_no_external_root_framework_dependency(self):
        forbidden=re.compile(r'(?i)(?:\bsu\b|/data/adb|\bAPatch\b|\bMagisk\b|\bKernelSU\b|root[ _-]proxy)')
        for folder in ('app/src/main','framework_patch/src','payload/system','native'):
            for p in (ROOT/folder).rglob('*'):
                if not p.is_file():continue
                data=p.read_bytes()
                if b'\0' in data:continue
                text=data.decode('utf8',errors='replace')
                self.assertIsNone(forbidden.search(text),str(p.relative_to(ROOT)))

if __name__ == '__main__':unittest.main()
