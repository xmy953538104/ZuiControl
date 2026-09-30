"""Feed exact production Java owner messages to the real native projection.

Linux host execution only. Policy decisions are the unchanged AppPolicyStore;
the old Java projection validator supplies the expected hashes and mode values.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'framework_patch/src/services/com/zui/server/control'


def main(binary):
    source = (BASE / 'PolicyCommand.java').read_text(encoding='utf8')
    owner = source[source.index('    static AppPolicyStore.Owner owner('):source.index('    static Map<String,Object> snapshot(')]
    validation = source[source.index('    static String projectionMode('):source.index('    private static final class Projection')]
    harness = r'''
package com.zui.server.control;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static com.zui.server.control.PolicyJson.*;
public class NativeProjectionParity {
    static class IBinder {Map<String,byte[]> stages=new TreeMap<>();}
    static String b64(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    static String callback(IBinder remote,String action,String text)throws Exception{
        Map<String,Object> r=object(parse(text.getBytes(StandardCharsets.UTF_8)));
        String tx=string(r.get("transaction"));String expected;
        if(action.equals("prepare")){
            byte[] bytes=bytes(r.get("policy"));AppPolicyStore.State.parse(bytes);
            require(!remote.stages.containsKey(tx)||Arrays.equals(remote.stages.get(tx),bytes),"immutable stage");
            remote.stages.put(tx,bytes);expected=hash(bytes);
        }else {byte[] prepared=remote.stages.get(tx);projectionMode(r,prepared);expected=hash(prepared);}
        System.out.println(action+"\t"+b64(text)+"\t"+expected);
        return expected;
    }
    OWNER
    VALIDATION
    static int tx;
    static void project(AppPolicyStore.State s,IBinder remote)throws Exception{
        String id=String.format(Locale.ROOT,"00000000-0000-0000-0000-%012d",++tx);
        AppPolicyStore.Owner owner=owner(remote,state->state.global(0).uperfMode);
        owner.prepare(s,id);owner.apply(s,id);owner.apply(s,id);
    }
    public static void main(String[] args)throws Exception{
        Map<Integer,Long> users=new TreeMap<>();users.put(0,0L);
        AppPolicyStore.State s=AppPolicyStore.migrate("version=1\n".getBytes(),"balance\n".getBytes(),new byte[0],"balance","",users,Collections.emptySet()).state;
        IBinder remote=new IBinder();project(s,remote);
        Object[][] actions={
            {"org.example.app","refresh",120,"",0,0,false}, // explicit equals global
            {"","refresh",60,"",0,0,true},                 // HOME / global
            {"org.example.app","mode",0,"performance",0,0,false},
            {"","mode",0,"powersave",0,0,true},
            {"org.example.app","gpu",0,"",310,680,false},
            {"org.example.app","gpuDefault",0,"",0,0,false},
            {"","defaultGpu",0,"performance",422,903,true},
            {"org.example.app","mode",0,"performance",0,0,false},
            {"org.example.app","delete",0,"",0,0,false}
        };
        for(Object[] a:actions){s=AppPolicyStore.change(s,s.generation,0,(String)a[0],(String)a[1],(Integer)a[2],(String)a[3],(Integer)a[4],(Integer)a[5],(Boolean)a[6]);project(s,remote);}
    }
}
'''.replace('OWNER', owner).replace('VALIDATION', validation)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        java = tmp / 'NativeProjectionParity.java'
        java.write_text(harness, encoding='utf8')
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(tmp),
                        *[str(BASE / n) for n in ('PolicyJson.java', 'GpuRange.java', 'AppPolicyStore.java')], str(java)], check=True)
        messages = subprocess.check_output(['java', '-cp', str(tmp), 'com.zui.server.control.NativeProjectionParity'])
        subprocess.run([binary, '--parity'], input=messages, check=True)


if __name__ == '__main__':
    main(sys.argv[1])
