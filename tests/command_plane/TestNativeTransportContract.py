"""Execute the production root transport's identity/ACK guards on the host."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'framework_patch/src/services/com/zui/server/control'


class NativeTransportContract(unittest.TestCase):
    def test_production_transport_guards(self):
        source = (BASE / 'ZuiControlService.java').read_text(encoding='utf8')
        method = source[source.index('    private synchronized String commandTransport('):source.index('    private synchronized String notifyControlRequest(')]
        validators = source[source.index('    private static boolean validRequestId('):source.index('    private static int parseInt(')]
        harness = r'''
package com.zui.server.control;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
public class TransportFixture {
    static final String PROP_COMMAND_ID="id",PROP_COMMAND_SHA256="hash",PROP_COMMAND_SEQ="seq",SETTING_REQUEST_TEXT="request";
    static class Context {Object getContentResolver(){return null;}}
    final Context mContext=new Context();
    static class Binder {static long clearCallingIdentity(){return 1;}static void restoreCallingIdentity(long token){}}
    static class SystemProperties {static Map<String,String> values=new HashMap<>();static String get(String key,String fallback){return values.getOrDefault(key,fallback);}}
    static class Settings {static class System {
        static Map<String,String> values=new HashMap<>();static int writes;
        static String getString(Object resolver,String key){return values.get(key);}
        static boolean putString(Object resolver,String key,String value){writes++;values.put(key,value);return true;}
    }}
    static String safe(String value){return value==null?"":value;}
    METHOD
    VALIDATORS
    interface Work {void run();}
    static int checks;
    static void check(boolean v){if(!v)throw new AssertionError();checks++;}
    static void rejects(Work work){int before=Settings.System.writes;try{work.run();}catch(IllegalArgumentException expected){check(Settings.System.writes==before);return;}throw new AssertionError("accepted invalid transport");}
    public static void main(String[] args){
        TransportFixture fixture=new TransportFixture();String request="request1|policy|||e30=",hash=sha256(request);
        SystemProperties.values.put("id","request1");SystemProperties.values.put("hash",hash);SystemProperties.values.put("seq","sequence1");Settings.System.values.put("request",request);
        check(fixture.commandTransport("request1",hash,"sequence1","read","").equals(request));check(Settings.System.writes==0);
        for(String state:new String[]{"processing","done","failed"})check(fixture.commandTransport("request1",hash,"sequence1","ack","request1|"+state+"|policy|detail").equals("ok=1"));
        for(String value:new String[]{"request2|done|policy|x","request1|done|other|x","request1|unknown|policy|x","request1|done|policy|x\n","request1|done|policy|x|extra"})rejects(()->fixture.commandTransport("request1",hash,"sequence1","ack",value));
        rejects(()->fixture.commandTransport("request2",hash,"sequence1","read",""));
        rejects(()->fixture.commandTransport("request1",hash,"stale","read",""));
        rejects(()->fixture.commandTransport("request1",hash.replace('a','b'),"sequence1","read","unexpected"));
        rejects(()->fixture.commandTransport("request1",hash,"sequence1","unknown",""));
        Settings.System.values.put("request",request+"changed");rejects(()->fixture.commandTransport("request1",hash,"sequence1","read",""));
        java.lang.System.out.println("NATIVE_TRANSPORT_JAVA_GUARDS_PASS checks="+checks);
    }
}
'''.replace('METHOD', method).replace('VALIDATORS', validators)
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / 'TransportFixture.java'
            file.write_text(harness, encoding='utf8')
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', tmp, str(BASE / 'PolicyJson.java'), str(file)], check=True)
            subprocess.run(['java', '-cp', tmp, 'com.zui.server.control.TransportFixture'], check=True)
        root = source[source.index('if (code == 1012)'):source.index('if(code==1011)')]
        self.assertIn('data.enforceInterface(DESCRIPTOR)', root)
        self.assertIn('Binder.getCallingUid() != Process.ROOT_UID || (flags & IBinder.FLAG_ONEWAY) != 0', root)
        self.assertIn('data.dataAvail()!=0', root)


if __name__ == '__main__':
    unittest.main()
