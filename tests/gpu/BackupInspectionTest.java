package com.zui.server.control;
import java.util.*;
import static com.zui.server.control.AppPolicyFixture.*;
import static com.zui.server.control.PolicyJson.*;
public final class BackupInspectionTest {
 public static void main(String[] args)throws Exception {
  Disk d=new Disk();AppPolicyStore.State s=AtomicPolicyTest.legacy();
  for(String mode:AppPolicyStore.MODES)s.defaults.put(AppPolicyStore.key(0,mode),s.range(0,mode));
  d.write(AppPolicyStore.ACTIVE,s.bytes());AppPolicyStore p=new AppPolicyStore(d);SettingsBackup b=new SettingsBackup(p);
  Rules rules=new Rules();byte[] archive=b.export(rules,"inspection-fixture",1),before=p.current.bytes();
  String tx="000000000000000000000071",sha=hash(archive),path="settings-upload-"+tx;
  Map<String,Object> meta=map("transaction",tx,"size",archive.length,"hash",sha);
  AppPolicyStore.Uploads.begin(meta,"boot",10);d.write(path+".json",bytes(meta));d.write(path+".zip",archive);
  rejects(()->b.confirm(tx,sha,"boot",11));
  Map<String,Object> summary=b.inspect(tx,sha,"boot",12);
  check(summary.get("compatibility").equals("PASS")&&integer(summary.get("appPolicyRows"))==2,"bounded typed preview");
  check(Arrays.equals(before,p.current.bytes())&&Arrays.equals(before,d.read(AppPolicyStore.ACTIVE))&&rules.finishes==0,"inspect no configuration or owner mutation");
  rejects(()->b.confirm(tx,"a".repeat(64),"boot",13));
  byte[] corrupt=archive.clone();corrupt[40]^=1;d.write(path+".zip",corrupt);
  rejects(()->b.confirm(tx,sha,"boot",14));d.write(path+".zip",archive);
  rejects(()->b.confirm(tx,sha,"other-boot",15));
  rejects(()->b.confirm(tx,sha,"boot",600011));
  b.confirm(tx,sha,"boot",16);check(object(parse(d.read(path+".json"))).get("confirmedHash").equals(sha),"confirm exact inspected bytes");
  meta=object(parse(d.read(path+".json")));meta.put("transferState","REJECTED");d.write(path+".json",bytes(meta));
  rejects(()->b.confirm(tx,sha,"boot",17));
  check(Arrays.equals(before,p.current.bytes()),"cancel leaves configuration unchanged");
  System.out.println("PASS backup inspection checks="+checks);
 }
}
