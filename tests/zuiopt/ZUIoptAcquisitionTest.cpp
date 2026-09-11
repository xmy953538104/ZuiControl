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
#include <chrono>
#include <sys/resource.h>
#include <sys/vfs.h>
#include <sys/wait.h>
namespace fs=std::filesystem;
namespace Kernel {
struct Thread {uint64_t birth=10;std::string group="/top-app";Mask mask=255;};
std::map<int,Thread> tasks;std::map<int,std::string> fds;
std::map<std::string,std::string> procFiles;std::vector<int> listedTasks;
uint64_t procMaterializations=0,taskMaterializations=0;
std::string root,virtualRoot;int user=10001;int64_t time=0;int reads=0,moves=0,affinities=0;
int groupReads=0,identityReads=0,uidReads=0,affinityReads=0;
bool stuck=false;int moveError=0;std::function<void(const std::string&)> hook;
std::function<void(int,bool,Mask)> beforeWrite;
std::function<void(int,bool,Mask)> afterWrite;
std::string mapped(const std::string& path){
    if(path.rfind("/dev/cpuset",0)==0||path=="/proc/42"||path.rfind("/proc/42/",0)==0)return virtualRoot+path;
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
    if(path.rfind("/proc/",0)==0){
        auto slash=path.find('/',6);auto number=path.substr(6,slash-6);
        if(slash!=std::string::npos&&!number.empty()&&number.find_first_not_of("0123456789")==std::string::npos){
            auto it=tasks.find(std::stoi(number));auto suffix=path.substr(slash);
            if(it!=tasks.end()){
                if(suffix=="/stat"){text=statText(it->first);return true;}
                if(suffix=="/cgroup"){text="1:cpuset:"+it->second.group+"\n";return true;}
                if(suffix=="/status"){text="Tgid:\t42\n";return true;}
            }
        }
    }
    if(path=="/proc/42/stat"){text=statText(42);return true;}
    if(path.rfind("/proc/42/task/",0)==0&&path.size()>5&&path.substr(path.size()-5)=="/stat"){
        text=statText(std::stoi(path.substr(14)));return true;
    }
    return false;
}
void initialize(){
    char path[]="/tmp/zuiopt-acquire-XXXXXX";require(mkdtemp(path),"exclusive synthetic kernel root");root=path;
    // Only emulated proc/cpuset files live in RAM, like the real kernel files.
    // Journal/CRC/fsync/rename/locks/SIGKILL still use the disk-backed /tmp root.
    char ram[]="/dev/shm/zuiopt-kernel-XXXXXX";require(mkdtemp(ram),"exclusive virtual kernel root");virtualRoot=ram;
    struct statfs storage{};require(statfs(virtualRoot.c_str(),&storage)==0&&storage.f_type==0x01021994,"virtual kernel must be tmpfs");
    require(statfs(root.c_str(),&storage)==0&&storage.f_type!=0x01021994,"journal must remain disk-backed");
    tasks={{42,{}},{43,{}}};fds.clear();procFiles.clear();listedTasks.clear();user=10001;time=0;reads=moves=affinities=0;stuck=false;moveError=0;hook={};beforeWrite={};afterWrite={};
    for(auto g:{"","/top-app","/background","/foreground","/ZUIopt"})for(auto f:{"tasks","mems","cpus"})put(virtualRoot+"/dev/cpuset"+g+"/"+f,f==std::string("mems")?"0":"0-7");
    fs::permissions(virtualRoot+"/dev/cpuset/ZUIopt",fs::perms::owner_all|fs::perms::group_read|fs::perms::group_exec|fs::perms::others_read|fs::perms::others_exec);
    fs::create_directories(virtualRoot+"/proc/42/task");
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
        if(procText(original,data)){reads++;
            if(original.size()>=7&&original.substr(original.size()-7)=="/cgroup")groupReads++;
            if(original.size()>=5&&original.substr(original.size()-5)=="/stat")identityReads++;
            if(data.empty()){errno=ENOENT;return -1;}target=virtualRoot+"/read/"+std::to_string(checksum(original));
            // Generate fresh text on every read, but materialize only changed bytes.
            auto& previous=procFiles[original];if(previous!=data){put(target,data);previous=data;procMaterializations++;}}
        else target=mapped(original);
        if(original.rfind("/dev/cpuset",0)==0&&original.size()>=6&&original.substr(original.size()-6)=="/tasks"){
            auto g=original.substr(11,original.size()-17);std::string members;
            for(auto& [tid,t]:tasks)if(t.group==g)members+=std::to_string(tid)+"\n";
            put(target,members);
        }
        // Synthetic absent TID reads must never fall through to real host proc.
        if(original.rfind("/proc/",0)==0&&original.size()>6&&original[6]>='0'&&original[6]<='9'){
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
            if(beforeWrite)beforeWrite(tid,true,0);
            if(moveError){errno=moveError;return -1;}
            moves++;if(!stuck||tid<44)tasks.at(tid).group=path.substr(11,path.size()-17);
            if(afterWrite)afterWrite(tid,true,0);
            return static_cast<ssize_t>(size);
        }
        // cpus/mems are kernel files too; new release fixtures read the mask
        // written by Placement::target instead of an uninitialized empty file.
        return __real_write(fd,bytes,size);
    }
    return __real_write(fd,bytes,size);
}
int __wrap_stat(const char* path,struct stat* st){
    if(!Kernel::root.empty()&&std::string(path)=="/proc/42"){
        Kernel::uidReads++;
        if(!Kernel::tasks.count(42)){errno=ENOENT;return -1;}memset(st,0,sizeof(*st));st->st_uid=Kernel::user;st->st_mode=S_IFDIR|0555;return 0;
    }return __real_stat(Kernel::root.empty()?path:Kernel::mapped(path).c_str(),st);
}
int __wrap_lstat(const char* path,struct stat* st){return __real_lstat(Kernel::root.empty()?path:Kernel::mapped(path).c_str(),st);}
int __wrap_access(const char* path,int mode){return __real_access(Kernel::root.empty()?path:Kernel::mapped(path).c_str(),mode);}
DIR* __wrap_opendir(const char* path){
    if(!Kernel::root.empty()&&std::string(path)=="/proc/42/task"){
        std::vector<int> current;if(Kernel::tasks.count(42))for(auto& [tid,_]:Kernel::tasks)current.push_back(tid);
        if(current!=Kernel::listedTasks){
            auto dest=Kernel::mapped(path);fs::remove_all(dest);fs::create_directories(dest);
            for(int tid:current)fs::create_directories(dest+"/"+std::to_string(tid));
            Kernel::listedTasks=std::move(current);
            Kernel::taskMaterializations++;
        }
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
    Kernel::affinityReads++;
    auto callback=Kernel::hook;if(callback)callback("affinity/"+std::to_string(tid));
    auto it=Kernel::tasks.find(tid);if(it==Kernel::tasks.end()){errno=ESRCH;return -1;}
    CPU_ZERO(set);for(int i=0;i<64;i++)if(it->second.mask&(Mask(1)<<i))CPU_SET(i,set);return 0;
}
int __wrap_sched_setaffinity(pid_t tid,size_t size,const cpu_set_t* set){
    if(Kernel::root.empty())return __real_sched_setaffinity(tid,size,set);
    auto it=Kernel::tasks.find(tid);if(it==Kernel::tasks.end()){errno=ESRCH;return -1;}
    Mask mask=0;for(int i=0;i<64;i++)if(CPU_ISSET(i,set))mask|=Mask(1)<<i;
    if(Kernel::beforeWrite)Kernel::beforeWrite(tid,false,mask);
    it->second.mask=mask;Kernel::affinities++;
    if(Kernel::afterWrite)Kernel::afterWrite(tid,false,mask);
    return 0;
}
}

