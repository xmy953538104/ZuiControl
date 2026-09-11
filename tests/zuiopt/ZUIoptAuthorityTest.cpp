// Shared RawEvents/fence + actual production Placement/Journal/release/acquire.
// Synthetic scan injection points mirror the source-checked Core::scan wiring.
// This reproduces the concurrency class, not the uncaptured device TID.
#define ZUIOPT_REENTRY_LIBRARY 1
#include "ZUIoptReleaseReentryTest.cpp"
struct AuthorityFixture:ReentryFixture {
    RawEvents raw;int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    uint64_t accepted=0;int transitions=0,aborts=0,fresh=0;bool scanning=false;
    AuthorityFence* active=nullptr;
    ~AuthorityFixture(){close(fd);}
    void setup(int n=200){
        ReentryFixture::setup(n);
        owner->apply(p(),43,p().tasks.at(43),0x1c,"render");
        owner->apply(p(),44,p().tasks.at(44),0x80,"game");
        raw.pushScene(fd,9);raw.takeScene();accepted=raw.authorityEpoch();
        auto previous=Kernel::beforeWrite;
        Kernel::beforeWrite=[&,previous](int tid,bool move,Mask mask){
            if(scanning)require(active&&!active->stale,"PLACEMENT_AFTER_STALE_AUTHORITY");
            if(!scanning&&!p().backgroundReleasing()){previous(tid,move,mask);return;}
            require(journal->entries.count(tid)&&journal->same(journal->entries.at(tid)),"STALE_PID_OR_UNJOURNALED_WRITE");
            if(p().backgroundReleasing()&&move)require(Kernel::tasks.at(tid).group.rfind("/ZUIopt",0)==0,"UNSAFE_CPUSET_REWRITE");
        };
    }
    void transition(int mode,int percent,int kind){
        require(++transitions==1,"duplicate transition injection");
        app.state=19;app.flags=0;handoff(percent,kind);
        if(mode==0||mode==2||mode==5)raw.pushScene(fd,10);
        if(mode==1||mode==3||mode==5)raw.push(fd,1,42,10001,0);
        if(mode==0||mode==3)raw.push(fd,1,42,10001,0);
        if(mode==1)raw.pushScene(fd,10);
        if(mode==5){raw.pushScene(fd,10);raw.pushScene(fd,9);}
    }
    void reactor(){
        auto token=raw.authorityEpoch();
        for(const auto& e:raw.take())processEvent(e,states,*this);
        auto seq=raw.takeScene();if(seq>=0){scene.accept(seq,Kernel::time);tick(Kernel::time);}
        if(raw.authorityCurrent(token))accepted=token;
    }
    void scan(int point,int mode,int percent=100,int kind=2,int index=100,bool mutate=false){
        AuthorityFence guard{[&]{return raw.authorityCurrent(accepted);},[&]{
            fresh++;auto snapshot=activitySnapshot();
            for(const auto& s:snapshot)if(s.pid==p().pid&&s.uid==p().uid&&s.state==2&&(s.flags&4))
                return validateManagedSnapshot(s,config,*this).start==p().generation;
            return false;
        }};
        active=&guard;scanning=true;auto episodes=p().coherenceEpisodes;
        auto inject=[&]{transition(mode,percent,kind);
            if(mutate){Kernel::tasks.erase(46);Kernel::tasks[47]={90000,"/background",67};
                Kernel::tasks[300]={p().ownershipFloor+1,"/background",31};}};
        try{
            if(point==0)inject();guard.check();
            auto tids=ids("/proc/42/task");int at=0;
            for(int tid:tids){
                if(point==1&&at++==index)inject();guard.check();
                auto id=identity(42,tid);if(!id.start)continue;
            }
            if(point==2)inject();guard.check();
            if(point==3)Kernel::hook=[&](const std::string& path){if(path=="affinity/43"){Kernel::hook={};inject();}};
            auto result=owner->verifyCoherence(p(),Kernel::time,&guard);guard.check();
            if(result==CoherenceResult::REVOKED)throw PhysicalRevoke{};
            require(result==CoherenceResult::CLEAN,"unexpected pretransition drift");
            if(point==4)inject();guard.check();owner->prepare(p(),&guard);guard.check();
            if(point==5)Kernel::hook=[&](const std::string& path){if(path=="/dev/cpuset/ZUIopt/80/tasks"){Kernel::hook={};inject();}};
            at=0;
            for(auto& [tid,t]:p().tasks){
                if(point==6&&at++==index)inject();guard.check();
                owner->apply(p(),tid,t,0x80,"race",&guard);
            }
            finishScan(p(),Kernel::time);
        }catch(const PhysicalRevoke&){
            aborts++;fresh++;scanning=false;p().activity_foreground=false;release(p());
        }catch(const StaleAuthorityScan&){
            aborts++;p().coherenceEpisodes=episodes;
            scanning=false;
            if(guard.background){p().activity_foreground=false;release(p());}
        }
        scanning=false;active=nullptr;Kernel::hook={};
        require(transitions==1&&aborts==1,"STALE_SCAN_ABORT_MISSING");
        require(fresh<=1,"DRIFT_SNAPSHOT_LOOP");
        reactor();
        for(int dt:{50,100,250,500})tick(250+dt);
        require(!p().managed()&&!p().backgroundReleasing()&&!p().releaseBlocked,"OWNER_LEAK");
        require(journal->entries.empty()&&journal->leases.empty()&&!fs::exists(Kernel::root+"/state/owner_state.v1"),"JOURNAL_NOT_CLOSED");
        for(auto& [_,t]:Kernel::tasks)require(t.group.rfind("/ZUIopt",0)!=0,"PHYSICAL_OWNER_LEAK");
        require(!fs::exists(Kernel::root+"/state/fatal.v1")&&!fs::exists(Kernel::root+"/state/runtime_blocker.v1"),"RECOVERABLE_FATAL_OR_BLOCKER");
        safe();foreground(1000,2);acquireG07(1000);
    }
};
void authorityDeterministic(){
    // Physical-first no-event path now revokes instead of entering the V59 fatal.
    {AuthorityFixture f;f.setup();f.transition(0,100,2);
        require(f.owner->verifyCoherence(f.p(),250)==CoherenceResult::REVOKED&&f.p().revoked(),"physical revocation absent");}
    auto daemon=getpid();
    for(int point=0;point<7;point++)for(int mode:{0,1,2,3,5}){
        AuthorityFixture f;f.setup();f.scan(point,mode,100,2,100);
        require(getpid()==daemon,"DAEMON_RESTART");
    }
    {AuthorityFixture f;f.setup();f.scan(3,4);require(f.fresh==1,"fresh background authority missing");}
    {AuthorityFixture f;f.setup();handoff(100,2);
        AuthorityFence guard{[&]{return f.raw.authorityCurrent(f.accepted);},[&]{f.fresh++;return true;}};
        require(f.owner->verifyCoherence(f.p(),250,&guard)==CoherenceResult::REVOKED,"same authority must revoke first");
        require(f.fresh==0&&f.p().revoked()&&!f.journal->entries.empty(),"SAME_AUTHORITY_DRIFT_IGNORED");}
    {RawEvents raw;int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);raw.pushScene(fd,10);
        auto epoch=raw.authorityEpoch();require(!raw.authorityCurrent(epoch),"pending scene accepted as reconciled");
        raw.pushScene(fd,10);require(raw.authorityEpoch()==epoch,"duplicate scene invalidation");raw.takeScene();
        require(raw.authorityCurrent(epoch),"consumed authority not current");
        for(int i=0;i<10000;i++)require(raw.authorityCurrent(epoch),"idle epoch changed");
        uint64_t value;require(::read(fd,&value,sizeof(value))==sizeof(value)&&value==1,"idle extra wake");
        require(::read(fd,&value,sizeof(value))<0&&errno==EAGAIN,"idle polling notification");close(fd);}
    puts("V59_FATAL_PATH_REPLACED_BY_REVOCATION=PASS;A01_200_TASKS=PASS;EXACT_DEVICE_TID=NOT_PROVEN;RACE_A_TO_F=PASS;EVENT_SOURCE_VARIANTS=PASS;SAME_AUTHORITY_DRIFT_PENDING=PASS;IDLE_EXTRA_WAKE=0");
}
void authorityStress(){
    std::mt19937 random(0x59e90c);constexpr int total=2000;
    for(int i=0;i<total;i++)try{
        AuthorityFixture f;int n=8+random()%33;f.setup(n);Kernel::time=250+random()%4000;
        int point=random()%7,mode=random()%5;mode=mode==4?5:mode;
        f.scan(point,mode,random()%101,random()%3,random()%n,random()%2);
    }catch(const std::exception& e){throw std::runtime_error("authority case="+std::to_string(i)+" "+e.what());}
    puts("AUTHORITY_RANDOM_STRESS_COUNT=2000;RECOVERABLE_GLOBAL_FATAL=0;STATUS3_RECOVERABLE=0;STALE_AUTHORITY_SCAN_FATAL=0;PLACEMENT_AFTER_STALE_AUTHORITY=0;OWNER_LEAK=0;STALE_PID=0;JOURNAL_CLEAR_WITH_LIVE_ZUIOPT_TASK=0;G07_REENTRY=PASS");
}
int main(){try{std::cout<<std::unitbuf;timed("AUTHORITY_DETERMINISTIC",authorityDeterministic);timed("AUTHORITY_RANDOM",authorityStress);return 0;}
    catch(const std::exception& e){std::cerr<<"AUTHORITY_FAIL "<<e.what()<<'\n';return 1;}}
