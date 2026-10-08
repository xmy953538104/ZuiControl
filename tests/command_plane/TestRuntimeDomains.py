"""Execute production active-domain planner and service apply with isolated platform boundaries."""
from pathlib import Path
import subprocess,tempfile,json,sys
ROOT=Path(__file__).resolve().parents[2];S=ROOT/'framework_patch/src/services/com/zui/server/control'
source=(S/'ZuiControlService.java').read_text('utf8')
def method(start):
 a=source.index(start);b=source.index('{',a);depth=1;i=b+1
 while depth:
  depth+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
apply=method('private String applyUnifiedPolicy(')
update=method('private String updateDesiredMode(')
reconcile=method('private void reconcile(String reason, long eventNanos)')
harness=r'''package com.zui.server.control;
import java.util.*;
import static com.zui.server.control.PolicyJson.*;
public class DomainFixture {
 static final String PROP_UPERF_MODE="mode",TAG="fixture",DEFAULT_SCENE="default";
 static int checks;static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static class SystemClock{static long elapsedRealtimeNanos(){return 0;}}
 static class SystemProperties{static String mode="balance";static String get(String k,String d){return mode;}static void set(String k,String v){mode=v;}}
 static class Log{static void i(String t,String m){}static void w(String t,String m,Throwable e){}}
 static class PolicyCommand{static void timing(String name){}}
 static String key(int user,String pkg){return user+":"+pkg;}
 static class Disk implements AppPolicyStore.Storage{final Map<String,byte[]> files=new TreeMap<>();public byte[] read(String n){return files.getOrDefault(n,new byte[0]);}public void write(String n,byte[] b){files.put(n,b.clone());}}
 static class Gpu {String runtimeStatus(){return "READY";}void resolve(String pkg,String mode,GpuRange range,boolean eligible,boolean screen,boolean enabled){}}
 final Gpu mGpuPolicy=new Gpu();AppPolicyStore mAppPolicies;AppPolicyStore.State mRuntimePolicy;
 boolean mPolicyReady=true,mAppRequestOwned=true,mRenderVoteOwned=true,mAppRequestHandoffPending=false,mScreenInteractive=true;
 String mLastApplyError="",mRuntimeContext="ctx";int mLastAppliedDisplayId,mRawFocusedDisplayId,mRenderVoteHz=120,mLastAppliedDisplayHz=120;
 long mPolicyRefreshApplies,mPolicyUperfApplies,mPolicyRefreshSkips,mPolicyUperfSkips;
 int refreshCalls,gpuCalls;boolean refreshFailure,uperfFailure;
 class Profile{int userId;String packageName=DEFAULT_SCENE;}
 Profile focusedProfile(){return new Profile();}void projectPolicy(AppPolicyStore.State state){}
 String policyRuntimeContext(){return "ctx";}
 String applyProfile(Profile p,String reason,boolean force){refreshCalls++;if(refreshFailure)throw new IllegalStateException("Refresh injected");return "applied";}
 final Uperf mUperfScenePolicy=new Uperf();
 APPLY
 class Uperf {
  int mSceneUserId,mApplyCount;boolean mInteractive=true;String mScenePackage="org.example.active",mGlobalMode="balance",mSceneMode="balance",mDesiredMode="balance",mLastRequestedMode="balance",mLastAppliedMode="balance",mLastReason="";
  boolean validUperfMode(String mode){return AppPolicyStore.mode(mode);}
  void refreshGpu(){gpuCalls++;if(uperfFailure)throw new IllegalStateException("Uperf/GPU injected");}
  UPDATE
  RECONCILE
 }
 static AppPolicyStore.State initial()throws Exception{
  Map<Integer,Long> users=new TreeMap<>();users.put(0,0L);
  AppPolicyStore.State s=AppPolicyStore.migrate("version=1\n".getBytes(),"balance\n".getBytes(),new byte[0],"balance","",users,Collections.emptySet()).state;
  s.schema=3;return s;
 }
 DomainFixture()throws Exception{Disk disk=new Disk();disk.write(AppPolicyStore.ACTIVE,initial().bytes());mAppPolicies=new AppPolicyStore(disk);mRuntimePolicy=mAppPolicies.current;SystemProperties.mode="balance";}
 void commit(AppPolicyStore.State next)throws Exception{
  mAppPolicies.commit(next,new AppPolicyStore.Owner(){public void prepare(AppPolicyStore.State s,String tx){}
   public void apply(AppPolicyStore.State s,String tx){applyUnifiedPolicy(s,false);mRuntimePolicy=s;}});
 }
 public static void main(String[] args)throws Exception{
  DomainFixture refresh=new DomainFixture();refresh.uperfFailure=true;
  refresh.commit(AppPolicyStore.change(refresh.mAppPolicies.current,1,0,"","refresh",90,"",0,0,true));
  check(refresh.refreshCalls==1&&refresh.gpuCalls==0&&refresh.mPolicyUperfSkips==1,"refresh isolated from Uperf failure");
  check(refresh.mAppPolicies.current.generation==2&&!refresh.mAppPolicies.recoveryRequired,"refresh exact committed generation");
  DomainFixture mode=new DomainFixture();mode.refreshFailure=true;
  mode.commit(AppPolicyStore.change(mode.mAppPolicies.current,1,0,"","mode",0,"performance",0,0,true));
  check(mode.refreshCalls==0&&mode.gpuCalls==1&&mode.mPolicyRefreshSkips==1,"mode isolated from Refresh failure");
  check(SystemProperties.mode.equals("performance")&&mode.mAppPolicies.current.generation==2,"mode authoritative property");
  AppPolicyStore.State base=initial(),mixed=AppPolicyStore.change(base,1,0,"","refresh",60,"",0,0,true);
  mixed=AppPolicyStore.change(mixed,2,0,"","mode",0,"fast",0,0,true);
  PolicyRuntimePlan p=PolicyRuntimePlan.between(base,mixed,0,"",0,"org.example.active",true,false);
  check(p.full&&p.refresh&&p.uperf,"mixed update full");
  for(String reason:new String[]{"boot","recovery","scene","lost ownership","uncertain state"}){
   p=PolicyRuntimePlan.between(base,base,0,"",0,"org.example.active",true,true);
   check(p.full&&p.refresh&&p.uperf,reason+" force retained");
  }
  p=PolicyRuntimePlan.between(null,base,0,"",0,"org.example.active",true,false);check(p.full,"unqualified initial runtime");
  AppPolicyStore.State app=AppPolicyStore.draft(base,1,0,"org.example.active",165,"performance","DEFAULT_FOR_MODE",0,0);
  AppPolicyStore.State defaults=AppPolicyStore.change(app,2,0,"","defaultGpu",0,"performance",500,834,true);
  p=PolicyRuntimePlan.between(app,defaults,0,"org.example.active",0,"org.example.active",true,false);
  check(!p.refresh&&p.uperf&&!p.full,"DEFAULT_FOR_MODE GPU dependency");
  AppPolicyStore.State custom=AppPolicyStore.draft(app,2,0,"org.example.active",165,"performance","CUSTOM",629,903);
  defaults=AppPolicyStore.change(custom,3,0,"","defaultGpu",0,"performance",500,834,true);
  p=PolicyRuntimePlan.between(custom,defaults,0,"org.example.active",0,"org.example.active",true,false);
  check(!p.refresh&&!p.uperf,"CUSTOM GPU independent from default edits");
  AppPolicyStore.State off=AppPolicyStore.change(base,1,0,"","mode",0,"fast",0,0,true);
  p=PolicyRuntimePlan.between(base,off,0,"",0,"org.example.active",false,false);check(!p.uperf&&!p.refresh,"screen-off powersave retained");
  DomainFixture lost=new DomainFixture();SystemProperties.mode="fast";lost.applyUnifiedPolicy(lost.mAppPolicies.current,false);
  check(lost.refreshCalls==1&&lost.gpuCalls==1&&SystemProperties.mode.equals("balance"),"lost property triggers full healing");
  DomainFixture unqualified=new DomainFixture();unqualified.mRuntimePolicy=null;unqualified.refreshFailure=true;
  boolean rejected=false;try{unqualified.applyUnifiedPolicy(unqualified.mAppPolicies.current,false);}catch(IllegalStateException expected){rejected=true;}
  check(rejected,"unknown runtime never skips broken required domain");
  System.out.println("R7_RUNTIME_DOMAIN_PRODUCTION_PASS checks="+checks);
 }
}'''.replace('APPLY',apply).replace('UPDATE',update).replace('RECONCILE',reconcile)
with tempfile.TemporaryDirectory(prefix='r7-domain-') as tmp:
 f=Path(tmp)/'DomainFixture.java';f.write_text(harness,encoding='utf8')
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*[str(S/n) for n in ['PolicyJson.java','GpuRange.java','AppPolicyStore.java','PolicyRuntimePlan.java']],str(f)],check=True)
 subprocess.run(['java','-cp',tmp,'com.zui.server.control.DomainFixture'],check=True)