struct AcquisitionFixture:RuntimeFixture {
    struct TimedProc:BaselineProc {int64_t time(){return Kernel::time;}};
    Counters count;std::unique_ptr<Journal> journal;std::unique_ptr<Placement> owner;
    RuntimeBlocker receipt;int blockerAttempts=0,probes=0;uint64_t initialCommits=0;SceneBurst scene;
    AcquisitionFixture(){
        Kernel::initialize();journal=std::make_unique<Journal>(Kernel::root+"/state");
        owner=std::make_unique<Placement>(count,*journal);initialCommits=journal->commits;
    }
    ~AcquisitionFixture(){owner.reset();journal.reset();Kernel::hook={};auto dir=Kernel::root;Kernel::root.clear();fs::remove_all(dir);fs::remove_all(Kernel::virtualRoot);Kernel::virtualRoot.clear();}
    void activate(ProcessState& p){if(!p.activity_foreground){release(p);return;}beginAcquisition(p,Kernel::time);}
    void release(ProcessState& p){owner->release(p);}
    BaselineResult acquire(ProcessState& p){probes++;TimedProc proc;return owner->acquire(p,proc);}
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
        for(auto& [_,p]:states){require(!p.managed()&&!p.ownershipFloor&&p.androidGroup.empty()&&!p.androidMask,"pending ownership state");for(auto& [__,t]:p.tasks)require(!t.owned,"pending owned flag");}
        for(auto& [_,t]:Kernel::tasks)require(t.group.find("/ZUIopt")==std::string::npos,"pending cpuset membership");
    }
    void confirm(){auto base=p().acquireStarted;for(int dt:{100,250,500,750})if(!p().managed())tick(base+dt);require(p().managed(),"fixture temporal confirmation");}
    void place(){if(!p().managed())confirm();owner->prepare(p());for(auto& [tid,t]:p().tasks)owner->apply(p(),tid,t,cpus("2-6"),"default");}
    void background(){app.state=19;app.flags=0;primary();}
    void empty(){require(journal->entries.empty()&&journal->leases.empty()&&!p().managed()&&!p().acquiring(),"release owner leak");}
};
void kernelBacking(){
    AcquisitionFixture f;
    require(read("/proc/43/cgroup")=="1:cpuset:/top-app\n","initial virtual group");
    auto files=Kernel::procMaterializations;
    for(int i=0;i<20;i++)require(read("/proc/43/cgroup")=="1:cpuset:/top-app\n","repeat virtual read");
    require(Kernel::procMaterializations==files,"unchanged read rematerialized");
    Kernel::tasks.at(43).group="/foreground";require(read("/proc/43/cgroup")=="1:cpuset:/foreground\n"&&Kernel::procMaterializations==files+1,"stale group cache");
    auto first=read("/proc/43/stat");Kernel::tasks.at(43).birth++;
    require(read("/proc/43/stat")!=first,"stale generation cache");
    require(ids("/proc/42/task").size()==2,"initial task list");auto dirs=Kernel::taskMaterializations;
    for(int i=0;i<20;i++)require(ids("/proc/42/task").size()==2,"repeat task list");
    require(Kernel::taskMaterializations==dirs,"unchanged list rebuilt");
    Kernel::tasks.erase(43);require(ids("/proc/42/task").size()==1,"stale dead task");
    Kernel::tasks[43]={99,"/top-app",255};require(ids("/proc/42/task").size()==2&&read("/proc/43/stat")!=first,"stale reborn task");
    Kernel::tasks.clear();require(ids("/proc/42/task").empty(),"dead process task list");
    puts("SYNTHETIC_KERNEL_CACHE_INVALIDATION=PASS;REAL_JOURNAL_DISK=PASS");
}
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
        if(visible==750){f.noOwnership();require(!f.p().acquireBlocked(),"SLA boundary blocker");f.tick(1000);}
        require(f.p().managed()&&f.journal->commits==f.initialCommits+1&&f.journal->leases.size()==1,"temporal acquire miss/duplicate lease");f.place();
        f.background();f.empty();
    }
    // D/E: timeout is local to this request; duplicate foreground cannot restart it.
    for(bool custom:{false,true}){
        AcquisitionFixture f;Kernel::tasks.at(43).mask=custom?128:67;if(!custom)Kernel::tasks.at(43).group="/background";
        f.start(2);for(int t:{100,250,500,750,1000,1250,1500,2000,2500,3000}){f.tick(t);f.noOwnership();}
        require(f.blockerAttempts==1&&f.p().acquireBlocked()&&!f.p().acquiring(),"persistent mismatch not blocked");
        auto path=Kernel::root+"/state/runtime_blocker.v1";auto data=read(path);struct stat st{};require(lstat(path.c_str(),&st)==0,"blocker exists");auto inode=st.st_ino;
        require(data.find("reason=inheritance_baseline_unstable\n")!=data.npos&&data.find("org.example")==data.npos&&(st.st_mode&07777)==0600&&data.size()<1024,"blocker privacy/bound");
        for(int t=3001;t<4000;t++){f.primary();f.tick(t);}
        require(f.blockerAttempts==1&&lstat(path.c_str(),&st)==0&&st.st_ino==inode,"persistent steady blocker writes");f.noOwnership();
        f.background();for(auto& [_,task]:Kernel::tasks){task.group="/top-app";task.mask=255;}
        f.app.state=2;f.app.flags=4;f.primary();f.tick(4000);f.confirm();require(f.p().managed(),"fresh authority did not retry");f.background();
    }
    // F: disappearing/reused thread is not a baseline mismatch.
    for(bool reuse:{false,true}){
        AcquisitionFixture f;Kernel::tasks.at(43).mask=67;
        Kernel::hook=[&](const std::string& path){if(path=="affinity/43"){Kernel::hook={};if(reuse)Kernel::tasks.at(43).birth++;else Kernel::tasks.erase(43);}};
        f.start(2);if(reuse){f.noOwnership();Kernel::tasks.at(43).mask=255;f.tick(100);}
        f.confirm();require(f.p().managed(),"exit/reuse observation recovery");f.background();
    }
    // G: an original child born in the observation pass forces a new full pass.
    {AcquisitionFixture f;Kernel::hook=[](const std::string& path){if(path=="affinity/43"){Kernel::hook={};Kernel::tasks[44]={10000,"/top-app",255};}};
        f.start(2);f.noOwnership();f.tick(100);f.noOwnership();f.confirm();require(f.p().managed()&&f.journal->entries.count(44)&&f.p().ownershipFloor>10000,"pending-born child baseline/floor");
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
        f.noOwnership();require(!f.p().acquiring()&&!f.p().next,"pending deadline survived cancellation");
        if(mode==1){f.generation=20;Kernel::tasks.at(43).mask=255;f.primary();f.tick(100);f.confirm();require(f.p().managed()&&f.journal->leases.at(42).processStart==20,"stale PID acquired");f.background();}
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
        expectFailure([&]{f.background();},"background unrecoverable owned task");Kernel::stuck=false;f.background();f.empty();}
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
        require(fs::is_directory(Kernel::virtualRoot+"/dev/cpuset/ZUIopt"),"KEEP_ROOT scaffold");}
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
        for(int dt:{0,100,250,500,750,1000}){
            if(dt>=visible)for(auto& [_,t]:Kernel::tasks){t.group="/top-app";t.mask=255;}
            f.tick(base+dt);if(dt<visible)f.noOwnership();
        }
        {
            require(f.p().managed()&&f.journal->commits==f.initialCommits+1,"stress managed miss/duplicate lease");
            require(f.p().ownershipFloor>=static_cast<uint64_t>((100.+(base+visible+250)/1000.)*sysconf(_SC_CLK_TCK)),"lease predates temporal baseline");
            f.place();
        }
        f.background();f.empty();
    }
    auto proc=Kernel::reads,queries=f.snapshotQueries;auto commits=f.journal->commits;for(int t=2000000;t<2001000;t++)f.tick(t);
    require(Kernel::reads==proc&&f.snapshotQueries==queries&&f.journal->commits==commits&&!f.p().next&&f.scene.timeout(2001000)==-1,"idle baseline polling/timer");
    require(deferrals>400&&subsets.size()>150&&observedWindows.size()==8&&f.blockerAttempts==0,"stress coverage/recovery");
    std::cout<<"RANDOM_HETEROGENEOUS_SUBSETS="<<subsets.size()<<";STRESS_THREAD_COUNT=9;WINDOWS_COVERED="<<observedWindows.size()<<"\n";
    std::cout<<"TRANSIENT_STRESS_COUNT=512;DEFERRED="<<deferrals<<";TRANSIENT_STRESS_FATALS=0;UNSAFE_WRITES=0;OWNER_LEAK=0;STALE_PID=0;MANAGED_MISS=0;IDLE_ACQUISITION_TIMER=0\n";
}
struct CoherenceFixture:AcquisitionFixture {
    int drift=0,repairs=0,reacquires=0,contested=0,scans=0;
    SceneAuthority sceneIdentity{1,0,true,"org.example.game"};
    void ownershipBlocked(){contested++;receipt.record(Kernel::root+"/state",journal->currentBootId(),RuntimeBlockerReason::OWNERSHIP_CONTESTED);}
    void activate(ProcessState& p){
        bool valid=app.state==2&&(app.flags&4);
        if(valid!=sceneIdentity.valid){sceneIdentity.valid=valid;++sceneIdentity.sequence;}
        arbitrateLease(p,sceneIdentity,Kernel::time,*this);
    }
    void primary(){processEvent({1,42,10001,1,1},states,*this);}
    void background(){app.state=19;app.flags=0;primary();}
    // Templates dispatch on *this's static type: the inherited start() invoked
    // AcquisitionFixture::primary, bypassing this fixture's scene arbitration.
    void start(int mode=0){if(mode!=1)primary();if(mode!=2)scene.accept(1,0);tick(0);}
    Mask desired(int tid){return tid==43?28:tid==44?128:124;}
    void uniform(const std::string& group,Mask mask){for(auto& [_,t]:Kernel::tasks){t.group=group;t.mask=mask;}}
    void scanAt(int64_t time){
        tick(time);
        if(!p().managed()||p().next>time)return;
        scans++;
        auto result=owner->verifyCoherence(p(),time);
        if(result!=CoherenceResult::CLEAN)drift++;
        if(result==CoherenceResult::REVOKED){
            arbitrateLease(p(),sceneIdentity,time,*this);
            if(p().acquiring())reacquires++;
            return;
        }
        if(result==CoherenceResult::REPAIR)repairs++;
        if(result==CoherenceResult::REACQUIRE)reacquires++;
        if(result==CoherenceResult::CONTESTED){contested++;receipt.record(Kernel::root+"/state",journal->currentBootId(),RuntimeBlockerReason::OWNERSHIP_CONTESTED);}
        if(!p().managed()){if(p().acquiring())advanceAcquisition(p(),time,*this);return;}
        owner->prepare(p());
        for(auto& [tid,t]:p().tasks)owner->apply(p(),tid,t,desired(tid),"fixture_G07");
        finishScan(p(),time,result==CoherenceResult::REPAIR);
    }
    void managed(){
        require(p().managed(),"coherence managed miss");
        for(auto& [tid,t]:Kernel::tasks){std::ostringstream expected;expected<<"/ZUIopt/"<<std::hex<<desired(tid);
            require(t.group==expected.str()&&t.mask==desired(tid),"silent journal/physical divergence");
            require(journal->entries.count(tid)&&journal->same(journal->entries.at(tid)),"unknown owned task");
        }
    }
    void settle(){auto base=p().acquireStarted;for(int dt:{0,100,250,500,750})if(!p().managed())scanAt(base+dt);managed();}
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
        require(lease.savedGroup=="/top-app"&&lease.savedMask==255&&f.p().ownershipFloor==10350,"intermediate commit/floor");
        f.background();f.empty();}
    // Candidate generation/UID/epoch resets and unchanged uniform thread growth/shrink.
    {CoherenceFixture f;f.start(2);f.noOwnership();Kernel::tasks[44]={10,"/top-app",255};f.tick(100);
        require(f.p().baselineCandidate.firstStableAt==0,"uniform growth reset dwell");Kernel::tasks.erase(43);f.tick(250);
        require(f.p().managed()&&f.journal->entries.count(44)&&!f.journal->entries.count(43),"fresh final task set");f.background();}
    {CoherenceFixture f;f.start(2);Kernel::user++;f.tick(100);f.noOwnership();require(!f.p().acquiring(),"UID change not cancelled");}
    // Timing variants + edge just after 1000ms cover the worst old 1s gap.
    int worst=0;
    for(int when:{20,50,80,120,200,400,700,1000,1001,1500})for(int type=0;type<3;type++)for(int percent:{0,25,50,75,100}){
        CoherenceFixture f;f.boot(200);auto committed=Kernel::time;bool injected=false;
        for(int dt:{100,250,500,1000,1500,2000}){
            if(!injected&&dt>=when){f.overwrite(percent,type);injected=true;}
            f.scanAt(committed+dt);
            if(injected){require(f.drift==1,"cache hid overwrite");if(f.p().acquiring())f.settle();f.managed();
                worst=std::max(worst,static_cast<int>(Kernel::time-committed-when));break;}
        }
        require(f.p().revokeEpisodes==1&&f.repairs+f.reacquires==1,"repair counted per task");
        f.background();f.empty();
    }
    require(worst<=750,"coherence response budget");
    // Three process-level episodes cannot turn into an unlimited Android tug-of-war.
    {CoherenceFixture f;f.boot(200);
        for(int episode=0;episode<3;episode++){
            f.overwrite(100,2);f.scanAt(f.p().next);
            if(episode<2){require(f.p().acquiring(),"broad handoff not reacquiring");f.settle();}
        }
        require(!f.p().managed()&&!f.p().acquiring()&&f.p().acquireBlocked()&&f.contested==1&&f.p().revokeEpisodes==3,"unbounded contention");
        require(f.journal->entries.empty()&&f.journal->leases.empty(),"contested journal leak");
        for(auto& [_,t]:Kernel::tasks)require(t.group=="/top-app"&&t.mask==255,"contested residual owner");
        auto path=Kernel::root+"/state/runtime_blocker.v1";struct stat st{};require(lstat(path.c_str(),&st)==0,"contested receipt absent");auto inode=st.st_ino;
        require((st.st_mode&07777)==0600&&read(path).size()<1024&&read(path).find("reason=ownership_contested")!=std::string::npos,"contested receipt contract");
        auto writes=Kernel::moves+Kernel::affinities;auto commits=f.journal->commits;
        for(int t=10000;t<11000;t++){f.primary();f.scanAt(t);f.receipt.record(Kernel::root+"/state",f.journal->currentBootId(),RuntimeBlockerReason::OWNERSHIP_CONTESTED);}
        require(lstat(path.c_str(),&st)==0&&st.st_ino==inode&&commits==f.journal->commits&&writes==Kernel::moves+Kernel::affinities&&!f.p().next,"contested steady writes/timer");f.background();}
    // Changed normal Android placement is accepted, not fatal. Exact own
    // affinity residue is cleaned within the current group before a new baseline.
    {CoherenceFixture f;f.boot();for(auto& [_,t]:Kernel::tasks)t.group="/foreground";
        f.scanAt(f.p().next);require(f.p().acquiring()&&f.contested==0,"normal group handoff fatal");
        for(auto& [_,t]:Kernel::tasks)require(t.group=="/foreground"&&t.mask==255,"residue cleanup lost Android group");
        f.settle();f.background();f.empty();}
    {CoherenceFixture f;f.boot();f.uniform("/top-app",255);Kernel::tasks.at(43).group="/foreground";
        f.scanAt(f.p().next);require(f.p().acquiring()&&!f.p().managed()&&f.contested==0,"heterogeneous handoff not stabilized");
        require(f.journal->entries.empty()&&Kernel::tasks.at(43).group=="/foreground","safe external handoff rewritten");
        f.uniform("/top-app",255);f.settle();f.background();}
    {CoherenceFixture f;f.boot();Kernel::tasks[60]={1,"/ZUIopt/7c",124};f.overwrite(75,2);
        auto commits=f.journal->commits;auto moves=Kernel::moves;
        expectFailure([&]{f.scanAt(f.p().next);},"background unknown owned task");
        require(f.journal->commits==commits&&Kernel::moves==moves&&!f.journal->entries.empty(),"unknown coherence owner cleared");
        Kernel::tasks.erase(60);f.uniform("/top-app",255);f.owner->relinquishCoherence(f.p());f.empty();}
    // Late inherited, death and reuse safety during a coherence release.
    {CoherenceFixture f;f.boot();Kernel::tasks[60]={f.p().ownershipFloor+1,"/ZUIopt/7c",124};f.overwrite(75,2);
        f.scanAt(f.p().next);require(f.p().acquiring()&&Kernel::tasks.at(60).group=="/top-app"&&Kernel::tasks.at(60).mask==255,"late owned leak");f.settle();f.background();}
    {CoherenceFixture f;f.boot();Kernel::tasks.clear();f.owner->relinquishCoherence(f.p());f.empty();}
    {CoherenceFixture f;f.boot();f.uniform("/top-app",255);Kernel::tasks.at(42).birth++;
        auto writes=Kernel::moves+Kernel::affinities;f.owner->relinquishCoherence(f.p());f.empty();require(Kernel::moves+Kernel::affinities==writes,"stale PID write");}
    // End probation: cache remains cheap. Only an explicit event re-arms verification.
    {CoherenceFixture f;f.boot();auto base=Kernel::time;for(int dt:coherenceSchedule)f.scanAt(base+dt);
        require(!forceCoherence(f.p()),"probation never ended");auto reads=Kernel::reads;
        f.scanAt(base+7000);require(Kernel::reads==reads,"steady physical scan");
        f.overwrite(100,2);armCoherence(f.p(),base+7100);f.scanAt(base+7100);require(f.drift==1,"authority invalidation absent");f.settle();f.background();
        reads=Kernel::reads;auto commits=f.journal->commits;for(int t=100000;t<101000;t++)f.scanAt(t);
        require(Kernel::reads==reads&&f.journal->commits==commits&&!f.p().next,"idle coherence work");}
    std::mt19937 random(0x56c0ae);int recovered=0;std::set<int> types,subsets,windows;std::array<int,6> buckets{};
    {CoherenceFixture f;for(int tid=44;tid<58;tid++)Kernel::tasks[tid]={10,"/top-app",255};
        for(int i=0;i<1024;i++){
            int base=i*10000;Kernel::time=base;f.app.state=2;f.app.flags=4;
            // Uniform intermediate states below dwell, and later heterogeneous
            // Android handoffs (which must never count as candidate dwell).
            int duration=static_cast<int>(random()%201);windows.insert(duration);
            f.uniform("/foreground",31);f.primary();f.initialCommits=f.journal->commits;Kernel::moves=Kernel::affinities=0;
            for(int dt:{0,100,250,500,750}){
                if(dt>=duration)f.uniform("/top-app",255);
                f.scanAt(base+dt);
                if(f.p().managed())break;
                f.noOwnership();
            }
            f.managed();require(f.journal->leases.at(42).savedGroup=="/top-app"&&f.journal->leases.at(42).savedMask==255,"random intermediate commit");
            int percents[]={0,25,50,75,100};int percent=percents[random()%5],type=random()%3;
            types.insert(type);subsets.insert(percent);auto committed=Kernel::time;
            int when=1+random()%5999;buckets[when/1000]++;bool injected=false;auto before=f.drift;
            for(int dt:coherenceSchedule){
                if(!injected&&dt>=when){f.overwrite(percent,type);injected=true;}
                f.scanAt(committed+dt);
                if(injected){require(f.drift==before+1,"random silent divergence");if(f.p().acquiring())f.settle();f.managed();
                    require(Kernel::time-committed-when<=750,"random response budget");break;}
            }
            recovered++;f.background();f.empty();
            for(auto& [_,t]:Kernel::tasks)require(t.group=="/top-app"&&t.mask==255,"random owner leak");
        }
    }
    require(recovered==1024&&types.size()==3&&subsets.size()==5&&windows.size()>150,"random coherence coverage");
    for(int n:buckets)require(n>=100,"sparse random timing bucket");
    std::cout<<"RANDOM_0_6S_BUCKET_COVERAGE=";for(int n:buckets)std::cout<<n<<',';std::cout<<'\n';
    std::cout<<"RANDOM_COHERENCE_COUNT="<<recovered<<";GLOBAL_FATAL_RECOVERABLE=0;UNSAFE_JOURNAL_CLEAR=0;UNKNOWN_ZUIOPT_TASK=0;STALE_PID=0;OWNER_LEAK=0;SILENT_JOURNAL_PHYSICAL_DIVERGENCE=0\n";
    std::cout<<"SLEEP06_EXACT=PASS;OVERWRITE_CASES=150;PARTIAL_100_100=PASS;CPUSET_ONLY=PASS;AFFINITY_ONLY=PASS;COHERENCE_RESPONSE_MAX_MS="<<worst<<";CONTESTED_BUDGET=2;CACHE_IDLE=PASS\n";
}
void horizon(){
    constexpr int times[]={2001,2100,2250,2499,2500,2750,3000,3250,3500,3750,4000,4250,4500,4750,5000,5250,5500,5750,5999};
    constexpr int percents[]={0,25,50,75,100};int cases=0,near=0,worst=0;
    for(int when:times)for(int type=0;type<3;type++){
        CoherenceFixture f;f.boot(200);auto base=Kernel::time;int percent=percents[cases%5];
        for(int dt:coherenceSchedule){
            if(dt>=when)f.overwrite(percent,type);
            f.scanAt(base+dt);
            if(dt>=when){require(f.drift==1,"late window undetected drift");if(f.p().acquiring())f.settle();f.managed();
                worst=std::max(worst,static_cast<int>(Kernel::time-base-when));break;}
        }
        require(f.p().revokeEpisodes==1,"late window episode budget");cases++;near+=when>=5750;f.background();f.empty();
    }
    require(cases==57&&near==6&&worst<=750,"late timing coverage/budget");
    // Historical overwrite time is censored, not known to be exactly 3.5s.
    // This deliberately delayed fixture closes the old 2s blind spot.
    {CoherenceFixture f;f.boot(200);auto base=Kernel::time;
        for(int dt:coherenceSchedule){if(dt>3500)break;f.scanAt(base+dt);f.managed();}
        f.overwrite(100,2);require(f.journal->entries.size()==200,"delayed journal unexpectedly gone");
        for(auto& [_,t]:Kernel::tasks)require(t.group=="/top-app"&&t.mask==255,"delayed external takeover");
        f.scanAt(base+4000);require(f.reacquires==1,"delayed sleep06 missed");f.settle();f.managed();f.background();f.empty();}
    // Last-checkpoint repair and broad reacquisition both retain confirmation.
    // Also inject a second overwrite at each interval of post-repair confirmation.
    int seconds=0;
    for(int second:{0,1,101,251,501,999})for(int type=0;type<3;type++){
        CoherenceFixture f;f.boot(200);auto base=Kernel::time;
        for(int dt:coherenceSchedule){if(dt==6000)f.overwrite(0,type);f.scanAt(base+dt);}
        require(f.reacquires==1&&f.p().revokeEpisodes==1&&f.p().acquiring(),"last revoke lost reacquisition");
        f.settle();auto recommitted=Kernel::time;
        require(forceCoherence(f.p()),"new lease lost confirmation");
        for(int dt:repairSchedule){
            if(second&&dt>=second)f.overwrite(25,type);
            f.scanAt(recommitted+dt);
            if(second&&dt>=second){require(f.drift==2&&f.reacquires==2&&f.p().revokeEpisodes==2,"post reacquire second drift miss/budget reset");
                f.settle();f.managed();require(forceCoherence(f.p()),"reacquire lost new window");seconds++;break;}
        }
        if(!second){for(int dt:coherenceSchedule)f.scanAt(recommitted+dt);
            require(!forceCoherence(f.p())&&f.p().revokeEpisodes==1,"lease confirmation did not expire");}
        f.background();f.empty();
    }
    for(int type=0;type<3;type++){
        CoherenceFixture f;f.boot(200);auto base=Kernel::time;
        for(int dt:coherenceSchedule){if(dt==6000)f.overwrite(100,type);f.scanAt(base+dt);}
        require(f.reacquires==1,"last broad overwrite missed");f.settle();auto committed=Kernel::time;
        for(int dt:coherenceSchedule)f.scanAt(committed+dt);
        f.managed();require(!forceCoherence(f.p()),"broad re-acquisition timer unbounded");f.background();f.empty();
    }
    // Count actual production verify/prepare/apply POSIX operations for 200 tasks.
    // Core discovery/rank reads are unchanged and accounted separately in report.
    {CoherenceFixture f;f.boot(200);auto base=Kernel::time;
        int g=Kernel::groupReads,a=Kernel::affinityReads,id=Kernel::identityReads,u=Kernel::uidReads,s=f.scans;
        for(int dt:coherenceSchedule)f.scanAt(base+dt);
        int groups=Kernel::groupReads-g,affinity=Kernel::affinityReads-a,identities=Kernel::identityReads-id,users=Kernel::uidReads-u;
        require(f.scans-s==14&&groups==8400&&affinity==8400,"bounded scan/group/affinity count");
        std::cout<<"ACTIVE_200_THREADS:SCANS="<<f.scans-s<<";GROUP_READS="<<groups<<";AFFINITY_GET="<<affinity<<";IDENTITY_READS="<<identities<<";UID_READS="<<users<<'\n';
        auto original=f.p().activated;auto commits=f.journal->commits;auto moves=Kernel::moves+Kernel::affinities;
        require(f.p().leaseScene==f.sceneIdentity.sequence,"initial scene was not arbitrated");
        auto scansBeforeDuplicates=f.scans;
        // Same production primary activation contract: duplicates do not arm.
        for(int i=0;i<1000;i++){f.primary();f.scanAt(base+6001+i);}
        require(!forceCoherence(f.p())&&f.p().activated==original&&commits==f.journal->commits&&moves==Kernel::moves+Kernel::affinities,"duplicate rearm/write");
        f.scanAt(base+8000);
        // The R2 contract requires one fresh physical observation per task even
        // on cache hits; cache still prevents extra writes and journal commits.
        auto mandatoryReads=200*(f.scans-scansBeforeDuplicates);
        require(Kernel::groupReads==g+groups+mandatoryReads&&Kernel::affinityReads==a+affinity+mandatoryReads,"per-task revoke observation count");
        puts("ACQUISITION_HORIZON=PASS;DUPLICATE_REARM_WRITE=0;INITIAL_SCENE_ARBITRATION=PASS");
        f.background();f.empty();auto reads=Kernel::reads;commits=f.journal->commits;
        for(int i=0;i<1000;i++)f.scanAt(base+9000+i);
        require(!f.p().next&&!forceCoherence(f.p())&&reads==Kernel::reads&&commits==f.journal->commits,"idle coherence work");
    }
    require(seconds==15,"second overwrite coverage");
    std::cout<<"SLEEP06_DELAYED_OVERWRITE=PASS;OVERWRITE_AFTER_2S_CASES="<<cases<<";OVERWRITE_NEAR_6S_CASES="<<near+21<<";SECOND_DRIFT_AFTER_REPAIR="<<seconds<<";LATE_WINDOW_UNDETECTED_DRIFT=0;POST_REPAIR_SECOND_DRIFT_MISS=0;POST_REPAIR_CONFIRMATION_MS=1000;NO_UNBOUNDED_REARM=PASS;LATE_RESPONSE_MAX_MS="<<worst<<'\n';
}
void liveness(){
    // Same class as wake01, not a claim to reconstruct unobserved Android writes.
    for(bool old:{true,false}){
        CoherenceFixture f;for(int tid=44;tid<238;tid++)Kernel::tasks[tid]={10,"/foreground",31};
        f.uniform("/foreground",31);f.primary();
        for(int t:{0,100,250,500,750,1000,1250,1500}){
            Kernel::time=t;
            if(t>=500){Kernel::tasks.at(42)={10,"/top-app",255};Kernel::tasks.at(43)={10,"/top-app",255};}
            // Partial transitions reset the first candidate before its dwell.
            if(t>=250)Kernel::tasks.at(44).mask=255;
            if(t>=1200)f.uniform("/top-app",255);
            if(old){ // Exact V56 scheduling semantics, using real Placement::acquire.
                auto& p=f.p();if(p.acquiring()&&p.next<=t){
                    auto result=t>750?BaselineResult::DEFER:f.acquire(p);
                    require(result!=BaselineResult::STABLE,"old exact-class unexpectedly committed");
                    if(t>=750){f.release(p);p.transition(Ownership::LOCAL_BLOCKED);f.baselineBlocked();}
                    else {constexpr int due[]={100,250,500,750};for(int next:due)if(next>t){p.next=next;break;}}
                }
            }else f.tick(t);
            if(!f.p().managed())f.noOwnership();
            if(!old&&t<=1000)require(!f.p().acquireBlocked()&&!f.blockerAttempts,"fast deadline disabled liveness");
        }
        if(old){require(f.p().acquireBlocked()&&f.probes==5&&!f.p().acquiring(),"old sticky gap absent");f.noOwnership();}
        else{require(f.p().managed()&&f.blockerAttempts==0,"wake01 eventual miss");
            require(f.journal->leases.at(42).savedMask==255&&f.p().ownershipFloor==10150,"late safe commit floor");
            f.scanAt(1500);f.managed();require(forceCoherence(f.p()),"late commit lost V56 coherence");}
        f.background();f.empty();
    }
    // Late execution always performs exactly one fresh probe, never catch-up.
    for(int late:{751,764,780,825}){
        AcquisitionFixture f;Kernel::tasks.at(43).mask=67;f.start(2);
        for(int t:{107,263,519,late,1012,1060,1257,1517,2020,2570,3010}){
            bool due=f.p().acquiring()&&f.p().next<=t;auto probes=f.probes;f.tick(t);
            require(f.probes-probes==(due?1:0),"late timer skipped probe/catch-up storm");f.noOwnership();
            if(t<=1060)require(!f.blockerAttempts,"jitter became terminal at SLA");
        }
        require(f.blockerAttempts==1&&!f.p().next,"late final probe did not close");f.background();
    }
    // Force first complete candidate observation at each requested boundary.
    for(int first:{700,750,850,900,1000,1250,2750,2950}){
        AcquisitionFixture f;Kernel::tasks.at(43).mask=67;f.start(2);
        int lastDue=0;for(int due:{0,100,250,500,750,1000,1250,1500,2000,2500,3000})if(due<=first)lastDue=due;
        while(f.p().next<lastDue){f.tick(f.p().next);f.noOwnership();}
        // Execute the last due opportunity late, never edit production deadlines.
        require(f.p().next<=first,"candidate fixture needs a due timer");
        for(auto& [_,t]:Kernel::tasks){t.group="/top-app";t.mask=255;}
        f.tick(first);f.noOwnership();require(f.p().baselineCandidate.firstStableAt==first,"late candidate not observed");
        while(f.p().acquiring()){auto due=f.p().next;require(due<=3500,"candidate cap moved");f.tick(due);if(!f.p().managed())f.noOwnership();}
        require(f.p().managed()&&Kernel::time==first+250&&!f.blockerAttempts,"250ms confirmation opportunity missing");f.background();
    }
    // A changed candidate in the final extension cannot buy a second extension.
    {AcquisitionFixture f;Kernel::tasks.at(43).mask=67;f.start(2);
        while(f.p().next<3000){f.tick(f.p().next);f.noOwnership();}
        Kernel::tasks.at(43).mask=255;f.tick(3000);f.noOwnership();require(f.p().next==3250,"final candidate opportunity");
        for(auto& [_,t]:Kernel::tasks)t.mask=31;
        f.tick(3250);f.noOwnership();require(f.p().acquireBlocked()&&f.blockerAttempts==1&&!f.p().next,"moving final extension");f.background();}
    {AcquisitionFixture f;Kernel::tasks.at(43).mask=67;f.start(2);
        while(f.p().next<3000){f.tick(f.p().next);f.noOwnership();}
        Kernel::tasks.at(43).mask=255;f.tick(3250);f.noOwnership();
        require(f.p().next==3500&&f.p().acquireFinalConfirmation,"absolute final confirmation missing");
        f.tick(3500);require(f.p().managed()&&!f.blockerAttempts,"absolute cap safe commit miss");f.background();}
    // Background/null/death/reuse during SELF_HEAL_SETTLE cancel without writes.
    for(int mode=0;mode<4;mode++){
        AcquisitionFixture f;Kernel::tasks.at(43).mask=67;f.start(2);
        for(int t:{100,250,500,750,1000,1250})f.tick(t);f.noOwnership();
        if(mode==0)f.background();
        if(mode==1){f.absent=true;f.primary();}
        if(mode==2){Kernel::tasks.clear();f.tick(1500);}
        if(mode==3){Kernel::tasks.at(42).birth++;f.tick(1500);}
        f.noOwnership();for(auto& [_,p]:f.states)require(!p.acquiring()&&!p.next,"settlement cancel timer leak");
    }
    // Full 200-task persistent episode: count actual read boundaries, not zero cost.
    {AcquisitionFixture f;for(int tid=44;tid<242;tid++)Kernel::tasks[tid]={10,"/top-app",255};Kernel::tasks.at(43).mask=67;
        int g=Kernel::groupReads,a=Kernel::affinityReads,id=Kernel::identityReads,u=Kernel::uidReads;
        f.start(2);while(f.p().acquiring()){f.noOwnership();f.tick(f.p().next);}f.noOwnership();
        require(f.probes==11&&f.blockerAttempts==1,"functional episode not finite");
        std::cout<<"SETTLE_200_TASKS:PROBES="<<f.probes<<";GROUP_READS="<<Kernel::groupReads-g<<";AFFINITY_GET="<<Kernel::affinityReads-a<<";IDENTITY_READS="<<Kernel::identityReads-id<<";UID_READS="<<Kernel::uidReads-u<<'\n';
        auto reads=Kernel::reads;for(int t=4000;t<5000;t++)f.tick(t);require(reads==Kernel::reads&&!f.p().next,"terminal idle polling");f.background();}
    std::mt19937 random(0x57ac01);int maxProbes=0,lateRecovered=0,births=0,deaths=0;
    {CoherenceFixture f;for(int tid=44;tid<58;tid++)Kernel::tasks[tid]={10,"/top-app",255};
        for(int i=0;i<1024;i++){
            int base=i*10000,settled=random()%2501;Kernel::time=base;f.app.state=2;f.app.flags=4;
            f.uniform("/top-app",255);unsigned subset=1+random()%16383;
            if(settled)for(auto& [tid,t]:Kernel::tasks)if(subset&(1u<<(tid-42))){t.group="/foreground";t.mask=31;}
            // Guarantee nonuniform before settlement, regardless of random subset.
            if(settled){Kernel::tasks.at(42)={10,"/top-app",255};Kernel::tasks.at(43)={10,"/foreground",31};}
            f.initialCommits=f.journal->commits;Kernel::moves=Kernel::affinities=0;int probes=f.probes;
            int source=i%4;
            if(source!=1)f.primary();if(source!=2)f.scene.accept(i+100,base);
            f.tick(base);if(!f.p().managed())f.noOwnership();
            while(f.p().acquiring()){
                int64_t due=f.p().next,t=due+random()%101;
                if(t-base>=settled)f.uniform("/top-app",255);
                // A birth/death in a probe is never guessed as an inherited child.
                if(t-base<settled){
                    bool birth=random()%2;
                    Kernel::hook=[&,birth](const std::string& path){if(path=="affinity/43"){
                        Kernel::hook={};if(birth){Kernel::tasks[58]={10000+static_cast<uint64_t>(base/10),"/foreground",31};births++;}
                        else{Kernel::tasks.erase(58);deaths++;}
                    }};
                }
                auto before=f.probes;f.tick(t);require(f.probes==before+1,"random late probe skipped");
                if(!f.p().managed())f.noOwnership();
                require(!f.p().acquireBlocked(),"safe settlement became sticky");
            }
            require(f.p().managed()&&f.blockerAttempts==0,"random eventual acquisition miss");
            require(f.journal->leases.at(42).savedMask==255&&f.journal->entries.size()==Kernel::tasks.size(),"random baseline/late child guess");
            maxProbes=std::max(maxProbes,f.probes-probes);lateRecovered+=Kernel::time-base>1000;
            f.scanAt(Kernel::time);f.managed();f.background();f.empty();Kernel::tasks.erase(58);
            f.tick(base+5000); // Drain any already-pending scene burst before idle accounting.
        }
        auto reads=Kernel::reads,queries=f.snapshotQueries;for(int t=11000000;t<11001000;t++)f.tick(t);
        require(Kernel::reads==reads&&f.snapshotQueries==queries&&!f.p().next&&f.scene.timeout(11001000)==-1,"idle settlement work");
    }
    require(lateRecovered>500&&births>100&&deaths>100&&maxProbes<=21,"liveness stress coverage/bound");
    std::cout<<"V56_WAKE01_EXACT_CLASS=PASS;LATE_TIMER_SKIPPED_PROBE=0;LATE_CANDIDATES=8;ZERO_WRITE_PRECOMMIT=PASS;THREAD_CHURN=PASS;PERSISTENT_HETEROGENEITY=PASS\n";
    std::cout<<"ACQUISITION_LIVENESS_STRESS=1024;EVENTUAL_ACQUISITION_MISS=0;GLOBAL_FATAL=0;UNSAFE_WRITE=0;STALE_PID=0;OWNER_LEAK=0;IDLE_STEADY_ACQUISITION_TIMER=0;MAX_PROBES="<<maxProbes<<";RECOVERED_AFTER_SLA="<<lateRecovered<<";BIRTH_RACES="<<births<<";DEATH_RACES="<<deaths<<'\n';
}
void timed(const char* name,void(*test)()){
    using clock=std::chrono::steady_clock;auto start=clock::now();rusage before{},after{};
    require(getrusage(RUSAGE_SELF,&before)==0,"fixture resource usage");
    std::cout<<"ACQUISITION_PHASE_START="<<name<<'\n';test();
    require(getrusage(RUSAGE_SELF,&after)==0,"fixture resource usage");
    auto seconds=[](timeval t){return t.tv_sec+t.tv_usec/1000000.;};
    std::cout<<"ACQUISITION_PHASE="<<name<<";WALL_S="<<std::chrono::duration<double>(clock::now()-start).count()
        <<";USER_S="<<seconds(after.ru_utime)-seconds(before.ru_utime)<<";SYS_S="<<seconds(after.ru_stime)-seconds(before.ru_stime)
        <<";BLOCK_OUT="<<after.ru_oublock-before.ru_oublock<<'\n';
}
#ifndef ZUIOPT_ACQUISITION_LIBRARY
int main(){try{std::cout<<std::unitbuf;require(getuid()==0,"isolated root fixture");
    timed("kernel_backing",kernelBacking);
    timed("matrix",matrix);timed("recovery",recovery);timed("stress512",stress);timed("coherence1024",coherence);timed("horizon",horizon);timed("liveness1024",liveness);
    puts("ZUIOPT_BASELINE_NATIVE=PASS");return 0;}
catch(const std::exception& e){std::cerr<<"BASELINE_FAIL "<<e.what()<<'\n';return 1;}}
#endif
