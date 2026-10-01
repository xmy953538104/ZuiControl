"""Run the exact service dump override; platform base dump has no implicit gate."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
source=(ROOT/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java').read_text('utf8')
start=source.index('    protected synchronized void dump(')
method=source[start:source.index('    private String policyStateLines()',start)]
harness='''import java.io.*;
class Binder{static int uid;static int getCallingUid(){return uid;}}
class Process{static final int ROOT_UID=0,SHELL_UID=2000,SYSTEM_UID=1000;}
public class DumpFixture{
 String state(){return "ALL_USERS_PRIVATE_STATE";}
 String callerState(boolean scene){return "USER_"+(Binder.uid/100000);}
 void enforceZuiControlCaller(int uid){if(uid!=10253&&uid!=1010253)throw new SecurityException("wrong cert");}
'''+method+'''
 public static void main(String[]args){DumpFixture f=new DumpFixture();
 for(int uid:new int[]{10123,1010123}){Binder.uid=uid;StringWriter s=new StringWriter();boolean denied=false;
 try{f.dump(null,new PrintWriter(s),new String[0]);}catch(SecurityException e){denied=true;}
 if(!denied&&s.toString().contains("PRIVATE_STATE"))throw new AssertionError("unauthorized dump uid="+uid);}
 for(int uid:new int[]{0,1000,2000}){Binder.uid=uid;StringWriter s=new StringWriter();f.dump(null,new PrintWriter(s),new String[0]);if(!s.toString().contains("PRIVATE_STATE"))throw new AssertionError("trusted diagnostic lost");}
 System.out.println("DIAGNOSTIC_DUMP_BOUNDARY=PASS");}
}'''
with tempfile.TemporaryDirectory(prefix='dump-gate-') as tmp:
    file=Path(tmp)/'DumpFixture.java';file.write_text(harness,encoding='utf8')
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,str(file)],check=True)
    subprocess.run(['java','-cp',tmp,'DumpFixture'],check=True)
