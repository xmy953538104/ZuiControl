package com.zui.server.control;
import java.util.*;
import static com.zui.server.control.PolicyJson.*;
public class UtilityFixture {
 static int checks;
 interface Work {void run()throws Exception;}
 static void check(boolean value){if(!value)throw new AssertionError("utility check "+checks);checks++;}
 static void rejects(Work work)throws Exception{try{work.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("accepted invalid utility");}
 static RequestIdentity identity(int user,String request,Map<Integer,Long> users){return new RequestIdentity(user,users.get(user),UtilityTransport.fields(request)[0],hash(request.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"1".repeat(32));}
 public static void main(String[] args)throws Exception{
  AppPolicyFixture.Disk disk=new AppPolicyFixture.Disk();Map<Integer,Long> users=new TreeMap<>();users.put(0,0L);users.put(10,42L);
  UtilityTransport t=new UtilityTransport(disk,users);String first="first|export_logs|||";RequestIdentity id=identity(0,first,users);
  t.stage(0,first);t.publish(id,"logs","CURRENT-REQUEST-PRIVATE-LOG");rejects(()->t.result(0,"first","logs"));
  t.acknowledge(id,"first|done|export_logs|logs=exported");check(t.result(0,"first","logs").equals("CURRENT-REQUEST-PRIVATE-LOG"));
  rejects(()->t.result(10,"first","logs"));rejects(()->t.publish(id,"rulesChunk","wrong kind"));
  rejects(()->t.publish(id,"logs","x".repeat(UtilityTransport.RESULT_LIMIT+1)));
  rejects(()->t.stage(0,"first|export_logs||changed|"));
  t.stage(0,"second|export_logs|||");rejects(()->t.result(0,"first","logs"));rejects(()->t.result(0,"second","logs"));
  rejects(()->t.publish(id,"logs","stale response"));
  RequestIdentity second=identity(0,t.request(0),users);
  disk.write("control-admission.json",bytes(map("identity",second.json(),"terminal",false)));
  rejects(()->t.stage(0,"third|export_logs|||"));check(t.request(0).startsWith("second|"));
  t.stage(0,t.request(0)); // Exact re-kick preserves current slot, including uncertain recovery.
  t.stage(10,"secondary|zo_state|||");RequestIdentity secondary=identity(10,t.request(10),users);
  t.publish(secondary,"rulesState","GENERATION");t.acknowledge(secondary,"secondary|done|zo_state|ok");
  check(t.result(10,"secondary","rulesState").equals("GENERATION"));users.put(10,43L);
  rejects(()->t.result(10,"secondary","rulesState"));rejects(()->t.publish(secondary,"rulesState","stale user"));
  t.stage(10,"newuser|zo_state|||");check(t.request(10).startsWith("newuser|"));
  users.remove(10);rejects(()->t.request(10));
  // Quota preserves every live transaction; expiry permits only unreferenced cleanup.
  AppPolicyFixture.Disk uploads=new AppPolicyFixture.Disk();Set<String> keep=new HashSet<>();
  for(int i=0;i<16;i++){String tx=String.format("%024x",i);AppPolicyStore.Uploads.quota(uploads,"settings-upload-",".zip",196608);
   Map<String,Object> meta=map("size",8192,"hash","a".repeat(64));AppPolicyStore.Uploads.begin(meta,"epoch",1000);
   uploads.write("settings-upload-"+tx+".json",bytes(meta));uploads.write("settings-upload-"+tx+".zip",new byte[8192]);if(i==0)keep.add(tx);
  }
  AppPolicyStore.Uploads.prune(uploads,"settings-upload-",".zip","epoch",2000,keep,196608);
  check(uploads.names().length==32);rejects(()->AppPolicyStore.Uploads.quota(uploads,"settings-upload-",".zip",196608));
  AppPolicyStore.Uploads.prune(uploads,"settings-upload-",".zip","epoch",700000,keep,196608);
  check(uploads.names().length==2&&uploads.read("settings-upload-"+String.format("%024x",0)+".zip").length==8192);
  uploads.write("settings-upload-"+"f".repeat(24)+".json",bytes(map("size",8192,"hash","a".repeat(64))));
  uploads.write("settings-upload-"+"f".repeat(24)+".zip",new byte[8192]);uploads.write("unknown-file",new byte[]{1});
  AppPolicyStore.Uploads.prune(uploads,"settings-upload-",".zip","different",900000,keep,196608);
  check(uploads.names().length==5);check(uploads.read("unknown-file").length==1);
  System.out.println("PRIVATE_UTILITY_AND_REFERENCED_RETENTION=PASS checks="+checks);
 }
}
