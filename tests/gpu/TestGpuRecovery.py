"""Exercise the real controller; an explicit recovery needs transport ownership proof."""
from pathlib import Path
import subprocess,tempfile

root=Path(__file__).resolve().parents[2]
source=root/'framework_patch/src/services/com/zui/server/control'
fixture='''package com.zui.server.control;
public class GpuRecoveryTest {
 static void need(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Link implements GpuPolicyController.Transport {
  int acquireCount,active,releases;boolean available,proof,releaseFails;
  public int acquire(int duration,int[] pairs){need(active==0,"double handle");acquireCount++;if(!available)return -1;return active=71;}
  public int release(int handle){need(handle==active,"wrong handle");releases++;if(releaseFails)return -1;active=0;return 0;}
  public boolean recoverUnowned(){return active==0 && proof && available;}
 }
 static void scene(GpuPolicyController c,boolean enabled){c.resolve("org.test.app","performance",null,true,true,enabled);}
 static boolean recover(GpuPolicyController c)throws Exception {
  try{return (Boolean)c.getClass().getDeclaredMethod("recover").invoke(c);}
  catch(NoSuchMethodException missing){throw new AssertionError("Missing safe explicit recovery; first failure remains boot-latched",missing);}
 }
 public static void main(String[]args)throws Exception {
  Link t=new Link();GpuPolicyController c=new GpuPolicyController(t);scene(c,true);t.available=true;
  c.resolve("com.zui.launcher","balance",null,false,true,true);scene(c,true);scene(c,false);scene(c,true);
  need(t.acquireCount==1 && t.active==0,"ordinary navigation must not retry");
  System.out.println("FIRST_FAILURE_NAVIGATION_ACQUIRES="+t.acquireCount+" "+c.runtimeStatus());
  need(!recover(c),"local handle0 is not remote ownership proof");
  t.proof=true;need(recover(c),"proven unowned and ready must recover");scene(c,true);
  need(t.acquireCount==2 && t.active==71,"recovery did not acquire once");
  for(int i=0;i<10;i++)scene(c,true);need(t.acquireCount==2,"same range reacquired");
  t.releaseFails=true;c.resolve("com.zui.launcher","balance",null,false,true,true);
  need(!recover(c),"unknown release must stay protected");scene(c,true);need(t.acquireCount==2,"second unknown handle");
  t=new Link();t.proof=true;c=new GpuPolicyController(t);scene(c,true);need(!recover(c),"not ready");
  t.available=true;need(recover(c),"ready proof");t.available=false;scene(c,true);need(!recover(c),"one recovery budget per controller lifetime");
  t=new Link();t.available=true;t.proof=true;c=new GpuPolicyController(t);scene(c,true);c.failSafe("manual safety");need(!recover(c),"explicit fail-safe is not an acquisition readiness fault");
  System.out.println("GPU_EXPLICIT_RECOVERY=PASS; UNKNOWN_OWNERSHIP_BLOCKED=PASS; PERIODIC_RETRY=0");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    tmp=Path(tmp);(tmp/'GpuRecoveryTest.java').write_text(fixture,encoding='utf8')
    subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp),str(source/'GpuPolicyController.java'),str(source/'GpuRange.java'),str(tmp/'GpuRecoveryTest.java')],check=True)
    subprocess.run(['java','-cp',str(tmp),'com.zui.server.control.GpuRecoveryTest'],check=True)
    binder=tmp/'android/os/IBinder.java';binder.parent.mkdir(parents=True)
    binder.write_text('''package android.os;public interface IBinder{
 boolean pingBinder();String getInterfaceDescriptor();boolean transact(int code,Parcel in,Parcel out,int flags);
}''',encoding='utf8')
    parcel=tmp/'android/os/Parcel.java'
    parcel.write_text('''package android.os;
public class Parcel {
 public java.util.ArrayList<Object> values=new java.util.ArrayList<>();public int position;
 public static int obtained,recycled;
 public static Parcel obtain(){obtained++;return new Parcel();}
 public void writeInterfaceToken(String s){values.add(s);} public void writeInt(int n){values.add(n);}
 public void writeIntArray(int[] v){values.add(v.clone());} public void writeString(String s){values.add(s);}
 public void readException(){} public int readInt(){return (Integer)values.get(position++);}
 public String readString(){return (String)values.get(position++);} public int dataAvail(){return values.size()-position;}
 public void recycle(){recycled++;}
}''',encoding='utf8')
    process=tmp/'android/os/Process.java'
    process.write_text('package android.os;public class Process{public static int myTid(){return 14;}public static int myPid(){return 28;}}',encoding='utf8')
    manager=tmp/'android/os/ServiceManager.java'
    manager.write_text('''package android.os;
