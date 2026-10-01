package com.zui.server.control;

import java.util.Map;
import static com.zui.server.control.PolicyJson.*;

/** Authenticated admission identity. Root parameters are claims, never authority. */
final class RequestIdentity {
    final int user;
    final long serial;
    final String id,sha,sequence;
    RequestIdentity(int user,long serial,String id,String sha,String sequence){
        require(user>=0&&user<=21474&&serial>=0,"request user identity");
        require(id!=null&&id.matches("[A-Za-z0-9._-]{1,64}")&&sha!=null&&sha.matches("[0-9a-f]{64}")
                &&sequence!=null&&sequence.matches("[0-9a-f]{32}"),"request identity bounds");
        this.user=user;this.serial=serial;this.id=id;this.sha=sha;this.sequence=sequence;
    }
    Map<String,Object> json(){return map("userId",user,"serial",serial,"id",id,"sha",sha,"sequence",sequence);}
    static RequestIdentity read(Object value){
        Map<String,Object> r=object(value);keys(r,"userId","serial","id","sha","sequence");
        return new RequestIdentity(AppPolicyStore.user(r.get("userId")),integer(r.get("serial")),
                string(r.get("id")),string(r.get("sha")),string(r.get("sequence")));
    }
    void verify(int user,String id,String sha,String sequence,Map<Integer,Long> inventory){
        require(this.user==user&&this.id.equals(id)&&this.sha.equals(sha)&&this.sequence.equals(sequence)
                &&java.util.Objects.equals(inventory.get(user),serial),"authenticated request identity");
    }
}
