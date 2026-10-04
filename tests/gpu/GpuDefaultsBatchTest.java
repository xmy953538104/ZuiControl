package com.zui.server.control;
import java.util.*;
import static com.zui.server.control.PolicyJson.*;
import static com.zui.server.control.AppPolicyFixture.*;

public final class GpuDefaultsBatchTest {
 static Map<String,Object> ranges(){return map("powersave",map("min",310,"max",422),"balance",map("min",366,"max",629),
  "performance",map("min",500,"max",834),"fast",map("min",680,"max",903));}
 static AppPolicyStore.State initial(){
  AppPolicyStore.State s=AtomicPolicyTest.legacy();s.schema=3;s.users.put(10,42L);
  s.globals.put(10,new AppPolicyStore.Row(90,"performance",422,903));
  s.apps.put("0:org.equal.app",new AppPolicyStore.Row(120,"fast",629,903,"DEFAULT_FOR_MODE"));
  s.apps.put("10:org.equal.app",new AppPolicyStore.Row(90,"fast",629,903,"DEFAULT_FOR_MODE"));
  for(int user:s.users.keySet())for(String mode:AppPolicyStore.MODES)s.defaults.put(AppPolicyStore.key(user,mode),AppPolicyStore.factory(mode,false));return s;
 }
 public static void main(String[] args)throws Exception {
  AppPolicyStore.State old=initial();byte[] before=old.bytes();
  AppPolicyStore.State next=AppPolicyStore.defaultGpuBatch(old,1,0,ranges());
  check(next.generation==2&&Arrays.equals(old.bytes(),before),"one generation, original untouched");
  for(String mode:AppPolicyStore.MODES){Map<String,Object> r=object(ranges().get(mode));
   check(next.range(0,mode).minMHz==intValue(r.get("min"))&&next.range(0,mode).maxMHz==intValue(r.get("max")),"all four confirmed");}
  check(next.apps.equals(old.apps)&&next.apps.get("0:org.equal.app")==old.apps.get("0:org.equal.app"),"no app row rewrite");
  check(next.resolved(0,"org.equal.app").gpuMinMHz==680&&next.resolved(0,"org.custom.app").gpuMinMHz==834,"follow versus custom");
  check(next.global(0).uperfMode.equals("balance")&&next.global(0).gpuMinMHz==366&&next.resolved(0,"org.unknown").gpuMaxMHz==629,"global mode coherent");
  check(next.global(10)==old.global(10)&&next.resolved(10,"org.equal.app").gpuMinMHz==629,"secondary isolation");
  AppPolicyStore.State secondary=AppPolicyStore.defaultGpuBatch(next,2,10,ranges());
  check(secondary.global(10).gpuMinMHz==500&&secondary.global(0)==next.global(0)&&secondary.users.equals(old.users),"secondary admitted serial preserved");
  rejects(()->AppPolicyStore.defaultGpuBatch(old,0,0,ranges()));rejects(()->AppPolicyStore.defaultGpuBatch(old,1,11,ranges()));
  Map<String,Object> invalid=ranges();invalid.put("fast",map("min",903,"max",629));
  rejects(()->AppPolicyStore.defaultGpuBatch(old,1,0,invalid));check(Arrays.equals(old.bytes(),before),"fourth range invalid whole rejection");
  Map<String,Object> missing=ranges();missing.remove("fast");rejects(()->AppPolicyStore.defaultGpuBatch(old,1,0,missing));
  Map<String,Object> extra=ranges();extra.put("turbo",map("min",680,"max",903));rejects(()->AppPolicyStore.defaultGpuBatch(old,1,0,extra));
  Disk count=new Disk();count.write(AppPolicyStore.ACTIVE,before);AppPolicyStore probe=new AppPolicyStore(count);int start=count.writes;
  Owner owner=new Owner();probe.commit(next,owner);int boundaries=count.writes-start;
  check(owner.applies==1&&owner.prepares==1,"one owner transaction");
  for(int cut=1;cut<=boundaries;cut++)for(boolean after:new boolean[]{false,true}){
   Disk d=new Disk();d.write(AppPolicyStore.ACTIVE,before);d.crashAt=d.writes+cut;d.after=after;
   try{new AppPolicyStore(d).commit(next,new Owner());throw new AssertionError("missing cut");}catch(Crash expected){}
   d.crashAt=-1;AppPolicyStore recovered=new AppPolicyStore(d);recovered.recover(new Owner());
   check(Arrays.equals(recovered.current.bytes(),before)||Arrays.equals(recovered.current.bytes(),next.bytes()),"atomic recovery cut="+cut+" after="+after);
  }
  Disk failed=new Disk();failed.write(AppPolicyStore.ACTIVE,before);AppPolicyStore rollback=new AppPolicyStore(failed);Owner refusing=new Owner();refusing.failOnce=true;
  rejects(()->rollback.commit(next,refusing));AppPolicyStore.State restored=rollback.current.copy();restored.generation=old.generation;
  check(Arrays.equals(restored.bytes(),before),"rollback all four together");
  Map<String,Object> request=map("id","batch","sha","a".repeat(64),"payload",map("action","defaultGpuBatch","userId",0,"generation",1,"ranges",ranges()),
   "previousHash",hash(before),"targetHash",hash(next.bytes()));
  count.write("policy-request.json",bytes(request));int applies=owner.applies;
  String reply=probe.reconcileRequest("batch","a".repeat(64),owner,()->null);
  check(reply.contains("policyGeneration=2")&&reply.contains("gpuDefaults="),"lost reply supplies all confirmed ranges");
  check(encode(object(parse(reply.substring(reply.indexOf("gpuDefaults=")+12).getBytes()))).equals(encode(ranges())),"exact confirmed ranges");
  check(probe.reconcileRequest("batch","a".repeat(64),owner,()->null).equals(reply)&&owner.applies==applies+1,"replay does not mutate or reapply");
  backupTests(next);
  SettingsBackup backup=new SettingsBackup(probe);Map<String,byte[]> archive=SettingsBackup.validate(backup.factoryArchive(0,FactoryResetTest.rules(new Rules()),"fixture",0),next.users);
  AppPolicyStore.State reset=AppPolicyStore.State.parse(archive.get("policy.json"));
  for(String mode:AppPolicyStore.MODES)check(reset.range(0,mode).minMHz==AppPolicyStore.factory(mode,false).minMHz,"same validated reset builder");
  check(reset.global(10).gpuMinMHz==next.global(10).gpuMinMHz,"factory secondary isolation");
  System.out.println("GPU_DEFAULTS_ATOMIC_BATCH=PASS checks="+checks+" journalBoundaries="+boundaries);
 }
}
