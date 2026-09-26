package com.zui.server.control;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.zui.server.control.PolicyJson.*;

/** Bounded settings format and durable cross-owner coordinator. No record/runtime state. */
final class SettingsBackup {
    static final String JOURNAL="settings-transaction.json", PREFS="settings-preferences.json";
    static final int LIMIT=196608;
    static final String[] NAMES={"manifest.json","policy.json","zuiopt.canonical.conf","preferences.json"};
    static final String OPP_HASH=hash("231,310,366,422,500,578,629,680,720,770,834,903".getBytes(StandardCharsets.US_ASCII));
    interface Rules {
        Map<String,Object> snapshot() throws Exception;
        void prepare(String tx,String generation,byte[] rules) throws Exception;
        void apply(String tx,byte[] rules,boolean rollback) throws Exception;
        void finish(String tx) throws Exception;
    }
    static Map<String,Object> defaultPreferences(Map<Integer,Long> users){
        List<Object> rows=new ArrayList<>();
        for(Map.Entry<Integer,Long> e:users.entrySet())rows.add(map("userId",e.getKey(),"serial",e.getValue(),
            "powerPresentation","CONSUMPTION","circle_x",0.5,"circle_y",0));
        return map("schema",1,"users",rows);
    }
    static byte[] preferences(byte[] raw,Map<Integer,Long> users)throws Exception{
        Map<String,Object> value=object(parse(raw));keys(value,"schema","users");
        require(integer(value.get("schema"))==1,"preferences schema");Map<Integer,Long> seen=new TreeMap<>();
        for(Object item:array(value.get("users"))){Map<String,Object> row=object(item);
            keys(row,"userId","serial","powerPresentation","circle_x","circle_y");
            int user=AppPolicyStore.user(row.get("userId"));long serial=integer(row.get("serial"));
            require(seen.put(user,serial)==null&&Objects.equals(users.get(user),serial),"preferences user serial");
            require(Arrays.asList("CONSUMPTION","INPUT").contains(string(row.get("powerPresentation"))),"power presentation");
            for(String coordinate:new String[]{"circle_x","circle_y"}){
                Object n=row.get(coordinate);require(n instanceof Number,"position type");double d=((Number)n).doubleValue();
                require(Double.isFinite(d)&&d>=0&&d<=1,"position range");
            }
        }
        require(seen.equals(users),"preferences inventory");return bytes(value);
    }
    private static void le(ByteArrayOutputStream out,long n,int size){for(int i=0;i<size;i++)out.write((int)(n>>>(8*i))&255);}
    static byte[] zip(Map<String,byte[]> entries)throws Exception{
        ByteArrayOutputStream local=new ByteArrayOutputStream(),central=new ByteArrayOutputStream();
        for(String name:NAMES){byte[] data=entries.get(name),label=name.getBytes(StandardCharsets.US_ASCII);
            require(data!=null&&data.length>0,"archive entry");java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(data);
            int offset=local.size();ByteArrayOutputStream shared=new ByteArrayOutputStream();
            le(shared,20,2);le(shared,0x800,2);le(shared,0,2);le(shared,0,2);le(shared,33,2); // 1980-01-01, no host time
            le(shared,crc.getValue(),4);le(shared,data.length,4);le(shared,data.length,4);
            le(local,0x04034b50,4);local.write(shared.toByteArray());le(local,label.length,2);le(local,0,2);local.write(label);local.write(data);
            le(central,0x02014b50,4);le(central,0x314,2);central.write(shared.toByteArray());le(central,label.length,2);
            le(central,0,2);le(central,0,2);le(central,0,2);le(central,0,2);le(central,0100600L<<16,4);le(central,offset,4);central.write(label);
        }
        int start=local.size();local.write(central.toByteArray());le(local,0x06054b50,4);le(local,0,2);le(local,0,2);
        le(local,NAMES.length,2);le(local,NAMES.length,2);le(local,central.size(),4);le(local,start,4);le(local,0,2);
        require(local.size()<=LIMIT,"backup size");return local.toByteArray();
    }
    static byte[] archive(AppPolicyStore.State policy,Map<String,Object> rule,byte[] prefs,String build,long created)throws Exception{
        Map<String,byte[]> entries=new TreeMap<>();entries.put("policy.json",policy.bytes());
        entries.put("zuiopt.canonical.conf",Base64.getDecoder().decode(string(rule.get("data"))));
        entries.put("preferences.json",preferences(prefs,policy.users));List<Object> hashes=new ArrayList<>();
        for(int i=1;i<NAMES.length;i++){byte[] data=entries.get(NAMES[i]);hashes.add(map("name",NAMES[i],"bytes",data.length,"sha256",hash(data)));}
        Map<String,Object> manifest=map("schemaVersion",1,"kind","zui.settings.backup","productSpecVersion","1.0",
            "sourceBuild",build,"policySchema",2,"zuioptSchema",2,"targetSoC","SM8650","gpuOppSetHash",OPP_HASH,
            "createdAt",created,"userInventory",object(parse(policy.bytes())).get("users"),"entries",hashes,
            "configGeneration",map("policy",policy.generation,"rules",rule.get("generation"),"preferences",hash(prefs)));
        manifest.put("manifestHash",hash(bytes(manifest)));entries.put("manifest.json",bytes(manifest));return zip(entries);
    }
    static Map<String,byte[]> validate(byte[] archive,Map<Integer,Long> users)throws Exception{
        Map<String,byte[]> entries=UperfConfigStore.unpack(archive,NAMES,new int[]{16384,98304,65536,16384},LIMIT);
        require(Arrays.equals(zip(entries),archive),"noncanonical ZIP metadata");
        Map<String,Object> manifest=object(parse(entries.get("manifest.json")));
        keys(manifest,"schemaVersion","kind","productSpecVersion","sourceBuild","policySchema","zuioptSchema","targetSoC",
            "gpuOppSetHash","createdAt","userInventory","entries","configGeneration","manifestHash");
        String digest=string(manifest.remove("manifestHash"));require(digest.equals(hash(bytes(manifest))),"manifest hash");
        require(integer(manifest.get("schemaVersion"))==1&&"zui.settings.backup".equals(manifest.get("kind"))
            &&"1.0".equals(manifest.get("productSpecVersion"))&&integer(manifest.get("policySchema"))==2
            &&integer(manifest.get("zuioptSchema"))==2&&"SM8650".equals(manifest.get("targetSoC"))
            &&OPP_HASH.equals(manifest.get("gpuOppSetHash")),"unsupported backup compatibility");
        require(!string(manifest.get("sourceBuild")).isEmpty()&&integer(manifest.get("createdAt"))>=0,"backup provenance");
        List<Object> hashes=array(manifest.get("entries"));require(hashes.size()==3,"manifest members");
        for(int i=0;i<3;i++){Map<String,Object> entry=object(hashes.get(i));keys(entry,"name","bytes","sha256");
            byte[] data=entries.get(NAMES[i+1]);require(NAMES[i+1].equals(entry.get("name"))&&integer(entry.get("bytes"))==data.length
                &&hash(data).equals(entry.get("sha256")),"member digest/size");}
        AppPolicyStore.State policy=AppPolicyStore.State.parse(entries.get("policy.json"));
        require(policy.users.equals(users)&&Arrays.equals(bytes(manifest.get("userInventory")),bytes(object(parse(policy.bytes())).get("users"))),"backup user inventory");
        require(policy.globals.keySet().equals(users.keySet()),"complete global inventory");
        for(int user:users.keySet())for(String mode:AppPolicyStore.MODES)require(policy.defaults.containsKey(AppPolicyStore.key(user,mode)),"complete GPU defaults");
        for(String key:policy.apps.keySet())require(users.containsKey(AppPolicyStore.splitKey(key,false)),"unknown app user");
        for(String key:policy.defaults.keySet())require(users.containsKey(AppPolicyStore.splitKey(key,true)),"unknown GPU user");
        preferences(entries.get("preferences.json"),users);
        Map<String,Object> gen=object(manifest.get("configGeneration"));keys(gen,"policy","rules","preferences");
        require(integer(gen.get("policy"))==policy.generation&&string(gen.get("rules")).matches("g[0-9a-f]{24}")
            &&hash(entries.get("preferences.json")).equals(gen.get("preferences")),"backup generation vector");
        // Native owner additionally parses and round-trips canonical rules before COMMIT.
        require(!utf8(entries.get("zuiopt.canonical.conf")).contains("\r"),"rules LF encoding");return entries;
    }
    final AppPolicyStore policy;
    SettingsBackup(AppPolicyStore policy){this.policy=policy;}
    byte[] prefs()throws Exception{
        byte[] value=policy.disk.read(PREFS);return value.length==0?bytes(defaultPreferences(policy.current.users)):preferences(value,policy.current.users);
    }
    boolean busy()throws Exception{
        byte[] raw=policy.disk.read(JOURNAL);if(raw.length==0)return false;
        return !Arrays.asList("APPLIED","ROLLED_BACK").contains(object(parse(raw)).get("phase"));
    }
    byte[] export(Rules rules,String build,long time)throws Exception{
        require(!busy()&&!policy.recoveryRequired,"settings recovery required");byte[] before=policy.current.bytes(),preferences=prefs();
        Map<String,Object> snapshot=rules.snapshot();byte[] archive=archive(policy.current,snapshot,preferences,build,time);
        require(Arrays.equals(before,policy.disk.read(AppPolicyStore.ACTIVE))&&Arrays.equals(preferences,prefs())
            &&snapshot.equals(rules.snapshot()),"settings changed during export");validate(archive,policy.current.users);return archive;
    }
    void restore(byte[] archive,String transaction,AppPolicyStore.Owner owner,Rules rules)throws Exception{
        require(transaction.matches("[0-9a-f]{24}"),"restore identity");
        byte[] journal=policy.disk.read(JOURNAL);
        if(journal.length!=0&&transaction.equals(object(parse(journal)).get("transaction"))){
            Map<String,Object> prior=object(parse(journal));require(hash(archive).equals(prior.get("archiveHash")),"restore replay conflict");
            recover(owner,rules);require("APPLIED".equals(object(parse(policy.disk.read(JOURNAL))).get("phase")),"restore previously rolled back");return;
        }
        require(!busy()&&!policy.recoveryRequired,"settings busy");Map<String,byte[]> entries=validate(archive,policy.current.users);
        AppPolicyStore.State next=AppPolicyStore.State.parse(entries.get("policy.json"));next.generation=Math.addExact(policy.current.generation,1);
        // Preserve installation migration identity; archive generation is provenance, never a replayed CAS.
        next.migration=policy.current.migration;Map<String,Object> nativeBefore=rules.snapshot();
        String prefix="settings-"+transaction;
        policy.disk.write(prefix+"-policy.json",policy.current.bytes());policy.disk.write(prefix+"-prefs.json",prefs());
        policy.disk.write(prefix+"-rules.conf",Base64.getDecoder().decode(string(nativeBefore.get("data"))));
        Map<String,Object> j=map("transaction",transaction,"phase","PREPARING","archiveHash",hash(archive),"prefix",prefix,
            "policyHash",hash(policy.current.bytes()),"prefsHash",hash(prefs()),"rulesHash",hash(policy.disk.read(prefix+"-rules.conf")));
        policy.disk.write(JOURNAL,bytes(j));
        try{
            rules.prepare(transaction,string(nativeBefore.get("generation")),entries.get("zuiopt.canonical.conf"));
            owner.prepare(next,UUID.randomUUID().toString());
            j.put("phase","COMMIT_INTENT");policy.disk.write(JOURNAL,bytes(j));
            policy.commit(next,owner);j.put("policyAppliedGeneration",policy.current.generation);j.put("phase","POLICY_APPLIED");policy.disk.write(JOURNAL,bytes(j));
            rules.apply(transaction,entries.get("zuiopt.canonical.conf"),false);
            j.put("rulesAppliedHash",hash(entries.get("zuiopt.canonical.conf")));j.put("phase","RULES_APPLIED");policy.disk.write(JOURNAL,bytes(j));
            policy.disk.write(PREFS,entries.get("preferences.json"));
            require(Arrays.equals(policy.current.bytes(),policy.disk.read(AppPolicyStore.ACTIVE))&&Arrays.equals(prefs(),entries.get("preferences.json")),"settings applied readback");
            j.put("phase","APPLIED");policy.disk.write(JOURNAL,bytes(j));rules.finish(transaction);
        }catch(Exception failure){try{recover(owner,rules);
                if("APPLIED".equals(object(parse(policy.disk.read(JOURNAL))).get("phase")))return;
            }catch(Exception recovery){failure.addSuppressed(recovery);}throw failure;}
    }
    void recover(AppPolicyStore.Owner owner,Rules rules)throws Exception{
        byte[] raw=policy.disk.read(JOURNAL);if(raw.length==0)return;Map<String,Object> j=object(parse(raw));
        String tx=string(j.get("transaction")),prefix=string(j.get("prefix")),phase=string(j.get("phase"));
        require(tx.matches("[0-9a-f]{24}")&&prefix.equals("settings-"+tx),"settings journal identity");
        if(phase.equals("APPLIED")||phase.equals("ROLLED_BACK")){rules.finish(tx);return;}
        byte[] old=policy.disk.read(prefix+"-policy.json"),prefs=policy.disk.read(prefix+"-prefs.json"),canonical=policy.disk.read(prefix+"-rules.conf");
        require(hash(old).equals(j.get("policyHash"))&&hash(prefs).equals(j.get("prefsHash"))&&hash(canonical).equals(j.get("rulesHash")),"settings rollback hashes");
        policy.recover(owner);
        AppPolicyStore.State prior=AppPolicyStore.State.parse(old);
        Map<String,Object> nativeNow=rules.snapshot();
        if(string(nativeNow.get("pending")).isEmpty()&&phase.equals("PREPARING")){
            // Failed native CAS acquired no lease and changed nothing. Do not overwrite a concurrent rule writer.
            require(Arrays.equals(policy.current.bytes(),old)&&Arrays.equals(prefs(),prefs),"unexpected pre-commit mutation");
            j.put("phase","ROLLED_BACK");policy.disk.write(JOURNAL,bytes(j));return;
        }
        require(tx.equals(nativeNow.get("pending")),"RECOVERY_REQUIRED_native_lease_missing");
        rules.apply(tx,canonical,true);
        if(!Arrays.equals(policy.current.bytes(),old)){prior.generation=Math.addExact(policy.current.generation,1);policy.commit(prior,owner);}
        else {String apply=UUID.randomUUID().toString();owner.prepare(prior,apply);owner.apply(prior,apply);}
        policy.disk.write(PREFS,prefs);require(Arrays.equals(prefs(),prefs),"settings rollback readback");
        j.put("phase","ROLLED_BACK");policy.disk.write(JOURNAL,bytes(j));rules.finish(tx);
    }
}
