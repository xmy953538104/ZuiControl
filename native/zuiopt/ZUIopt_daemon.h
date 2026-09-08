// ZUIopt event reactor. No periodic process authority query or idle task scan.
#pragma once
#include "ZUIopt_binder.h"
#include "ZUIopt_owner.h"
#include "ZUIopt_events.h"
#include <csignal>
#include <deque>
#include <memory>
#include <mutex>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
namespace ZUIopt {
class Core {
#ifdef ZUIOPT_RESEARCH
    friend struct Research;
#endif
    Config config;std::string configPath,stateRoot;Mask available;
    Counters counters;std::map<int,ProcessState> states;
    std::unique_ptr<Journal> journal;std::unique_ptr<Placement> placement;std::unique_ptr<Observer> observer;
    RawEvents events;RuntimeBlocker runtimeBlocker;
    int eventFd=-1,signalFd=-1;bool serviceDied=false;
public:
    Core(std::string path,const std::string& statePath):configPath(std::move(path)),stateRoot(statePath){
        available=cpus(read("/sys/devices/system/cpu/online"));config=parseConfig(read(configPath),available);ZUIOPT_debug=config.debug;
        journal=std::make_unique<Journal>(statePath);
        sigset_t signals;sigemptyset(&signals);for(int s:{SIGTERM,SIGINT,SIGHUP,SIGUSR1})sigaddset(&signals,s);
        require(pthread_sigmask(SIG_BLOCK,&signals,nullptr)==0,"signal mask");
        signalFd=signalfd(-1,&signals,SFD_CLOEXEC|SFD_NONBLOCK);eventFd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        if(signalFd<0||eventFd<0){if(signalFd>=0)close(signalFd);if(eventFd>=0)close(eventFd);throw std::runtime_error("event descriptors");}
    }
    void enqueue(int code,int pid,int user,int value){
        events.push(eventFd,code,pid,user,value);
    }
    Identity procIdentity(int pid){return identity(pid);}
    int procUid(int pid){return uid(pid);}
    std::string procName(int pid){return processName(pid,true);}
    void blocked(RuntimeBlockerReason reason){runtimeBlocker.record(stateRoot,journal->currentBootId(),reason);}
    void procBlocked(const ProcError& error){
        blocked(strcmp(error.what(),"proc UID stat failed")==0?RuntimeBlockerReason::PROC_UID_PERMISSION:RuntimeBlockerReason::PROC_READ_PERMISSION);
    }
    std::vector<std::string> packageAuthority(int user){
        try{return observer->packagesForUid(user);}
        catch(const BinderError& error){if(error.status==STATUS_PERMISSION_DENIED||error.status==-EACCES)blocked(RuntimeBlockerReason::PACKAGE_AUTHORITY_PERMISSION);return {};}
        catch(const std::runtime_error&){return {};}
    }
    std::vector<Snapshot> activitySnapshot(){counters.snapshots++;return observer->snapshot();}
    void release(ProcessState& p){placement->release(p);}
    void activate(ProcessState& p){
        bool wanted=p.alive&&p.activity_foreground&&config.find(p.package);
        if(!wanted){if(p.managed)placement->release(p);return;}
        if(!p.managed){
            p.androidGroup=group(p.pid);p.androidMask=affinity(p.pid);
            require(normalGroup(p.androidGroup)&&p.androidMask,"initial Android owner unavailable");
            p.managed=true;p.activated=now();p.next=now();p.burst=0;
            double uptime=0;std::istringstream(read("/proc/uptime"))>>uptime;
            p.ownershipFloor=static_cast<uint64_t>(uptime*sysconf(_SC_CLK_TCK));require(p.ownershipFloor>0,"ownership birth floor");
            ZUIOPT_NOTE("ACTIVATE","pid="+std::to_string(p.pid)+" generation="+std::to_string(p.generation)+" package="+p.package);
        }
    }
    ProcessState* resolve(const Snapshot& s){
        Identity id;
        try{id=validateManagedSnapshot(s,config,*this);}
        catch(const ProcError& error){if(!error.permission())throw;procBlocked(error);return nullptr;}
        if(!id.start)return nullptr;
        auto it=states.find(s.pid);if(it!=states.end()&&(it->second.generation!=id.start||it->second.uid!=s.uid)){placement->release(it->second);states.erase(it);}
        auto& p=states[s.pid];p.pid=s.pid;p.uid=s.uid;p.generation=id.start;p.name=s.name;p.package=s.packages[0];p.alive=true;return &p;
    }
    void reconcile(const std::vector<Snapshot>& snapshot){
        counters.snapshots++;std::set<int> present;
        for(auto& s:snapshot){
            auto* p=resolve(s);if(!p)continue;
            present.insert(s.pid);p->activity_foreground=(s.state==2)&&((s.flags&4)!=0);
            ZUIOPT_NOTE("SNAPSHOT","pid="+std::to_string(s.pid)+" name="+s.name+" state="+std::to_string(s.state)+" focused="+std::to_string(s.focused)+" generation="+std::to_string(p->generation));
        }
        for(auto it=states.begin();it!=states.end();)if(!present.count(it->first)){placement->release(it->second);it=states.erase(it);}else ++it;
        ZUIOPT_NOTE("RECONCILE","records="+std::to_string(present.size()));
        edges();for(auto& [_,p]:states)activate(p);
    }
    void edges(){
        for(auto& e:events.take()){
            counters.events++;ZUIOPT_NOTE("EVENT","seq="+std::to_string(e.sequence)+" code="+std::to_string(e.code));
            if(e.code==0){serviceDied=true;continue;}
            try{processEvent(e,states,*this);}
            catch(const ProcError& error){if(!error.permission())throw;procBlocked(error);}
        }
    }
    void scan(ProcessState& p){
        if(identity(p.pid).start!=p.generation){p.alive=false;placement->release(p);return;}
        const auto* profile=config.find(p.package);if(!profile){placement->release(p);return;}
        counters.scans++;auto start=now();auto tids=ids("/proc/"+std::to_string(p.pid)+"/task");std::set<int> live;
        std::map<std::string,std::vector<RankedTask>> candidates;
        std::map<std::string,const Rule*> groups;std::map<int,const Rule*> selected;
        for(int tid:tids){
            auto id=identity(p.pid,tid);if(!id.start)continue;live.insert(tid);auto& t=p.tasks[tid];bool fresh=t.generation!=id.start;
            if(fresh){t=Task{};t.generation=id.start;t.discovered=start;}
            bool renameDue=(t.renameStage==0&&start-t.discovered>=1000)||(t.renameStage==1&&start-t.discovered>=3000)||(t.renameStage>=2&&start-t.renamed>=30000);
            if(fresh||renameDue){
                auto comm=trim(read("/proc/"+std::to_string(tid)+"/comm"));counters.comm++;if(comm.empty())continue;t.comm=comm;t.renamed=start;
                if(!fresh)t.renameStage=std::min(2u,t.renameStage+1);
                auto* r=classify(*profile,t.comm);ZUIOPT_NOTE(fresh?"DISCOVER":"RENAME_CHECK","pid="+std::to_string(p.pid)+" tid="+std::to_string(tid)+" birth_ticks="+std::to_string(t.generation)+" comm="+t.comm+" class="+(r?r->cls:"default"));
            }
            if(auto* r=classify(*profile,t.comm)){
                if(r->rank==0){selected[tid]=r;continue;}
                counters.schedstat++;uint64_t score=0;std::istringstream sched(read("/proc/"+std::to_string(tid)+"/schedstat"));
                if(!(sched>>score))score=id.ticks*1000000000ull/static_cast<uint64_t>(sysconf(_SC_CLK_TCK));
                candidates[r->cls].push_back({score,tid});groups[r->cls]=r;
            }
        }
        for(auto it=p.tasks.begin();it!=p.tasks.end();)if(!live.count(it->first))it=p.tasks.erase(it);else ++it;
        for(auto& [cls,items]:candidates){auto* rule=groups.at(cls);int tid=representative(items,rule->rank);if(tid)selected[tid]=rule;
            ZUIOPT_NOTE("REPRESENTATIVE","pid="+std::to_string(p.pid)+" class="+cls+" rank="+std::to_string(rule->rank)+" tid="+std::to_string(tid));}
        placement->prepare(p);
        for(auto& [tid,t]:p.tasks){auto it=selected.find(tid);auto* r=it==selected.end()?nullptr:it->second;placement->apply(p,tid,t,r?r->mask:profile->general,r?r->cls:"default");}
        static constexpr std::array<int,5> burst={100,250,500,1000,2000};
        while(p.burst<burst.size()&&p.activated+burst[p.burst]<=now())p.burst++;
        p.next=p.burst<burst.size()?p.activated+burst[p.burst]:now()+1000;
        ZUIOPT_NOTE("SCAN","pid="+std::to_string(p.pid)+" tids="+std::to_string(p.tasks.size())+" elapsed_ms="+std::to_string(now()-start)+" next="+std::to_string(p.next));
    }
    void stats(){size_t active=0,tasks=0;for(auto& [_,p]:states){active+=p.managed;tasks+=p.tasks.size();}
        ZUIOPT_NOTE("STATS","events="+std::to_string(counters.events)+" snapshots="+std::to_string(counters.snapshots)+" scans="+std::to_string(counters.scans)+" comm="+std::to_string(counters.comm)+" schedstat="+std::to_string(counters.schedstat)+" placements="+std::to_string(counters.placements)+" releases="+std::to_string(counters.releases)+" wakeups="+std::to_string(counters.wakeups)+" reloads="+std::to_string(counters.reloads)+" active="+std::to_string(active)+" tasks="+std::to_string(tasks)+" journal_commits="+std::to_string(journal->commits));}
    void releaseAll(){if(placement){for(auto& [_,p]:states)placement->release(p);placement->cleanup();placement.reset();}stats();}
    int run(){StartupStage phase=StartupStage::CORE_CONSTRUCTED;try{
        recordLifecycle(stateRoot,journal->currentBootId(),phase);
        phase=StartupStage::OBSERVER;
        observer=std::make_unique<Observer>([this](int c,int p,int u,int v){enqueue(c,p,u,v);},&phase);
        recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::OBSERVER_OK);
        ZUIOPT_NOTE("REGISTER_OK","transaction=120");
        phase=StartupStage::SNAPSHOT;auto initial=observer->snapshot();
        recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::SNAPSHOT_OK);
        phase=StartupStage::PACKAGE_ABI;observer->packagesForUid(1000); // Validate both private reply ABIs before owner acquisition.
        recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::PACKAGE_ABI_OK);
        ZUIOPT_NOTE("ABI_GATE","PASS");phase=StartupStage::PLACEMENT;placement=std::make_unique<Placement>(counters,*journal);
        recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::PLACEMENT_OK);
        phase=StartupStage::RECONCILE;reconcile(initial);
        recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::RECONCILE_OK);
        phase=StartupStage::READY;prctl(PR_SET_NAME,"ZUIopt",0,0,0);ZUIOPT_NOTE("READY","pid="+std::to_string(getpid()));
        recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::READY);bool stop=false;
        while(!stop){
            phase=StartupStage::EVENT_LOOP; // In-memory only; there are no steady-state receipt writes.
            edges();require(!serviceDied,"activity service died: fail closed");for(auto& [_,p]:states)if(p.managed&&p.next<=now())scan(p);
            int timeout=-1;for(auto& [_,p]:states)if(p.managed){int delay=static_cast<int>(std::max<int64_t>(0,p.next-now()));timeout=timeout<0?delay:std::min(timeout,delay);}
            pollfd fds[]={{eventFd,POLLIN,0},{signalFd,POLLIN,0}};int n=poll(fds,2,timeout);if(n<0&&errno==EINTR)continue;require(n>=0,"poll failed");counters.wakeups++;
            if(fds[0].revents&POLLIN){uint64_t v;ssize_t ignored=::read(eventFd,&v,sizeof(v));(void)ignored;}
            if(fds[1].revents&POLLIN){signalfd_siginfo s{};while(::read(signalFd,&s,sizeof(s))==sizeof(s)){
                if(s.ssi_signo==SIGTERM||s.ssi_signo==SIGINT){stop=true;break;}if(s.ssi_signo==SIGUSR1)stats();
                if(s.ssi_signo==SIGHUP){
                    Config next;bool valid=false;try{next=parseConfig(read(configPath),available);valid=true;}catch(const std::exception& e){ZUIOPT_NOTE("RELOAD_REJECTED",e.what());}
                    if(valid){phase=StartupStage::RELOAD;for(auto& [_,p]:states)if(p.managed)placement->release(p);config=std::move(next);ZUIOPT_debug=config.debug;counters.reloads++;reconcile(observer->snapshot());ZUIOPT_NOTE("RELOAD_OK","last-known-good replaced");}
                }
            }}
        }
        phase=StartupStage::STOP;observer.reset();releaseAll();ZUIOPT_NOTE("STOPPED","owner_release=PASS");return 0;
    }catch(const std::exception& e){recordLifecycle(stateRoot,journal->currentBootId(),phase,&e);ZUIOPT_NOTE("FATAL",e.what());observer.reset();try{releaseAll();}catch(const std::exception& x){ZUIOPT_NOTE("RELEASE_BLOCKER",x.what());return 3;}return 2;}}
    ~Core(){observer.reset();if(signalFd>=0)close(signalFd);if(eventFd>=0)close(eventFd);}
};
}
