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
    perf=tmp/'com/qualcomm/qti/Performance.java';perf.parent.mkdir(parents=True)
    perf.write_text('''package com.qualcomm.qti;
public class Performance {
 public static boolean ready,ambiguous;public static int calls,active;
 private boolean released;
 public double perfGetHalVer(){if(!ready)throw new UnsatisfiedLinkError("not registered");return 2.3;}
 public int perfLockAcquire(int duration,int[] pairs){if(released)throw new AssertionError("released client reused");calls++;if(ambiguous){active=99;return -1;}return active=73;}
 public int perfLockReleaseHandler(int handle){if(handle!=active)throw new AssertionError("wrong handle");released=true;active=0;return 1;}
}''',encoding='utf8')
    boost=tmp/'android/util/BoostFramework.java';boost.parent.mkdir(parents=True)
    boost.write_text('''package android.util;
public class BoostFramework {private Object mPerf=new com.qualcomm.qti.Performance();}
''',encoding='utf8')
    native_test=tmp/'QtiBoundaryTest.java'
    native_test.write_text('''package com.zui.server.control;
import com.qualcomm.qti.Performance;
public class QtiBoundaryTest {
 static void need(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static void scene(GpuPolicyController c){c.resolve("test.app","performance",null,true,true,true);}
 public static void main(String[]args){
  GpuPolicyController c=new GpuPolicyController(new GpuPolicyController.QtiTransport());
  scene(c);need(Performance.calls==0,"unregistered JNI crossed acquire boundary");
  Performance.ready=true;scene(c);need(Performance.calls==0,"implicit retry");
  need(c.recover(),"proven pre-call failure did not recover");scene(c);need(Performance.calls==1 && Performance.active==73,"no real acquisition");
  c.resolve("home","balance",null,false,true,true);need(Performance.active==0,"release");
  scene(c);need(Performance.calls==2 && Performance.active==73,"fresh client after acknowledged release");
  c.resolve("home","balance",null,false,true,true);
  Performance.ambiguous=true;c=new GpuPolicyController(new GpuPolicyController.QtiTransport());scene(c);
  int before=Performance.calls;need(!c.recover(),"negative return treated as no remote lease");scene(c);
  need(Performance.calls==before && Performance.active==99,"double unknown handle");
  System.out.println("REAL_QTI_TRANSPORT_PRECALL_PROOF=PASS; AMBIGUOUS_NEGATIVE_REJECTED=PASS");
 }
}''',encoding='utf8')
    subprocess.run(['javac','-encoding','UTF-8','-d',str(tmp),str(source/'GpuPolicyController.java'),str(source/'GpuRange.java'),str(perf),str(boost),str(native_test)],check=True)
    subprocess.run(['java','-cp',str(tmp),'com.zui.server.control.QtiBoundaryTest'],check=True)
