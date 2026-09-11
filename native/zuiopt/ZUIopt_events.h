// Shared production event/validation logic; fixtures supply observation/placement operations.
#pragma once
#include "ZUIopt_core.h"
#include <deque>
#include <mutex>
#include <system_error>

namespace ZUIopt {
struct Event {int code,pid,uid,value;uint64_t sequence;};
class RawEvents {
    std::mutex mutex;std::deque<Event> queue;uint64_t sequence=0;
    int64_t scene=-1;bool scenePending=false;uint64_t epoch=0;
    static void notify(int fd){
        uint64_t one=1;ssize_t n;
        do{n=::write(fd,&one,sizeof(one));}while(n<0&&errno==EINTR);
        if(n!=static_cast<ssize_t>(sizeof(one))&&!(n<0&&errno==EAGAIN))throw std::system_error(n<0?errno:EIO,std::generic_category(),"eventfd notify");
    }
public:
    void push(int fd,int code,int pid,int user,int value){
        // Callback boundary: value copy + queue + eventfd only. No process observation.
        {std::lock_guard<std::mutex> lock(mutex);queue.push_back({code,pid,user,value,++sequence});++epoch;}
        notify(fd);
    }
    std::deque<Event> take(){std::deque<Event> result;std::lock_guard<std::mutex> lock(mutex);result.swap(queue);return result;}
    // Independent AUTHORITY_RECONCILE slot, not an IProcessObserver transaction.
    void pushScene(int fd,int64_t seq){
        bool wake;
        {std::lock_guard<std::mutex> lock(mutex);if(seq<0||seq<=scene)return;scene=seq;++epoch;wake=!scenePending;scenePending=true;}
        if(wake)notify(fd);
    }
    int64_t takeScene(){std::lock_guard<std::mutex> lock(mutex);if(!scenePending)return -1;scenePending=false;return scene;}
    bool latestScene(int64_t seq){std::lock_guard<std::mutex> lock(mutex);return scene==seq;}
    uint64_t authorityEpoch(){std::lock_guard<std::mutex> lock(mutex);return epoch;}
    bool authorityCurrent(uint64_t token){std::lock_guard<std::mutex> lock(mutex);return epoch==token&&!scenePending&&queue.empty();}
};
inline bool sameProcess(const ProcessState& p,const Identity& id,int user){
    return id.start&&id.start==p.generation&&user==p.uid;
}
template<class Runtime> Identity validateManagedSnapshot(const Snapshot& s,const Config& config,Runtime& runtime){
    if(!eligibleSnapshot(s,config))return {};
    auto first=runtime.procIdentity(s.pid);if(!first.start)return {};
    if(runtime.procName(s.pid)!=s.name||runtime.procUid(s.pid)!=s.uid)return {};
    if(!authoritativeIdentity(s,runtime.packageAuthority(s.uid)))return {};
    if(runtime.procIdentity(s.pid).start!=first.start||runtime.procUid(s.pid)!=s.uid)return {};
    return first;
}
template<class Runtime> void processEvent(const Event& e,std::map<int,ProcessState>& states,Runtime& runtime){
    auto it=states.find(e.pid);
    if(it!=states.end()){
        auto id=runtime.procIdentity(e.pid);int user=runtime.procUid(e.pid);
        if(!sameProcess(it->second,id,user)){
            it->second.alive=false;runtime.release(it->second);states.erase(it);it=states.end();
        }
    }
    // A delayed death callback never removes a live, same-generation/UID state.
    if(e.code==3)return;
    if(e.uid<10000||e.uid>=20000||(it!=states.end()&&it->second.uid!=e.uid))return;
    auto snapshot=runtime.activitySnapshot();
    for(const auto& s:snapshot)if(s.pid==e.pid&&s.uid==e.uid){
        auto* p=runtime.resolve(s);if(!p)return;
        // Current AM state wins over delayed callback values, including known processes.
        p->activity_foreground=s.state==2&&(s.flags&4)!=0;
        if(e.code==2)p->foreground_service_state=e.value;
        runtime.activate(*p);return;
    }
    // A missing authority record is an acquisition race, not a daemon fatal.
    if(it!=states.end()){it->second.activity_foreground=false;runtime.release(it->second);if(!it->second.backgroundReleasing)states.erase(it);}
}

// Shared with event-loss fixtures; the existing authority/ownership path is unchanged.
template<class Runtime> void reconcileSnapshot(const std::vector<Snapshot>& snapshot,std::map<int,ProcessState>& states,Runtime& runtime){
    std::set<int> present;
    for(auto& s:snapshot){
        auto* p=runtime.resolve(s);if(!p)continue;
        present.insert(s.pid);p->activity_foreground=(s.state==2)&&((s.flags&4)!=0);
        ZUIOPT_NOTE("SNAPSHOT","pid="+std::to_string(s.pid)+" name="+s.name+" state="+std::to_string(s.state)+" focused="+std::to_string(s.focused)+" generation="+std::to_string(p->generation));
    }
    for(auto it=states.begin();it!=states.end();)if(!present.count(it->first)){
        it->second.activity_foreground=false;runtime.release(it->second);
        if(!it->second.backgroundReleasing)it=states.erase(it);else ++it;
    }else ++it;
    ZUIOPT_NOTE("RECONCILE","records="+std::to_string(present.size()));
    runtime.edges();for(auto& [_,p]:states)runtime.activate(p);
}

struct SceneBurst {
    static constexpr std::array<int,4> schedule={0,100,250,500};
    int64_t sequence=-1,started=0;size_t step=schedule.size();
    void accept(int64_t seq,int64_t time){if(seq>sequence){sequence=seq;started=time;step=0;}}
    int timeout(int64_t time)const{return step==schedule.size()?-1:static_cast<int>(std::max<int64_t>(0,started+schedule[step]-time));}
    bool complete(int64_t time){
        // Do not accumulate overdue retries when the reactor was busy.
        do{++step;}while(step<schedule.size()&&started+schedule[step]<time);
        return step==schedule.size();
    }
};
}
