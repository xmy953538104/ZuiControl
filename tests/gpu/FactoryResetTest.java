package com.zui.server.control;
import java.util.*;
import static com.zui.server.control.AppPolicyFixture.*;
import static com.zui.server.control.PolicyJson.*;
public final class FactoryResetTest {
 static final byte[] UPSTREAM=b("schema 2\nenabled true\ndebug false\n");
 static SettingsBackup.Rules rules(Rules owner){return new SettingsBackup.Rules(){
  public Map<String,Object> snapshot(){return owner.snapshot();}
  public Map<String,Object> upstream(){return map("generation",owner.generation,"data",Base64.getEncoder().encodeToString(UPSTREAM));}
  public void prepare(String t,String g,byte[] d){owner.prepare(t,g,d);}
  public void apply(String t,byte[] d,boolean r)throws Exception{owner.apply(t,d,r);}
  public void finish(String t){owner.finish(t);}
 };}
 static AppPolicyStore.State initial(){
  AppPolicyStore.State s=AppPolicyStore.gpuFollowMigration(AtomicPolicyTest.legacy());
  s.users.put(10,10L);s.globals.put(10,new AppPolicyStore.Row(165,"fast",834,903));s.apps.put("10:org.keep.app",new AppPolicyStore.Row(90,"performance",680,903));
  for(int user:s.users.keySet())for(String mode:AppPolicyStore.MODES)s.defaults.put(AppPolicyStore.key(user,mode),new GpuRange(834,903));return s;
 }
 public static void main(String[] args)throws Exception{
  AppPolicyStore.State initial=initial();Disk disk=new Disk();disk.write(AppPolicyStore.ACTIVE,initial.bytes());disk.write("monitor.db",b("record sentinel"));
  AppPolicyStore p=new AppPolicyStore(disk);SettingsBackup backup=new SettingsBackup(p);Rules r=new Rules();SettingsBackup.Rules rules=rules(r);
  byte[] archive=backup.factoryArchive(0,rules,"factory-fixture",0);Map<String,byte[]> entries=SettingsBackup.validate(archive,initial.users);
  AppPolicyStore.State reset=AppPolicyStore.State.parse(entries.get("policy.json"));
  check(reset.apps.size()==1&&reset.apps.containsKey("10:org.keep.app"),"only current user AppPolicies reset");
  check(reset.global(0).refreshHz==120&&reset.global(0).uperfMode.equals("balance")&&reset.global(10).refreshHz==165,"current global factory/other user preserved");
  for(String mode:AppPolicyStore.MODES)check(reset.range(0,mode).minMHz==AppPolicyStore.factory(mode,false).minMHz&&reset.range(10,mode).minMHz==834,"per user GPU defaults");
  check(Arrays.equals(entries.get("zuiopt.canonical.conf"),UPSTREAM)&&Arrays.equals(p.current.bytes(),initial.bytes()),"archive uses upstream without mutation");
  Owner owner=new Owner();int begin=disk.writes;backup.restore(archive,"000000000000000000000091",owner,rules);int count=disk.writes-begin;
  check(Arrays.equals(disk.read("monitor.db"),b("record sentinel"))&&Arrays.equals(UPSTREAM,Base64.getDecoder().decode(string(rules.upstream().get("data")))),"record/upstream preserved");
  for(boolean after:new boolean[]{false,true})for(int point=1;point<=count;point++){
   Disk d=new Disk();d.write(AppPolicyStore.ACTIVE,initial.bytes());d.write("monitor.db",b("record sentinel"));
   AppPolicyStore store=new AppPolicyStore(d);SettingsBackup b=new SettingsBackup(store);Rules nativeOwner=new Rules();SettingsBackup.Rules transport=rules(nativeOwner);Owner o=new Owner();
   d.after=after;d.crashAt=d.writes+point;
   try{b.restore(archive,"000000000000000000000092",o,transport);}catch(Crash expected){}
   d.crashAt=-1;store=new AppPolicyStore(d);b=new SettingsBackup(store);b.recover(o,transport);
   AppPolicyStore.State actual=store.current;boolean applied=actual.apps.size()==1;
   AppPolicyStore.State expected=(applied?reset:initial).copy();expected.generation=actual.generation;
   check(Arrays.equals(expected.bytes(),actual.bytes()),"reset fault no partial fields "+point+" after="+after);
   check(Arrays.equals(d.read("monitor.db"),b("record sentinel")),"reset fault records preserved");
  }
  System.out.println("PASS factory reset current-user/whole-transaction fault matrix checks="+checks);
 }
}
