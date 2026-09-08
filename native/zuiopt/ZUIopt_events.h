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
public:
    void push(int fd,int code,int pid,int user,int value){
        // Callback boundary: value copy + queue + eventfd only. No process observation.
        {std::lock_guard<std::mutex> lock(mutex);queue.push_back({code,pid,user,value,++sequence});}
        uint64_t one=1;ssize_t n;
        do{n=::write(fd,&one,sizeof(one));}while(n<0&&errno==EINTR);
        if(n!=static_cast<ssize_t>(sizeof(one))&&!(n<0&&errno==EAGAIN))throw std::system_error(n<0?errno:EIO,std::generic_category(),"eventfd notify");
    }
    std::deque<Event> take(){std::deque<Event> result;std::lock_guard<std::mutex> lock(mutex);result.swap(queue);return result;}
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
            runtime.release(it->second);states.erase(it);it=states.end();
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
    if(it!=states.end()){runtime.release(it->second);states.erase(it);}
}
}
