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
    static String[] fields(String request){
        require(request!=null&&request.getBytes(StandardCharsets.UTF_8).length<=REQUEST_LIMIT,"request_transport_bound");
        String[] f=request.split("\\|",-1);
        require(f.length==5&&f[0].matches("[A-Za-z0-9_.-]{1,64}")&&!f[1].isEmpty()&&f[2].isEmpty(),"request_payload_mismatch");return f;
    }
    String request(int user)throws Exception{return string(load(user).get("request"));}
    void stage(int user,String request)throws Exception{
        String[] next=fields(request);Map<String,Object> slot=load(user);String before=string(slot.get("request"));
        if(before.equals(request))return;
        if(!before.isEmpty()){
            String oldId=fields(before)[0];require(!oldId.equals(next[0]),"request_payload_mismatch");
            byte[] receipt=disk.read("control-admission.json");
            if(receipt.length!=0){Map<String,Object> admission=object(parse(receipt));RequestIdentity old=RequestIdentity.read(admission.get("identity"));
                require(!old.id.equals(oldId)||Boolean.TRUE.equals(admission.get("terminal")),"request_busy");}
        }
        slot.put("request",request);slot.put("ack","");slot.put("kind","");slot.put("data","");save(user,slot);
    }
    String ack(int user,String id)throws Exception{
        Map<String,Object> slot=load(user);String request=string(slot.get("request"));
        return request.isEmpty()||!fields(request)[0].equals(id)?"":string(slot.get("ack"));
    }
    void acknowledge(RequestIdentity identity,String ack)throws Exception{
        Map<String,Object> slot=bound(identity);String[] request=fields(string(slot.get("request"))),a=ack.split("\\|",-1);
        require(ack.length()<=8192&&ack.indexOf('\n')<0&&ack.indexOf('\r')<0&&a.length==4&&a[0].equals(identity.id)
                &&a[2].equals(request[1])&&(a[1].equals("processing")||a[1].equals("done")||a[1].equals("failed")),"ACK identity/state");
        slot.put("ack",ack);save(identity.user,slot);
    }
    private Map<String,Object> bound(RequestIdentity identity)throws Exception{
        identity.verify(identity.user,identity.id,identity.sha,identity.sequence,users);
        Map<String,Object> slot=load(identity.user);String request=string(slot.get("request"));
        require(fields(request)[0].equals(identity.id)&&hash(request.getBytes(StandardCharsets.UTF_8)).equals(identity.sha),"utility request identity");return slot;
    }
    void publish(RequestIdentity identity,String kind,String data)throws Exception{
        Map<String,Object> slot=bound(identity);String command=fields(string(slot.get("request")))[1];
        require((kind.equals("logs")&&command.equals("export_logs"))||(kind.equals("rulesState")&&command.equals("zo_state"))
                ||(kind.equals("rulesChunk")&&command.equals("zo_read")),"utility result kind");
        require(data!=null&&!data.isEmpty()&&data.getBytes(StandardCharsets.UTF_8).length<=RESULT_LIMIT,"utility result bound");
        slot.put("kind",kind);slot.put("data",data);save(identity.user,slot);
    }
    String result(int user,String id,String kind)throws Exception{
        Map<String,Object> slot=load(user);String request=string(slot.get("request"));
        require(!request.isEmpty()&&fields(request)[0].equals(id)&&slot.get("kind").equals(kind),"utility result expired");
        String[] ack=string(slot.get("ack")).split("\\|",-1);
        require(ack.length==4&&ack[0].equals(id)&&ack[1].equals("done")&&ack[2].equals(fields(request)[1]),"utility result not completed");
        return string(slot.get("data"));
    }
}
