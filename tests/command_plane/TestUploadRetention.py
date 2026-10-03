"""Exercise the service's exact backup transport, with only disk/clock fixtures."""
from pathlib import Path
import subprocess, tempfile
from UtilityWireContract import java_encoder
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
 String mObservedRuleGeneration="UNAVAILABLE";
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
'''+java_encoder()+method+'''
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
 String request=appWire("restore","sb_restore",keep,"");f.utilities().stage(0,request);
 RequestIdentity id=new RequestIdentity(0,0L,"restore",f.sha256(request),"1".repeat(32));
 f.disk.write("control-admission.json",bytes(map("identity",id.json(),"terminal",false)));
 SystemClock.time+=600001;
 f.settingsTransport("backupBegin",encode(map("transaction","e".repeat(24),"size",payload.length,"hash",hash(payload))),0);
 if(f.disk.read("settings-upload-"+keep+".zip").length!=payload.length)throw new AssertionError("admitted restore payload deleted");
 // Same authenticated request remains protected after terminal ACK while replay-referenced.
 f.disk.write("control-admission.json",bytes(map("identity",id.json(),"terminal",true)));
 if(!f.settingsUploadProtection().contains(keep))throw new AssertionError("replay reference lost");
 String resetTx="c".repeat(24),resetWire=appWire("reset","sb_reset",resetTx,"");
 f.utilities().stage(0,resetWire);
 RequestIdentity resetId=new RequestIdentity(0,0L,"reset",f.sha256(resetWire),"3".repeat(32));
 f.disk.write("control-admission.json",bytes(map("identity",resetId.json(),"terminal",false)));
 if(!f.settingsUploadProtection().contains(resetTx))throw new AssertionError("factory reset recovery archive not protected");
 f.disk.write("control-admission.json",bytes(map("identity",id.json(),"terminal",true)));
 for(String[] args2:new String[][]{{"",keep},{"",""},{keep,keep},{"bad",""}}){
  RetentionFixture bad=new RetentionFixture();String wire=appWire("bad","sb_restore",args2[0],args2[1]);
  bad.utilities().stage(0,wire);RequestIdentity bid=new RequestIdentity(0,0L,"bad",bad.sha256(wire),"2".repeat(32));
  bad.disk.write("control-admission.json",bytes(map("identity",bid.json(),"terminal",false)));
  if(!bad.settingsUploadProtection().isEmpty())throw new AssertionError("invalid restore wire protected");
 }
 for(RequestIdentity bad:new RequestIdentity[]{
   new RequestIdentity(0,0L,"other",id.sha,id.sequence),new RequestIdentity(0,0L,id.id,"0".repeat(64),id.sequence),
   new RequestIdentity(1,0L,id.id,id.sha,id.sequence),new RequestIdentity(0,0L,id.id,id.sha,"2".repeat(32))}){
  boolean denied=false;try{f.utilities().admitted(bad);}catch(IllegalArgumentException expected){denied=true;}
  if(!denied)throw new AssertionError("admission identity mismatch accepted");
 }
 // Real protection rejects fabricated hash/ID/serial; no request text alone grants protection.
 f.disk.write("control-admission.json",bytes(map("identity",new RequestIdentity(0,0L,"restore","0".repeat(64),id.sequence).json(),"terminal",false)));
 if(f.settingsUploadProtection().contains(keep))throw new AssertionError("wrong hash protected");
 f.disk.write("control-admission.json",bytes(map("identity",new RequestIdentity(1,0L,"restore",id.sha,id.sequence).json(),"terminal",false)));
 if(f.settingsUploadProtection().contains(keep))throw new AssertionError("wrong user protected");
 f.disk.remove("control-admission.json");
 f.disk.write(SettingsBackup.JOURNAL,bytes(map("transaction",keep)));
 AppPolicyStore.Uploads.prune(f.disk,"settings-upload-",".zip",f.mUploadEpoch,SystemClock.time,f.settingsUploadProtection(),SettingsBackup.LIMIT);
 if(f.disk.read("settings-upload-"+keep+".zip").length!=payload.length)throw new AssertionError("journal upload deleted");
 f.disk.remove(SettingsBackup.JOURNAL);
 Map<String,Object> done=object(parse(f.disk.read("settings-upload-"+keep+".json")));done.put("completed",true);f.disk.write("settings-upload-"+keep+".json",bytes(done));
 AppPolicyStore.Uploads.prune(f.disk,"settings-upload-",".zip",f.mUploadEpoch,SystemClock.time,Collections.emptySet(),SettingsBackup.LIMIT);
 if(f.disk.read("settings-upload-"+keep+".zip").length!=0)throw new AssertionError("unreferenced completed upload retained");
 if(f.disk.read("settings-upload-"+"e".repeat(24)+".json").length==0)throw new AssertionError("active current upload deleted");
 f.disk.write("unknown-payload",payload);f.disk.write("settings-upload-"+"f".repeat(24)+".json",bytes(map("size",8192)));
 AppPolicyStore.Uploads.prune(f.disk,"settings-upload-",".zip",f.mUploadEpoch,SystemClock.time+700000,Collections.emptySet(),SettingsBackup.LIMIT);
 if(f.disk.read("unknown-payload").length!=8192||f.disk.read("settings-upload-"+"f".repeat(24)+".json").length==0)throw new AssertionError("unknown file deleted");
 System.out.println("REAL_WIRE_NEGATIVES=PASS JOURNAL_REPLAY_PROTECTION=PASS COMPLETED_RETIREMENT=PASS NO_ACTIVE_UPLOAD_DELETION=PASS");
 System.out.println("INVALID_RESTORE_300_BOUNDED=PASS ADMITTED_RESTORE_PRESERVED=PASS");
 }
}'''
with tempfile.TemporaryDirectory(prefix='upload-retention-') as tmp:
    file=Path(tmp)/'RetentionFixture.java';file.write_text(harness,encoding='utf8')
    names=['PolicyJson.java','GpuRange.java','AppPolicyStore.java','SettingsBackup.java','UperfConfigStore.java','UtilityTransport.java','RequestIdentity.java']
    subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*[str(BASE/n) for n in names],str(ROOT/'tests/gpu/AppPolicyFixture.java'),str(file)],check=True)
    subprocess.run(['java','-cp',tmp,'com.zui.server.control.RetentionFixture'],check=True)
