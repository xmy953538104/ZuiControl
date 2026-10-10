// ZUIopt event reactor. No periodic process authority query or idle task scan.
#pragma once
#include "ZUIopt_binder.h"
#include "ZUIopt_owner.h"
#include "ZUIopt_events.h"
#include "ZUIopt_scene.h"
#include "ZUIopt_store.h"
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
    Config config;std::string configPath,stateRoot,loadedConfig;Mask available;
    Counters counters;std::map<int,ProcessState> states;
    std::unique_ptr<Journal> journal;std::unique_ptr<Placement> placement;std::unique_ptr<Observer> observer;
    RawEvents events;RuntimeBlocker runtimeBlocker;
    std::unique_ptr<SceneObserver> sceneObserver;SceneBurst sceneBurst;
    int eventFd=-1,signalFd=-1;bool serviceDied=false,authorityEvent=false;
    EventSubstage substage=EventSubstage::NONE;
    uint64_t acceptedAuthority=0;
    SceneAuthority currentScene;
    std::string runtimeStatus;
    std::map<int,std::string> runtimeProcesses;
    bool topologyQualified=false,waltQualified=false;
public:
    Core(std::string path,const std::string& statePath):configPath(std::move(path)),stateRoot(statePath){
        available=cpus(read("/sys/devices/system/cpu/online"));loadedConfig=read(configPath);config=parseConfig(loadedConfig,available);ZUIOPT_debug=config.debug;
        topologyQualified=sm8650Topology();waltQualified=qualifiedWaltIdentity();
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
    void readScene(){
        if(!sceneObserver)return;
        try{currentScene=sceneObserver->current();events.pushScene(eventFd,currentScene.sequence);}
        catch(const std::exception&){currentScene={};} // No fallback to a stale AM foreground bit.
    }
    bool sceneForeground(const ProcessState& p,bool)const{return currentScene.wants(p.package,p.uid);}
    std::vector<Snapshot> activitySnapshot(){readScene();counters.snapshots++;return observer->snapshot();}
    void release(ProcessState& p){
        substage=EventSubstage::BACKGROUND_RELEASE;
        placement->release(p,p.alive?ReleaseCause::AUTHORITY_BACKGROUND:ReleaseCause::PROCESS_DEATH);
        if(!p.managed()){runtimeProcesses.erase(p.pid);publishRuntime();}
        if(p.releaseBlocked)blocked(RuntimeBlockerReason::BACKGROUND_RELEASE_BLOCKED);
    }
    void publishRuntime(){
        std::ostringstream out;out<<"{\"schema\":1,\"boot\":"<<std::quoted(journal->currentBootId())<<",\"processes\":[";
        bool comma=false;for(const auto& [_,state]:runtimeProcesses){if(comma)out<<',';comma=true;out<<state;}
        out<<"]}\n";if(out.str()!=runtimeStatus){PrivateDir(stateRoot).put("runtime-status.v1",out.str());runtimeStatus=out.str();}
    }
    BaselineResult acquire(ProcessState& p){
        try{return placement->acquire(p);}
        catch(const ProcError& error){if(!error.permission())throw;p.baselineCandidate={};procBlocked(error);return BaselineResult::DEFER;}
    }
    void baselineBlocked(){blocked(RuntimeBlockerReason::INHERITANCE_BASELINE_UNSTABLE);}
    void ownershipBlocked(){blocked(RuntimeBlockerReason::OWNERSHIP_CONTESTED);}
    void activate(ProcessState& p){
        if(!config.find(p.package)){release(p);return;}
        arbitrateLease(p,currentScene,now(),*this);
    }
    ProcessState* resolve(const Snapshot& s){
        Identity id;
        try{id=validateManagedSnapshot(s,config,*this);}
        catch(const ProcError& error){if(!error.permission())throw;procBlocked(error);return nullptr;}
        if(!id.start)return nullptr;
        auto it=states.find(s.pid);if(it!=states.end()&&(it->second.generation!=id.start||it->second.uid!=s.uid)){it->second.alive=false;release(it->second);states.erase(it);}
        auto& p=states[s.pid];p.pid=s.pid;p.uid=s.uid;p.generation=id.start;p.name=s.name;p.package=s.packages[0];p.alive=true;return &p;
    }
    void reconcile(const std::vector<Snapshot>& snapshot){
        counters.snapshots++;reconcileSnapshot(snapshot,states,*this);
    }
    void edges(){
        bool outerAuthority=authorityEvent;
        for(auto& e:events.take()){
            counters.events++;ZUIOPT_NOTE("EVENT","seq="+std::to_string(e.sequence)+" code="+std::to_string(e.code));
            if(e.code==0){serviceDied=true;continue;}
            // Primary transitions acquire/release normally. Same-state duplicate
            // callbacks cannot extend probation; only a new scene sequence can.
            authorityEvent=false;
            substage=EventSubstage::PROCESS_EVENT;
            try{processEvent(e,states,*this);}
            catch(const ProcError& error){if(!error.permission())throw;procBlocked(error);}
            authorityEvent=outerAuthority;
        }
    }
    void scan(ProcessState& p){
        if(!p.writable())return;
        // Never bless a queued epoch using foreground state from an older snapshot.
        const auto token=acceptedAuthority;
        AuthorityFence authority{[&]{return events.authorityCurrent(token);},[&]{
            auto snapshot=activitySnapshot();
            for(const auto& s:snapshot)if(s.pid==p.pid&&s.uid==p.uid&&s.state==2&&(s.flags&4))
                return validateManagedSnapshot(s,config,*this).start==p.generation;
            return false;
        }};
        try{
        authority.check();
        substage=EventSubstage::SCAN_COHERENCE;
        if(identity(p.pid).start!=p.generation){p.alive=false;release(p);return;}
        const auto* profile=config.find(p.package);if(!profile){release(p);return;}
        counters.scans++;auto start=now();auto tids=ids("/proc/"+std::to_string(p.pid)+"/task");std::set<int> live;
        std::map<std::string,std::vector<RankedTask>> candidates;
        std::map<std::string,const Rule*> groups;std::map<int,const Rule*> selected;
        for(int tid:tids){
            authority.check();
            auto id=identity(p.pid,tid);if(!id.start)continue;live.insert(tid);auto& t=p.tasks[tid];bool fresh=t.generation!=id.start;
            if(fresh){t=Task{};t.generation=id.start;t.discovered=start;}
            bool firstName=fresh||t.comm.empty();if(firstName)t.discovered=start;
            bool renameDue=(t.renameStage==0&&start-t.discovered>=1000)||(t.renameStage==1&&start-t.discovered>=3000)||(t.renameStage>=2&&start-t.renamed>=30000);
            if(firstName||renameDue){
                auto comm=trim(read("/proc/"+std::to_string(tid)+"/comm"));counters.comm++;if(comm.empty())continue;t.comm=comm;t.renamed=start;
                if(!firstName)t.renameStage=std::min(2u,t.renameStage+1);
                auto* r=classify(*profile,t.comm);ZUIOPT_NOTE(firstName?"DISCOVER":"RENAME_CHECK","pid="+std::to_string(p.pid)+" tid="+std::to_string(tid)+" birth_ticks="+std::to_string(t.generation)+" comm="+t.comm+" class="+(r?r->cls:"default"));
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
        auto plan=runtimePlan(*profile,config,topologyQualified,waltQualified);
        RuntimeRepresentatives reps;
        for(const auto& [tid,r]:selected){if(r->rank&&r->cls=="C1")reps.c1=tid;if(r->rank&&r->cls=="C2")reps.c2=tid;}
        if(profile->runtime.preset){
            reps=runtimeRepresentatives(candidates["C1"],candidates["C2"],profile->runtime.marker);
            for(auto it=selected.begin();it!=selected.end();)if(it->second->cls=="C1"||it->second->cls=="C2")it=selected.erase(it);else ++it;
            if(reps.c1&&groups.count("C1"))selected[reps.c1]=groups.at("C1");
            // __DoubleMain C1 may be selected from a C2-only classifier.
            if(reps.c1&&!groups.count("C1")&&groups.count("C2"))selected[reps.c1]=groups.at("C2");
            if(reps.c2&&groups.count("C2"))selected[reps.c2]=groups.at("C2");
        }
        // Kana mode2 0x7ac4 suppresses the C1 slot; every thread retains the general mask.
        if(plan.effectiveMode==2)reps.c1=0;
        authority.check();
        auto coherence=placement->verifyCoherence(p,start,&authority);
        if(coherence==CoherenceResult::REVOKED)throw PhysicalRevoke{};
        authority.check();
        if(coherence==CoherenceResult::CONTESTED)blocked(RuntimeBlockerReason::OWNERSHIP_CONTESTED);
        if(!p.managed())return;
        substage=EventSubstage::SCAN_PREPARE;authority.check();placement->prepare(p,&authority);authority.check();
        substage=EventSubstage::SCAN_APPLY;
        if(plan.requestedMode||plan.requestedRt)journal->runtimeVersion=true;
        bool waltComplete=true;
        for(auto& [tid,t]:p.tasks){
            (void)t;authority.check();
            if(plan.effectiveMode!=2||tid!=reps.c2){
                auto result=placement->walt(p,tid,0,0,&authority);require(result!=SchedulerResult::UNAVAILABLE,"WALT revocation unresolved");
            }
        }
        if(plan.effectiveMode==2){
            if(!reps.c2){waltComplete=false;plan.reason="NO_C2_REPRESENTATIVE";}
            else{
                auto result=placement->walt(p,reps.c2,plan.requestedRt?2:1,plan.requestedRt?128:2,&authority);
                waltComplete=result==SchedulerResult::APPLIED||result==SchedulerResult::UNCHANGED;
                if(!waltComplete)plan.reason=result==SchedulerResult::CONFLICT?"EXTERNAL_WALT_OWNER":result==SchedulerResult::UNRESTORABLE?"WALT_ORIGINAL_UNRESTORABLE":"WALT_WRITE_OR_READBACK_FAILED";
            }
            if(!waltComplete){
                if(reps.c2){auto result=placement->walt(p,reps.c2,0,0,&authority);require(result!=SchedulerResult::UNAVAILABLE,"partial WALT recovery unresolved");}
                if(plan.reason=="EXTERNAL_WALT_OWNER"){p.transition(Ownership::REVOKE_PENDING);throw PhysicalRevoke{};}
                plan.effectiveMode=0;plan.general=0x7c;plan.c1=0x1c;plan.c2=0x80;
                reps=runtimeRepresentatives(candidates["C1"],candidates["C2"],profile->runtime.marker);
            }
        }
        bool globalConflict=journal->globals.count("rr_timeslice_ms")&&trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))!="3";
        bool rtAllowed=plan.requestedRt&&!globalConflict&&(reps.c1||reps.c2)&&placement->prepareRealtimeGlobal();
        bool rtReadback=true,rtContested=globalConflict;std::string rtReason=globalConflict?"EXTERNAL_RT_GLOBAL_OWNER":plan.requestedRt&&!rtAllowed?"RT_BUDGET_OR_GLOBAL_UNAVAILABLE":"NONE";
        // Revoke outgoing representatives BEFORE granting incoming ones.
        for(auto& [tid,t]:p.tasks){
            (void)t;authority.check();
            if(!rtAllowed||(tid!=reps.c1&&tid!=reps.c2)){
                auto result=placement->realtime(p,tid,false,&authority);
                require(result!=SchedulerResult::UNAVAILABLE,"scheduler revocation unresolved");
                if(result==SchedulerResult::CONFLICT){rtReason="EXTERNAL_SCHEDULER_OWNER";rtContested=true;}
            }
        }
        std::map<int,Mask> masks;
        for(auto& [tid,t]:p.tasks){
            (void)t;auto it=selected.find(tid);auto* r=it==selected.end()?nullptr:it->second;
            Mask mask=r?r->mask:plan.general;
            if(profile->runtime.preset){mask=plan.effectiveMode==2?plan.general:tid==reps.c2?plan.c2:tid==reps.c1?plan.c1:plan.general;if(!mask)mask=r?r->mask:profile->general;}
            masks[tid]=mask;
        }
        if(journal->runtimeVersion)placement->stageMasks(p,masks,&authority);
        for(auto& [tid,t]:p.tasks){
            authority.check();auto it=selected.find(tid);
            placement->apply(p,tid,t,masks.at(tid),it==selected.end()?"default":it->second->cls,&authority);
        }
        if(rtAllowed)for(int tid:{reps.c1,reps.c2})if(tid){
            authority.check();auto result=placement->realtime(p,tid,true,&authority);
            if(result==SchedulerResult::UNAVAILABLE||result==SchedulerResult::CONFLICT||result==SchedulerResult::DEAD){
                rtReadback=false;rtContested=rtContested||result==SchedulerResult::CONFLICT;rtReason=rtContested?"EXTERNAL_SCHEDULER_OWNER":"RT_WRITE_OR_READBACK_FAILED";break;
            }
        }
        if(!rtReadback)for(int tid:{reps.c1,reps.c2})if(tid){
            auto result=placement->realtime(p,tid,false,&authority);require(result!=SchedulerResult::UNAVAILABLE,"partial RT recovery unresolved");
        }
        if(rtContested){p.transition(Ownership::REVOKE_PENDING);throw PhysicalRevoke{};}
        if(plan.effectiveMode==2&&plan.requestedRt&&(!rtAllowed||!rtReadback)){
            for(auto& [tid,t]:p.tasks){
                (void)t;auto result=placement->walt(p,tid,0,0,&authority);require(result!=SchedulerResult::UNAVAILABLE,"mode2 RT fallback WALT recovery unresolved");
            }
            plan.effectiveMode=0;plan.general=0x7c;plan.c1=0x1c;plan.c2=0x80;plan.reason="RT_REQUIRED_FOR_WALT_VARIANT";
            reps=runtimeRepresentatives(candidates["C1"],candidates["C2"],profile->runtime.marker);
            for(auto& [tid,t]:p.tasks){(void)t;masks[tid]=tid==reps.c2?plan.c2:tid==reps.c1?plan.c1:plan.general;}
            placement->stageMasks(p,masks,&authority);
            for(auto& [tid,t]:p.tasks)placement->apply(p,tid,t,masks.at(tid),"runtime_fallback",&authority);
        }
        if(!rtAllowed||!rtReadback)placement->retireRealtimeGlobal();
        placement->retireWaltGlobal();
        std::ostringstream status;
        status<<"{\"schema\":1,\"boot\":"<<std::quoted(journal->currentBootId())<<",\"pid\":"<<p.pid<<",\"processStart\":"<<p.generation
            <<",\"package\":"<<std::quoted(p.package)<<",\"requestedMode\":"<<plan.requestedMode<<",\"effectiveMode\":"<<plan.effectiveMode
            <<",\"modeReason\":"<<std::quoted(plan.reason)<<",\"requestedRt\":"<<plan.requestedRt<<",\"effectiveRt\":"<<(rtAllowed&&rtReadback?1:0)
            <<",\"rtReason\":"<<std::quoted(rtReason)<<",\"C1\":"<<reps.c1<<",\"C2\":"<<reps.c2<<"}";
        runtimeProcesses[p.pid]=status.str();publishRuntime();
        finishScan(p,now(),coherence==CoherenceResult::REPAIR);
        ZUIOPT_NOTE("SCAN","pid="+std::to_string(p.pid)+" tids="+std::to_string(p.tasks.size())+" elapsed_ms="+std::to_string(now()-start)+" next="+std::to_string(p.next));
        }catch(const PhysicalRevoke&){
            // Physical revocation already forbids writes. Query only on this
            // event, never periodically; accepted scene wins over stale AM.
            readScene();activate(p);
        }catch(const StaleAuthorityScan&){
            if(authority.background){p.activity_foreground=false;release(p);}
            // Pending callbacks retain their eventfd notification for the reactor.
        }
    }
    void stats(){size_t active=0,tasks=0;for(auto& [_,p]:states){active+=p.managed();tasks+=p.tasks.size();}
        ZUIOPT_NOTE("STATS","events="+std::to_string(counters.events)+" snapshots="+std::to_string(counters.snapshots)+" scans="+std::to_string(counters.scans)+" comm="+std::to_string(counters.comm)+" schedstat="+std::to_string(counters.schedstat)+" placements="+std::to_string(counters.placements)+" releases="+std::to_string(counters.releases)+" wakeups="+std::to_string(counters.wakeups)+" reloads="+std::to_string(counters.reloads)+" active="+std::to_string(active)+" tasks="+std::to_string(tasks)+" journal_commits="+std::to_string(journal->commits));}
    void releaseAll(ReleaseCause cause=ReleaseCause::CRASH_RECOVERY){
        if(placement){for(auto& [_,p]:states)placement->release(p,cause);
            placement->retireRealtimeGlobal();
            placement->retireWaltGlobal();
            substage=EventSubstage::CLEANUP;placement->cleanup();
            for(const auto& [pid,p]:states)if(!p.managed())runtimeProcesses.erase(pid);
            publishRuntime();placement.reset();}stats();
    }
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
        sceneObserver=std::make_unique<SceneObserver>([this](int64_t seq){events.pushScene(eventFd,seq);},
            [root=stateRoot](const std::string& argument){
                auto factory=read("/system/etc/zuiopt/factory_rules.conf",true);require(factory.size()<=RULE_LIMIT,"factory read bound");
                RuleStore store(root,factory,true);return store.readReply(argument);
            });
        readScene();
        phase=StartupStage::RECONCILE;reconcile(initial);
        recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::RECONCILE_OK);
        phase=StartupStage::READY;prctl(PR_SET_NAME,"ZUIopt",0,0,0);ZUIOPT_NOTE("READY","pid="+std::to_string(getpid()));
        recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::READY);recordLoadedGeneration(stateRoot,loadedConfig);bool stop=false;
        while(!stop){
            phase=StartupStage::EVENT_LOOP; // In-memory only; there are no steady-state receipt writes.
            auto epoch=events.authorityEpoch();
            edges();require(!serviceDied,"activity service died: fail closed");
            sceneBurst.accept(events.takeScene(),now());
            if(sceneBurst.timeout(now())==0){
                substage=EventSubstage::SCENE_RECONCILE;
                auto seq=sceneBurst.sequence;readScene();auto snapshot=observer->snapshot();
                if(events.latestScene(seq)){
                    authorityEvent=sceneBurst.step==0;
                    reconcile(snapshot);
                    authorityEvent=false;
                    if(sceneBurst.complete(now())&&events.latestScene(seq))sceneObserver->ack(seq);
                }
            }
            if(events.authorityCurrent(epoch))acceptedAuthority=epoch;
            for(auto& [_,p]:states){
                if(p.revoked()){activate(p);continue;}
                if(p.backgroundReleasing()){release(p);if(!p.backgroundReleasing())activate(p);continue;}
                substage=EventSubstage::ACQUISITION;advanceAcquisition(p,now(),*this);if(p.managed()&&p.next<=now())scan(p);
            }
            int timeout=sceneBurst.timeout(now());for(auto& [_,p]:states)if((p.writable()||p.backgroundReleasing()||p.acquiring())&&!p.releaseParked){int delay=static_cast<int>(std::max<int64_t>(0,p.next-now()));timeout=timeout<0?delay:std::min(timeout,delay);}
            pollfd fds[]={{eventFd,POLLIN,0},{signalFd,POLLIN,0}};int n=poll(fds,2,timeout);if(n<0&&errno==EINTR)continue;require(n>=0,"poll failed");counters.wakeups++;
            if(fds[0].revents&POLLIN){uint64_t v;ssize_t ignored=::read(eventFd,&v,sizeof(v));(void)ignored;}
            if(fds[1].revents&POLLIN){signalfd_siginfo s{};while(::read(signalFd,&s,sizeof(s))==sizeof(s)){
                if(s.ssi_signo==SIGTERM||s.ssi_signo==SIGINT){stop=true;break;}if(s.ssi_signo==SIGUSR1)stats();
                if(s.ssi_signo==SIGHUP){
                    Config next;std::string nextBytes;bool valid=false;try{nextBytes=read(configPath);next=parseConfig(nextBytes,available);valid=true;}catch(const std::exception& e){ZUIOPT_NOTE("RELOAD_REJECTED",e.what());}
                    if(valid){phase=StartupStage::RELOAD;substage=EventSubstage::RELOAD_RELEASE;for(auto& [_,p]:states)placement->release(p,ReleaseCause::RELOAD_OR_CONTROLLED_STOP);config=std::move(next);ZUIOPT_debug=config.debug;counters.reloads++;reconcile(observer->snapshot());loadedConfig=std::move(nextBytes);recordLoadedGeneration(stateRoot,loadedConfig);ZUIOPT_NOTE("RELOAD_OK","last-known-good replaced");}
                }
            }}
        }
        phase=StartupStage::STOP;substage=EventSubstage::STOP_RELEASE;sceneObserver.reset();observer.reset();releaseAll(ReleaseCause::RELOAD_OR_CONTROLLED_STOP);ZUIOPT_NOTE("STOPPED","owner_release=PASS");return 0;
    }catch(const std::exception& e){
        const auto primarySubstage=substage;recordLifecycle(stateRoot,journal->currentBootId(),phase,&e,primarySubstage);
        ZUIOPT_NOTE("FATAL",e.what());sceneObserver.reset();observer.reset();
        try{substage=EventSubstage::CATCH_RELEASE_ALL;releaseAll();}
        catch(const std::exception& x){recordLifecycle(stateRoot,journal->currentBootId(),phase,&e,primarySubstage,&x,substage);ZUIOPT_NOTE("RELEASE_BLOCKER",x.what());return 3;}return 2;}}
    ~Core(){sceneObserver.reset();observer.reset();if(signalFd>=0)close(signalFd);if(eventFd>=0)close(eventFd);}
};
}
