// Actual production enum, Placement, Journal, temporal acquisition and release.
// The synthetic kernel controls OS transitions, not production decision results.
#define ZUIOPT_REENTRY_LIBRARY 1
#include "ZUIoptReleaseReentryTest.cpp"

struct LeaseFixture:ReentryFixture {
    SceneAuthority accepted{10,0,true,"org.example.game"};
    int contested=0,revokes=0;
    bool sceneForeground(const ProcessState& p,bool)const{return accepted.wants(p.package,p.uid);}
    void ownershipBlocked(){contested++;receipt.record(Kernel::root+"/state",journal->currentBootId(),RuntimeBlockerReason::OWNERSHIP_CONTESTED);}
    void activate(ProcessState& p){arbitrateLease(p,accepted,Kernel::time,*this);}
    void primary(){processEvent({1,42,10001,1,1},states,*this);}
    void tick(int64_t time){
        Kernel::time=time;
        for(auto& [_,p]:states){
            if(p.backgroundReleasing()){release(p);if(!p.backgroundReleasing())activate(p);continue;}
            if(p.revoked()){activate(p);continue;}
            advanceAcquisition(p,time,*this);
        }
    }
    void setup(int n=200){
        ReentryFixture::setup(n);p().leaseScene=accepted.sequence;
        auto prior=Kernel::beforeWrite;
        Kernel::beforeWrite=[&,prior](int tid,bool move,Mask mask){
            require(!p().revoked(),"PLACEMENT_AFTER_REVOKE_PENDING");
            require(p().writable()||p().backgroundReleasing(),"WRITE_WITHOUT_LEASE_PERMISSION");
            prior(tid,move,mask);
        };
    }
    void detect(){
        auto writes=Kernel::moves+Kernel::affinities;auto commits=journal->commits;
        auto result=owner->verifyCoherence(p(),Kernel::time);
        require(result==CoherenceResult::REVOKED&&p().revoked(),"PHYSICAL_REVOKE_MISSING");
        require(Kernel::moves+Kernel::affinities==writes&&journal->commits==commits,"REVOKE_PROBE_WRITE");
        revokes++;
        bool prevented=false;
        try{owner->apply(p(),42,p().tasks.at(42),128,"forbidden");}catch(const PhysicalRevoke&){prevented=true;}
        require(prevented&&Kernel::moves+Kernel::affinities==writes,"PLACEMENT_AFTER_REVOKE");
    }
    void done(){
        require(!p().managed()&&!p().acquiring()&&!p().releaseBlocked&&contested==0,"NORMAL_HOME_BLOCKER_OR_OWNER_LEAK");
        require(journal->entries.empty()&&journal->leases.empty()&&!fs::exists(Kernel::root+"/state/owner_state.v1"),"UNSAFE_JOURNAL_CLOSE");
        for(auto& [_,t]:Kernel::tasks)require(normalGroup(t.group),"OWNER_LEAK");
        require(!fs::exists(Kernel::root+"/state/fatal.v1")&&!fs::exists(Kernel::root+"/state/runtime_blocker.v1"),"NORMAL_HOME_FATAL");
    }
    void backgroundCase(int delay,int percent,int kind,int order,int mutation=0){
        auto seq=accepted.sequence;
        if(order==1){accepted={seq+1,0,true,"org.example.launcher"};primary();}
        if(mutation==1){Kernel::tasks.erase(45);}
        if(mutation==2){Kernel::tasks[46]={90000,"/background",67};reused.insert(46);}
        if(mutation==3){Kernel::tasks[300]={p().ownershipFloor+1,"/ZUIopt/7c",124};}
        if(mutation==4){Kernel::tasks[301]={p().ownershipFloor+1,"/background",67};}
        handoff(percent,kind);
        if(order!=1){
            detect();accepted={};activate(p());
            auto reads=Kernel::reads,writes=Kernel::moves+Kernel::affinities;auto commits=journal->commits;
            for(int t=0;t<delay;t++)tick(250+t);
            require(p().revoked()&&p().next==0&&Kernel::reads==reads&&Kernel::moves+Kernel::affinities==writes&&journal->commits==commits,"PENDING_POLLS_OR_GUESSES_AUTHORITY");
            // AM intentionally remains foreground. Explicit scene must win.
            accepted={seq+1,0,order!=2,order==2?"":"org.example.launcher"};
            if(order==3)primary();else activate(p());
        }
        for(int dt:{0,50,100,250,500})tick(250+delay+dt);
        done();
    }
};
void leaseTransitions(){
    using O=Ownership;
    const std::set<std::pair<O,O>> legal={
        {O::ANDROID_OWNED,O::ACQUIRING},{O::ANDROID_OWNED,O::LOCAL_BLOCKED},
        {O::ACQUIRING,O::ANDROID_OWNED},{O::ACQUIRING,O::ZUIOPT_OWNED},{O::ACQUIRING,O::LOCAL_BLOCKED},
        {O::ZUIOPT_OWNED,O::REVOKE_PENDING},{O::ZUIOPT_OWNED,O::RELEASING},
        {O::REVOKE_PENDING,O::RELEASING},{O::RELEASING,O::ANDROID_OWNED},{O::LOCAL_BLOCKED,O::ANDROID_OWNED}};
    for(int i=0;i<6;i++)for(int j=0;j<6;j++){
        ProcessState p;p.ownership=static_cast<O>(i);auto next=static_cast<O>(j);bool rejected=false;
        try{p.transition(next);}catch(const std::runtime_error&){rejected=true;}
        require(rejected==!(i==j||legal.count({static_cast<O>(i),next})),"ILLEGAL_STATE_TRANSITION_ACCEPTED");
        if(rejected)require(p.ownership==static_cast<O>(i),"REJECTED_TRANSITION_MUTATED_STATE");
    }
    puts("EXPLICIT_STATE_MACHINE=PASS;STATE_TRANSITION_PAIRS=36");
}
void leaseDeterministic(){
    for(int delay:{0,10,25,50,75,100,150,200})for(int kind=0;kind<3;kind++)for(int order=0;order<4;order++){
        LeaseFixture f;f.setup();f.backgroundCase(delay,100,kind,order);
    }
    {LeaseFixture f;f.setup();
        for(int episode=1;episode<=3;episode++){
            handoff(100,2);f.detect();f.activate(f.p());
            require(f.journal->entries.empty(),"OLD_LEASE_NOT_CLOSED_BEFORE_REACQUIRE");
            if(episode<=2){
                require(f.p().acquiring()&&!f.p().managed(),"SAME_FOREGROUND_REACQUIRE_MISSING");
                f.safe();auto time=Kernel::time;for(int dt:{0,100,250,500})f.tick(time+dt);
                require(f.p().writable()&&f.p().revokeEpisodes==static_cast<unsigned>(episode),"REACQUIRE_BUDGET_RESET");
                f.owner->prepare(f.p());for(auto& [tid,t]:f.p().tasks)f.owner->apply(f.p(),tid,t,124,"new_lease");
            }else require(f.p().acquireBlocked()&&f.contested==1,"LOCAL_CONTESTED_BOUND_MISSING");
        }
        auto writes=Kernel::moves+Kernel::affinities,reads=Kernel::reads;
        for(int i=0;i<100;i++)f.activate(f.p());
        require(writes==Kernel::moves+Kernel::affinities&&reads==Kernel::reads&&f.contested==1,"BLOCKED_IDLE_WORK");
        ++f.accepted.sequence;f.activate(f.p());require(f.p().acquiring()&&f.p().revokeEpisodes==0,"FRESH_EPOCH_RECOVERY_MISSING");
    }
    puts("HOME_PHYSICAL_FIRST_200_TASKS=PASS;DELAYS=0,10,25,50,75,100,150,200;AM_STALE_SCENE_WINS=PASS;UNKNOWN_AUTHORITY_PENDING=PASS;NORMAL_HOME_BLOCKER=0;SAME_FOREGROUND_REACQUISITION=PASS;REACQUISITION_BUDGET=2;IDLE_POLLING=0");
}
void leaseStress(){
    std::mt19937 random(0x60ea5e);constexpr int total=5000;
    std::set<int> delays,kinds,orders,mutations;
    for(int i=0;i<total;i++)try{
        LeaseFixture f;f.setup(8+random()%33);
        int delay=random()%251,kind=random()%3,order=random()%4,mutation=order==1?0:random()%5;
        delays.insert(delay);kinds.insert(kind);orders.insert(order);mutations.insert(mutation);
        f.backgroundCase(delay,25+random()%76,kind,order,mutation);
    }catch(const std::exception& e){throw std::runtime_error("lease case="+std::to_string(i)+" "+e.what());}
    require(delays.size()==251&&kinds.size()==3&&orders.size()==4&&mutations.size()==5,"STRESS_COVERAGE_MISSING");
    std::cout<<"RANDOM_STRESS_COUNT="<<total<<";RECOVERABLE_GLOBAL_FATAL=0;STATUS3_RECOVERABLE=0;PLACEMENT_AFTER_REVOKE=0;STALE_PID=0;OWNER_LEAK=0;UNSAFE_JOURNAL_CLEAR=0;NORMAL_HOME_BLOCKER=0;EVENTUAL_RELEASE_MISS=0\n";
}
void physicalSyscallBoundary(){
    // One process, not one apply. Count ALL optimization effects after the
    // physical handoff, separately from writes after an observed cancellation.
    int cases=0,failed=0,maxTasks=0,maxCalls=0,postObserved=0,nextAfterRevoke=0;
    for(int index:{0,100,199})for(int population:{1,50,100,150,200})
    for(int phase=0;phase<4;phase++)for(int delay:{0,10,25,50,100,200}){
        LeaseFixture f;f.setup(200);
        const int target=42+index;
        bool injected=false,optimizing=true,observed=false;
        int calls=0,afterObserved=0,nextWrites=0;
        std::set<int> written,handed;
        auto inject=[&]{
            require(!injected,"DUPLICATE_HANDOFF");
            injected=true;
            // Cyclic contiguous population includes the current task; a single
            // task handoff does NOT magically alter its remaining 199 siblings.
            for(int n=0;n<population;n++){
                int tid=42+(index+n)%200;handed.insert(tid);
                Kernel::tasks.at(tid).group="/background";Kernel::tasks.at(tid).mask=67;
            }
        };
        Kernel::hook=[&](const std::string& path){
            if(optimizing&&!injected&&phase==0&&path=="/proc/"+std::to_string(target)+"/cgroup")inject();
        };
        Kernel::beforeWrite=[&](int tid,bool move,Mask){
            if(!optimizing)return;
            require(f.journal->entries.count(tid)&&f.journal->same(f.journal->entries.at(tid)),"UNJOURNALED_OPTIMIZATION");
            if(!injected&&tid==target&&((phase==1&&move)||(phase==2&&!move)))inject();
            if(injected){calls++;written.insert(tid);}
            if(observed||f.p().revoked()){afterObserved++;if(tid!=target)nextWrites++;}
        };
        Kernel::afterWrite=[&](int tid,bool move,Mask){
            if(optimizing&&!injected&&phase==3&&tid==target&&!move)inject();
        };
        try{
            for(auto& [tid,t]:f.p().tasks)
                f.owner->apply(f.p(),tid,t,128,tid%2?"rank":"default");
        }catch(const PhysicalRevoke&){observed=true;}
        require(injected,"SYSCALL_INTERLEAVING_NOT_EXERCISED");
        // No optimization may start once the process is actually revoked.
        if(observed){
            auto before=calls;bool rejected=false;
            try{f.owner->apply(f.p(),target,f.p().tasks.at(target),128,"blocked_next");}
            catch(const PhysicalRevoke&){rejected=true;}
            require(rejected&&before==calls,"NEXT_TASK_WRITE_AFTER_REVOKE");
        }
        maxTasks=std::max(maxTasks,static_cast<int>(written.size()));
        maxCalls=std::max(maxCalls,calls);postObserved+=afterObserved;nextAfterRevoke+=nextWrites;
        bool bounded=written.size()<=1&&calls<=2&&!afterObserved&&!nextWrites;
        if(!bounded){
            failed++;
            // Preserve the exact counterexample; never count only handed-off
            // tasks and silently omit optimization writes to unaffected siblings.
            std::cout<<"TAIL_CASE_FAIL index="<<index<<";population="<<population
                     <<";phase="<<phase<<";delay="<<delay<<";tasks_written="<<written.size()
                     <<";syscalls="<<calls<<";revoke_observed="<<observed<<'\n';
        }
        optimizing=false;Kernel::hook={};Kernel::beforeWrite={};Kernel::afterWrite={};
        f.accepted={};f.activate(f.p()); // Unknown slow-path authority: park, do not guess.
        auto writes=Kernel::moves+Kernel::affinities;
        for(int dt=0;dt<delay;dt++)f.tick(250+dt);
        require(Kernel::moves+Kernel::affinities==writes,"PENDING_PLACEMENT_TAIL");
        f.accepted={11,0,true,"org.example.launcher"};f.activate(f.p());
        for(int dt:{0,50,100,250,500})f.tick(250+delay+dt);
        f.done();cases++;
    }
    std::cout<<"SYSCALL_BOUNDARY_CASES="<<cases<<";FAILED="<<failed
             <<";MAX_POST_PHYSICAL_HANDOFF_TASKS_WRITTEN="<<maxTasks
             <<";MAX_POST_PHYSICAL_HANDOFF_SYSCALLS="<<maxCalls
             <<";POST_REVOKE_OBSERVED_WRITES="<<postObserved
             <<";NEXT_TASK_WRITE_AFTER_REVOKE="<<nextAfterRevoke
             <<";EVENTUAL_RELEASE_MISS=0;RECOVERABLE_GLOBAL_FATAL=0\n";
    require(!failed,"BOUNDED_INFLIGHT_TAIL_EXCEEDED");
}
int main(){try{std::cout<<std::unitbuf;timed("LEASE_TRANSITIONS",leaseTransitions);timed("LEASE_DETERMINISTIC",leaseDeterministic);timed("LEASE_RANDOM5000",leaseStress);timed("PHYSICAL_SYSCALL_BOUNDARY",physicalSyscallBoundary);return 0;}
    catch(const std::exception& e){std::cerr<<"LEASE_FAIL "<<e.what()<<'\n';return 1;}}
