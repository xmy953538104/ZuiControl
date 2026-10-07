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
 static class Monitor {int arms,lastSeconds;void armEdgeTrace(int seconds){arms++;lastSeconds=seconds;}String edgeTrace(){return "BOUNDED_PRIVATE_EDGE_TRACE";}}
 final Monitor mMonitor=new Monitor();
 String state(){return "ALL_USERS_PRIVATE_STATE";}
 String callerState(boolean scene){return "USER_"+(Binder.uid/100000);}
 void enforceZuiControlCaller(int uid){if(uid!=10253&&uid!=1010253)throw new SecurityException("wrong cert");}
'''+method+'''
 public static void main(String[]args){DumpFixture f=new DumpFixture();
 for(int uid:new int[]{10123,1010123})for(String[] arguments:new String[][]{new String[0],{"--edge-trace-seconds=60"}}){Binder.uid=uid;StringWriter s=new StringWriter();boolean denied=false;
 try{f.dump(null,new PrintWriter(s),arguments);}catch(SecurityException e){denied=true;}
 if(!denied||!s.toString().isEmpty()||f.mMonitor.arms!=0)throw new AssertionError("unauthorized dump uid="+uid);}
 for(int uid:new int[]{10253,1010253}){Binder.uid=uid;StringWriter s=new StringWriter();f.dump(null,new PrintWriter(s),new String[]{"--edge-trace-seconds=60"});
 if(!s.toString().equals("USER_"+(uid/100000))||f.mMonitor.arms!=0)throw new AssertionError("signed caller trace/user boundary");}
 for(int uid:new int[]{0,1000,2000}){Binder.uid=uid;StringWriter s=new StringWriter();int arms=f.mMonitor.arms;
 f.dump(null,new PrintWriter(s),new String[0]);if(!s.toString().contains("PRIVATE_STATE")||!s.toString().contains("EDGE_TRACE")||f.mMonitor.arms!=arms)throw new AssertionError("trusted diagnostic lost");
 f.dump(null,new PrintWriter(s),new String[]{"--edge-trace-seconds=60"});if(f.mMonitor.arms!=arms+1||f.mMonitor.lastSeconds!=60)throw new AssertionError("privileged trace arm");}
 int arms=f.mMonitor.arms;boolean invalid=false;try{f.dump(null,new PrintWriter(new StringWriter()),new String[]{"--edge-trace-seconds=invalid"});}catch(NumberFormatException e){invalid=true;}
 if(!invalid||f.mMonitor.arms!=arms)throw new AssertionError("invalid trace number");
 System.out.println("DIAGNOSTIC_DUMP_BOUNDARY=PASS unauthorized-denied/signed-users-scoped-no-trace/root-shell-system-only/invalid-number");}
}'''
with tempfile.TemporaryDirectory(prefix='dump-gate-') as tmp:
    file=Path(tmp)/'DumpFixture.java';file.write_text(harness,encoding='utf8')
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,str(file)],check=True)
    subprocess.run(['java','-cp',tmp,'DumpFixture'],check=True)
