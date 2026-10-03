package com.zui.server.control;
import java.util.*;
import static com.zui.server.control.AppPolicyFixture.*;
public final class AtomicPolicyTest {
 static AppPolicyStore.State legacy(){
  AppPolicyStore.State s=new AppPolicyStore.State();s.generation=1;s.migration="00000000-0000-0000-0000-000000000001";
  s.users.put(0,0L);s.globals.put(0,new AppPolicyStore.Row(120,"balance",231,578));
  s.apps.put("0:org.equal.app",new AppPolicyStore.Row(120,"fast",629,903));
  s.apps.put("0:org.custom.app",new AppPolicyStore.Row(90,"fast",834,903));return s;
 }
 public static void main(String[] a)throws Exception {
  AppPolicyStore.State old=legacy();byte[] original=old.bytes();
  check(Arrays.equals(original,AppPolicyStore.State.parse(original).bytes()),"V82 exact bytes until transactional migration");
  Disk d=new Disk();d.write(AppPolicyStore.ACTIVE,original);Owner o=new Owner();AppPolicyStore p=new AppPolicyStore(d);
  p.migrateGpuFollow(o);check(p.current.schema==3&&p.current.generation==2,"migration one generation");
  check(p.current.apps.get("0:org.equal.app").gpuPolicy.equals("DEFAULT_FOR_MODE"),"equal migrated follows");
  check(p.current.apps.get("0:org.custom.app").gpuPolicy.equals("CUSTOM"),"non-equal preserved");
  check(d.data.containsKey("policy-gpu-migration-1.json"),"durable migration choice receipt");
  p.migrateGpuFollow(o);check(p.current.generation==2,"migration once");
  Disk migrationCount=new Disk();migrationCount.write(AppPolicyStore.ACTIVE,original);int migrationBefore=migrationCount.writes;
  new AppPolicyStore(migrationCount).migrateGpuFollow(new Owner());int migrationWrites=migrationCount.writes-migrationBefore;
  for(int cut=1;cut<=migrationWrites;cut++)for(boolean after:new boolean[]{false,true}){
   Disk disk=new Disk();disk.write(AppPolicyStore.ACTIVE,original);disk.crashAt=disk.writes+cut;disk.after=after;
   try{new AppPolicyStore(disk).migrateGpuFollow(new Owner());}catch(Crash expected){}
   disk.crashAt=-1;AppPolicyStore recovered=new AppPolicyStore(disk);recovered.recover(new Owner());recovered.migrateGpuFollow(new Owner());
   check(recovered.current.schema==3&&recovered.current.apps.get("0:org.equal.app").gpuPolicy.equals("DEFAULT_FOR_MODE")
     &&recovered.current.apps.get("0:org.custom.app").gpuPolicy.equals("CUSTOM"),"migration retry keeps classified ranges");
  }
  AppPolicyStore.State changed=AppPolicyStore.change(p.current,2,0,"","defaultGpu",0,"fast",680,903,true);
  check(changed.resolved(0,"org.equal.app").gpuMinMHz==680,"default follows new tier");
  check(changed.resolved(0,"org.custom.app").gpuMinMHz==834,"custom unaffected");
  AppPolicyStore.State next=AppPolicyStore.draft(changed,3,0,"org.equal.app",144,"performance","CUSTOM",500,770);
  AppPolicyStore.Row row=next.resolved(0,"org.equal.app");
  check(next.generation==4&&row.refreshHz==144&&row.uperfMode.equals("performance")&&row.gpuMinMHz==500&&row.gpuMaxMHz==770,"all fields one generation");
  rejects(()->AppPolicyStore.draft(changed,2,0,"org.equal.app",144,"fast","DEFAULT_FOR_MODE",0,0));
  rejects(()->AppPolicyStore.draft(changed,3,0,"org.equal.app",144,"fast","DEFAULT_FOR_MODE",629,903));
  rejects(()->AppPolicyStore.draft(changed,3,0,"org.equal.app",144,"fast","CUSTOM",903,629));
  rejects(()->AppPolicyStore.draft(changed,3,0,"org.equal.app",144,"fast","TYPO",629,903));
  rejects(()->AppPolicyStore.draft(changed,3,10,"org.equal.app",144,"fast","CUSTOM",629,903));
  AppPolicyStore.State follow=AppPolicyStore.draft(changed,3,0,"org.equal.app",165,"fast","DEFAULT_FOR_MODE",0,0);
  check(follow.resolved(0,"org.equal.app").gpuMinMHz==680,"draft current default");
  AppPolicyStore.State refresh=AppPolicyStore.change(follow,4,0,"org.equal.app","refresh",90,"",0,0,false);
  check(refresh.apps.get("0:org.equal.app").gpuPolicy.equals("DEFAULT_FOR_MODE"),"refresh preserves default policy");
  // Every durable write boundary, before and after, must recover old or complete new state.
  Disk count=new Disk();count.write(AppPolicyStore.ACTIVE,changed.bytes());AppPolicyStore baseline=new AppPolicyStore(count);int before=count.writes;
  baseline.commit(next,new Owner());int writes=count.writes-before;
  for(int cut=1;cut<=writes;cut++)for(boolean after:new boolean[]{false,true}){
   Disk disk=new Disk();disk.write(AppPolicyStore.ACTIVE,changed.bytes());disk.crashAt=disk.writes+cut;disk.after=after;
   AppPolicyStore store=new AppPolicyStore(disk);
   try{store.commit(next,new Owner());}catch(Crash expected){}
   disk.crashAt=-1;store=new AppPolicyStore(disk);store.recover(new Owner());
   byte[] actual=store.current.bytes();check(Arrays.equals(actual,changed.bytes())||Arrays.equals(actual,next.bytes()),"no partial row cut="+cut+" after="+after);
  }
  Disk failed=new Disk();failed.write(AppPolicyStore.ACTIVE,changed.bytes());AppPolicyStore failStore=new AppPolicyStore(failed);Owner failOwner=new Owner();failOwner.failOnce=true;
  rejects(()->failStore.commit(next,failOwner));AppPolicyStore.State restored=failStore.current.copy();restored.generation=changed.generation;
  check(Arrays.equals(restored.bytes(),changed.bytes()),"owner failure restores entire policy");
  System.out.println("ATOMIC_POLICY=PASS migrate/CAS/default-follow/custom/all-write-crashes/owner-rollback checks="+checks);
 }
}
