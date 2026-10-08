from pathlib import Path
import subprocess
import tempfile
repo=Path(__file__).resolve().parents[2];src=repo/'framework_patch/src/services/com/zui/server/control'
fixture=tempfile.TemporaryDirectory();O=Path(fixture.name)
s=(src/'ZuiControlService.java').read_text('utf8')
def method(start):
 a=s.index(start);b=s.index('{',a);level=1;i=b+1
 while level:
  if s[i]=='{':level+=1
  if s[i]=='}':level-=1
  i+=1
 return s[a:i]
apply=method('private String applyUnifiedPolicy(');reconcile=method('private void reconcile(String reason, long eventNanos)');update=method('private String updateDesiredMode(')
text='''package com.zui.server.control;
import java.util.*;import java.nio.file.*;
public class CouplingProbe {
static final String PROP_UPERF_MODE="mode",TAG="probe";
static class SystemProperties {static String mode="fast";static String get(String n,String d){return mode;}static void set(String n,String v){mode=v;}}
static class SystemClock {static long elapsedRealtimeNanos(){return 0;}}
static class Log {static void i(String a,String b){}static void w(String a,String b,Throwable t){}}
static class PolicyCommand {static void timing(String name){}}
static String key(int u,String p){return u+":"+p;}
AppPolicyStore mAppPolicies;boolean mPolicyReady=true;GpuPolicyController mGpuPolicy;Uperf mUperfScenePolicy=new Uperf();
static final String DEFAULT_SCENE="default";
AppPolicyStore.State mRuntimePolicy;String mRuntimeContext="";
boolean mAppRequestOwned=true,mRenderVoteOwned=true,mAppRequestHandoffPending=false,mScreenInteractive=true;
int mLastAppliedDisplayId,mRawFocusedDisplayId,mRenderVoteHz=120,mLastAppliedDisplayHz=120;
String mLastApplyError="";long mPolicyRefreshApplies,mPolicyRefreshSkips,mPolicyUperfApplies,mPolicyUperfSkips;
static class Profile{int userId;String packageName=DEFAULT_SCENE;}
String policyRuntimeContext(){return "";}Profile focusedProfile(){return new Profile();}
void projectPolicy(AppPolicyStore.State s){}String applyProfile(Profile p,String r,boolean b){return "applied";}
'''+apply+'''
class Uperf {
int mSceneUserId=0,mApplyCount;boolean mInteractive=true;String mGlobalMode="fast",mScenePackage="org.example.a",mSceneMode="fast",mDesiredMode="fast",mLastRequestedMode="fast",mLastAppliedMode="fast",mLastReason="";
boolean validUperfMode(String s){return AppPolicyStore.mode(s);}void refreshGpu(){}
'''+update+reconcile+'''
}
static class Disk implements AppPolicyStore.Storage {Map<String,byte[]> data=new HashMap<>();public byte[] read(String n){return data.getOrDefault(n,new byte[0]);}public void write(String n,byte[] b){data.put(n,b.clone());}}
static class Transport implements GpuPolicyController.Transport {boolean acquireFails,releaseFails;int acquired;public int acquire(int d,int[]p){acquired++;return acquireFails?-1:17;}public int release(int h){return releaseFails?-1:0;}}
public static void main(String[]args)throws Exception {for(String failure:new String[]{"acquire","release"})for(String action:new String[]{"refresh","mode"}){
 SystemProperties.mode="fast";CouplingProbe c=new CouplingProbe();Disk d=new Disk();AppPolicyStore.State initial=new AppPolicyStore.State();initial.generation=1;initial.migration="00000000-0000-0000-0000-000000000000";initial.users.put(0,0L);initial.globals.put(0,new AppPolicyStore.Row(120,"fast",629,903));d.write(AppPolicyStore.ACTIVE,initial.bytes());c.mAppPolicies=new AppPolicyStore(d);Transport t=new Transport();c.mGpuPolicy=new GpuPolicyController(t);
 t.acquireFails=failure.equals("acquire");c.mGpuPolicy.resolve("org.example.a","fast",null,true,true,true);
 if(failure.equals("release")){t.releaseFails=true;c.mGpuPolicy.resolve("org.example.a","balance",null,true,true,true);}
 boolean failed=false;try{c.mAppPolicies.commit(AppPolicyStore.change(c.mAppPolicies.current,c.mAppPolicies.current.generation,0,"org.example.a",action,90,"fast",0,0,false),new AppPolicyStore.Owner(){public void prepare(AppPolicyStore.State s,String tx){}public void apply(AppPolicyStore.State s,String tx){c.applyUnifiedPolicy(s,false);}});}catch(Exception e){failed=true;c.mPolicyReady=false;System.out.println("exception="+e);}
 if(failed||c.mAppPolicies.recoveryRequired||!c.mPolicyReady)throw new AssertionError("unrelated policy blocked");
 if(!c.mGpuPolicy.runtimeStatus().equals("DEGRADED_FAIL_SAFE"))throw new AssertionError("GPU false success");
 int acquired=t.acquired;c.mGpuPolicy.resolve("org.example.a","performance",null,true,true,true);
 if(t.acquired!=acquired)throw new AssertionError("unsafe second acquisition");
 c.mPolicyReady=false;SystemProperties.mode="fast";c.mUperfScenePolicy.mInteractive=false;c.mUperfScenePolicy.reconcile("screenOff",0);
 if(!SystemProperties.mode.equals("powersave"))throw new AssertionError("screen-off safety blocked");
 System.out.println(failure+" "+action+" commitFailed="+failed+" recoveryRequired="+c.mAppPolicies.recoveryRequired+" policyReady="+c.mPolicyReady+" screenOffMode="+SystemProperties.mode+" acquisitions="+t.acquired+c.mGpuPolicy.stateLines().replace('\\n',' '));
}}
}'''
(O/'CouplingProbe.java').write_text(text,encoding='utf8')
cmd=['javac','-encoding','UTF-8','-d',str(O),str(O/'CouplingProbe.java'),*[str(src/n) for n in ['AppPolicyStore.java','PolicyJson.java','GpuPolicyController.java','GpuRange.java','PolicyRuntimePlan.java']]]
p=subprocess.run(cmd,capture_output=True);(O/'compile.txt').write_bytes(p.stdout+p.stderr);assert p.returncode==0,p.stderr.decode(errors='replace')
p=subprocess.run(['java','-cp',str(O),'com.zui.server.control.CouplingProbe'],capture_output=True);print(p.stdout.decode());assert p.returncode==0,p.stderr.decode()
print('GPU_POLICY_DEGRADED_ISOLATION_AND_SCREEN_OFF=PASS')
fixture.cleanup()
