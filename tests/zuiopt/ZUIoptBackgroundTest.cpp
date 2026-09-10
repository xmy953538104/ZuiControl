// Real Journal/Placement + shared production event dispatch; isolated POSIX kernel.
// V57 reproduction is sealed at 5a52c215 / CI 34504713515, not rewritten as V58 proof.
#define ZUIOPT_ACQUISITION_LIBRARY 1
#include "ZUIoptAcquisitionTest.cpp"
extern "C" int __real_unlinkat(int,const char*,int);
extern "C" int __wrap_unlinkat(int fd,const char* name,int flags){
    if(!Kernel::root.empty()&&std::string(name)=="owner_state.v1")
        for(auto& [_,t]:Kernel::tasks)require(t.group.rfind("/ZUIopt",0)!=0,"JOURNAL_CLEAR_WITH_LIVE_ZUIOPT_TASK");
    return __real_unlinkat(fd,name,flags);
}
struct BackgroundFixture:AcquisitionFixture {
    int passes=0;std::set<int> reused;
    void release(ProcessState& p){owner->release(p,p.alive?ReleaseCause::AUTHORITY_BACKGROUND:ReleaseCause::PROCESS_DEATH,Kernel::time);}
    void activate(ProcessState& p){if(!p.activity_foreground){release(p);return;}if(!p.backgroundReleasing)beginAcquisition(p,Kernel::time);}
    void primary(){processEvent({1,42,10001,1,1},states,*this);}
    void background(bool reverse=false){
        app.state=19;app.flags=0;
        if(reverse)reconcileSnapshot(activitySnapshot(),states,*this);
        primary();if(!reverse)reconcileSnapshot(activitySnapshot(),states,*this);
    }
    void setup(int n){
        for(int tid=44;tid<42+n;tid++)Kernel::tasks[tid]={10,"/top-app",255};
        start(2);place();require(p().tasks.size()==static_cast<size_t>(n),"initial committed count");
        p().coherenceReleasing=true;
        Kernel::beforeWrite=[this](int tid,bool move,Mask mask){
            require(!reused.count(tid),"STALE_PID");const auto& t=Kernel::tasks.at(tid);
            require(!journal->leases.empty(),"write without live lease");
            if(move)require(t.group.rfind("/ZUIopt",0)==0,"UNSAFE_CPUSET_REWRITE");
            else require(t.mask==0x7c&&normalGroup(t.group)&&mask==255,"UNSAFE_AFFINITY_REWRITE");
        };
    }
    void complete(){
        for(int dt:{0,50,100,250,500}){
            Kernel::time=250+dt;
            if(p().backgroundReleasing&&!p().releaseBlocked&&p().next<=Kernel::time){release(p());passes++;}
        }
        Kernel::hook={};
        require(!p().backgroundReleasing&&!p().releaseBlocked,"bounded normal release unfinished");empty();
        for(auto& [_,t]:Kernel::tasks)require(t.group.rfind("/ZUIopt",0)!=0,"OWNER_LEAK");
        owner->release(p(),ReleaseCause::CRASH_RECOVERY,Kernel::time);
        owner->release(p(),ReleaseCause::CRASH_RECOVERY,Kernel::time);owner->cleanup();
        require(!fs::exists(Kernel::root+"/state/owner_state.v1"),"journal not absent");
    }
};
void handoff(int percent,int kind){
    int count=static_cast<int>(Kernel::tasks.size())*percent/100,index=0;
    for(auto& [tid,t]:Kernel::tasks)if(index++<count){
        if(kind!=1)t.group=tid%2?"/foreground":"/background";
        if(kind!=0)t.mask=tid%2?31:67;
    }
}
void deterministic(){
    int cases=0;
    for(int percent:{0,25,50,75,100})for(int kind:{0,1,2})for(bool reverse:{false,true}){
        BackgroundFixture f;f.setup(200);handoff(percent,kind);f.background(reverse);f.complete();cases++;
    }
    for(int action=0;action<7;action++){
        BackgroundFixture f;f.setup(200);
        Kernel::hook=[&](const std::string& path){
            if(path!="affinity/43")return;Kernel::hook={};
            if(action==0)Kernel::tasks.erase(43);
            if(action==1)Kernel::tasks[300]={f.p().ownershipFloor+1,"/ZUIopt/7c",0x7c};
            if(action==2)Kernel::tasks[301]={f.p().ownershipFloor+1,"/background",67};
            if(action==3){Kernel::tasks[43]={90000,"/background",67};f.reused.insert(43);}
            if(action==4)Kernel::tasks.clear();
            if(action==5)handoff(75,2);
            if(action==6)handoff(100,0);
        };
        f.background(action%2);f.background(action%2);f.complete();cases++;
    }
    {BackgroundFixture f;f.setup(200);Kernel::hook=[](const std::string& path){
        if(path=="/dev/cpuset/top-app/tasks"){Kernel::hook={};handoff(100,2);}
    };f.background();f.complete();cases++;}
    {BackgroundFixture f;f.setup(200);bool open=false;int reads=0;
        Kernel::hook=[&](const std::string& path){
            if(path=="/dev/cpuset/top-app/tasks")open=true;
            if(open&&path=="/proc/42/stat"&&++reads==2){Kernel::hook={};handoff(100,2);}
        };f.background();f.complete();require(reads>=2,"late generation-read handoff not injected");cases++;}
    {BackgroundFixture f;f.setup(200);Kernel::stuck=true;f.background();
        require(f.p().backgroundReleasing&&f.p().next==300&&!f.p().acquiring&&!forceCoherence(f.p()),"release state cancellation");
        auto moves=Kernel::moves;for(int i=0;i<20;i++)f.background();require(Kernel::moves==moves,"duplicate release busy loop");
        Kernel::stuck=false;f.complete();cases++;}
    {BackgroundFixture f;f.setup(200);Kernel::stuck=true;
        reconcileSnapshot(std::vector<Snapshot>{},f.states,f);require(f.states.count(42)&&f.p().backgroundReleasing,"pending state erased");
        Kernel::stuck=false;f.complete();cases++;}
    // Safe external observation delay expires locally; duplicates do not poll.
    // Catch cleanup retains durable evidence instead of throwing status3.
    {BackgroundFixture f;f.setup(200);handoff(100,2);Kernel::tasks.at(43).mask=0;f.background();
        for(int dt:{50,100,250,500}){Kernel::time=250+dt;f.release(f.p());}
        require(f.p().releaseBlocked&&f.p().backgroundReleasing&&!f.journal->entries.empty(),"external delay must retain journal");
        auto reads=Kernel::reads,writes=Kernel::moves+Kernel::affinities;
        for(int i=0;i<100;i++)f.release(f.p());
        require(Kernel::reads==reads&&Kernel::moves+Kernel::affinities==writes,"blocked release idle polling");
        f.owner->release(f.p(),ReleaseCause::CRASH_RECOVERY,Kernel::time);
        require(!f.journal->entries.empty(),"incomplete observation cleared journal");
        Kernel::tasks.at(43).mask=67;f.owner->release(f.p(),ReleaseCause::CRASH_RECOVERY,Kernel::time);f.complete();cases++;}
    std::cout<<"BACKGROUND_DETERMINISTIC_A_TO_O=PASS;CASES="<<cases<<";INITIAL_TASKS=200;LOCAL_BLOCKER_IDLE_POLLING=0;CATCH_CLEANUP_IDEMPOTENT=PASS\n";
}
void strictBoundaries(){
    {BackgroundFixture f;f.setup(200);Kernel::tasks[300]={1,"/ZUIopt/7c",0x7c};
        expectFailure([&]{f.background();},"background unknown owned task");require(!f.journal->entries.empty(),"unknown clear");}
    {BackgroundFixture f;f.setup(200);f.journal->leases.at(42).processStart++;
        expectFailure([&]{f.background();},"committed lease identity mismatch");}
    {BackgroundFixture f;f.setup(200);f.journal->entries.erase(43);
        expectFailure([&]{f.background();},"write without durable owner identity");}
    {BackgroundFixture f;f.setup(200);Kernel::stuck=true;f.background();Kernel::time=750;
        f.release(f.p());require(f.p().releaseBlocked&&!f.journal->entries.empty(),"known contention deadline killed daemon");
        auto reads=Kernel::reads;for(int i=0;i<100;i++)f.release(f.p());require(Kernel::reads==reads,"owned blocker idle polling");
        expectFailure([&]{f.owner->release(f.p(),ReleaseCause::CRASH_RECOVERY);},"background unrecoverable owned task");
        Kernel::stuck=false;f.owner->release(f.p(),ReleaseCause::RELOAD_OR_CONTROLLED_STOP);f.complete();}
    {BackgroundFixture f;f.setup(200);Kernel::moveError=EACCES;
        expectFailure([&]{f.background();},"background owned release failed");
        expectFailure([&]{f.owner->release(f.p(),ReleaseCause::CRASH_RECOVERY);},"background owned release failed");}
    {BackgroundFixture f;f.setup(200);Kernel::tasks[300]={1,"/ZUIopt/7c",0x7c};
        Kernel::hook=[](const std::string& path){if(path=="/proc/300/cgroup"){Kernel::hook={};Kernel::tasks.at(300).group.clear();}};
        expectFailure([&]{f.background();},"background physical owner unavailable");
        require(!f.journal->entries.empty(),"unobservable live physical owner ignored");}
    {BackgroundFixture f;f.setup(200);Kernel::tasks[300]={1,"/ZUIopt/7c",0x7c};f.owner.reset();
        f.journal->entries.clear();f.journal->leases.clear();
        expectFailure([&]{Placement recovery(f.count,*f.journal);},"RECOVERY_UNKNOWN_TASK_FAIL_CLOSED");}
    puts("BACKGROUND_TRUE_FATAL_BOUNDARIES=PASS;CRASH_RECOVERY_STRICT=PASS");
}
void backgroundStress(){
    std::mt19937 random(0x58bac);int total=1024;
    for(int i=0;i<total;i++)try{
        BackgroundFixture f;int n=2+random()%199;f.setup(n);
        int when=random()%3,percent=random()%101,kind=random()%3,action=random()%5;
        int boundary=1+random()%(n*12);
        auto change=[&]{
            std::vector<int> subset;for(auto& [tid,_]:Kernel::tasks)subset.push_back(tid);
            std::shuffle(subset.begin(),subset.end(),random);subset.resize(subset.size()*percent/100);
            for(int tid:subset){auto& t=Kernel::tasks.at(tid);
                if(kind!=1)t.group=random()%2?"/foreground":"/background";
                if(kind!=0)t.mask=random()%2?31:67;
            }
            if(action==0&&n>2)Kernel::tasks.erase(44);
            if(action==1){
                bool parentOwned=std::any_of(Kernel::tasks.begin(),Kernel::tasks.end(),[](const auto& item){return item.second.group=="/ZUIopt/7c";});
                Kernel::tasks[300]={f.p().ownershipFloor+1,parentOwned?"/ZUIopt/7c":"/background",parentOwned?Mask(0x7c):Mask(67)};
            }
            if(action==2)Kernel::tasks[301]={f.p().ownershipFloor+1,"/background",67};
            if(action==3&&n>2){Kernel::tasks[44]={90000,"/foreground",31};f.reused.insert(44);}
        };
        if(when==0)change();else Kernel::hook=[&](const std::string& path){
            if((when==1&&--boundary==0)||(when==2&&path=="/dev/cpuset/top-app/tasks")){Kernel::hook={};change();}
        };
        f.background(random()%2);if(random()%2)f.background();f.complete();
    }catch(const std::exception& e){throw std::runtime_error("random release case="+std::to_string(i)+" "+e.what());}
    std::cout<<"RANDOM_RELEASE_STRESS_COUNT="<<total<<";GLOBAL_FATAL_RECOVERABLE=0;STATUS3_RECOVERABLE=0;OWNER_LEAK=0;STALE_PID=0;UNKNOWN_TASK_FALSE_POSITIVE=0;UNSAFE_CPUSET_REWRITE=0;UNSAFE_AFFINITY_REWRITE=0;JOURNAL_CLEAR_WITH_LIVE_ZUIOPT_TASK=0\n";
}
int main(){try{std::cout<<std::unitbuf;timed("BACKGROUND_DETERMINISTIC",deterministic);timed("BACKGROUND_STRICT",strictBoundaries);timed("BACKGROUND_STRESS",backgroundStress);
    puts("NORMAL_BACKGROUND_RELEASE_STATUS3=0;BACKGROUND_RELEASE_FIXTURES=PASS");return 0;
}catch(const std::exception& e){std::cerr<<"BACKGROUND_FAIL "<<e.what()<<'\n';return 1;}}
