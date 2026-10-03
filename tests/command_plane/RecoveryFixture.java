package com.zui.server.control;

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static com.zui.server.control.PolicyJson.*;

/** Generated service slices are exact; platform scene/runtime inputs are isolated fixtures. */
public class RecoveryFixture {
    static final class Crash extends Error {}
    final Map<String,byte[]> files=new TreeMap<>();
    String fault="", ack="", phase="", currentId="X";
    boolean armed, ownerUnavailable, failApply, failResultOnce, blockAfterCrash;
    int mutations;
    int callerUser;
    long projectionGeneration;
    BufferedReader reader; PrintWriter writer;
    final AppPolicyStore.Storage storage=new AppPolicyStore.Storage(){
        public byte[] read(String name){return files.getOrDefault(name,new byte[0]).clone();}
        public void write(String name,byte[] data)throws Exception {
            if(name.equals(AppPolicyStore.ACTIVE))hit("policy_durable_commit_before");
            if(name.equals("policy-request.json")&&object(parse(data)).containsKey("targetHash")
                    &&!object(parse(data)).containsKey("result"))hit("targetHash_before");
            files.put(name,data.clone());
            if(name.equals(AppPolicyStore.ACTIVE)) { mutations++; hit("policy_durable_commit"); }
            if(name.equals("policy-request.json")){
                Map<String,Object> r=object(parse(data));
                if(r.containsKey("result")){
                    if(failResultOnce){failResultOnce=false;throw new IOException("result durability reply lost");}
                    hit("policy_result_written");
                }
                else if(r.containsKey("targetHash"))hit("targetHash_written");
            }
        }
    };
    AppPolicyStore mAppPolicies;
    AppPolicyStore.Owner owner=new AppPolicyStore.Owner(){
        public void prepare(AppPolicyStore.State next,String tx)throws Exception {
            if(ownerUnavailable)throw new IOException("owner unavailable");
            hit("owner_prepare_before");
            require(project("prepare",encode(map("transaction",tx,"policy",object(parse(next.bytes()))))).equals(hash(next.bytes())),"prepare ACK");
            hit("owner_prepare_after");
        }
        public void apply(AppPolicyStore.State next,String tx)throws Exception {
            if(ownerUnavailable)throw new IOException("owner unavailable");
            if(failApply){failApply=false;throw new IOException("qualified owner refusal");}
            hit("projection_apply_before");
            require(project("apply",encode(map("transaction",tx,"hash",hash(next.bytes()),"generation",next.generation,"desiredMode",next.global(callerUser).uperfMode))).equals(hash(next.bytes())),"apply ACK");
            projectionGeneration=next.generation; hit("projection_apply_after");
        }
    };
    String project(String action,String data)throws Exception {
        writer.println("PROJECTION\t"+action+"\t"+b64(data));
        String line=reader.readLine();require(line!=null&&line.startsWith("PROJECTED\t"),"native projection reply: "+line);
        return unb64(line.substring(10));
    }
    void hit(String point){if(armed&&point.equals(fault)){armed=false;if(blockAfterCrash)ownerUnavailable=true;throw new Crash();}}
    static String b64(String v){return Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8));}
    static String unb64(String v){return new String(Base64.getDecoder().decode(v),StandardCharsets.UTF_8);}
    static final String SHA=hash("X|policy|||e30=".getBytes(StandardCharsets.UTF_8));
    final class Top {
        int stableUserId(){return callerUser;} long generation(){return 1;} String stablePackage(){return "org.example.probe";}
    }
    final Top mTopResumedState=new Top(); final boolean mScreenInteractive=true;
    String policyHome(int user){return "org.example.home";}
    static String key(int user,String pkg){return AppPolicyStore.key(user,pkg);}
    boolean isTransientPackage(String pkg){return false;}
    static final class Gpu {String runtimeStatus(){return "READY";}}
    final Gpu mGpuPolicy=new Gpu();
    static final class Monitor {void removeUser(int user){throw new AssertionError("fixture inventory unchanged");}}
    final Monitor mMonitor=new Monitor();
    void publishPolicySettings(){hit("settings_projection");}
    String mPolicyError="";
    void publishState(){hit("state_publication");}
    /* REFUSAL */
    String admit(String id,String sha256)throws Exception {
        int policyCallerUser=callerUser;Map<Integer,Long> inventory=mAppPolicies.current.users;
        RequestIdentity admitted=new RequestIdentity(callerUser,inventory.get(callerUser),id,sha256,UUID.randomUUID().toString().replace("-",""));
        Map<String,Object> policy=map("action","refresh","userId",callerUser,"packageName","org.example.probe",
            "generation",mAppPolicies.current.generation,"sceneGeneration",1,"scenePackage","org.example.probe",
            "scope","APP","value",90,"mode","","min",0,"max",0);
        /* ADMISSION */
        return "ok=1";
    }
    String execute()throws Exception {
        hit("binder_entered");
        String requestId=currentId,requestHash=hash((currentId+"|policy|||e30=").getBytes(StandardCharsets.UTF_8));
        Map<Integer,Long> users=mAppPolicies.current.users; Set<String> excluded=Collections.emptySet();
        /* POLICY */
        return string(object(parse(storage.read("policy-request.json"))).get("result"));
    }
    String reconcile()throws Exception {
        mAppPolicies=new AppPolicyStore(storage);
        return mAppPolicies.reconcileRequest(currentId,hash((currentId+"|policy|||e30=").getBytes(StandardCharsets.UTF_8)),owner,()->{publishPolicySettings();return null;});
    }
    RecoveryFixture()throws Exception {
        this(0);
    }
    RecoveryFixture(int user)throws Exception {
        callerUser=user;
        Map<Integer,Long> users=new TreeMap<>();users.put(0,0L);users.put(10,42L);
        AppPolicyStore.State initial=AppPolicyStore.migrate("version=1\n".getBytes(),"balance\n".getBytes(),new byte[0],"balance","",users,Collections.emptySet()).state;
        files.put(AppPolicyStore.ACTIVE,initial.bytes());mAppPolicies=new AppPolicyStore(storage);
        require(admit("X",SHA).equals("ok=1"),"admit X");
    }
    int peer(List<String> prefix,String name,String crash)throws Exception {
        List<String> command=new ArrayList<>(prefix);command.add(name);command.add(crash);command.add(currentId);
        RequestIdentity identity=RequestIdentity.read(object(parse(storage.read("policy-request.json"))).get("identity"));
        identity.verify(callerUser,currentId,hash((currentId+"|policy|||e30=").getBytes(StandardCharsets.UTF_8)),identity.sequence,mAppPolicies.current.users);
        command.add(String.valueOf(callerUser));command.add(identity.sequence);
        Process p=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        reader=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8));
        writer=new PrintWriter(new OutputStreamWriter(p.getOutputStream(),StandardCharsets.UTF_8),true);
        String line;
        while((line=reader.readLine())!=null){
            System.out.println("NATIVE "+name+" "+line);
            if(line.startsWith("STATE\t"))continue;
            if(line.equals("EXECUTE")||line.equals("RECONCILE")){
                try { String result=line.equals("EXECUTE")?execute():reconcile();writer.println("RESULT\t"+b64(result)); }
                catch(Crash e){writer.println("LOST");}
                catch(Exception e){writer.println("RESULT\t"+b64("pending=1\nrecoveryOutcome=STILL_RECOVERY_REQUIRED"));}
            }else if(line.startsWith("PUBLISH\t"))ack=unb64(line.substring(8));
            else if(line.startsWith("FINAL\t")){
                String[] f=line.split("\t",-1);require(f.length==3&&f[1].isEmpty(),"claim retired only when terminal");
                require(unb64(f[2]).contains(ack),"root receipt matches ACK");
            }else throw new AssertionError("unexpected frame "+line);
        }
        return p.waitFor();
    }
    public static void main(String[] args)throws Exception {
        List<String> prefix=Arrays.asList(args);
        String[] points={"before_claim","after_claim","before_binder","binder_entered","request_authenticated","targetHash_before","targetHash_written",
            "owner_prepare_before","owner_prepare_after","policy_durable_commit_before","policy_durable_commit","projection_apply_before",
            "projection_apply_after","settings_projection","policy_result_written","after_binder",
            "before_root_receipt","after_root_receipt","before_terminal_ack","after_terminal_ack","lost_client"};
        int n=0;
        for(String point:points){
            RecoveryFixture f=new RecoveryFixture(n%2==0?0:10);f.fault=point;f.armed=true;
            String name="case"+(++n);int rc=f.peer(prefix,name,point);
            require(rc==0||rc==73,"unexpected native exit "+rc+" at "+point);
            f.armed=false;require(f.peer(prefix,name,"none")==0,"replay");
            byte[] active=f.storage.read(AppPolicyStore.ACTIVE);int mutations=f.mutations;
            require(f.peer(prefix,name,"none")==0,"second replay");
            require(Arrays.equals(active,f.storage.read(AppPolicyStore.ACTIVE))&&mutations==f.mutations,"at most once replay");
            Map<String,Object> r=object(parse(f.storage.read("policy-request.json")));
            require(r.containsKey("result"),"server terminal");
            boolean applied=f.mAppPolicies.current.generation==2;
            require(f.ack.startsWith(applied?"X|done|policy|":"X|failed|policy|"),"truthful terminal "+point+":"+f.ack);
            require(f.mutations==(applied?1:0),"no duplicate generation");
            if(applied)require(f.projectionGeneration==2,"owner ACK");
            for(String key:f.files.keySet())if(key.endsWith("-previous.json"))require(f.files.get(key).length>0,"last good retained");
            System.out.println("PASS "+point+" generation="+f.mAppPolicies.current.generation+" mutations="+f.mutations+" ack="+f.ack);
            f.callerUser=n%2==0?10:0;
            require(f.admit("Y",hash("Y|policy|||e30=".getBytes(StandardCharsets.UTF_8))).equals("ok=1"),"new Y available");
            long beforeY=f.mAppPolicies.current.generation;f.currentId="Y";
            require(f.peer(prefix,name,"none")==0&&f.ack.startsWith("Y|done|policy|"),"Y succeeds automatically");
            require(f.mAppPolicies.current.generation==beforeY+1&&f.mutations==mutations+1,"Y mutates exactly once");
            require(f.mAppPolicies.current.apps.containsKey(f.callerUser+":org.example.probe"),"Y targets admitted user");
            Map<String,Object> snapshot=new TreeMap<>();for(Map.Entry<String,byte[]> entry:f.files.entrySet())snapshot.put(entry.getKey(),Base64.getEncoder().encodeToString(entry.getValue()));
            System.out.println("EVIDENCE "+point+" "+encode(snapshot));
        }
        RecoveryFixture blocked=new RecoveryFixture();blocked.ownerUnavailable=true;
        require(blocked.peer(prefix,"blocked","after_claim")==73,"crash claim");
        require(blocked.peer(prefix,"blocked","none")==74,"unresolved exit");
        require(blocked.ack.contains("|processing|")&&!object(parse(blocked.storage.read("policy-request.json"))).containsKey("result"),"no terminal while unresolved");
        require(blocked.admit("Y","b".repeat(64)).contains("policy_request_busy"),"unknown blocks Y");
        blocked.ownerUnavailable=false;require(blocked.peer(prefix,"blocked","none")==0,"self recovery");
        require(blocked.admit("Y","b".repeat(64)).equals("ok=1"),"self unblock");
        System.out.println("PASS unavailable_then_recovered");n++;
        RecoveryFixture rolled=new RecoveryFixture();rolled.failApply=true;
        require(rolled.peer(prefix,"rollback","none")==0,"rollback native");
        require(rolled.mAppPolicies.current.generation==3&&rolled.mutations==2,"one attempted commit plus governed rollback");
        require(rolled.ack.contains("failed|policy|ok=0;recoveryOutcome=FAILED_ROLLED_BACK"),"rollback terminal truth");
        require(rolled.peer(prefix,"rollback","none")==0&&rolled.mutations==2,"no repeated rollback");
        require(rolled.admit("Y","b".repeat(64)).equals("ok=1"),"rollback unblocks Y");
        System.out.println("PASS failed_rolled_back");n++;
        RecoveryFixture committed=new RecoveryFixture();committed.fault="policy_durable_commit";committed.armed=true;committed.blockAfterCrash=true;
        require(committed.peer(prefix,"committed_unavailable","none")==74,"pending after committed owner failure");
        require(committed.mutations==1&&committed.admit("Y","b".repeat(64)).contains("policy_request_busy"),"committed unknown blocks Y");
        committed.ownerUnavailable=false;
        require(committed.peer(prefix,"committed_unavailable","none")==0&&committed.mutations==1&&committed.ack.contains("APPLIED_RECOVERED"),"committed self recovery no false failure");
        System.out.println("PASS committed_owner_unavailable_then_recovered");n++;
        RecoveryFixture lostResult=new RecoveryFixture();lostResult.failResultOnce=true;
        require(lostResult.peer(prefix,"result_sync","none")==0&&lostResult.mutations==1&&lostResult.ack.startsWith("X|done|"),"durability reply lost");
        System.out.println("PASS result_durability_reply_lost");n++;
        RecoveryFixture unknown=new RecoveryFixture();unknown.fault="targetHash_written";unknown.armed=true;unknown.blockAfterCrash=true;
        require(unknown.peer(prefix,"unknown","none")==74,"retain unresolved claim");
        byte[] original=unknown.files.get(AppPolicyStore.ACTIVE).clone();
        AppPolicyStore.State alien=AppPolicyStore.State.parse(original);alien.generation+=7;
        unknown.files.put(AppPolicyStore.ACTIVE,alien.bytes());unknown.ownerUnavailable=false;
        require(unknown.peer(prefix,"unknown","none")==74,"unknown durable state is not failure or success");
        require(Arrays.equals(unknown.files.get(AppPolicyStore.ACTIVE),alien.bytes()),"unknown state never deleted");
        require(unknown.admit("Y","b".repeat(64)).contains("policy_request_busy"),"unknown blocks Y");
        // Restore only the deliberately corrupted in-memory fixture, never a device policy.
        unknown.files.put(AppPolicyStore.ACTIVE,original);
        require(unknown.peer(prefix,"unknown","none")==0&&unknown.mutations==0,"known prior state self closes");
        require(unknown.admit("Y","b".repeat(64)).equals("ok=1"),"known state unblocks Y");
        System.out.println("PASS unknown_state_retained_until_authority_known");n++;
        System.out.println("CROSS_LAYER_CASE_COUNT="+n);
    }
}
