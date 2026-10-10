// Runtime contract and exact scheduler readback. Durable ownership lives in owner.h.
#pragma once
#include "ZUIopt_core.h"
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/resource.h>
namespace ZUIopt {
inline constexpr size_t RUNTIME_STATUS_LIMIT=32768;
inline std::string runtimeEnvelope(const std::string& boot,const std::map<int,std::string>& processes){
    std::ostringstream header;header<<"{\"schema\":1,\"boot\":"<<std::quoted(boot)<<",\"totalProcesses\":"<<processes.size()<<",\"processes\":[";
    auto out=header.str();bool comma=false,truncated=false;
    for(const auto& [_,state]:processes){
        if(out.size()+state.size()+64>RUNTIME_STATUS_LIMIT){truncated=true;break;}
        if(comma)out+=',';comma=true;out+=state;
    }
    out+=truncated?"],\"truncated\":true}\n":"],\"truncated\":false}\n";
    return out;
}
struct SchedulerState {
    uint32_t size=56,policy=0;uint64_t flags=0;int32_t nice=0;uint32_t priority=0;
    uint64_t runtime=0,deadline=0,period=0;uint32_t utilMin=0,utilMax=1024;
};
static_assert(sizeof(SchedulerState)==56,"Linux sched_attr layout");
inline bool operator==(const SchedulerState& a,const SchedulerState& b){
    return a.policy==b.policy&&a.flags==b.flags&&a.nice==b.nice&&a.priority==b.priority&&
        a.runtime==b.runtime&&a.deadline==b.deadline&&a.period==b.period&&a.utilMin==b.utilMin&&a.utilMax==b.utilMax;
}
inline bool schedulerState(int tid,SchedulerState& state){
    state=SchedulerState{};
    if(syscall(SYS_sched_getattr,tid,&state,sizeof(state),0)!=0||state.size<56)return false;
    // sched_getattr omits nice for RT; read the actual saved nice before preserving it.
    errno=0;int nice=getpriority(PRIO_PROCESS,tid);if(errno!=0)return false;
    state.nice=nice;return true;
}
inline bool restorableScheduler(const SchedulerState& state){
    return (state.policy==SCHED_OTHER||state.policy==SCHED_BATCH||state.policy==SCHED_IDLE||
        state.policy==SCHED_RR||state.policy==SCHED_FIFO)&&(state.flags&~uint64_t(1))==0&&state.priority<=99&&
        state.nice>=-20&&state.nice<=19&&state.utilMin<=state.utilMax&&state.utilMax<=1024;
}
inline bool setScheduler(int tid,SchedulerState state){
    // Preserve clamp values explicitly; changing to RT must not silently set uclamp.min=1024.
    state.size=sizeof(state);state.flags|=0x60;
    return syscall(SYS_sched_setattr,tid,&state,0)==0;
}
inline SchedulerState realtimeState(SchedulerState original){
    original.policy=SCHED_RR;original.priority=1;original.flags|=1;
    original.runtime=original.deadline=original.period=0;return original;
}
inline void schedulerText(std::ostream& out,const SchedulerState& s){
    out<<s.policy<<' '<<s.flags<<' '<<s.nice<<' '<<s.priority<<' '<<s.runtime<<' '<<s.deadline<<' '<<s.period<<' '<<s.utilMin<<' '<<s.utilMax;
}
inline bool parseScheduler(std::istream& in,SchedulerState& s){
    return bool(in>>s.policy>>s.flags>>s.nice>>s.priority>>s.runtime>>s.deadline>>s.period>>s.utilMin>>s.utilMax)&&restorableScheduler(s);
}
struct SchedulerOwnership {bool active=false;SchedulerState original,expected;};
enum class SchedulerResult {UNCHANGED,APPLIED,RESTORED,DEAD,CONFLICT,UNAVAILABLE,UNRESTORABLE};
inline bool schedulerControlSame(const SchedulerState& a,const SchedulerState& b){
    // RR owns these fields. OEM nice/uclamp updates must neither strand RR nor be overwritten.
    return a.policy==b.policy&&a.flags==b.flags&&a.priority==b.priority&&
        a.runtime==b.runtime&&a.deadline==b.deadline&&a.period==b.period;
}
template<class Kernel,class Persist,class Same>
SchedulerResult restoreScheduler(SchedulerOwnership& owned,int tid,Kernel& kernel,Persist persist,Same same){
    if(!owned.active)return SchedulerResult::UNCHANGED;
    if(!same()){owned.active=false;persist();return SchedulerResult::DEAD;}
    SchedulerState current;
    if(!kernel.get(tid,current))return same()?SchedulerResult::UNAVAILABLE:SchedulerResult::DEAD;
    if(!same()){owned.active=false;persist();return SchedulerResult::DEAD;}
    if(!schedulerControlSame(current,owned.expected)){
        // The original value means a crash before apply or a completed restore.
        auto result=schedulerControlSame(current,owned.original)?SchedulerResult::RESTORED:SchedulerResult::CONFLICT;
        owned.active=false;persist();return result;
    }
    auto restored=current;restored.policy=owned.original.policy;restored.flags=owned.original.flags;
    restored.priority=owned.original.priority;restored.runtime=owned.original.runtime;
    restored.deadline=owned.original.deadline;restored.period=owned.original.period;
    SchedulerState checked;
    if(!same())return SchedulerResult::DEAD;
    if(!kernel.get(tid,checked)||!(checked==current))return SchedulerResult::UNAVAILABLE;
    if(!same())return SchedulerResult::DEAD;
    if(!kernel.set(tid,restored))return SchedulerResult::UNAVAILABLE;
    if(!same()){owned.active=false;persist();return SchedulerResult::DEAD;}
    if(!kernel.get(tid,current)||!schedulerControlSame(current,restored))return SchedulerResult::UNAVAILABLE;
    owned.active=false;persist();return SchedulerResult::RESTORED;
}
template<class Kernel,class Persist,class Same>
SchedulerResult applyRealtime(SchedulerOwnership& owned,int tid,Kernel& kernel,Persist persist,Same same){
    if(!same())return SchedulerResult::DEAD;
    SchedulerState current;
    if(!kernel.get(tid,current)||!restorableScheduler(current))return SchedulerResult::UNAVAILABLE;
    if(!same())return SchedulerResult::DEAD;
    if(owned.active)return schedulerControlSame(current,owned.expected)?SchedulerResult::UNCHANGED:SchedulerResult::CONFLICT;
    auto expected=realtimeState(current);
    if(current==expected)return SchedulerResult::UNCHANGED; // Existing external RT is not ours to revoke.
    owned={true,current,expected};persist(); // Intent+original durable BEFORE the syscall.
    if(!same())return SchedulerResult::DEAD;
    if(!kernel.get(tid,current)||!schedulerControlSame(current,owned.original)){
        owned.active=false;persist();return SchedulerResult::CONFLICT;
    }
    expected=realtimeState(current);
    if(!(expected==owned.expected)){owned.expected=expected;persist();}
    SchedulerState checked;
    if(!same())return SchedulerResult::DEAD;
    if(!kernel.get(tid,checked)||!(checked==current))return SchedulerResult::UNAVAILABLE;
    if(!same())return SchedulerResult::DEAD;
    if(!kernel.set(tid,expected))return SchedulerResult::UNAVAILABLE;
    if(!same())return SchedulerResult::DEAD;
    if(!kernel.get(tid,current)||!schedulerControlSame(current,expected))return SchedulerResult::UNAVAILABLE;
    return SchedulerResult::APPLIED;
}
struct SchedulerKernel {
    bool get(int tid,SchedulerState& state){return schedulerState(tid,state);}
    bool set(int tid,const SchedulerState& state){return setScheduler(tid,state);}
};
struct WaltOwnership {bool active=false;int kind=0,original=0,expected=0;};
inline bool waltValue(int kind,int value){return kind==1?(value>=0&&value<=3):kind==2&&(value>=-1&&value<=255);}
inline const char* waltPath(int kind){return kind==1?"/proc/sys/walt/sched_per_task_boost":kind==2?"/proc/sys/walt/task_reduce_affinity":"";}
inline bool qualifiedWaltIdentity(){
    struct utsname kernel{};
    if(uname(&kernel)!=0||std::string(kernel.release)!="6.1.112-android14-11-gd03cb1d7abcd-ab12592111")return false;
    auto note=read("/sys/module/sched_walt/notes/.note.gnu.build-id");
    const unsigned char expected[]={0x04,0,0,0,0x14,0,0,0,0x03,0,0,0,'G','N','U',0,
        0xbf,0xc7,0xdf,0xdc,0xde,0x5f,0x81,0xb4,0x3c,0x7f,0xe5,0x29,0x51,0x2b,0xcb,0x29,0x7a,0x6a,0x13,0x3c};
    return note.size()==sizeof(expected)&&memcmp(note.data(),expected,sizeof(expected))==0;
}
struct WaltKernel {
    std::function<bool(int)> select;
    bool get(int kind,int tid,int& value){
        if(!select(tid))return false;
        std::istringstream in(read(waltPath(kind)));int observed=0;std::string extra;
        return bool(in>>observed>>value)&&observed==tid&&!(in>>extra)&&waltValue(kind,value);
    }
    bool set(int kind,int tid,int value){return waltValue(kind,value)&&write(waltPath(kind),std::to_string(tid)+" "+std::to_string(value));}
};
template<class Kernel,class Persist,class Same>
SchedulerResult restoreWalt(WaltOwnership& owned,int tid,Kernel& kernel,Persist persist,Same same){
    if(!owned.active)return SchedulerResult::UNCHANGED;
    if(!same()){owned.active=false;persist();return SchedulerResult::DEAD;}
    int current=0;if(!kernel.get(owned.kind,tid,current))return SchedulerResult::UNAVAILABLE;
    if(!same()){owned.active=false;persist();return SchedulerResult::DEAD;}
    if(current!=owned.expected){auto result=current==owned.original?SchedulerResult::RESTORED:SchedulerResult::CONFLICT;owned.active=false;persist();return result;}
    if(!same()||!kernel.set(owned.kind,tid,owned.original))return SchedulerResult::UNAVAILABLE;
    if(!same()){owned.active=false;persist();return SchedulerResult::DEAD;}
    if(!kernel.get(owned.kind,tid,current)||current!=owned.original)return SchedulerResult::UNAVAILABLE;
    owned.active=false;persist();return SchedulerResult::RESTORED;
}
template<class Kernel,class Persist,class Same>
SchedulerResult applyWalt(WaltOwnership& owned,int kind,int tid,int value,Kernel& kernel,Persist persist,Same same){
    if(!same())return SchedulerResult::DEAD;
    int current=0;if(!waltValue(kind,value)||!kernel.get(kind,tid,current))return SchedulerResult::UNAVAILABLE;
    if(!same())return SchedulerResult::DEAD;
    // Exact qualified module: node9 rejects negative input (1f154..1f158),
    // but reads the default mask as signed -1 (1f250). It cannot restore that raw value.
    // Decline BEFORE changing the task; an online-mask approximation is not exact restore.
    if(kind==2&&current<0)return SchedulerResult::UNRESTORABLE;
    if(owned.active){
        if(owned.kind==kind&&owned.expected==value)return current==value?SchedulerResult::UNCHANGED:SchedulerResult::CONFLICT;
        auto result=restoreWalt(owned,tid,kernel,persist,same);if(result==SchedulerResult::UNAVAILABLE||result==SchedulerResult::CONFLICT)return result;
        if(!kernel.get(kind,tid,current)||!same())return SchedulerResult::UNAVAILABLE;
    }
    if(current==value)return SchedulerResult::UNCHANGED;
    owned={true,kind,current,value};persist();
    if(!same())return SchedulerResult::DEAD;
    if(!kernel.get(kind,tid,current)||current!=owned.original){owned.active=false;persist();return SchedulerResult::CONFLICT;}
    if(!same()||!kernel.set(kind,tid,value))return SchedulerResult::UNAVAILABLE;
    if(!same())return SchedulerResult::DEAD;
    if(!kernel.get(kind,tid,current)||current!=value)return SchedulerResult::UNAVAILABLE;
    return SchedulerResult::APPLIED;
}
struct RuntimePlan {
    int requestedMode=0,effectiveMode=0,requestedRt=0;Mask general=0,c1=0,c2=0;
    std::string reason="NONE";
};
inline bool sm8650Topology(){
    for(auto [policy,mask]:std::array<std::pair<int,Mask>,4>{{{0,3},{2,28},{5,96},{7,128}}}){
        auto value=trim(read("/sys/devices/system/cpu/cpufreq/policy"+std::to_string(policy)+"/related_cpus"));
        std::replace(value.begin(),value.end(),' ',',');if(value.empty()||cpus(value)!=mask)return false;
    }return cpus(read("/sys/devices/system/cpu/online"))==255;
}
inline RuntimePlan runtimePlan(const Profile& p,const Config& c,bool topology,bool qualifiedWalt){
    auto request=resolvedProfile(p,c).runtime;
    RuntimePlan out;out.requestedMode=request.mode;out.requestedRt=request.rt;out.general=p.general;
    if(!request.preset){if(request.mode)out.reason="EXPLICIT_MASKS";return out;}
    if(!topology){out.reason="TOPOLOGY_UNQUALIFIED";return out;}
    out.general=0x7c;out.c1=0x1c;out.c2=0x80;
    if(request.mode==2&&!qualifiedWalt){out.reason="WALT_UNQUALIFIED";return out;}
    out.effectiveMode=request.mode;
    if(request.mode){out.general=0xfc;out.c1=0xfc;}
    return out;
}
struct RuntimeRepresentatives {int c1=0,c2=0;};
inline RuntimeRepresentatives runtimeRepresentatives(std::vector<RankedTask> c1,std::vector<RankedTask> c2,const std::string& marker){
    RuntimeRepresentatives out;
    if(marker=="dummy")out.c2=representative(c2,2);
    else out.c2=representative(c2,1);
    out.c1=marker=="double"?representative(c2,2):representative(c1,1);
    return out;
}
}
