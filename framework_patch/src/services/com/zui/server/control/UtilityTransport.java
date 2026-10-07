package com.zui.server.control;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import static com.zui.server.control.PolicyJson.*;

/** One private latest utility slot per admitted Android user/serial, not a policy owner. */
final class UtilityTransport {
    static final int REQUEST_LIMIT=131072, RESULT_LIMIT=32768;
    final AppPolicyStore.Storage disk;
    final Map<Integer,Long> users;
    UtilityTransport(AppPolicyStore.Storage disk,Map<Integer,Long> users){this.disk=disk;this.users=users;}
    private String path(int user){require(users.containsKey(user),"utility user unavailable");return "utility-u"+user+".json";}
    private Map<String,Object> load(int user)throws Exception{
        byte[] data=disk.read(path(user));
        if(data.length==0)return map("schema",1,"user",user,"serial",users.get(user),"request","","ack","","kind","","data","");
        Map<String,Object> slot=object(parse(data));keys(slot,"schema","user","serial","request","ack","kind","data");
        require(integer(slot.get("schema"))==1&&integer(slot.get("user"))==user,"utility slot identity");
        // A recycled user ID cannot read or resume its predecessor's private slot.
        if(integer(slot.get("serial"))!=users.get(user))return map("schema",1,"user",user,"serial",users.get(user),"request","","ack","","kind","","data","");
        return slot;
    }
    private void save(int user,Map<String,Object> slot)throws Exception{
        byte[] data=bytes(slot);require(data.length<=262144,"utility slot bound");disk.write(path(user),data);
        require(java.util.Arrays.equals(disk.read(path(user)),data),"utility durable readback");
    }
    static final class Request {
        final String id,command,packageName,mode;
        private Request(String[] fields){id=fields[0];command=fields[1];packageName=fields[3];mode=fields[4];}
        String restoreTransaction(){
            return (command.equals("sb_restore")||command.equals("sb_reset"))&&mode.isEmpty()&&packageName.matches("[0-9a-f]{24}")?packageName:"";
        }
    }
    static Request fields(String request){
        require(request!=null&&request.getBytes(StandardCharsets.UTF_8).length<=REQUEST_LIMIT,"request_transport_bound");
        String[] f=request.split("\\|",-1);
        require(f.length==5&&f[0].matches("[A-Za-z0-9_.-]{1,64}")&&!f[1].isEmpty()&&f[2].isEmpty(),"request_payload_mismatch");return new Request(f);
    }
    String request(int user)throws Exception{return string(load(user).get("request"));}
    void stage(int user,String request)throws Exception{
        Request next=fields(request);Map<String,Object> slot=load(user);String before=string(slot.get("request"));
        if(before.equals(request))return;
        if(!before.isEmpty()){
            String oldId=fields(before).id;require(!oldId.equals(next.id),"request_payload_mismatch");
            byte[] receipt=disk.read("control-admission.json");
            if(receipt.length!=0){Map<String,Object> admission=object(parse(receipt));RequestIdentity old=RequestIdentity.read(admission.get("identity"));
                require(!old.id.equals(oldId)||reconcileTerminal(user)||Boolean.TRUE.equals(admission.get("terminal")),"request_busy");}
        }
        slot.put("request",request);slot.put("ack","");slot.put("kind","");slot.put("data","");save(user,slot);
    }
    String ack(int user,String id)throws Exception{
        Map<String,Object> slot=load(user);String request=string(slot.get("request"));
        return request.isEmpty()||!fields(request).id.equals(id)?"":string(slot.get("ack"));
    }
    /** Only private exact terminal facts can close a lost admission update. */
    boolean reconcileTerminal(int user)throws Exception{
        byte[] receipt=disk.read("control-admission.json");if(receipt.length==0)return false;
        Map<String,Object> admission=object(parse(receipt));RequestIdentity identity=RequestIdentity.read(admission.get("identity"));
        require(identity.user==user,"utility admission user");
        identity.verify(user,identity.id,identity.sha,identity.sequence,users);
        Map<String,Object> slot=load(user);String request=string(slot.get("request"));
        if(request.isEmpty()||!fields(request).id.equals(identity.id))return false;
        Request parsed=fields(string(bound(identity).get("request")));String ack=string(slot.get("ack"));
        if(ack.isEmpty())return false;
        String[] a=ack.split("\\|",-1);
        require(ack.length()<=8192&&ack.indexOf('\n')<0&&ack.indexOf('\r')<0&&a.length==4
                &&a[0].equals(identity.id)&&a[2].equals(parsed.command)
                &&(a[1].equals("processing")||a[1].equals("done")||a[1].equals("failed")),"ACK identity/state");
        if(a[1].equals("processing"))return false;
        if(!Boolean.TRUE.equals(admission.get("terminal"))){
            admission.put("terminal",true);byte[] terminal=bytes(admission);disk.write("control-admission.json",terminal);
            require(java.util.Arrays.equals(disk.read("control-admission.json"),terminal),"terminal durable readback");
        }
        return true;
    }
    void acknowledge(RequestIdentity identity,String ack)throws Exception{
        Map<String,Object> slot=bound(identity);Request request=fields(string(slot.get("request")));String[] a=ack.split("\\|",-1);
        require(ack.length()<=8192&&ack.indexOf('\n')<0&&ack.indexOf('\r')<0&&a.length==4&&a[0].equals(identity.id)
                &&a[2].equals(request.command)&&(a[1].equals("processing")||a[1].equals("done")||a[1].equals("failed")),"ACK identity/state");
        String before=string(slot.get("ack"));String[] prior=before.split("\\|",-1);
        if(prior.length==4&&(prior[1].equals("done")||prior[1].equals("failed"))){
            require(before.equals(ack),"terminal ACK immutable");return;
        }
        slot.put("ack",ack);save(identity.user,slot);
    }
    private Map<String,Object> bound(RequestIdentity identity)throws Exception{
        identity.verify(identity.user,identity.id,identity.sha,identity.sequence,users);
        Map<String,Object> slot=load(identity.user);String request=string(slot.get("request"));
        require(fields(request).id.equals(identity.id)&&hash(request.getBytes(StandardCharsets.UTF_8)).equals(identity.sha),"utility request identity");return slot;
    }
    Request admitted(RequestIdentity identity)throws Exception{
        RequestIdentity current=RequestIdentity.read(object(parse(disk.read("control-admission.json"))).get("identity"));
        current.verify(identity.user,identity.id,identity.sha,identity.sequence,users);
        return fields(string(bound(identity).get("request")));
    }
    void publish(RequestIdentity identity,String kind,String data)throws Exception{
        Map<String,Object> slot=bound(identity);String command=fields(string(slot.get("request"))).command;
        require((kind.equals("logs")&&command.equals("export_logs"))||(kind.equals("rulesState")&&(command.equals("zo_state")||command.equals("zo_commit")||command.equals("zo_rollback")||command.equals("zo_restore_app")||command.equals("zo_enable")||command.equals("zo_disable")))
                ||(kind.equals("rulesChunk")&&(command.equals("zo_read")||command.equals("zo_upstream_read")))
                ||(kind.equals("rulesPreview")&&command.equals("zo_preview"))
                ||(kind.equals("rulesValidation")&&command.equals("zo_validate")),"utility result kind");
        require(data!=null&&!data.isEmpty()&&data.getBytes(StandardCharsets.UTF_8).length<=RESULT_LIMIT,"utility result bound");
        String[] ack=string(slot.get("ack")).split("\\|",-1);
        if(ack.length==4&&(ack[1].equals("done")||ack[1].equals("failed"))){
            require(kind.equals(slot.get("kind"))&&data.equals(slot.get("data")),"terminal result immutable");return;
        }
        slot.put("kind",kind);slot.put("data",data);save(identity.user,slot);
    }
    String result(int user,String id,String kind)throws Exception{
        Map<String,Object> slot=load(user);String request=string(slot.get("request"));
        require(!request.isEmpty()&&fields(request).id.equals(id)&&slot.get("kind").equals(kind),"utility result expired");
        String[] ack=string(slot.get("ack")).split("\\|",-1);
        require(ack.length==4&&ack[0].equals(id)&&ack[1].equals("done")&&ack[2].equals(fields(request).command),"utility result not completed");
        return string(slot.get("data"));
    }
}