public class ServiceManager {
 public static boolean ready=true;public static Perf binder=new Perf();
 public static IBinder checkService(String name){if(!name.equals("vendor.qti.hardware.perf2.IPerf/default"))throw new AssertionError(name);return ready?binder:null;}
 public static class Perf implements IBinder {
  public int calls,releases,active,version=1;public boolean ambiguous,releaseFails,dead,unknown,extra;
  public String hash="c5d67f27386b8feb13a185a4cdd647afbeb758ce";
  public boolean pingBinder(){return !dead;}public String getInterfaceDescriptor(){return "vendor.qti.hardware.perf2.IPerf";}
  public boolean transact(int code,Parcel in,Parcel out,int flags){
   if(dead)throw new IllegalStateException("DEAD_OBJECT");
   if(flags!=0||!in.values.get(0).equals(getInterfaceDescriptor()))throw new AssertionError("protocol");
   if(code==16777215){out.writeInt(version);return true;}if(code==16777214){out.writeString(hash);return true;}
   if(code==11){
    if(unknown)return false;
    if(active!=0||in.values.size()!=5||(Integer)in.values.get(1)!=0||(Integer)in.values.get(2)!=0)throw new AssertionError("fresh exclusive acquisition grammar");
    if(!java.util.Arrays.equals((int[])in.values.get(3),new int[]{0x42804000,8,0x42808000,0})||!java.util.Arrays.equals((int[])in.values.get(4),new int[]{14,28}))throw new AssertionError("pairs/reserved");
    calls++;active=73;if(ambiguous){active=99;out.writeInt(-1);}else out.writeInt(active);if(extra)out.writeInt(123);return true;
   }
   if(code==13){releases++;if(in.values.size()!=3||(Integer)in.values.get(1)!=active)throw new AssertionError("release owner grammar");if(releaseFails)throw new IllegalStateException("lost release ACK");active=0;return true;}
   throw new AssertionError("unqualified transaction "+code);
  }
 }
}
''',encoding='utf8')
    native_test=tmp/'QtiBoundaryTest.java'
    native_test.write_text('''package com.zui.server.control;
import android.os.ServiceManager;import android.os.Parcel;
public class QtiBoundaryTest {
 static void need(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static void scene(GpuPolicyController c){c.resolve("test.app","performance",null,true,true,true);}
 public static void main(String[]args){
  ServiceManager.ready=false;
  GpuPolicyController c=new GpuPolicyController(new GpuPolicyController.QtiTransport());
  scene(c);ServiceManager.ready=true;scene(c);need(ServiceManager.binder.calls==0,"implicit service retry");
  need(c.recover(),"proven pre-IPC failure cannot recover");scene(c);need(ServiceManager.binder.calls==1&&ServiceManager.binder.active==73,"live V1 acquisition");
  scene(c);need(ServiceManager.binder.calls==1,"same range dedup");
  c.resolve("home","balance",null,false,true,true);need(ServiceManager.binder.active==0,"void ACK release");
  scene(c);need(ServiceManager.binder.calls==2,"fresh handle after release");
  c.resolve("home","balance",null,false,true,true);
  ServiceManager.binder.dead=true;ServiceManager.binder=new ServiceManager.Perf();
  scene(c);need(ServiceManager.binder.calls==1&&ServiceManager.binder.active==73,"safe zero-owned service rebind");
  c.resolve("home","balance",null,false,true,true);
  ServiceManager.binder=new ServiceManager.Perf();ServiceManager.binder.hash="wrong";
  c=new GpuPolicyController(new GpuPolicyController.QtiTransport());scene(c);need(ServiceManager.binder.calls==0,"unqualified ABI crossed GPU boundary");
  ServiceManager.binder.hash="c5d67f27386b8feb13a185a4cdd647afbeb758ce";need(c.recover(),"ABI restored before any acquisition");scene(c);c.resolve("home","balance",null,false,true,true);
  ServiceManager.binder=new ServiceManager.Perf();ServiceManager.binder.ambiguous=true;
  c=new GpuPolicyController(new GpuPolicyController.QtiTransport());scene(c);need(!c.recover(),"negative return implies no remote lease");scene(c);need(ServiceManager.binder.calls==1&&ServiceManager.binder.active==99,"double unknown handle");
  ServiceManager.binder=new ServiceManager.Perf();ServiceManager.binder.extra=true;
  c=new GpuPolicyController(new GpuPolicyController.QtiTransport());scene(c);need(!c.recover(),"malformed reply discarded possible handle");scene(c);need(ServiceManager.binder.calls==1,"reply error triggered second request");
  ServiceManager.binder=new ServiceManager.Perf();ServiceManager.Perf original=ServiceManager.binder;
  c=new GpuPolicyController(new GpuPolicyController.QtiTransport());scene(c);original.releaseFails=true;c.resolve("home","balance",null,false,true,true);
  ServiceManager.binder=new ServiceManager.Perf();ServiceManager.binder.active=99;
  for(int i=0;i<4;i++)scene(c);c.failSafe("manual");need(original.releases==1,"unknown release repeated on navigation");
  need(!c.recover()&&ServiceManager.binder.calls==0&&ServiceManager.binder.active==99,"new service's foreign lease touched");
  need(Parcel.obtained==Parcel.recycled,"parcel leak");
  System.out.println("REAL_AIDL_V1_BOUNDARY=PASS; UNKNOWN_RELEASE_NO_RETRY=PASS; NO_DOUBLE_HANDLE=PASS");
 }
}''',encoding='utf8')
    subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp),str(source/'GpuPolicyController.java'),str(source/'GpuRange.java'),str(binder),str(parcel),str(process),str(manager),str(native_test)],check=True)
    subprocess.run(['java','-cp',str(tmp),'com.zui.server.control.QtiBoundaryTest'],check=True)
