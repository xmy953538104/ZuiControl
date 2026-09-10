// Production acquisition, placement and recovery against an isolated synthetic
// proc/cpuset kernel. Link with --wrap for the POSIX boundary listed in CI.
// Journal/CRC/fsync/rename/locks and SIGKILL are real. No device is used.
#define ZUIOPT_TEST 1
#define main existing_binder_fixture_main
#include "ZUIoptBinderTest.cpp"
#undef main
#include "../../native/zuiopt/ZUIopt_owner.h"
#include <cstdarg>
#include <filesystem>
#include <fstream>
#include <functional>
#include <random>
#include <sys/wait.h>
namespace fs=std::filesystem;
namespace Kernel {
struct Thread {uint64_t birth=10;std::string group="/top-app";Mask mask=255;};
std::map<int,Thread> tasks;std::map<int,std::string> fds;
std::string root;int user=10001;int64_t time=0;int reads=0,moves=0,affinities=0;
bool stuck=false;std::function<void(const std::string&)> hook;
std::string mapped(const std::string& path){
    if(path.rfind("/dev/cpuset",0)==0||path=="/proc/42"||path.rfind("/proc/42/",0)==0)return root+path;
    return path;
}
void put(const std::string& path,const std::string& data){
    fs::create_directories(fs::path(path).parent_path());std::ofstream out(path,std::ios::binary);out<<data;require(out.good(),"synthetic file");
}
std::string statText(int tid){
    auto it=tasks.find(tid);if(it==tasks.end()||!tasks.count(42))return {};
    std::ostringstream s;s<<tid<<" (fixture) S";for(int i=1;i<19;i++)s<<" 0";s<<' '<<it->second.birth<<"\n";return s.str();
}
bool procText(const std::string& path,std::string& text){
    if(path=="/sys/devices/system/cpu/online"){text="0-7\n";return true;}
    if(path=="/proc/uptime"){text=std::to_string(100.+time/1000.)+" 0\n";return true;}
    if(path=="/proc/42/cmdline"){text="org.example.game";text.push_back(0);return true;}
    if(path=="/proc/42/status"){text="Tgid:\t42\n";return true;}
    for(auto& [tid,t]:tasks){
        auto prefix="/proc/"+std::to_string(tid);
        if(path==prefix+"/stat"){text=statText(tid);return true;}
        if(path==prefix+"/cgroup"){text="1:cpuset:"+t.group+"\n";return true;}
        if(path==prefix+"/status"){text="Tgid:\t42\n";return true;}
    }
    if(path=="/proc/42/stat"){text=statText(42);return true;}
    if(path.rfind("/proc/42/task/",0)==0&&path.size()>5&&path.substr(path.size()-5)=="/stat"){
        text=statText(std::stoi(path.substr(14)));return true;
    }
    return false;
}
void initialize(){
    char path[]="/tmp/zuiopt-acquire-XXXXXX";require(mkdtemp(path),"exclusive synthetic kernel root");root=path;
    tasks={{42,{}},{43,{}}};fds.clear();user=10001;time=0;reads=moves=affinities=0;stuck=false;hook={};
    for(auto g:{"","/top-app","/background","/foreground","/ZUIopt"})for(auto f:{"tasks","mems","cpus"})put(root+"/dev/cpuset"+g+"/"+f,f==std::string("mems")?"0":"0-7");
    fs::permissions(root+"/dev/cpuset/ZUIopt",fs::perms::owner_all|fs::perms::group_read|fs::perms::group_exec|fs::perms::others_read|fs::perms::others_exec);
    fs::create_directories(root+"/proc/42/task");
}
}
extern "C" {
int __real_open(const char*,int,...);int __real_close(int);ssize_t __real_write(int,const void*,size_t);
int __real_stat(const char*,struct stat*);int __real_lstat(const char*,struct stat*);
int __real_access(const char*,int);DIR* __real_opendir(const char*);
int __real_mkdir(const char*,mode_t);int __real_rmdir(const char*);
int __real_sched_getaffinity(pid_t,size_t,cpu_set_t*);int __real_sched_setaffinity(pid_t,size_t,const cpu_set_t*);
int __wrap_open(const char* path,int flags,...){
    using namespace Kernel;mode_t mode=0;if(flags&O_CREAT){va_list args;va_start(args,flags);mode=va_arg(args,int);va_end(args);}
    std::string original=path,target=original;
    if(!root.empty()){
        auto callback=hook;if(callback)callback(original);
        std::string data;
        if(procText(original,data)){reads++;if(data.empty()){errno=ENOENT;return -1;}target=root+"/read/"+std::to_string(checksum(original));put(target,data);}
        else target=mapped(original);
        if(original.rfind("/dev/cpuset",0)==0&&original.size()>=6&&original.substr(original.size()-6)=="/tasks"){
            auto g=original.substr(11,original.size()-17);std::string members;
            for(auto& [tid,t]:tasks)if(t.group==g)members+=std::to_string(tid)+"\n";
            put(target,members);
        }
        // Synthetic absent TID reads must never fall through to real host proc.
        if(original.rfind("/proc/43/",0)==0||original.rfind("/proc/44/",0)==0||original.rfind("/proc/45/",0)==0){
            if(target==original){errno=ENOENT;return -1;}
        }
    }
    int fd=__real_open(target.c_str(),flags,mode);if(fd>=0&&!root.empty())fds[fd]=original;return fd;
}
int __wrap_close(int fd){Kernel::fds.erase(fd);return __real_close(fd);}
ssize_t __wrap_write(int fd,const void* bytes,size_t size){
    using namespace Kernel;auto it=fds.find(fd);
    if(it!=fds.end()&&it->second.rfind("/dev/cpuset",0)==0){
        auto path=it->second;
        if(path.substr(path.size()-6)=="/tasks"){
            int tid=std::stoi(std::string(static_cast<const char*>(bytes),size));
            if(!tasks.count(tid)){errno=ESRCH;return -1;}
            moves++;if(!stuck||tid<44)tasks.at(tid).group=path.substr(11,path.size()-17);
        }
        return static_cast<ssize_t>(size);
    }
    return __real_write(fd,bytes,size);
}
int __wrap_stat(const char* path,struct stat* st){
    if(!Kernel::root.empty()&&std::string(path)=="/proc/42"){
        if(!Kernel::tasks.count(42)){errno=ENOENT;return -1;}memset(st,0,sizeof(*st));st->st_uid=Kernel::user;st->st_mode=S_IFDIR|0555;return 0;
    }return __real_stat(Kernel::root.empty()?path:Kernel::mapped(path).c_str(),st);
}
int __wrap_lstat(const char* path,struct stat* st){return __real_lstat(Kernel::root.empty()?path:Kernel::mapped(path).c_str(),st);}
int __wrap_access(const char* path,int mode){return __real_access(Kernel::root.empty()?path:Kernel::mapped(path).c_str(),mode);}
DIR* __wrap_opendir(const char* path){
    if(!Kernel::root.empty()&&std::string(path)=="/proc/42/task"){
        auto dest=Kernel::mapped(path);fs::remove_all(dest);fs::create_directories(dest);
        if(Kernel::tasks.count(42))for(auto& [tid,_]:Kernel::tasks)fs::create_directories(dest+"/"+std::to_string(tid));
    }return __real_opendir(Kernel::root.empty()?path:Kernel::mapped(path).c_str());
}
int __wrap_mkdir(const char* path,mode_t mode){
    auto dest=Kernel::root.empty()?std::string(path):Kernel::mapped(path);int rc=__real_mkdir(dest.c_str(),mode);
    if(rc==0&&std::string(path).rfind("/dev/cpuset/ZUIopt/",0)==0)for(auto f:{"tasks","cpus","mems"})Kernel::put(dest+"/"+f,"");return rc;
}
int __wrap_rmdir(const char* path){
    auto dest=Kernel::root.empty()?std::string(path):Kernel::mapped(path);
    if(std::string(path).rfind("/dev/cpuset/ZUIopt/",0)==0)for(auto f:{"tasks","cpus","mems"})fs::remove(dest+"/"+f);
    return __real_rmdir(dest.c_str());
}
int __wrap_sched_getaffinity(pid_t tid,size_t size,cpu_set_t* set){
    if(Kernel::root.empty())return __real_sched_getaffinity(tid,size,set);
    auto callback=Kernel::hook;if(callback)callback("affinity/"+std::to_string(tid));
    auto it=Kernel::tasks.find(tid);if(it==Kernel::tasks.end()){errno=ESRCH;return -1;}
    CPU_ZERO(set);for(int i=0;i<64;i++)if(it->second.mask&(Mask(1)<<i))CPU_SET(i,set);return 0;
}
int __wrap_sched_setaffinity(pid_t tid,size_t size,const cpu_set_t* set){
    if(Kernel::root.empty())return __real_sched_setaffinity(tid,size,set);
    auto it=Kernel::tasks.find(tid);if(it==Kernel::tasks.end()){errno=ESRCH;return -1;}
    Mask mask=0;for(int i=0;i<64;i++)if(CPU_ISSET(i,set))mask|=Mask(1)<<i;it->second.mask=mask;Kernel::affinities++;return 0;
}
}

