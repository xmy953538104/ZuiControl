"""Execute the production scene registry with typed, test-only Binder peers."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java'
SOURCE = SERVICE.with_name('ZuioptSceneAuthority.java')

STUBS = {
    'android/os/RemoteException.java': 'package android.os; public class RemoteException extends Exception {}',
    'android/os/Binder.java': '''package android.os;
public class Binder { public static long clearCallingIdentity(){return 0;} public static void restoreCallingIdentity(long token){} }''',
    'android/os/IBinder.java': '''package android.os;
public interface IBinder {
 int FLAG_ONEWAY=1;
 interface DeathRecipient { void binderDied(); }
 void linkToDeath(DeathRecipient r,int flags) throws RemoteException;
 boolean unlinkToDeath(DeathRecipient r,int flags);
 boolean isBinderAlive();
 boolean transact(int code,Parcel data,Parcel reply,int flags) throws RemoteException;
}''',
    'android/os/Parcel.java': '''package android.os;
public class Parcel {
 public String descriptor; public long seq;
 public static Parcel obtain(){return new Parcel();}
 public void writeInterfaceToken(String s){descriptor=s;}
 public void writeLong(long s){seq=s;}
 public java.util.List<Integer> ints=new java.util.ArrayList<>(); public String pkg;
 public void writeInt(int v){ints.add(v);}
 public void writeString(String s){pkg=s;}
 public void recycle(){}
}''',
}

FIXTURE = '''package com.zui.server.control;
import android.os.*;
import java.util.*;
public class SceneFixture {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static class Peer implements IBinder {
  boolean alive=true,fail=false; List<Long> seqs=new ArrayList<>(); DeathRecipient death;
  public void linkToDeath(DeathRecipient d,int f)throws RemoteException {if(!alive)throw new RemoteException(); death=d;}
  public boolean unlinkToDeath(DeathRecipient d,int f){if(death==d)death=null;return true;}
  public boolean isBinderAlive(){return alive;}
  public boolean transact(int code,Parcel p,Parcel reply,int flags)throws RemoteException {
   check(code==1 && flags==FLAG_ONEWAY && reply==null);
   check(p.descriptor.equals(ZuioptSceneAuthority.CALLBACK_DESCRIPTOR));
   if(fail)throw new RemoteException(); seqs.add(p.seq); return true;
  }
  void die(){alive=false;if(death!=null)death.binderDied();}
 }
 static void rejected(Runnable action){try{action.run();}catch(SecurityException e){return;}throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  ZuioptSceneAuthority s=new ZuioptSceneAuthority();Peer a=new Peer();
  s.register(a,42);check(a.seqs.equals(Arrays.asList(0L)));check(s.stateLines().contains("Sync=pending"));
  s.ack(a,42,0);check(s.stateLines().contains("Sync=ok"));
  for(int i=0;i<12;i++)s.changed("org.example.game", 0);check(a.seqs.get(12)==12L);
  check(s.stateLines().contains("Ack=0")&&s.stateLines().contains("Sync=pending"));
  s.ack(a,42,12);s.ack(a,42,10);check(s.stateLines().contains("Ack=12"));
  rejected(()->s.ack(a,43,12));rejected(()->s.ack(new Peer(),42,12));
  rejected(()->s.ack(a,42,13));rejected(()->s.ack(a,42,-1));
  try{s.register(new Peer(),43);throw new AssertionError();}catch(SecurityException expected){}
  s.unregister(a,43);s.changed("org.example.game", 0);check(a.seqs.get(13)==13L);
  IBinder.DeathRecipient stale=a.death;
  s.unregister(a,42);s.changed("org.example.game", 0);s.changed("org.example.game", 0);check(a.seqs.size()==14);
  Peer b=new Peer();s.register(b,52);check(b.seqs.equals(Arrays.asList(15L)));
  stale.binderDied();s.changed("org.example.game", 0);check(b.seqs.equals(Arrays.asList(15L,16L)));
  s.ack(b,52,16);b.die();check(s.stateLines().contains("Sync=pending"));
  s.changed("org.example.game", 0);s.changed("org.example.game", 0);Peer c=new Peer();s.register(c,62);check(c.seqs.equals(Arrays.asList(18L)));
  check(s.stateLines().contains("Ack=-1"));s.ack(c,62,18);check(s.stateLines().contains("Sync=ok"));
  c.fail=true;s.changed("org.example.game", 0);check(s.stateLines().contains("Sync=pending"));
  Peer d=new Peer();s.register(d,72);check(d.seqs.equals(Arrays.asList(19L)));
  for(int i=0;i<128;i++){s.unregister(d,72);s.changed("org.example.game", 0);s.register(d,72);s.ack(d,72,20L+i);check(s.stateLines().contains("Sync=ok"));}
  Parcel current=Parcel.obtain();s.writeCurrent(d,72,current);
  check(current.seq==147 && current.ints.equals(Arrays.asList(0,1)) && current.pkg.equals("org.example.game"));
  rejected(()->s.writeCurrent(d,73,Parcel.obtain()));
  s.changed("",0);current=Parcel.obtain();s.writeCurrent(d,72,current);
  check(current.seq==148 && current.ints.equals(Arrays.asList(0,0)) && current.pkg.isEmpty());
  System.out.println("SCENE_EXPLICIT_CURRENT_VALID_NULL_USER_REGISTERED_READER=PASS");
  System.out.println("SCENE_REGISTRY_ONEWAY_LATEST_REPLAY_DEATH_REREGISTER_ACK=PASS transitions=147");
 }
}'''


class SceneAuthorityTests(unittest.TestCase):
    def test_actual_registry_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='zuiopt-scene-java-') as temporary:
            root = Path(temporary)
            for name, text in STUBS.items():
                p = root/name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text, encoding='utf8')
            p = root/'com/zui/server/control'
            p.mkdir(parents=True)
            shutil.copyfile(SOURCE, p/SOURCE.name)
            (p/'SceneFixture.java').write_text(FIXTURE, encoding='utf8')
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(root), *map(str, root.rglob('*.java'))], check=True)
            subprocess.run(['java', '-cp', str(root), 'com.zui.server.control.SceneFixture'], check=True)

    def test_accepted_authority_and_private_abi(self):
        service = SERVICE.read_text(encoding='utf8')
        start = service.index('    private synchronized void handleTopResumedActivityChanged(')
        end = service.index('    public void onFocusedWindowChanged(')
        section = service[start:end]
        self.assertEqual(section.count('mZuioptScene.changed(pkg, userId);'), 2)
        valid, revalidate = section.split('    private synchronized void revalidateTopResumed(')
        self.assertLess(valid.index('acceptValid('), valid.index('mZuioptScene.changed(pkg, userId);'))
        self.assertLess(valid.index('mZuioptScene.changed(pkg, userId);'), valid.index('deferNull('))
        self.assertLess(revalidate.index('REVALIDATE_SAME'), revalidate.index('mZuioptScene.changed(pkg, userId);'))
        self.assertIn('Binder.getCallingUid() != 0', service)
        self.assertIn('data.dataAvail() != 0', service)
        self.assertIn('Binder.getCallingPid()', service)
        scene = SOURCE.read_text()
        self.assertIn('IBinder.FLAG_ONEWAY', scene)
        self.assertIn('callback.linkToDeath(next, 0)', scene)
        self.assertNotIn('SystemProperties', scene)
        self.assertNotIn('postDelayed', scene)
        policy = (ROOT/'payload/patches/plat_sepolicy_zui_control.cil').read_text()
        self.assertIn('(allow zuiopt zui_control_service (service_manager (find)))', policy)

    def test_event_contract_and_bounded_schedule(self):
        scene = (ROOT/'native/zuiopt/ZUIopt_scene.h').read_text()
        callback = scene.split('static binder_status_t transact(', 1)[1].split('explicit SceneObserver(', 1)[0]
        for token in ('/proc', 'snapshot(', 'packagesForUid(', 'placement', 'journal', 'ack('):
            self.assertNotIn(token, callback)
        daemon = (ROOT/'native/zuiopt/ZUIopt_daemon.h').read_text()
        self.assertIn('sceneBurst.complete(now())&&events.latestScene(seq)', daemon)
        self.assertLess(daemon.index('reconcile(snapshot);'), daemon.index('sceneObserver->ack(seq)'))
        self.assertLess(daemon.index('sceneObserver.reset();observer.reset();'), daemon.index('if(signalFd>=0)close(signalFd);', daemon.index('~Core()')))
        for p in (ROOT/'native/zuiopt').glob('*'):
            self.assertNotIn('__system_property_wait', p.read_text())
        events = (ROOT/'native/zuiopt/ZUIopt_events.h').read_text()
        self.assertIn('schedule={0,100,250,500}', events)
        self.assertIn('step==schedule.size()?-1:', events)


if __name__ == '__main__':
    unittest.main(verbosity=2)
