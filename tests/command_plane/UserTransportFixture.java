package com.zui.server.control;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import static com.zui.server.control.PolicyJson.*;

public class UserTransportFixture {
    static final String PROP_COMMAND_ID="id",PROP_COMMAND_SHA256="hash",PROP_COMMAND_SEQ="seq",SETTING_REQUEST_TEXT="request",SETTING_UPERF_RULES="zui_control_uperf_rules_text",TIMING_TAG="timing",TAG="test";
    final AppPolicyFixture.Disk disk=new AppPolicyFixture.Disk();
    AppPolicyStore mAppPolicies;
    final Map<Integer,Long> inventory=new TreeMap<>();
    final Map<Integer,Map<String,String>> settings=new HashMap<>();
    final List<Integer> reads=new ArrayList<>(),writes=new ArrayList<>();
    static class Binder {static int uid;static int getCallingUid(){return uid;}static long clearCallingIdentity(){return 1;}static void restoreCallingIdentity(long t){}}
    static class SystemClock {static long n;static long elapsedRealtimeNanos(){return ++n;}}
    static class Log {static void i(String a,String b){} }
    static class SystemProperties {static Map<String,String> values=new HashMap<>();static int kicks;static String get(String k,String d){return values.getOrDefault(k,d);}static void set(String k,String v){values.put(k,v);if(k.equals(PROP_COMMAND_SEQ))kicks++;}}
    Map<Integer,Long> policyUsers(){return inventory;}
    String getSettingForUser(String key,int user){reads.add(user);return settings.computeIfAbsent(user,u->new HashMap<>()).get(key);}
    boolean putSettingForUser(String key,String value,int user){writes.add(user);settings.computeIfAbsent(user,u->new HashMap<>()).put(key,value);return true;}
    static String safe(String v){return v==null?"":v;}
    static class Process {static final int SYSTEM_UID=1000;}
    static class Top {int user;int stableUserId(){return user;}}
    int mCurrentUserId;final Top mTopResumedState=new Top();
    String state(){return "ok=1\nprofile=0|primary.app\nprofile=10|secondary.app\ngpuProfile=0|primary.app\ngpuGlobal=10|balance\nrawFocusedPackage=old.user.app\nlastError=old.user.app";}
    String currentSceneState(){return "ok=1\ncurrentScenePackage=active.app";}
    /* CALLER STATE */
    void checkInventory(int callerUser){ /* INVENTORY GUARD */ }
    /* PRODUCTION METHODS */
    /* VALIDATORS */
    interface Work {void run()throws Exception;}
    static int checks;
    static void check(boolean v){if(!v)throw new AssertionError("check "+checks);checks++;}
    void rejects(Work work)throws Exception{int r=reads.size(),w=writes.size(),d=disk.writes;try{work.run();}catch(IllegalArgumentException expected){check(reads.size()==r&&writes.size()==w&&disk.writes==d);return;}throw new AssertionError("invalid identity accepted");}
    UserTransportFixture()throws Exception{
        inventory.put(0,0L);inventory.put(10,42L);
        AppPolicyStore.State state=AppPolicyStore.migrate("version=1\n".getBytes(),"balance\n".getBytes(),new byte[0],"balance","",inventory,Collections.emptySet()).state;
        disk.write(AppPolicyStore.ACTIVE,state.bytes());mAppPolicies=new AppPolicyStore(disk);
    }
    String request(String id,int user){return id+"|policy|||"+Base64.getEncoder().encodeToString(bytes(map("userId",user,"generation",1)));}
    RequestIdentity admit(String id,int user)throws Exception{
        String text=request(id,user);utilities().stage(user,text);Binder.uid=user*100000+10042;
        check(notifyControlRequest(id,sha256(text)).startsWith("ok=1"));
        return RequestIdentity.read(object(parse(disk.read("control-admission.json"))).get("identity"));
    }
    String transport(RequestIdentity identity,int user,String action,String value)throws Exception{
        return commandTransport(identity.id,identity.sha,identity.sequence,user,SystemProperties.get("seq",""),action,value);
    }
    void terminal(RequestIdentity identity)throws Exception{
        Map<String,Object> p=object(parse(disk.read("policy-request.json")));p.put("result","ok=1\npolicyGeneration=2");disk.write("policy-request.json",bytes(p));
        check(transport(identity,identity.user,"ack",identity.id+"|done|policy|ok").equals("ok=1"));
    }
    RequestIdentity utility(String request)throws Exception{
        Binder.uid=10253;check(utilityCommand("submit",request).startsWith("ok=1"));
        return RequestIdentity.read(object(parse(disk.read("control-admission.json"))).get("identity"));
    }
    boolean isTerminal()throws Exception{return Boolean.TRUE.equals(object(parse(disk.read("control-admission.json"))).get("terminal"));}
    static void monotonicity()throws Exception{
        UserTransportFixture f=new UserTransportFixture();String text="terminal_done|zo_state|||";
        RequestIdentity id=f.utility(text);check(!f.isTerminal());
        check(f.transport(id,0,"ack",id.id+"|processing|zo_state|running").equals("ok=1"));check(!f.isTerminal());
        int busyKicks=SystemProperties.kicks;
        check(f.utilityCommand("submit","different|zo_state|||").contains("request_busy"));
        check(SystemProperties.kicks==busyKicks&&!f.isTerminal());
        f.utilities().publish(id,"rulesState","generation=g"+"a".repeat(24));
        String ack=id.id+"|done|zo_state|zuiopt=state";
        check(f.transport(id,0,"ack",ack).equals("ok=1"));check(f.isTerminal());
        byte[] slot=f.disk.read("utility-u0.json");String seq=SystemProperties.get("seq","");int kicks=SystemProperties.kicks;
        for(int i=0;i<100;i++){
            String replay=f.utilityCommand("submit",text);
            check(replay.contains("ADMITTED_TERMINAL")&&replay.contains("ack="+ack));
            check(f.isTerminal()&&Arrays.equals(slot,f.disk.read("utility-u0.json"))
                    &&SystemProperties.kicks==kicks&&SystemProperties.get("seq","").equals(seq));
        }
        f.rejects(()->f.transport(id,0,"ack",id.id+"|processing|zo_state|late"));
        f.rejects(()->f.utilities().publish(id,"rulesState","changed terminal result"));
        Map<String,Object> contradiction=object(parse(f.disk.read("control-admission.json")));contradiction.put("terminal",false);
        f.disk.write("control-admission.json",bytes(contradiction));
        check(f.utilityCommand("submit",text).contains("ADMITTED_TERMINAL"));
        check(f.isTerminal()&&SystemProperties.kicks==kicks&&Arrays.equals(slot,f.disk.read("utility-u0.json")));
        f.disk.write("control-admission.json",bytes(contradiction));
        f.retireSensitiveSettings(0);
        check(f.isTerminal()&&SystemProperties.kicks==kicks&&Arrays.equals(slot,f.disk.read("utility-u0.json")));
        check(!f.utilityCommand("submit",text.replace("|||","||changed|")).startsWith("ok=1"));
        Binder.uid=1010253;check(!f.notifyControlRequest(id.id,id.sha,10).startsWith("ok=1"));
        Binder.uid=10253;f.inventory.put(0,1L);check(!f.notifyControlRequest(id.id,id.sha,0).startsWith("ok=1"));f.inventory.put(0,0L);
        // Different submit closes only exact old terminal facts before replacing its slot.
        f.disk.write("control-admission.json",bytes(contradiction));
        RequestIdentity failed=f.utility("terminal_failed|zo_validate|||");check(!f.isTerminal());
        check(f.transport(failed,0,"ack",failed.id+"|failed|zo_validate|invalid_rule").equals("ok=1"));check(f.isTerminal());
        byte[] failedSlot=f.disk.read("utility-u0.json");int failedKicks=SystemProperties.kicks;
        check(f.utilityCommand("submit","terminal_failed|zo_validate|||").contains("ADMITTED_TERMINAL"));
        check(f.isTerminal()&&failedKicks==SystemProperties.kicks&&Arrays.equals(failedSlot,f.disk.read("utility-u0.json")));
        // Corrupt ACK identity/command and request hash never repair a false admission.
        Map<String,Object> admission=object(parse(f.disk.read("control-admission.json")));admission.put("terminal",false);
        for(String invalidAck:new String[]{"other|done|zo_validate|x",failed.id+"|done|other|x"}){
            f.disk.write("control-admission.json",bytes(admission));Map<String,Object> bad=object(parse(failedSlot));bad.put("ack",invalidAck);f.disk.write("utility-u0.json",bytes(bad));
            f.rejects(()->f.utilities().reconcileTerminal(0));check(!f.isTerminal());
        }
        f.disk.write("utility-u0.json",failedSlot);f.rejects(()->f.utilities().reconcileTerminal(10));check(!f.isTerminal());
        f.inventory.put(0,1L);f.rejects(()->f.utilities().reconcileTerminal(0));f.inventory.put(0,0L);check(!f.isTerminal());
        Map<String,Object> bad=object(parse(failedSlot));bad.put("request","terminal_failed|zo_validate||changed|");f.disk.write("utility-u0.json",bytes(bad));
        f.rejects(()->f.utilities().reconcileTerminal(0));check(!f.isTerminal());
        System.out.println("TERMINAL_MONOTONICITY_MATRIX=PASS SAME_ID_REPLAYS=100 ACTIVE_REPLAY_KICKS=0 TERMINAL_RESULT_MUTATIONS=0");
    }
    static void projectionIsolation()throws Exception{
        UserTransportFixture f=new UserTransportFixture();AppPolicyStore.State s=f.mAppPolicies.current.copy();
        s.apps.put("0:fixture.app",new AppPolicyStore.Row(90,"balance",231,903,"DEFAULT_FOR_MODE"));
        byte[] before=bytes(f.appPolicyRows(s,0));
        s.globals.put(0,new AppPolicyStore.Row(60,"fast",231,903));s.generation++;
        check(Arrays.equals(before,bytes(f.appPolicyRows(s,0))));
        s.apps.put("10:other.user",new AppPolicyStore.Row(165,"performance",231,903));
        check(Arrays.equals(before,bytes(f.appPolicyRows(s,0))));
        s.apps.put("0:fixture.app",new AppPolicyStore.Row(120,"balance",231,903,"DEFAULT_FOR_MODE"));
        check(!Arrays.equals(before,bytes(f.appPolicyRows(s,0))));before=bytes(f.appPolicyRows(s,0));
        s.defaults.put("0:balance",new GpuRange(422,903));
        check(!Arrays.equals(before,bytes(f.appPolicyRows(s,0))));
        System.out.println("APPPOLICY_PROJECTION_GLOBAL_AND_OTHER_USER_ISOLATION=PASS RELEVANT_APP_AND_RESOLVED_GPU_CHANGE=PASS");
    }
    public static void main(String[] args)throws Exception{
        UserTransportFixture f=new UserTransportFixture();
        Binder.uid=1010253;check(f.callerState(false).equals("ok=0\nerror=inactive_user"));
        f.mCurrentUserId=10;f.mTopResumedState.user=10;
        String state=f.callerState(false);check(state.contains("secondary.app")&&!state.contains("primary.app")&&!state.contains("old.user.app"));
        check(f.callerState(true).contains("active.app"));Binder.uid=10253;check(f.callerState(true).contains("inactive_user"));
        f.checkInventory(10);f.inventory.put(10,43L);f.rejects(()->f.checkInventory(10));f.inventory.put(10,42L);
        f.inventory.put(11,44L);f.checkInventory(11);f.inventory.remove(11);f.rejects(()->f.checkInventory(11));
        for(int user:new int[]{0,10,0,10}){
            String id="request"+checks;RequestIdentity i=f.admit(id,user);int other=user==0?10:0;
            String otherAck=f.getSettingForUser("zui_control_request_ack",other);
            check(f.transport(i,user,"read","").equals(f.request(id,user)));
            f.rejects(()->f.transport(i,other,"read",""));
            f.rejects(()->f.transport(i,other,"ack",id+"|done|policy|wrong"));
            f.rejects(()->f.commandTransport(id,i.sha,"00000000000000000000000000000000",user,SystemProperties.get("seq",""),"read",""));
            f.rejects(()->f.commandTransport(id,i.sha,i.sequence,user,"wrong_kick","read",""));
            f.rejects(()->f.commandTransport(id,"0".repeat(64),i.sequence,user,SystemProperties.get("seq",""),"read",""));
            int writeCount=f.writes.size();
            try{f.transport(i,user,"invalid","");throw new AssertionError();}catch(IllegalArgumentException expected){check(f.writes.size()==writeCount);}
            f.putSettingForUser(SETTING_REQUEST_TEXT,"changed",user);
            check(f.transport(i,user,"read","").equals(f.request(id,user))); // Public legacy Settings cannot replace authenticated private input.
            String slot="utility-u"+user+".json";byte[] saved=f.disk.read(slot);
            Map<String,Object> damaged=object(parse(saved));damaged.put("request","changed");f.disk.write(slot,bytes(damaged));
            f.rejects(()->f.transport(i,user,"read",""));f.disk.write(slot,saved);
            for(String ack:new String[]{"wrong|done|policy|x",id+"|done|other|x",id+"|unknown|policy|x",id+"|done|policy|x\n",id+"|done|policy|x|extra"}){
                int before=f.writes.size();try{f.transport(i,user,"ack",ack);throw new AssertionError();}catch(IllegalArgumentException expected){check(f.writes.size()==before);}
            }
            f.terminal(i);check(Objects.equals(otherAck,f.getSettingForUser("zui_control_request_ack",other)));
            check(f.authenticatedPolicyIdentity(id,i.sha,i.sequence,user).user==user);
            f.rejects(()->f.authenticatedPolicyIdentity(id,i.sha,i.sequence,other));
            RequestIdentity replay=f.admit(id,user);check(replay.sequence.equals(i.sequence));f.terminal(replay);
            Binder.uid=other*100000+10042;f.putSettingForUser(SETTING_REQUEST_TEXT,f.request(id,user),other);
            check(f.notifyControlRequest(id,i.sha).contains("error="));
        }
        RequestIdentity x=f.admit("claim_recovery",10);f.terminal(x);
        RequestIdentity y=f.admit("next_other_user",0);f.terminal(y);
        check(f.authenticatedPolicyIdentity(x.id,x.sha,x.sequence,10).user==10);
        f.rejects(()->f.authenticatedPolicyIdentity(x.id,x.sha,x.sequence,0));
        check(f.mAppPolicies.reconcileRequest(x.id,x.sha,null,null).startsWith("ok=1"));
        long gen=f.mAppPolicies.current.generation;
        check(f.mAppPolicies.reconcileRequest(x.id,x.sha,null,null).startsWith("ok=1")&&f.mAppPolicies.current.generation==gen);
        f.inventory.put(10,43L);f.rejects(()->f.authenticatedPolicyIdentity(x.id,x.sha,x.sequence,10));f.inventory.put(10,42L);
        Binder.uid=1010042;String invalid="wrong_payload|policy|||"+Base64.getEncoder().encodeToString(bytes(map("userId",0)));
        f.utilities().stage(10,invalid);check(f.notifyControlRequest("wrong_payload",sha256(invalid)).contains("policy_user_mismatch"));
        String backup="secondary_backup|sb_export|||";
        String refusal=f.utilityCommand("submit",backup);
        check(refusal.contains("settings_primary_user_required")&&refusal.contains("DEFINITIVE_NOT_ADMITTED"));
        check(f.utilityCommand("submit","allowed_utility|zo_state|||").startsWith("ok=1"));
        for(String reason:new String[]{"settings_primary_user_required","policy_store_unavailable","policy_user_mismatch","request_busy","request_transport_bound","request_payload_mismatch"}){
            check(f.requestRefused(10,"never_admitted",reason).contains("DEFINITIVE_NOT_ADMITTED"));
            check(f.requestRefused(10,"allowed_utility",reason).contains("INDETERMINATE_OR_IN_PROGRESS"));
        }
        byte[] admission=f.disk.read("control-admission.json");f.disk.write("control-admission.json",new byte[]{1});
        check(f.requestRefused(10,"never_admitted","request_busy").contains("INDETERMINATE_OR_IN_PROGRESS"));f.disk.write("control-admission.json",admission);
        AppPolicyStore saved=f.mAppPolicies;f.mAppPolicies=null;
        check(f.requestRefused(10,"never_admitted","policy_store_unavailable").contains("INDETERMINATE_OR_IN_PROGRESS"));f.mAppPolicies=saved;
        RequestIdentity utility=RequestIdentity.read(object(parse(admission)).get("identity"));
        check(f.utilityCommand("ack","allowed_utility").contains("INDETERMINATE_OR_IN_PROGRESS"));
        f.utilities().publish(utility,"rulesState","current");f.utilities().acknowledge(utility,"allowed_utility|done|zo_state|ok");
        check(f.utilityCommand("ack","allowed_utility").contains("ADMITTED_TERMINAL"));
        check(f.utilityCommand("result","allowed_utility|rulesState").contains("ADMITTED_TERMINAL"));
        monotonicity();projectionIsolation();
        java.lang.System.out.println("USER_TRANSPORT_PRODUCTION_GUARDS_PASS checks="+checks);
    }
}
