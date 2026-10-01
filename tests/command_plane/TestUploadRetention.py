"""Exercise the service's exact backup transport, with only disk/clock fixtures."""
from pathlib import Path
import subprocess, tempfile
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'framework_patch/src/services/com/zui/server/control'
source=(BASE/'ZuiControlService.java').read_text('utf8')
start=source.index('    private synchronized String settingsCommand(')
method=source[start:source.index('    private synchronized String setGlobalGpuRange(',start)]
method=method.replace('android.os.Build.FINGERPRINT','"fixture-build"')
harness='''package com.zui.server.control;
import java.util.*;import java.nio.charset.StandardCharsets;
import static com.zui.server.control.PolicyJson.*;
public class RetentionFixture {
 final AppPolicyFixture.Disk disk=new AppPolicyFixture.Disk();AppPolicyStore mAppPolicies;
 final String mUploadEpoch="fixture-process";
 static class IBinder{}
 static class Binder{static long clearCallingIdentity(){return 0;}static void restoreCallingIdentity(long x){}}
 static class PolicyCommand{
  static final AppPolicyFixture.Owner OWNER=new AppPolicyFixture.Owner();static final AppPolicyFixture.Rules RULES=new AppPolicyFixture.Rules();
  static AppPolicyStore.Owner owner(IBinder remote,Runnable apply){return OWNER;}
  static SettingsBackup.Rules rules(IBinder remote){return RULES;}
 }
 static class Monitor{void invalidatePower(){}}final Monitor mMonitor=new Monitor();
 void applyUnifiedPolicy(){}void publishPolicySettings(){}
 Set<String> policyExcluded(Map<Integer,Long> users){return Collections.emptySet();}
 boolean isTransientPackage(String pkg){return false;}
 String safe(String value){return value==null?"":value;}
 UtilityTransport utilities(){return new UtilityTransport(disk,policyUsers());}
 String sha256(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
 static class SystemClock {static long time=1000;static long elapsedRealtime(){return time;}}
 Map<Integer,Long> policyUsers(){return Collections.singletonMap(0,0L);}
 RetentionFixture()throws Exception{disk.write(AppPolicyStore.ACTIVE,AppPolicyStore.migrate("version=1\\n".getBytes(),"balance\\n".getBytes(),new byte[0],"balance","",policyUsers(),Collections.emptySet()).state.bytes());mAppPolicies=new AppPolicyStore(disk);}
'''+method+'''
 public static void main(String[] args)throws Exception{
 RetentionFixture f=new RetentionFixture();byte[] payload=new byte[8192];Arrays.fill(payload,(byte)71);
 int accepted=0,rejected=0;
 for(int i=0;i<300;i++){
  SystemClock.time+=600001;String tx=String.format("%024x",i);
  try{f.settingsTransport("backupBegin",encode(map("transaction",tx,"size",payload.length,"hash",hash(payload))),0);
   f.settingsTransport("backupChunk",encode(map("transaction",tx,"offset",0,"data",Base64.getEncoder().encodeToString(payload))),0);accepted++;
  }catch(IllegalArgumentException|IllegalStateException bounded){rejected++;}
 }
 long count=0,bytes=0;for(String name:f.disk.names())if(name.startsWith("settings-upload-")&&name.endsWith(".zip")){count++;bytes+=f.disk.read(name).length;}
 System.out.println("attempts=300 accepted="+accepted+" rejected="+rejected+" payloadCount="+count+" payloadBytes="+bytes);
 if(count>16||bytes>16L*SettingsBackup.LIMIT)throw new AssertionError("abandoned restore uploads unbounded");
 if(accepted==0)throw new AssertionError("all legitimate uploads refused");
 byte[] policyBefore=f.disk.read(AppPolicyStore.ACTIVE);
 for(int i=300;i<600;i++){
  String tx=String.format("%024x",i);
  f.settingsTransport("backupBegin",encode(map("transaction",tx,"size",payload.length,"hash",hash(payload))),0);
  f.settingsTransport("backupChunk",encode(map("transaction",tx,"offset",0,"data",Base64.getEncoder().encodeToString(payload))),0);
  if(!f.settingsCommand("sb_restore",tx,null).startsWith("ok=0"))throw new AssertionError("invalid restore accepted");
  if(!object(parse(f.disk.read("settings-upload-"+tx+".json"))).get("transferState").equals("REJECTED"))throw new AssertionError("rejection not durable");
 }
 count=0;for(String name:f.disk.names())if(name.endsWith(".zip"))count++;
 if(count>2||!Arrays.equals(policyBefore,f.disk.read(AppPolicyStore.ACTIVE)))throw new AssertionError("rejected restore retention or configuration");
 String keep="d".repeat(24);
 f.settingsTransport("backupBegin",encode(map("transaction",keep,"size",payload.length,"hash",hash(payload))),0);
 f.settingsTransport("backupChunk",encode(map("transaction",keep,"offset",0,"data",Base64.getEncoder().encodeToString(payload))),0);
 String request="restore|sb_restore|||"+keep;f.utilities().stage(0,request);
 RequestIdentity id=new RequestIdentity(0,0L,"restore",f.sha256(request),"1".repeat(32));
 f.disk.write("control-admission.json",bytes(map("identity",id.json(),"terminal",false)));
 SystemClock.time+=600001;
 f.settingsTransport("backupBegin",encode(map("transaction","e".repeat(24),"size",payload.length,"hash",hash(payload))),0);
 if(f.disk.read("settings-upload-"+keep+".zip").length!=payload.length)throw new AssertionError("admitted restore payload deleted");
 System.out.println("INVALID_RESTORE_300_BOUNDED=PASS ADMITTED_RESTORE_PRESERVED=PASS");
 }
}'''
with tempfile.TemporaryDirectory(prefix='upload-retention-') as tmp:
    file=Path(tmp)/'RetentionFixture.java';file.write_text(harness,encoding='utf8')
    names=['PolicyJson.java','GpuRange.java','AppPolicyStore.java','SettingsBackup.java','UperfConfigStore.java','UtilityTransport.java','RequestIdentity.java']
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*[str(BASE/n) for n in names],str(ROOT/'tests/gpu/AppPolicyFixture.java'),str(file)],check=True)
    subprocess.run(['java','-cp',tmp,'com.zui.server.control.RetentionFixture'],check=True)
