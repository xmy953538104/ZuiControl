// V58 R2: actual durable release + shared authority activation + acquisition.
#define ZUIOPT_BACKGROUND_LIBRARY 1
#include "ZUIoptBackgroundTest.cpp"
struct ReentryFixture:BackgroundFixture {
    bool newScene=false;int blockerWrites=0;int64_t lastWindow=-1;int windows=0;
    Identity procIdentity(int pid){return identity(pid);}
    int procUid(int pid){return uid(pid);}
    ProcessState* resolve(const Snapshot& s){
        auto id=validateManagedSnapshot(s,config,*this);if(!id.start)return nullptr;
        auto it=states.find(s.pid);
        if(it!=states.end()&&(it->second.generation!=id.start||it->second.uid!=s.uid)){
            it->second.alive=false;release(it->second);states.erase(it);
        }
        auto& p=states[s.pid];p.pid=s.pid;p.uid=s.uid;p.generation=id.start;p.name=s.name;p.package=s.packages[0];p.alive=true;return &p;
    }
    void release(ProcessState& p){
        owner->release(p,p.alive?ReleaseCause::AUTHORITY_BACKGROUND:ReleaseCause::PROCESS_DEATH,Kernel::time);
        if(p.releaseBlocked)blockerWrites+=receipt.record(Kernel::root+"/state",journal->currentBootId(),RuntimeBlockerReason::BACKGROUND_RELEASE_BLOCKED);
    }
    void activate(ProcessState& p){
        activateAuthority(p,p.alive&&p.activity_foreground&&config.find(p.package),newScene,Kernel::time,*this);
        if(p.releaseRearmed&&!p.releaseParked&&p.releaseStarted!=lastWindow){lastWindow=p.releaseStarted;windows++;}
    }
    void primary(){bool outer=newScene;newScene=false;processEvent({1,42,10001,1,1},states,*this);newScene=outer;}
    void tick(int64_t time){
        Kernel::time=time;
        if(scene.timeout(time)==0){newScene=scene.step==0;reconcileSnapshot(activitySnapshot(),states,*this);newScene=false;scene.complete(time);}
        for(auto& [_,p]:states){
            if(p.backgroundReleasing){release(p);if(!p.backgroundReleasing)activate(p);continue;}
            advanceAcquisition(p,time,*this);
        }
    }
    void setup(int n=200){
        BackgroundFixture::setup(n);
        Kernel::beforeWrite=[this](int tid,bool move,Mask mask){
            require(!reused.count(tid),"STALE_PID");
            auto record=journal->entries.find(tid);
            require(record!=journal->entries.end()&&journal->same(record->second),"unauthorized reentry write");
            if(!p().backgroundReleasing){require(p().managed&&!p().acquiring,"placement before acquisition commit");return;}
            auto& current=Kernel::tasks.at(tid);
            if(move)require(current.group.rfind("/ZUIopt",0)==0,"UNSAFE_CPUSET_REWRITE");
            else require(normalGroup(current.group)&&current.mask==p().tasks.at(tid).appliedMask&&mask==255,"UNSAFE_AFFINITY_REWRITE");
        };
    }
    void background(){app.state=19;app.flags=0;primary();}
    void park(){
        Kernel::stuck=true;background();for(int dt:{50,100,250,500})tick(250+dt);
        require(p().releaseParked&&p().backgroundReleasing&&!p().releaseBlocked&&!p().acquiring,"background timeout not parked");
        require(blockerWrites==0&&!fs::exists(Kernel::root+"/state/runtime_blocker.v1"),"background-only durable blocker");
    }
    void idle(int64_t time){
        auto reads=Kernel::reads,aff=Kernel::affinityReads,writes=Kernel::moves+Kernel::affinities,snaps=snapshotQueries;
        auto commits=journal->commits;
        // Even unrelated reactor wakeups cannot poll a parked transaction.
        for(int i=0;i<20;i++)tick(time+i);
        require(Kernel::reads==reads&&Kernel::affinityReads==aff&&Kernel::moves+Kernel::affinities==writes&&journal->commits==commits&&snapshotQueries==snaps,"IDLE_BACKGROUND_RELEASE_POLLING");
    }
    void safe(){Kernel::stuck=false;for(auto& [_,t]:Kernel::tasks){t.group="/top-app";t.mask=255;}}
    void foreground(int64_t time,int mode=0,int64_t seq=1){
        Kernel::time=time;app.state=2;app.flags=4;
        if(mode!=1)primary();if(mode!=2)scene.accept(seq,time);tick(time);
    }
    void acquireG07(int64_t time){
        require(!p().backgroundReleasing&&!p().releaseParked&&!p().releaseBlocked&&p().acquiring,"STICKY_RELEASE_OUTAGE");
        require(journal->entries.empty()&&journal->leases.empty(),"old journal not cleared before acquisition");
        for(auto& [_,t]:Kernel::tasks)require(t.group.rfind("/ZUIopt",0)!=0,"old physical owner before acquisition");
        auto start=p().acquireStarted;tick(time);for(int dt:{100,250,500,750,1000})if(!p().managed)tick(std::max(start+dt,time+dt));
        require(p().managed&&!p().acquiring,"STICKY_RELEASE_OUTAGE");
        owner->prepare(p());
        auto g07=parseConfig("schema 2\nenabled true\nprofile G 2-6\nthread G R exact Render selector=rank:1 20 2-4\nthread G T exact Game selector=rank:1 10 7\npackage exact org.example.game G 100\n",255);
        const auto& profile=*g07.find("org.example.game");
        std::vector<RankedTask> render={{20,43}},game={{10,44}};
        int r=representative(render,1),g=representative(game,1);
        for(auto& [tid,t]:p().tasks){auto rule=tid==r?classify(profile,"Render"):tid==g?classify(profile,"Game"):nullptr;
            owner->apply(p(),tid,t,rule?rule->mask:profile.general,rule?rule->cls:"default");
            require(Kernel::tasks.at(tid).mask==(tid==43?0x1c:tid==44?0x80:0x7c),"G07 reentry mask");
        }
        // Cleanly release the new generation/transaction too, then strict cleanup.
        background();require(!p().backgroundReleasing,"reentry final release");empty();owner->cleanup();
    }
};
void reentryDeterministic(){
    {ReentryFixture f;f.setup();f.background();require(!f.p().backgroundReleasing,"A unchanged release");f.empty();}
    for(int mode:{0,1,2}){ReentryFixture f;f.setup();f.park();f.idle(900);f.safe();f.idle(950);
        // Missing primary edge: the new scene generation alone must work even
        // when the last delivered foreground boolean is stale/unchanged.
        if(mode==1)f.p().releaseAuthorityForeground=true;
        f.foreground(2250,mode);require(f.windows==1,"primary/scene rearm missing");f.acquireG07(2250);}
    {ReentryFixture f;f.setup();f.park();f.safe();f.idle(30250);f.foreground(31000,2);f.acquireG07(31000);}
    {ReentryFixture f;f.setup();Kernel::stuck=true;f.background();f.foreground(700,2);
        require(f.windows==1&&f.p().releaseStarted==700,"foreground before park lost epoch");
        f.tick(750);f.safe();f.tick(800);f.acquireG07(800);}
    {ReentryFixture f;f.setup();f.park();f.foreground(2250);require(f.windows==1&&f.p().releaseStarted==2250,"first rearm");
        for(int dt:{1,50,100,250,499}){Kernel::time=2250+dt;f.primary();f.scene.accept(1,Kernel::time);f.tick(Kernel::time);
            require(f.windows==1&&f.p().releaseStarted==2250,"DUPLICATE_REARM_LOOP");}
        f.tick(2750);require(f.p().releaseParked&&f.p().releaseBlocked&&f.blockerWrites==1,"foreground blocker not once");
        require(!f.journal->entries.empty(),"unsafe parked journal clear");
        auto receipt=read(Kernel::root+"/state/runtime_blocker.v1");require(receipt.find("background_release_blocked")!=receipt.npos,"blocker reason");
        for(int i=0;i<20;i++)f.primary();f.idle(3000);require(f.windows==1&&f.blockerWrites==1,"duplicate terminal blocker");
        // A later genuine epoch still has a bounded self-heal opportunity.
        f.background();f.safe();f.foreground(4000,1,2);require(f.windows==2,"later foreground epoch");f.acquireG07(4000);}
    {ReentryFixture f;f.setup();f.park();Kernel::tasks.clear();
        processEvent({3,42,10001,0,2},f.states,f);require(f.states.empty()&&f.journal->entries.empty()&&f.journal->leases.empty(),"parked death stale journal");f.owner->cleanup();}
    {ReentryFixture f;f.setup();f.park();Kernel::stuck=false;f.owner->release(f.p(),ReleaseCause::RELOAD_OR_CONTROLLED_STOP,2000);f.empty();f.owner->cleanup();}
    {ReentryFixture f;f.setup();f.park();f.safe();for(auto& [_,t]:Kernel::tasks)t.birth=900;
        auto writes=Kernel::moves+Kernel::affinities;f.foreground(2250,2);
        require(f.p().generation==900&&Kernel::moves+Kernel::affinities==writes&&f.journal->entries.empty(),"STALE_PID");f.acquireG07(2250);}
    {ReentryFixture f;f.setup();f.park();Kernel::tasks[300]={1,"/ZUIopt/7c",0x7c};
        expectFailure([&]{f.foreground(2250,1);},"background unknown owned task");require(!f.journal->entries.empty(),"unknown rearm clear");}
    puts("RELEASE_REARM_A_TO_L=PASS;PRIMARY_REARM=PASS;SCENE_BACKSTOP_REARM=PASS;DUPLICATE_REARM_LOOP=0;IDLE_BACKGROUND_RELEASE_POLLING=0;DURABLE_BLOCKER_ON_BACKGROUND_ONLY_TIMEOUT=NO;TERMINAL_BLOCKER_AFTER_FAILED_FOREGROUND_REARM=ONCE;STICKY_RELEASE_OUTAGE=0;G07_REENTRY=PASS");
}
void reentryStress(){
    std::mt19937 random(0x58bea);constexpr int total=1024;
    for(int i=0;i<total;i++)try{
        ReentryFixture f;f.setup(4+random()%29);int delay=random()%2001,mode=random()%3;bool reverse=random()%2;
        Kernel::stuck=true;f.background();
        int subset=random()%101;handoff(subset,random()%3);
        if(random()%2){bool inherited=std::any_of(Kernel::tasks.begin(),Kernel::tasks.end(),[](const auto& t){return t.second.group=="/ZUIopt/7c";});
            Kernel::tasks[300]={f.p().ownershipFloor+1,inherited?"/ZUIopt/7c":"/background",inherited?Mask(0x7c):Mask(255)};}
        if(random()%2)Kernel::tasks.erase(45);
        int64_t time=251+random()%2001,safeAt=250+delay;
        for(int dt:{50,100,250,500})if(250+dt<time){if(250+dt>=safeAt)f.safe();f.tick(250+dt);}
        if(time>=safeAt)f.safe();
        if(mode==1&&f.p().releaseParked)f.p().releaseAuthorityForeground=true;
        // Individually lost primary or scene; both delivered in either order.
        if(mode==0&&reverse){f.foreground(time,1);f.primary();}else f.foreground(time,mode);
        while(f.p().backgroundReleasing&&!f.p().releaseParked){
            auto due=f.p().next;require(due>Kernel::time&&due<=time+500,"unbounded foreground release deadline");
            if(due>=safeAt)f.safe();f.primary();f.scene.accept(1,Kernel::time);f.tick(due);
        }
        require(f.windows<=1,"DUPLICATE_REARM_LOOP");
        if(f.p().releaseParked){
            require(f.p().releaseBlocked&&f.blockerWrites==1,"failed foreground rearm classification");
            f.idle(Kernel::time+1);f.background();f.safe();time=std::max(Kernel::time+100,safeAt);
            f.foreground(time,mode,2);require(f.windows==2,"fresh recovery epoch lost");
        }
        f.safe();time=std::max(Kernel::time,safeAt);f.acquireG07(time);
    }catch(const std::exception& e){throw std::runtime_error("random reentry case="+std::to_string(i)+" "+e.what());}
    std::cout<<"RELEASE_REENTRY_RANDOM_STRESS_COUNT="<<total<<";RECOVERABLE_GLOBAL_FATAL=0;STATUS3_RECOVERABLE=0;STICKY_RELEASE_OUTAGE=0;DUPLICATE_REARM_LOOP=0;UNSAFE_CPUSET_REWRITE=0;UNSAFE_AFFINITY_REWRITE=0;JOURNAL_CLEAR_WITH_LIVE_ZUIOPT_TASK=0;STALE_PID=0;OWNER_LEAK=0\n";
}
#ifndef ZUIOPT_REENTRY_LIBRARY
int main(){try{std::cout<<std::unitbuf;timed("RELEASE_REENTRY_DETERMINISTIC",reentryDeterministic);timed("RELEASE_REENTRY_STRESS",reentryStress);
    puts("RELEASE_REENTRY_FIXTURES=PASS");return 0;
}catch(const std::exception& e){std::cerr<<"RELEASE_REENTRY_FAIL "<<e.what()<<'\n';return 1;}}
#endif