struct AcquisitionFixture:RuntimeFixture {
    struct TimedProc:BaselineProc {int64_t time(){return Kernel::time;}};
    Counters count;std::unique_ptr<Journal> journal;std::unique_ptr<Placement> owner;
    RuntimeBlocker receipt;int blockerAttempts=0;uint64_t initialCommits=0;SceneBurst scene;
    AcquisitionFixture(){
        Kernel::initialize();journal=std::make_unique<Journal>(Kernel::root+"/state");
        owner=std::make_unique<Placement>(count,*journal);initialCommits=journal->commits;
    }
    ~AcquisitionFixture(){owner.reset();journal.reset();Kernel::hook={};auto dir=Kernel::root;Kernel::root.clear();fs::remove_all(dir);}
    void activate(ProcessState& p){if(!p.activity_foreground){release(p);return;}beginAcquisition(p,Kernel::time);}
    void release(ProcessState& p){owner->release(p);}
    BaselineResult acquire(ProcessState& p){TimedProc proc;return owner->acquire(p,proc);}
    void baselineBlocked(){blockerAttempts++;receipt.record(Kernel::root+"/state",journal->currentBootId(),RuntimeBlockerReason::INHERITANCE_BASELINE_UNSTABLE);}
    ProcessState* resolve(const Snapshot& s){
        auto id=validateManagedSnapshot(s,config,*this);if(!id.start)return nullptr;
        auto it=states.find(s.pid);if(it!=states.end()&&(it->second.generation!=id.start||it->second.uid!=s.uid)){release(it->second);states.erase(it);}
        auto& p=states[s.pid];p.pid=s.pid;p.uid=s.uid;p.generation=id.start;p.name=s.name;p.package=s.packages[0];p.alive=true;return &p;
    }
    void edges(){}
    void primary(){processEvent({1,42,10001,1,1},states,*this);}
    void tick(int64_t time){
        Kernel::time=time;
        if(scene.timeout(time)==0){reconcileSnapshot(activitySnapshot(),states,*this);scene.complete(time);}
        for(auto& [_,p]:states)advanceAcquisition(p,time,*this);
    }
    void start(int mode=0){if(mode!=1)primary();if(mode!=2)scene.accept(1,0);tick(0);}
    ProcessState& p(){return states.at(42);}
    void noOwnership(){
        require(journal->leases.empty()&&journal->entries.empty()&&journal->commits==initialCommits,"pending journal write");
        require(Kernel::moves==0&&Kernel::affinities==0,"pending kernel write");
        for(auto& [_,p]:states){require(!p.managed&&!p.ownershipFloor&&p.androidGroup.empty()&&!p.androidMask,"pending ownership state");for(auto& [__,t]:p.tasks)require(!t.owned,"pending owned flag");}
        for(auto& [_,t]:Kernel::tasks)require(t.group.find("/ZUIopt")==std::string::npos,"pending cpuset membership");
    }
    void confirm(){auto base=p().acquireStarted;for(int dt:{100,250,500,750})if(!p().managed)tick(base+dt);require(p().managed,"fixture temporal confirmation");}
    void place(){if(!p().managed)confirm();owner->prepare(p());for(auto& [tid,t]:p().tasks)owner->apply(p(),tid,t,cpus("2-6"),"default");}
    void background(){app.state=19;app.flags=0;primary();}
    void empty(){require(journal->entries.empty()&&journal->leases.empty()&&!p().managed&&!p().acquiring,"release owner leak");}
};
template<class F> void expectFailure(F action,const std::string& message){
    std::string observed="NO_EXCEPTION";try{action();}catch(const std::exception& e){observed=e.what();}
    if(observed.find(message)==observed.npos)throw std::runtime_error("strict guard expected="+message+" observed="+observed);
}
void matrix(){
    // A/B/C/L/M/N: both sources or either alone, baseline refreshed on local deadlines.
    for(int source=0;source<3;source++)for(int visible:{0,100,250,500,750}){
        AcquisitionFixture f;Kernel::tasks.at(42).group="/background";Kernel::tasks.at(42).mask=67;
        Kernel::tasks.at(43).mask=255;Kernel::tasks.at(43).group="/top-app";
        if(!visible)for(auto& [_,task]:Kernel::tasks){task.group="/top-app";task.mask=255;}
        f.start(source);
        f.noOwnership();
        auto queries=f.snapshotQueries;
        for(int t:{100,250,500,750}){
            if(t>=visible)for(auto& [_,task]:Kernel::tasks){task.group="/top-app";task.mask=255;}
            f.tick(t);if(t<visible+250)f.noOwnership();
        }
        if(source==2)require(f.snapshotQueries==queries,"ProcessObserver-only retries queried activity");
        if(visible==750){require(f.p().acquireBlocked&&f.blockerAttempts==1,"late unconfirmed baseline not blocked");}
        else{require(f.p().managed&&f.journal->commits==f.initialCommits+1&&f.journal->leases.size()==1,"temporal acquire miss/duplicate lease");f.place();}
        f.background();f.empty();
    }
    // D/E: timeout is local to this request; duplicate foreground cannot restart it.
    for(bool custom:{false,true}){
        AcquisitionFixture f;Kernel::tasks.at(43).mask=custom?128:67;if(!custom)Kernel::tasks.at(43).group="/background";
        f.start(2);for(int t:{100,250,500,750}){f.tick(t);f.noOwnership();}
        require(f.blockerAttempts==1&&f.p().acquireBlocked&&!f.p().acquiring,"persistent mismatch not blocked");
        auto path=Kernel::root+"/state/runtime_blocker.v1";auto data=read(path);struct stat st{};require(lstat(path.c_str(),&st)==0,"blocker exists");auto inode=st.st_ino;
        require(data.find("reason=inheritance_baseline_unstable\n")!=data.npos&&data.find("org.example")==data.npos&&(st.st_mode&07777)==0600&&data.size()<1024,"blocker privacy/bound");
        for(int t=1000;t<3000;t++){f.primary();f.tick(t);}
        require(f.blockerAttempts==1&&lstat(path.c_str(),&st)==0&&st.st_ino==inode,"persistent steady blocker writes");f.noOwnership();
        f.background();for(auto& [_,task]:Kernel::tasks){task.group="/top-app";task.mask=255;}
        f.app.state=2;f.app.flags=4;f.primary();f.tick(3000);f.confirm();require(f.p().managed,"fresh authority did not retry");f.background();
    }
    // F: disappearing/reused thread is not a baseline mismatch.
    for(bool reuse:{false,true}){
        AcquisitionFixture f;Kernel::tasks.at(43).mask=67;
        Kernel::hook=[&](const std::string& path){if(path=="affinity/43"){Kernel::hook={};if(reuse)Kernel::tasks.at(43).birth++;else Kernel::tasks.erase(43);}};
        f.start(2);if(reuse){f.noOwnership();Kernel::tasks.at(43).mask=255;f.tick(100);}
        f.confirm();require(f.p().managed,"exit/reuse observation recovery");f.background();
    }
    // G: an original child born in the observation pass forces a new full pass.
    {AcquisitionFixture f;Kernel::hook=[](const std::string& path){if(path=="affinity/43"){Kernel::hook={};Kernel::tasks[44]={10000,"/top-app",255};}};
        f.start(2);f.noOwnership();f.tick(100);f.noOwnership();f.confirm();require(f.p().managed&&f.journal->entries.count(44)&&f.p().ownershipFloor>10000,"pending-born child baseline/floor");
        f.place();OwnerRecord child;f.journal->entries.erase(44);require(!f.journal->inherited(44,child),"precommit child retroactively inherited");
        Kernel::tasks.erase(44);f.p().tasks.erase(44);f.background();}
    // H/I/J/K/O: pending death, PID reuse, background, scene supersession, stop.
    for(int mode=0;mode<5;mode++){
        AcquisitionFixture f;Kernel::tasks.at(43).mask=67;f.start(2);f.noOwnership();
        if(mode==0){Kernel::tasks.clear();f.tick(100);}
        if(mode==1){Kernel::tasks.at(42).birth=20;f.tick(100);}
        if(mode==2)f.background();
        if(mode==3){f.app.flags=0;f.app.state=19;f.scene.accept(12,10);f.tick(10);}
        if(mode==4){for(auto& [_,p]:f.states)f.release(p);f.owner->cleanup();}
        f.noOwnership();require(!f.p().acquiring&&!f.p().next,"pending deadline survived cancellation");
        if(mode==1){f.generation=20;Kernel::tasks.at(43).mask=255;f.primary();f.tick(100);f.confirm();require(f.p().managed&&f.journal->leases.at(42).processStart==20,"stale PID acquired");f.background();}
    }
    puts("BASELINE_CASES_A_TO_P=PASS;PENDING_RELEASE_NOOP=PASS;ZERO_WRITE_PROBE=PASS");
}
void recovery(){
    // P + V2/CRC/late-spawn/scaffold/unknown/busy: real production release/recovery.
    {AcquisitionFixture f;f.start(2);f.place();auto floor=f.p().ownershipFloor;
        Kernel::tasks[44]={floor+1,"/ZUIopt/7c",124};OwnerRecord record;
        require(f.journal->inherited(44,record)&&record.savedMask==255,"postcommit late child coverage");
        auto saved=f.journal->entries;f.journal->entries.clear();f.journal->leases.clear();f.journal->load();require(f.journal->entries.size()==saved.size(),"V2 durable reload");
        f.background();f.empty();for(auto& [_,t]:Kernel::tasks)require(t.group=="/top-app"&&t.mask==255,"strict original restore changed");}
    {AcquisitionFixture f;f.start(2);f.place();Kernel::tasks[44]={f.p().ownershipFloor+1,"/ZUIopt/7c",124};Kernel::stuck=true;
        expectFailure([&]{f.background();},"release busy process");Kernel::stuck=false;f.background();f.empty();}
    {AcquisitionFixture f;f.start(2);f.place();auto r=f.journal->entries.at(42);f.journal->entries.erase(42);expectFailure([&]{f.owner->prepare(f.p());},"durable owner identity");f.journal->entries[42]=r;
        auto lease=f.journal->leases.at(42);f.journal->leases.at(42).processStart++;expectFailure([&]{f.owner->prepare(f.p());},"lease identity mismatch");f.journal->leases[42]=lease;f.background();}
    {AcquisitionFixture f;Kernel::tasks.at(43).group="/ZUIopt/7c";expectFailure([&]{f.start(2);},"initial Android owner unavailable");require(!Kernel::moves&&!Kernel::affinities&&f.journal->leases.empty(),"unknown acquisition wrote ownership");}
    {AcquisitionFixture f;auto& p=f.states[42];p.pid=42;p.tasks[43].owned=true;expectFailure([&]{f.release(p);},"uncommitted owned task");}
    {AcquisitionFixture f;f.start(2);f.place();auto floor=f.p().ownershipFloor;f.owner.reset();f.journal.reset();
        Kernel::tasks[44]={floor+1,"/ZUIopt/7c",124};
        pid_t child=fork();require(child>=0,"crash fixture fork");
        if(child==0){Journal locked(Kernel::root+"/state");kill(getpid(),SIGKILL);_exit(1);}
        int status=0;require(waitpid(child,&status,0)==child&&WIFSIGNALED(status)&&WTERMSIG(status)==SIGKILL,"real SIGKILL fixture");
        f.journal=std::make_unique<Journal>(Kernel::root+"/state");f.owner=std::make_unique<Placement>(f.count,*f.journal);
        require(f.journal->entries.empty()&&f.journal->leases.empty(),"SIGKILL recovery journal leak");
        for(auto& [_,t]:Kernel::tasks)require(t.group=="/top-app"&&t.mask==255,"SIGKILL late-spawn strict restore");
        require(fs::is_directory(Kernel::root+"/dev/cpuset/ZUIopt"),"KEEP_ROOT scaffold");}
    {AcquisitionFixture f;Kernel::tasks.at(43).group="/ZUIopt";f.owner.reset();f.journal.reset();
        f.journal=std::make_unique<Journal>(Kernel::root+"/state");expectFailure([&]{Placement unknown(f.count,*f.journal);},"RECOVERY_UNKNOWN_TASK_FAIL_CLOSED");require(!Kernel::moves&&!Kernel::affinities,"unknown recovery wrote kernel");}
    puts("BASELINE_JOURNAL_SIGKILL_LATE_SPAWN_STRICT_RELEASE=PASS;UNKNOWN_LIVE_AND_BUSY_FAIL_CLOSED=PASS");
}
void stress(){
    std::mt19937 random(0x55ba5e);constexpr int windows[]={0,20,50,80,120,200,400,700};int deferrals=0;
    AcquisitionFixture f;std::set<unsigned> subsets;std::set<int> observedWindows;
    for(int tid=44;tid<=50;tid++)Kernel::tasks[tid]={10,"/top-app",255};
    for(int i=0;i<512;i++){
        int base=i*2000,visible=windows[random()%8];Kernel::time=base;f.app.state=2;f.app.flags=4;
        unsigned subset=1+random()%255;observedWindows.insert(visible);
        for(auto& [_,t]:Kernel::tasks){t.group="/top-app";t.mask=255;}
        if(visible){for(int tid=43;tid<=50;tid++)if(subset&(1u<<(tid-43))){Kernel::tasks.at(tid).group="/background";Kernel::tasks.at(tid).mask=67;}subsets.insert(subset);deferrals++;}
        f.initialCommits=f.journal->commits;Kernel::moves=Kernel::affinities=0;
        if(i%3!=1)f.primary();if(i%3!=2)f.scene.accept(i+10,base);
        for(int dt:{0,100,250,500,750}){
            if(dt>=visible)for(auto& [_,t]:Kernel::tasks){t.group="/top-app";t.mask=255;}
            f.tick(base+dt);if(dt<visible)f.noOwnership();
        }
        if(visible==700){f.noOwnership();require(f.p().acquireBlocked,"unconfirmed late baseline escaped deadline");}
        else{
            require(f.p().managed&&f.journal->commits==f.initialCommits+1,"stress managed miss/duplicate lease");
            require(f.p().ownershipFloor>=static_cast<uint64_t>((100.+(base+visible+250)/1000.)*sysconf(_SC_CLK_TCK)),"lease predates temporal baseline");
            f.place();
        }
        f.background();f.empty();
    }
    auto proc=Kernel::reads,queries=f.snapshotQueries;auto commits=f.journal->commits;for(int t=2000000;t<2001000;t++)f.tick(t);
    require(Kernel::reads==proc&&f.snapshotQueries==queries&&f.journal->commits==commits&&!f.p().next&&f.scene.timeout(2001000)==-1,"idle baseline polling/timer");
    require(deferrals>400&&subsets.size()>150&&observedWindows.size()==8&&f.blockerAttempts>0,"stress coverage/late blocker");
    std::cout<<"RANDOM_HETEROGENEOUS_SUBSETS="<<subsets.size()<<";STRESS_THREAD_COUNT=9;WINDOWS_COVERED="<<observedWindows.size()<<"\n";
    std::cout<<"TRANSIENT_STRESS_COUNT=512;DEFERRED="<<deferrals<<";TRANSIENT_STRESS_FATALS=0;UNSAFE_WRITES=0;OWNER_LEAK=0;STALE_PID=0;MANAGED_MISS=0;IDLE_ACQUISITION_TIMER=0\n";
}
struct CoherenceFixture:AcquisitionFixture {
    int drift=0,repairs=0,reacquires=0,contested=0;
    Mask desired(int tid){return tid==43?28:tid==44?128:124;}
    void uniform(const std::string& group,Mask mask){for(auto& [_,t]:Kernel::tasks){t.group=group;t.mask=mask;}}
    void scanAt(int64_t time){
        tick(time);
        if(!p().managed||p().next>time)return;
        auto result=owner->verifyCoherence(p(),time);
        if(result!=CoherenceResult::CLEAN)drift++;
        if(result==CoherenceResult::REPAIR)repairs++;
        if(result==CoherenceResult::REACQUIRE)reacquires++;
        if(result==CoherenceResult::CONTESTED){contested++;receipt.record(Kernel::root+"/state",journal->currentBootId(),RuntimeBlockerReason::OWNERSHIP_CONTESTED);}
        if(!p().managed){if(p().acquiring)advanceAcquisition(p(),time,*this);return;}
        owner->prepare(p());
        for(auto& [tid,t]:p().tasks)owner->apply(p(),tid,t,desired(tid),"fixture_G07");
        finishScan(p(),time);
    }
    void managed(){
        require(p().managed,"coherence managed miss");
        for(auto& [tid,t]:Kernel::tasks){std::ostringstream expected;expected<<"/ZUIopt/"<<std::hex<<desired(tid);
            require(t.group==expected.str()&&t.mask==desired(tid),"silent journal/physical divergence");
            require(journal->entries.count(tid)&&journal->same(journal->entries.at(tid)),"unknown owned task");
        }
    }
    void settle(){auto base=p().acquireStarted;for(int dt:{0,100,250,500,750})if(!p().managed)scanAt(base+dt);managed();}
    void boot(int threads=8){for(int tid=44;tid<42+threads;tid++)Kernel::tasks[tid]={10,"/top-app",255};start(2);settle();}
    void overwrite(int percent,int type){
        size_t n=percent?std::max<size_t>(1,Kernel::tasks.size()*percent/100):1;
        for(auto& [_,t]:Kernel::tasks){if(!n--)break;if(type!=1)t.group="/top-app";if(type!=0)t.mask=255;}
    }
};
void coherence(){
    // Exact real sleep06: a uniform intermediate mask must never reach journal L.
    {CoherenceFixture f;f.boot(200);f.managed();require(f.journal->leases.at(42).savedGroup=="/top-app","pre-sleep baseline");
        f.background();f.empty();f.uniform("/background",67);require(!fs::exists(Kernel::root+"/state/owner_state.v1"),"sleep journal exists");
        Kernel::time=3000;f.app.state=2;f.app.flags=4;f.uniform("/foreground",31);f.primary();
        f.initialCommits=f.journal->commits;Kernel::moves=Kernel::affinities=0;
        f.scanAt(3000);f.noOwnership();f.scanAt(3100);f.noOwnership();
        require(f.p().baselineCandidate.group=="/foreground"&&f.p().baselineCandidate.firstStableAt==3000,"first candidate absent");
        f.uniform("/top-app",255);f.scanAt(3250);f.noOwnership();
        require(f.p().baselineCandidate.firstStableAt==3250,"candidate not reset");
        f.scanAt(3500);f.managed();auto& lease=f.journal->leases.at(42);
        require(lease.savedGroup=="/top-app"&&lease.savedMask==255&&f.p().ownershipFloor==13500,"intermediate commit/floor");
        f.background();f.empty();}
    // Candidate generation/UID/epoch resets and unchanged uniform thread growth/shrink.
    {CoherenceFixture f;f.start(2);f.noOwnership();Kernel::tasks[44]={10,"/top-app",255};f.tick(100);
        require(f.p().baselineCandidate.firstStableAt==0,"uniform growth reset dwell");Kernel::tasks.erase(43);f.tick(250);
        require(f.p().managed&&f.journal->entries.count(44)&&!f.journal->entries.count(43),"fresh final task set");f.background();}
    {CoherenceFixture f;f.start(2);Kernel::user++;f.tick(100);f.noOwnership();require(!f.p().acquiring,"UID change not cancelled");}
    // Timing variants + edge just after 1000ms cover the worst old 1s gap.
    int worst=0;
    for(int when:{20,50,80,120,200,400,700,1000,1001,1500})for(int type=0;type<3;type++)for(int percent:{0,25,50,75,100}){
        CoherenceFixture f;f.boot(200);auto committed=Kernel::time;bool injected=false;
        for(int dt:{100,250,500,1000,1500,2000}){
            if(!injected&&dt>=when){f.overwrite(percent,type);injected=true;}
            f.scanAt(committed+dt);
            if(injected){require(f.drift==1,"cache hid overwrite");if(f.p().acquiring)f.settle();f.managed();
                worst=std::max(worst,static_cast<int>(Kernel::time-committed-when));break;}
        }
        require(f.p().coherenceEpisodes==1&&f.repairs+f.reacquires==1,"repair counted per task");
        f.background();f.empty();
    }
    require(worst<=750,"coherence response budget");
    // Three process-level episodes cannot turn into an unlimited Android tug-of-war.
    {CoherenceFixture f;f.boot(200);
        for(int episode=0;episode<3;episode++){
            f.overwrite(100,2);f.scanAt(f.p().next);
            if(episode<2){require(f.p().acquiring,"broad handoff not reacquiring");f.settle();}
        }
        require(!f.p().managed&&!f.p().acquiring&&f.p().acquireBlocked&&f.contested==1&&f.p().coherenceEpisodes==2,"unbounded contention");
        require(f.journal->entries.empty()&&f.journal->leases.empty(),"contested journal leak");
        for(auto& [_,t]:Kernel::tasks)require(t.group=="/top-app"&&t.mask==255,"contested residual owner");
        auto path=Kernel::root+"/state/runtime_blocker.v1";struct stat st{};require(lstat(path.c_str(),&st)==0,"contested receipt absent");auto inode=st.st_ino;
        require((st.st_mode&07777)==0600&&read(path).size()<1024&&read(path).find("reason=ownership_contested")!=std::string::npos,"contested receipt contract");
        auto writes=Kernel::moves+Kernel::affinities;auto commits=f.journal->commits;
        for(int t=10000;t<11000;t++){f.primary();f.scanAt(t);f.receipt.record(Kernel::root+"/state",f.journal->currentBootId(),RuntimeBlockerReason::OWNERSHIP_CONTESTED);}
        require(lstat(path.c_str(),&st)==0&&st.st_ino==inode&&commits==f.journal->commits&&writes==Kernel::moves+Kernel::affinities&&!f.p().next,"contested steady writes/timer");f.background();}
    // No guessing: changed external group + our narrow mask is unresolved residue.
    {CoherenceFixture f;f.boot();for(auto& [_,t]:Kernel::tasks)t.group="/foreground";
        auto entries=f.journal->entries.size();auto commits=f.journal->commits;auto moves=Kernel::moves;
        expectFailure([&]{f.scanAt(f.p().next);},"coherence unresolved external affinity");
        require(f.journal->entries.size()==entries&&f.journal->commits==commits&&Kernel::moves==moves,"unsafe external preflight clear/write");
        f.uniform("/top-app",255);f.owner->relinquishCoherence(f.p());f.empty();}
    // Late inherited, death and reuse safety during a coherence release.
    {CoherenceFixture f;f.boot();Kernel::tasks[60]={f.p().ownershipFloor+1,"/ZUIopt/7c",124};f.overwrite(75,2);
        f.scanAt(f.p().next);require(f.p().acquiring&&Kernel::tasks.at(60).group=="/top-app"&&Kernel::tasks.at(60).mask==255,"late owned leak");f.settle();f.background();}
    {CoherenceFixture f;f.boot();Kernel::tasks.clear();f.owner->relinquishCoherence(f.p());f.empty();}
    {CoherenceFixture f;f.boot();f.uniform("/top-app",255);Kernel::tasks.at(42).birth++;
        auto writes=Kernel::moves+Kernel::affinities;f.owner->relinquishCoherence(f.p());f.empty();require(Kernel::moves+Kernel::affinities==writes,"stale PID write");}
    // End probation: cache remains cheap. Only an explicit event re-arms verification.
    {CoherenceFixture f;f.boot();auto base=Kernel::time;for(int dt:{100,250,500,1000,1500,2000})f.scanAt(base+dt);
        require(!forceCoherence(f.p()),"probation never ended");auto reads=Kernel::reads;
        f.scanAt(base+3000);require(Kernel::reads==reads,"steady physical scan");
        f.overwrite(100,2);armCoherence(f.p(),base+3100);f.scanAt(base+3100);require(f.drift==1,"authority invalidation absent");f.settle();f.background();
        reads=Kernel::reads;auto commits=f.journal->commits;for(int t=100000;t<101000;t++)f.scanAt(t);
        require(Kernel::reads==reads&&f.journal->commits==commits&&!f.p().next,"idle coherence work");}
    std::mt19937 random(0x56c0ae);int recovered=0;std::set<int> types,subsets,windows;
    {CoherenceFixture f;for(int tid=44;tid<58;tid++)Kernel::tasks[tid]={10,"/top-app",255};
        for(int i=0;i<512;i++){
            int base=i*10000;Kernel::time=base;f.app.state=2;f.app.flags=4;
            // Uniform intermediate states below dwell, and later heterogeneous
            // Android handoffs (which must never count as candidate dwell).
            int duration=static_cast<int>(random()%201);windows.insert(duration);
            f.uniform("/foreground",31);f.primary();f.initialCommits=f.journal->commits;Kernel::moves=Kernel::affinities=0;
            for(int dt:{0,100,250,500,750}){
                if(dt>=duration)f.uniform("/top-app",255);
                f.scanAt(base+dt);
                if(f.p().managed)break;
                f.noOwnership();
            }
            f.managed();require(f.journal->leases.at(42).savedGroup=="/top-app"&&f.journal->leases.at(42).savedMask==255,"random intermediate commit");
            int percents[]={0,25,50,75,100};int percent=percents[random()%5],type=random()%3;
            types.insert(type);subsets.insert(percent);auto committed=Kernel::time;
            int when=1+random()%1999;bool injected=false;auto before=f.drift;
            for(int dt:{100,250,500,1000,1500,2000}){
                if(!injected&&dt>=when){f.overwrite(percent,type);injected=true;}
                f.scanAt(committed+dt);
                if(injected){require(f.drift==before+1,"random silent divergence");if(f.p().acquiring)f.settle();f.managed();
                    require(Kernel::time-committed-when<=750,"random response budget");break;}
            }
            recovered++;f.background();f.empty();
            for(auto& [_,t]:Kernel::tasks)require(t.group=="/top-app"&&t.mask==255,"random owner leak");
        }
    }
    require(recovered==512&&types.size()==3&&subsets.size()==5&&windows.size()>150,"random coherence coverage");
    std::cout<<"RANDOM_COHERENCE_COUNT="<<recovered<<";GLOBAL_FATAL_RECOVERABLE=0;UNSAFE_JOURNAL_CLEAR=0;UNKNOWN_ZUIOPT_TASK=0;STALE_PID=0;OWNER_LEAK=0;SILENT_JOURNAL_PHYSICAL_DIVERGENCE=0\n";
    std::cout<<"SLEEP06_EXACT=PASS;OVERWRITE_CASES=150;PARTIAL_100_100=PASS;CPUSET_ONLY=PASS;AFFINITY_ONLY=PASS;COHERENCE_RESPONSE_MAX_MS="<<worst<<";CONTESTED_BUDGET=2;CACHE_IDLE=PASS\n";
}
int main(){try{require(getuid()==0,"isolated root fixture");matrix();recovery();stress();coherence();puts("ZUIOPT_BASELINE_NATIVE=PASS");return 0;}
catch(const std::exception& e){std::cerr<<"BASELINE_FAIL "<<e.what()<<'\n';return 1;}}
