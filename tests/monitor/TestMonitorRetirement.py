"""Execute the actual collector with counted Binder boundary registrations."""
from pathlib import Path
import subprocess,sys,tempfile
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
namespace={'__file__':str(HERE/'TestMonitor.py')}
text=(HERE/'TestMonitor.py').read_text(encoding='utf8')
exec(compile(text[:text.index('with tempfile.TemporaryDirectory(')],str(HERE/'TestMonitor.py'),'exec'),namespace)
stubs=namespace['stubs'];stubs.pop('com/zui/server/control/CollectorTest.java')
stubs['com/zui/server/control/RetirementTest.java']='''package com.zui.server.control;
import android.os.*;import android.content.*;import java.util.*;
public class RetirementTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Client implements IBinder {
  Set<DeathRecipient> links=new HashSet<>();boolean fail,alreadyDead;int calls;
  public void linkToDeath(DeathRecipient d,int f){links.add(d);}
  public boolean unlinkToDeath(DeathRecipient d,int f){if(!links.remove(d))throw new NoSuchElementException();return true;}
  public boolean transact(int c,Parcel p,Parcel r,int f)throws RemoteException{calls++;if(fail)throw new RemoteException();return true;}
 }
 public static void main(String[] a)throws Exception{
  MonitorCollector c=new MonitorCollector(new Context());Client first=new Client();
  for(int i=0;i<100;i++){c.register(first,0);check(first.links.size()==1,"one active link");c.unregister(first);check(first.links.isEmpty(),"retired recipient leaked at cycle "+i);}
  c.register(first,0);IBinder.DeathRecipient stale=first.links.iterator().next();Client next=new Client();
  c.register(next,0);check(first.links.isEmpty()&&next.links.size()==1,"replacement");
  long epoch=c.session.connectionEpoch;stale.binderDied();check(c.session.connectionEpoch==epoch&&next.links.size()==1,"stale death changed new connection");
  Client secondary=new Client();c.register(secondary,10);c.scene("secondary.app",10,true);
  check(next.links.isEmpty()&&secondary.links.size()==1,"switch");c.removeUser(10);check(secondary.links.isEmpty(),"remove user");
  c.scene("owner.app",0,true);c.register(first,0);stale=first.links.iterator().next();first.links.clear();
  stale.binderDied();check(first.links.isEmpty(),"dead binder retirement");
  c.register(next,0);next.fail=true;c.command("full",0,"");check(next.links.isEmpty(),"delivery failure");
  next.fail=false;c.register(next,0);check(next.links.size()==1,"reconnect");c.unregister(next);check(next.links.isEmpty(),"final retire");
  System.out.println("MONITOR_RETIREMENT=PASS cycles=100 replace/switch/remove/death/delivery/reconnect");
 }
}'''
with tempfile.TemporaryDirectory(prefix='monitor-retirement-') as tmp:
    out=Path(tmp);files=[]
    for name,text in stubs.items():
        p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');files.append(str(p))
    production=['MonitorCollector.java','MonitorSources.java','MonitorSnapshot.java','MonitorSession.java','MonitorLifecycle.java']
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*files,*[str(namespace['src']/n) for n in production]],check=True)
    subprocess.run(['java','-cp',tmp,'com.zui.server.control.RetirementTest'],check=True)
