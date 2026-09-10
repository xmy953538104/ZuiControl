// Durable write-ahead ownership. Unknown stale tasks are never assigned a guessed owner.
#pragma once
#include "ZUIopt_core.h"
#include <sys/file.h>
namespace ZUIopt {
inline bool validCpusetScaffold(const struct stat& st){
    return S_ISDIR(st.st_mode)&&st.st_uid==0&&st.st_gid==0&&(st.st_mode&07777)==0755;
}
struct OwnerRecord {
    int pid=0,user=0,tid=0;
    uint64_t processStart=0,threadStart=0;
    std::string package,name,savedGroup;
    Mask savedMask=0;
};
inline uint32_t checksum(const std::string& s){
    uint32_t crc=~uint32_t(0);
    for(unsigned char c:s){crc^=c;for(int i=0;i<8;i++)crc=(crc>>1)^(0xedb88320u&uint32_t(-int(crc&1)));}
    return ~crc;
}
inline void writeAll(int fd,const std::string& s){
    size_t at=0;
    while(at<s.size()){
        ssize_t n=::write(fd,s.data()+at,s.size()-at);
        if(n<0&&errno==EINTR)continue;
        require(n>0,"journal write failed");at+=static_cast<size_t>(n);
    }
}
struct BaselineProc {
    int64_t time(){return now();}
    Identity process(int pid){return identity(pid);}
    Identity thread(int pid,int tid){return identity(pid,tid);}
    int user(int pid){return uid(pid);}
    std::vector<int> tasks(int pid){return ids("/proc/"+std::to_string(pid)+"/task");}
    std::string cpuset(int tid){return group(tid);}
    Mask mask(int tid){return affinity(tid);}
    uint64_t floor(){
        double uptime=0;std::istringstream(read("/proc/uptime"))>>uptime;
        auto ticks=static_cast<uint64_t>(uptime*sysconf(_SC_CLK_TCK));
        require(ticks>0,"ownership birth floor");return ticks;
    }
};
struct AndroidBaseline {std::string group;Mask mask=0;std::map<int,uint64_t> tasks;};
template<class Proc> BaselineResult probeBaseline(const ProcessState& p,Proc& proc,AndroidBaseline& out){
    // Entire probe is read-only. Never cache a failed candidate across attempts.
    auto same=[&]{return proc.process(p.pid).start==p.generation&&proc.user(p.pid)==p.uid;};
    if(!same())return BaselineResult::STALE;
    AndroidBaseline candidate;candidate.group=proc.cpuset(p.pid);candidate.mask=proc.mask(p.pid);
    if(!same())return BaselineResult::STALE;
    auto safe=[](const std::string& g,Mask m){
        if(g.empty())return false; // Unavailable observation, not a guessed restore state.
        require(normalGroup(g),"initial Android owner unavailable");return m!=0;
    };
    if(!safe(candidate.group,candidate.mask))return BaselineResult::DEFER;
    bool uniform=true;
    for(int tid:proc.tasks(p.pid)){
        auto id=proc.thread(p.pid,tid);if(!id.start)continue;
        auto g=proc.cpuset(tid);auto m=proc.mask(tid);
        if(proc.thread(p.pid,tid).start!=id.start)continue; // Exit/reuse is a read race.
        if(!safe(g,m)||g!=candidate.group||m!=candidate.mask)uniform=false;
        candidate.tasks[tid]=id.start;
    }
    if(!same())return BaselineResult::STALE;
    // A pending-born task is an original Android task, never an inferred child.
    for(int tid:proc.tasks(p.pid)){
        auto id=proc.thread(p.pid,tid);if(!id.start)continue;
        auto it=candidate.tasks.find(tid);
        if(it==candidate.tasks.end()||it->second!=id.start)uniform=false;
    }
    auto g=proc.cpuset(p.pid);auto m=proc.mask(p.pid);
    if(!same())return BaselineResult::STALE;
    if(!uniform||!candidate.tasks.count(p.pid)||g!=candidate.group||m!=candidate.mask)return BaselineResult::DEFER;
    out=std::move(candidate);return BaselineResult::STABLE;
}
class Journal {
    int dirFd=-1,lockFd=-1;
    std::string currentBoot;
    static void secureFile(int fd){
        struct stat st{};
        require(fstat(fd,&st)==0&&S_ISREG(st.st_mode)&&st.st_uid==0&&(st.st_mode&0077)==0&&st.st_nlink==1,"unsafe journal/lock file");
    }
public:
    std::map<int,OwnerRecord> entries;
    // L record: process generation + birth floor + verified common Android inheritance baseline.
    // Only threads of this TGID born after the floor and still in our cpuset are covered.
    std::map<int,OwnerRecord> leases;
    std::string boot;
    uint64_t commits=0;
    explicit Journal(const std::string& path){
        try {
            require(getuid()==0,"ZUIopt requires root identity");
            if(mkdir(path.c_str(),0700)&&errno!=EEXIST)throw std::runtime_error("state directory create");
            dirFd=open(path.c_str(),O_DIRECTORY|O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
            struct stat st{};
            require(dirFd>=0&&fstat(dirFd,&st)==0&&st.st_uid==0&&(st.st_mode&0022)==0,"unsafe state directory");
            lockFd=openat(dirFd,"owner.lock",O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600);
            require(lockFd>=0,"owner lock open");secureFile(lockFd);
            require(flock(lockFd,LOCK_EX|LOCK_NB)==0,"another ZUIopt owner holds journal lock");
            currentBoot=trim(read("/proc/sys/kernel/random/boot_id"));require(currentBoot.size()==36,"boot identity");
            boot=currentBoot;
        }catch(...){if(lockFd>=0)close(lockFd);if(dirFd>=0)close(dirFd);throw;}
    }
    ~Journal(){if(lockFd>=0)close(lockFd);if(dirFd>=0)close(dirFd);}
    const std::string& currentBootId() const noexcept {return currentBoot;}
    bool same(const OwnerRecord& r) const {
        return boot==currentBoot&&identity(r.pid).start==r.processStart&&uid(r.pid)==r.user&&identity(r.pid,r.tid).start==r.threadStart;
    }
    bool inherited(int tid,OwnerRecord& out) const {
        int pid=0;std::istringstream status(read("/proc/"+std::to_string(tid)+"/status"));std::string line;
        while(std::getline(status,line))if(line.rfind("Tgid:",0)==0){std::istringstream(line.substr(5))>>pid;break;}
        auto it=leases.find(pid);if(it==leases.end()||boot!=currentBoot)return false;
        const auto& lease=it->second;auto id=identity(pid,tid);
        if(!id.start||id.start<lease.threadStart||identity(pid).start!=lease.processStart||uid(pid)!=lease.user||processName(pid)!=lease.name)return false;
        if(group(tid).rfind("/ZUIopt/",0)!=0)return false;
        out=lease;out.tid=tid;out.threadStart=id.start;return true;
    }
    void validate(const OwnerRecord& r) const {
        require(r.pid>0&&r.tid>0&&r.user>=0&&r.processStart&&r.threadStart,"journal identity");
        require(label(r.package)&&!r.name.empty()&&r.name.size()<256&&normalGroup(r.savedGroup),"journal owner fields");
        auto online=cpus(read("/sys/devices/system/cpu/online"));
        require(r.savedMask&&(r.savedMask&online)==r.savedMask,"journal affinity");
        require(access(("/dev/cpuset"+r.savedGroup+"/tasks").c_str(),F_OK)==0,"journal destination absent");
    }
    void load(){
        int fd=openat(dirFd,"owner_state.v1",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
        if(fd<0){require(errno==ENOENT,"journal open");return;}
        std::string text;char buf[4096];
        try {
            secureFile(fd);ssize_t n;
            while((n=::read(fd,buf,sizeof(buf)))>0){text.append(buf,n);require(text.size()<=4*1024*1024,"journal too large");}
            require(n==0,"journal read");close(fd);fd=-1;
            auto newline=text.find('\n');require(newline!=text.npos,"journal truncated header");
            std::istringstream header(text.substr(0,newline));std::string magic,extra;uint64_t crc=0;
            require(bool(header>>magic>>crc)&&(magic=="ZUIOPT_OWNER_STATE_V1"||magic=="ZUIOPT_OWNER_STATE_V2")&&!(header>>extra),"journal version/header");
            std::string body=text.substr(newline+1);require(crc==checksum(body),"journal checksum mismatch");
            std::istringstream in(body);std::string key,line;
            require(bool(std::getline(in,boot))&&boot.size()==36,"journal boot identity");
            while(std::getline(in,line)){
                require(!line.empty()&&entries.size()<32768,"journal entry bound");
                OwnerRecord r;std::istringstream row(line);
                std::string type="T";if(magic=="ZUIOPT_OWNER_STATE_V2")row>>type;
                require(type=="L"||type=="T","journal record type");
                require(bool(row>>r.pid>>r.processStart>>r.user>>r.tid>>r.threadStart>>std::quoted(r.package)>>std::quoted(r.name)>>std::quoted(r.savedGroup)>>r.savedMask)&&!(row>>extra),"journal entry syntax");
                validate(r);
                if(type=="L"){require(r.tid==r.pid&&leases.size()<128,"journal inheritance lease");require(leases.emplace(r.pid,r).second,"duplicate process lease");}
                else require(entries.emplace(r.tid,r).second,"duplicate journal TID");
            }
        }catch(...){if(fd>=0)close(fd);entries.clear();leases.clear();throw;}
    }
    void commit(){
        // CRC detects torn/corrupt storage; root-only DAC is the authenticity boundary.
        if(entries.empty()&&leases.empty()){
            require(unlinkat(dirFd,"owner_state.v1",0)==0||errno==ENOENT,"journal remove");
            require(fsync(dirFd)==0,"journal directory sync");boot=currentBoot;commits++;return;
        }
        boot=currentBoot;std::ostringstream body;body<<boot<<'\n';
        for(auto& [_,r]:leases){validate(r);body<<"L "<<r.pid<<' '<<r.processStart<<' '<<r.user<<' '<<r.tid<<' '<<r.threadStart<<' '<<std::quoted(r.package)<<' '<<std::quoted(r.name)<<' '<<std::quoted(r.savedGroup)<<' '<<r.savedMask<<'\n';}
        for(auto& [_,r]:entries){
            validate(r);
            body<<"T "<<r.pid<<' '<<r.processStart<<' '<<r.user<<' '<<r.tid<<' '<<r.threadStart<<' '
                <<std::quoted(r.package)<<' '<<std::quoted(r.name)<<' '<<std::quoted(r.savedGroup)<<' '<<r.savedMask<<'\n';
        }
        auto data=body.str();require(data.size()<=4*1024*1024,"journal size bound");
        data="ZUIOPT_OWNER_STATE_V2 "+std::to_string(checksum(data))+"\n"+data;
        int fd=openat(dirFd,"owner_state.v1.new",O_WRONLY|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600);
        require(fd>=0,"journal staging open");
        try {
            secureFile(fd);require(ftruncate(fd,0)==0,"journal truncate");writeAll(fd,data);
            require(fsync(fd)==0,"journal file sync");close(fd);fd=-1;
            require(renameat(dirFd,"owner_state.v1.new",dirFd,"owner_state.v1")==0,"journal atomic replace");
            require(fsync(dirFd)==0,"journal directory sync");commits++;
        }catch(...){if(fd>=0)close(fd);throw;}
    }
};
enum class CoherenceResult {CLEAN,REPAIR,REACQUIRE,CONTESTED};
class Placement {
    const std::string root="/dev/cpuset/ZUIopt";
    std::set<std::string> created;
    Journal& journal;
    Counters& count;
    void requireScaffold(){
        struct stat st{};
        require(lstat(root.c_str(),&st)==0&&validCpusetScaffold(st),"init cpuset scaffold absent or unsafe");
    }
    bool same(const ProcessState& p,int tid,const Task& t){return identity(p.pid).start==p.generation&&identity(p.pid,tid).start==t.generation&&uid(p.pid)==p.uid;}
    std::set<std::string> groups(){
        std::set<std::string> paths;
        DIR* dir=opendir(root.c_str());require(dir,"cpuset directory read");
        while(auto* e=readdir(dir)){
            std::string n=e->d_name;if(n=="."||n=="..")continue;
            struct stat st{};auto p=root+"/"+n;
            if(lstat(p.c_str(),&st)!=0||S_ISLNK(st.st_mode)){closedir(dir);throw std::runtime_error("unsafe cpuset entry");}
            if(S_ISDIR(st.st_mode)){
                if(n.find_first_not_of("0123456789abcdef")!=n.npos||paths.size()>=256){closedir(dir);throw std::runtime_error("unknown cpuset directory");}
                paths.insert(p);
            }
        }closedir(dir);return paths;
    }
    static std::vector<int> members(const std::string& p){
        int fd=open((p+"/tasks").c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW);require(fd>=0,"cpuset task read open");
        std::string text;char buf[4096];ssize_t n;
        while((n=::read(fd,buf,sizeof(buf)))>0){text.append(buf,n);if(text.size()>4*1024*1024){close(fd);throw std::runtime_error("cpuset task bound");}}
        close(fd);require(n==0,"cpuset task read failed");
        std::vector<int> v;std::istringstream in(text);int tid;
        while(in>>tid){require(tid>0,"cpuset task identity");v.push_back(tid);}require(in.eof(),"cpuset tasks parse");return v;
    }
    void restore(const OwnerRecord& r){
        if(!journal.same(r))return;
        auto current=group(r.tid);auto destination=normalGroup(current)?current:r.savedGroup;
        require(normalGroup(destination),"invalid Android release owner");
        bool moved=current==destination||(journal.same(r)&&write("/dev/cpuset"+destination+"/tasks",std::to_string(r.tid)));
        bool restored=journal.same(r)&&setAffinity(r.tid,r.savedMask);
        if((!moved||!restored)&&journal.same(r))throw std::runtime_error("journal release failed TID="+std::to_string(r.tid));
        count.releases++;ZUIOPT_NOTE("RELEASE","pid="+std::to_string(r.pid)+" tid="+std::to_string(r.tid)+" group="+destination);
    }
    bool coverRemaining(){
        auto paths=groups();paths.insert(root);bool remaining=false,added=false;
        for(auto& p:paths)for(int tid:members(p)){
            auto it=journal.entries.find(tid);
            if(it!=journal.entries.end()&&journal.same(it->second)){remaining=true;continue;}
            OwnerRecord inherited;
            if(journal.inherited(tid,inherited)){journal.entries[tid]=inherited;added=true;remaining=true;}
            else {
                // A task disappearing during enumeration is not an unknown live owner.
                if(!identity(tid).start)continue;
                throw std::runtime_error("RECOVERY_UNKNOWN_TASK_FAIL_CLOSED tid="+std::to_string(tid));
            }
        }
        if(added)journal.commit();return remaining;
    }
public:
    // Used before acquisition and by pending cancellation. Any owned residue is
    // corruption, not permission to take the no-op release path.
    void requireUncommitted(const ProcessState& p) const {
        require(!p.managed&&!journal.leases.count(p.pid),"uncommitted lease state");
        for(auto& [_,r]:journal.entries)require(r.pid!=p.pid,"uncommitted journal state");
        for(auto& [_,t]:p.tasks)require(!t.owned,"uncommitted owned task");
    }
#ifdef ZUIOPT_TEST
    Placement(Counters& c,Journal& j,std::nullptr_t):journal(j),count(c){} // Isolated fixture: no host cpuset startup.
#endif
    Placement(Counters& c,Journal& j):journal(j),count(c){
        // Init owns the top-level scaffold. Never create, repair or remove it here.
        requireScaffold();journal.load();
        size_t restored=0,discarded=0;
        coverRemaining(); // Preflight all before any restore; never guess unknown membership.
        for(auto& [_,r]:journal.entries){if(journal.same(r)){restore(r);restored++;}else discarded++;}
        {
            for(int pass=0;pass<8&&coverRemaining();pass++)for(auto& [_,r]:journal.entries)if(group(r.tid).rfind("/ZUIopt/",0)==0)restore(r);
            require(!coverRemaining(),"RECOVERY_BUSY_FAIL_CLOSED");created=groups();cleanup();
        }
        journal.entries.clear();journal.leases.clear();journal.commit();
        ZUIOPT_NOTE("RECOVERY_OK","restored="+std::to_string(restored)+" discarded="+std::to_string(discarded)+" remaining_tasks=0");
        require(write(root+"/mems",trim(read("/dev/cpuset/mems")))&&
                write(root+"/cpus",trim(read("/dev/cpuset/cpus"))),"cpuset initialization");
    }
    std::string target(Mask m){
        std::ostringstream n;n<<std::hex<<m;auto p=root+"/"+n.str();
        if(!created.count(p)){
            require(mkdir(p.c_str(),0755)==0,"cpuset child create");created.insert(p);
            require(write(p+"/mems",trim(read(root+"/mems")))&&write(p+"/cpus",cpuText(m)),"cpuset child initialize");
        }return p;
    }
    template<class Proc> BaselineResult acquire(ProcessState& p,Proc& proc){
        requireUncommitted(p);require(p.acquiring,"acquisition not pending");
        AndroidBaseline baseline;auto result=probeBaseline(p,proc,baseline);
        if(result!=BaselineResult::STABLE){p.baselineCandidate={};return result;}
        auto time=proc.time();auto& candidate=p.baselineCandidate;
        if(candidate.group!=baseline.group||candidate.mask!=baseline.mask||candidate.generation!=p.generation||
           candidate.uid!=p.uid||candidate.epoch!=p.acquisitionEpoch){
            candidate={baseline.group,baseline.mask,time,time,p.generation,p.acquisitionEpoch,p.uid};
            return BaselineResult::DEFER;
        }
        candidate.lastStableAt=time;
        if(time-candidate.firstStableAt<250)return BaselineResult::DEFER;
        // No ownership mutation until the complete fresh baseline has passed.
        // Commit L and original T records in one durable batch before placement.
        std::map<int,Task> tasks;
        for(auto& [tid,start]:baseline.tasks){
            Task t;t.generation=start;t.savedGroup=baseline.group;t.savedMask=baseline.mask;t.owned=true;
            tasks.emplace(tid,std::move(t));
            journal.validate(OwnerRecord{p.pid,p.uid,tid,p.generation,start,p.package,p.name,baseline.group,baseline.mask});
        }
        if(proc.process(p.pid).start!=p.generation||proc.user(p.pid)!=p.uid)return BaselineResult::STALE;
        const auto floor=proc.floor(); // Actual commit boundary, NOT activate()/first retry.
        journal.leases[p.pid]=OwnerRecord{p.pid,p.uid,p.pid,p.generation,floor,p.package,p.name,baseline.group,baseline.mask};
        for(auto& [tid,t]:tasks)journal.entries[tid]=OwnerRecord{p.pid,p.uid,tid,p.generation,t.generation,p.package,p.name,t.savedGroup,t.savedMask};
        journal.commit();
        p.androidGroup=std::move(baseline.group);p.androidMask=baseline.mask;p.ownershipFloor=floor;p.tasks=std::move(tasks);
        p.managed=true;p.acquiring=false;p.acquireStarted=0;p.acquireStep=0;p.baselineCandidate={};armCoherence(p,time);
        return BaselineResult::STABLE;
    }
    BaselineResult acquire(ProcessState& p){BaselineProc proc;return acquire(p,proc);}
    void prepare(ProcessState& p){
        bool changed=false;
        auto lease=journal.leases.find(p.pid);
        require(p.managed&&lease!=journal.leases.end()&&lease->second.processStart==p.generation&&
                lease->second.user==p.uid&&lease->second.threadStart==p.ownershipFloor,"committed lease identity mismatch");
        for(auto it=journal.entries.begin();it!=journal.entries.end();){
            auto& r=it->second;auto t=p.tasks.find(it->first);
            if(r.pid==p.pid&&r.processStart==p.generation&&(t==p.tasks.end()||t->second.generation!=r.threadStart)){
                it=journal.entries.erase(it);changed=true;
            }else ++it;
        }
        for(auto& [tid,t]:p.tasks){
            if(t.owned){auto r=journal.entries.find(tid);require(r!=journal.entries.end()&&r->second.pid==p.pid&&r->second.processStart==p.generation&&r->second.user==p.uid&&r->second.threadStart==t.generation,"write without durable owner identity");continue;}
            if(!same(p,tid,t))continue;
            auto current=group(tid);auto mask=affinity(tid);
            if(!same(p,tid,t))continue;
            if(normalGroup(current)){t.savedGroup=current;t.savedMask=mask;}
            else {
                require(current.rfind("/ZUIopt/",0)==0,"foreign owner during acquire");
                OwnerRecord inherited;bool covered=journal.inherited(tid,inherited);if(!same(p,tid,t))continue;
                require(covered,"uncovered inherited task");
                t.savedGroup=inherited.savedGroup;t.savedMask=inherited.savedMask;
            }
            OwnerRecord r{p.pid,p.uid,tid,p.generation,t.generation,p.package,p.name,t.savedGroup,t.savedMask};
            journal.validate(r);journal.entries[tid]=r;t.owned=true;changed=true;
        }
        if(changed)journal.commit(); // One durable batch, before any explicit placement in this scan.
    }
    void apply(ProcessState& p,int tid,Task& t,Mask m,const std::string& cls){
        // Cached default tasks do not reopen affinity/cgroup every second. Verify on change and 30s safety.
        if(!forceCoherence(p)&&t.appliedMask==m&&now()-t.verified<30000)return;
        if(!t.owned||!same(p,tid,t))return;
        auto it=journal.entries.find(tid);require(it!=journal.entries.end()&&journal.same(it->second),"write without durable owner identity");
        auto path=target(m);if(group(tid)==path.substr(11)&&affinity(tid)==m){t.appliedMask=m;t.verified=now();return;}
        bool moved=same(p,tid,t)&&write(path+"/tasks",std::to_string(tid));
        bool placed=same(p,tid,t)&&setAffinity(tid,m);
        if((!moved||!placed)&&same(p,tid,t))throw std::runtime_error("placement failed");
        t.appliedMask=m;t.verified=now();
        count.placements++;ZUIOPT_NOTE("PLACE","pid="+std::to_string(p.pid)+" tid="+std::to_string(tid)+" class="+cls+" mask="+cpuText(m));
    }
    bool relinquishCoherence(ProcessState& p){
        // Freeze before preflight, including the exception/releaseAll path. No
        // ordinary release may overwrite an unproven external affinity later.
        p.coherenceReleasing=true;
        for(int pass=0;pass<8;pass++){
            std::map<int,OwnerRecord> live;std::set<int> restoreOwned,restoreResidue;
            if(identity(p.pid).start==p.generation&&uid(p.pid)==p.uid){
                auto tids=ids("/proc/"+std::to_string(p.pid)+"/task");
                // Cross-check physical membership, including late inherited children.
                auto paths=groups();paths.insert(root);
                for(auto& path:paths)for(int tid:members(path))if(identity(p.pid,tid).start)tids.push_back(tid);
                for(int tid:tids){
                    auto id=identity(p.pid,tid);if(!id.start)continue;
                    auto g=group(tid);auto m=affinity(tid);
                    if(identity(p.pid,tid).start!=id.start)continue;
                    OwnerRecord r;auto entry=journal.entries.find(tid);
                    bool owned=g.rfind("/ZUIopt",0)==0;
                    if(entry!=journal.entries.end()&&journal.same(entry->second))r=entry->second;
                    else if(owned){require(journal.inherited(tid,r),"coherence unknown owned task");}
                    else continue; // A never-owned Android child has no restore effect.
                    live[tid]=r;
                    if(owned){restoreOwned.insert(tid);continue;}
                    require(normalGroup(g)&&m,"coherence external owner unavailable");
                    // A mask containing the entire saved Android affinity cannot
                    // retain our narrowing. Leave the external placement untouched.
                    if((m&r.savedMask)==r.savedMask)continue;
                    auto task=p.tasks.find(tid);
                    // Cpuset-only handoff back to the exact saved group: undo only
                    // our last affinity, never move Android to an old cpuset.
                    require(g==r.savedGroup&&task!=p.tasks.end()&&task->second.generation==id.start&&
                            task->second.appliedMask&&m==task->second.appliedMask,
                            "coherence unresolved external affinity");
                    restoreResidue.insert(tid);
                }
            }
            // Preflight the entire set before changing any task or durable record.
            if(restoreOwned.empty()&&restoreResidue.empty()){
                BaselineProc proc;AndroidBaseline baseline;
                bool uniform=probeBaseline(p,proc,baseline)==BaselineResult::STABLE;
                for(auto it=journal.entries.begin();it!=journal.entries.end();)
                    if(it->second.pid==p.pid&&it->second.processStart==p.generation)it=journal.entries.erase(it);else ++it;
                journal.leases.erase(p.pid);journal.commit();p.tasks.clear();p.managed=false;
                discardAcquisition(p);p.coherenceReleasing=false;return uniform;
            }
            bool added=false;
            for(auto& [tid,r]:live)if(!journal.entries.count(tid)){journal.entries[tid]=r;added=true;}
            if(added)journal.commit(); // Inherited originals stay crash-recoverable.
            for(int tid:restoreOwned){auto& r=live.at(tid);
                if(!journal.same(r)||group(tid).rfind("/ZUIopt",0)!=0)continue;
                bool moved=journal.same(r)&&write("/dev/cpuset"+r.savedGroup+"/tasks",std::to_string(tid));
                bool restored=journal.same(r)&&group(tid)==r.savedGroup&&setAffinity(tid,r.savedMask);
                require((moved&&restored)||!journal.same(r),"coherence owned release failed");count.releases++;
            }
            for(int tid:restoreResidue){auto& r=live.at(tid);auto& t=p.tasks.at(tid);
                if(journal.same(r)&&group(tid)==r.savedGroup&&affinity(tid)==t.appliedMask)
                    require(setAffinity(tid,r.savedMask)||!journal.same(r),"coherence affinity release failed");
            }
            // A fresh complete pass proves there is no owned member/residue before clear.
        }
        throw std::runtime_error("coherence release busy");
    }
    CoherenceResult verifyCoherence(ProcessState& p,int64_t time){
        if(!forceCoherence(p))return CoherenceResult::CLEAN;
        size_t checked=0,drift=0;
        for(auto& [tid,t]:p.tasks){
            if(!t.owned||!t.appliedMask||!same(p,tid,t))continue;
            auto entry=journal.entries.find(tid);
            require(entry!=journal.entries.end()&&journal.same(entry->second),"write without durable owner identity");
            auto g=group(tid);auto m=affinity(tid);if(!same(p,tid,t))continue;
            std::ostringstream expected;expected<<"/ZUIopt/"<<std::hex<<t.appliedMask;
            checked++;if(g!=expected.str()||m!=t.appliedMask)drift++;
        }
        if(!drift)return CoherenceResult::CLEAN;
        require(p.activity_foreground&&p.alive,"coherence authority absent");
        // One budget per foreground epoch, NOT per task, scan, or scene retry.
        bool exhausted=p.coherenceEpisodes>=2;
        if(!exhausted)++p.coherenceEpisodes;
        if(!exhausted&&p.coherenceEpisodes==1&&drift*2<checked)return CoherenceResult::REPAIR;
        bool uniform=relinquishCoherence(p);
        // Individually proven safe but heterogeneous Android handoff: close
        // without guessing a shared baseline; wait for fresh foreground authority.
        if(exhausted||!uniform){p.acquireBlocked=true;return CoherenceResult::CONTESTED;}
        beginAcquisition(p,time);return CoherenceResult::REACQUIRE;
    }
    // One finite pass, never an acquisition baseline probe. Crash recovery above
    // deliberately retains its original strict restore/coverRemaining contract.
    bool backgroundPass(ProcessState& p,bool finalPass){
        auto lease=journal.leases.find(p.pid);
        require(p.managed&&lease!=journal.leases.end()&&lease->second.processStart==p.generation&&
                lease->second.user==p.uid&&lease->second.threadStart==p.ownershipFloor,"committed lease identity mismatch");
        for(auto& [tid,t]:p.tasks)if(t.owned&&same(p,tid,t)){
            auto e=journal.entries.find(tid);
            require(e!=journal.entries.end()&&e->second.pid==p.pid&&e->second.threadStart==t.generation&&journal.same(e->second),"write without durable owner identity");
        }
        auto owned=[](const std::string& g){return g=="/ZUIopt"||g.rfind("/ZUIopt/",0)==0;};
        // Cross-check physical membership, including lease-covered children not
        // yet discovered by the foreground scan. Recheck stale enumeration before
        // declaring unknown ownership; Android may have moved/exited that TID.
        auto cover=[&](){
            std::set<int> ours;bool added=false;auto paths=groups();paths.insert(root);
            for(auto& path:paths)for(int tid:members(path)){
                auto id=identity(tid);if(!id.start||!owned(group(tid)))continue;
                auto e=journal.entries.find(tid);OwnerRecord r;
                if(e!=journal.entries.end()&&journal.same(e->second))r=e->second;
                else if(journal.inherited(tid,r)){
                    journal.entries[tid]=r;added=true;
                    if(r.pid==p.pid&&r.processStart==p.generation){
                        // A child born inside a known mask cpuset inherits that
                        // constraint. Record only an exact observed group-mask
                        // match; independently changed affinity is not our residue.
                        auto g=group(tid);auto m=affinity(tid);Mask applied=0;
                        if(created.count("/dev/cpuset"+g)&&m==cpus(read("/dev/cpuset"+g+"/cpus"))&&journal.same(r))applied=m;
                        Task t;t.generation=r.threadStart;t.savedGroup=r.savedGroup;t.savedMask=r.savedMask;t.appliedMask=applied;t.owned=true;
                        p.tasks[tid]=std::move(t);
                    }
                }
                else {
                    if(identity(tid).start!=id.start||!owned(group(tid)))continue;
                    throw std::runtime_error("background unknown owned task");
                }
                if(r.pid==p.pid&&r.processStart==p.generation)ours.insert(tid);
            }
            if(added)journal.commit(); // Durable before the first inherited write.
            return ours;
        };
        cover();
        bool pending=false;
        for(auto& [tid,r]:journal.entries){
            if(r.pid!=p.pid||r.processStart!=p.generation||!journal.same(r))continue;
            journal.validate(r);
            auto g=group(tid);if(!journal.same(r))continue;
            if(owned(g)){
                // Open first, then revalidate. An Android handoff during open or
                // earlier observation must not move an external task backwards.
                int fd=open(("/dev/cpuset"+r.savedGroup+"/tasks").c_str(),O_WRONLY|O_CLOEXEC|O_NOFOLLOW);
                require(fd>=0,"background release destination open");
                bool moved=true;
                try{if(journal.same(r)&&owned(group(tid))&&journal.same(r)){
                    auto value=std::to_string(tid);moved=::write(fd,value.data(),value.size())==static_cast<ssize_t>(value.size());
                }}catch(...){close(fd);throw;}
                close(fd);
                if(!moved&&journal.same(r)&&owned(group(tid)))pending=true;
                g=group(tid);
            }
            if(!journal.same(r))continue;
            if(owned(g)){pending=true;continue;}
            if(g.empty()){pending=true;continue;}
            require(normalGroup(g),"invalid Android release owner");
            auto t=p.tasks.find(tid);
            // No known last-applied/inherited constraint: no guessed affinity write.
            Mask applied=t!=p.tasks.end()&&t->second.generation==r.threadStart?t->second.appliedMask:0;
            auto mask=affinity(tid);if(!journal.same(r))continue;
            if(!mask){pending=true;continue;}
            if(!applied||mask!=applied||mask==r.savedMask)continue;
            Mask allowed=cpus(read("/dev/cpuset"+g+"/cpus"));
            Mask restoreMask=r.savedMask&allowed&cpus(read("/sys/devices/system/cpu/online"));
            require(restoreMask!=0,"background unsafe affinity destination");
            // Restore only our exact residue, constrained by the CURRENT Android
            // group. A changed Android mask/group wins; no saved-cpuset rewrite.
            if(journal.same(r)&&group(tid)==g&&affinity(tid)==applied&&journal.same(r)){
                if(!setAffinity(tid,restoreMask)&&journal.same(r))pending=true;
            }
        }
        auto remaining=cover();
        bool residue=false;
        // Fresh read proves completion; never clear on the basis of successful
        // writes alone. Heterogeneous Android groups/masks are legitimate here.
        for(auto& [tid,r]:journal.entries){
            if(r.pid!=p.pid||r.processStart!=p.generation||!journal.same(r))continue;
            auto g=group(tid);auto mask=affinity(tid);if(!journal.same(r))continue;
            if(owned(g)){remaining.insert(tid);continue;}
            if(g.empty()||!mask){residue=true;continue;}
            require(normalGroup(g),"invalid Android release owner");
            auto t=p.tasks.find(tid);
            if(t!=p.tasks.end()&&t->second.generation==r.threadStart&&t->second.appliedMask&&mask==t->second.appliedMask){
                Mask safe=r.savedMask&cpus(read("/dev/cpuset"+g+"/cpus"));
                require(safe!=0,"background unsafe affinity destination");
                if(mask!=safe)residue=true;
            }
        }
        if(finalPass)require(remaining.empty(),"background unrecoverable owned task");
        if(pending||!remaining.empty()||residue)return false;
        for(auto it=journal.entries.begin();it!=journal.entries.end();)
            if(it->second.pid==p.pid&&it->second.processStart==p.generation)it=journal.entries.erase(it);else ++it;
        journal.leases.erase(p.pid);journal.commit();p.tasks.clear();p.managed=false;discardAcquisition(p);
        p.backgroundReleasing=false;p.releaseBlocked=false;p.coherenceReleasing=false;p.acquireBlocked=false;p.coherenceEpisodes=0;
        count.releases++;return true;
    }
    void release(ProcessState& p,ReleaseCause cause=ReleaseCause::RELOAD_OR_CONTROLLED_STOP,int64_t time=now()){
        if(p.managed&&(cause==ReleaseCause::AUTHORITY_BACKGROUND||p.backgroundReleasing)){
            if(!p.backgroundReleasing){
                p.backgroundReleasing=true;p.releaseBlocked=false;p.releaseStarted=time;p.releaseStep=0;p.next=time;
                p.acquiring=false;p.acquireFinalConfirmation=false;p.baselineCandidate={};
                p.coherenceReleasing=false;p.burst=coherenceSchedule.size();p.repairStep=repairSchedule.size();
            }
            bool terminal=cause!=ReleaseCause::AUTHORITY_BACKGROUND;
            if(!terminal&&(p.releaseBlocked||p.next>time))return;
            bool last=terminal||time>=p.releaseStarted+backgroundReleaseSchedule.back();
            if(backgroundPass(p,last))return;
            if(last){
                // Only safe external observation/residue can reach this state;
                // known live owned residue is fatal above. No steady retry timer.
                // Catch/controlled-stop may retain this durable external-only
                // evidence for strict startup recovery. Do not turn a transient
                // observation delay into a second fatal/status3.
                p.releaseBlocked=true;return;
            }
            do{++p.releaseStep;}while(p.releaseStep<backgroundReleaseSchedule.size()&&p.releaseStarted+backgroundReleaseSchedule[p.releaseStep]<=time);
            p.next=p.releaseStarted+backgroundReleaseSchedule[p.releaseStep];return;
        }
        if(cause==ReleaseCause::COHERENCE_RELINQUISH){relinquishCoherence(p);return;}
        if(!p.managed){requireUncommitted(p);discardAcquisition(p);p.acquireBlocked=false;p.coherenceEpisodes=0;return;}
        if(p.coherenceReleasing){relinquishCoherence(p);p.acquireBlocked=false;p.coherenceEpisodes=0;return;}
        if(identity(p.pid).start==p.generation)for(int tid:ids("/proc/"+std::to_string(p.pid)+"/task")){
            auto id=identity(p.pid,tid);if(!id.start)continue;
            auto it=p.tasks.find(tid);
            if((it==p.tasks.end()||it->second.generation!=id.start)&&group(tid).rfind("/ZUIopt/",0)==0){Task t;t.generation=id.start;p.tasks[tid]=t;}
        }
        prepare(p);
        for(auto& [_,r]:journal.entries)if(r.pid==p.pid&&r.processStart==p.generation)restore(r);
        for(int pass=0;pass<8;pass++){
            bool remaining=false;
            for(int tid:ids("/proc/"+std::to_string(p.pid)+"/task"))if(group(tid).rfind("/ZUIopt/",0)==0){
                OwnerRecord r;bool covered=journal.inherited(tid,r);if(!identity(p.pid,tid).start)continue;
                require(covered,"release unknown inherited task");journal.entries[tid]=r;remaining=true;
            }
            if(!remaining)break;
            journal.commit();for(auto& [_,r]:journal.entries)if(r.pid==p.pid&&r.processStart==p.generation)restore(r);
        }
        for(int tid:ids("/proc/"+std::to_string(p.pid)+"/task"))require(group(tid).rfind("/ZUIopt/",0)!=0,"release busy process");
        for(auto it=journal.entries.begin();it!=journal.entries.end();)if(it->second.pid==p.pid&&it->second.processStart==p.generation)it=journal.entries.erase(it);else ++it;
        journal.leases.erase(p.pid);journal.commit();p.tasks.clear();p.managed=false;discardAcquisition(p);p.acquireBlocked=false;p.coherenceEpisodes=0;
    }
    void cleanup(){
        requireScaffold();
        require(members(root).empty(),"remaining root tasks");
        for(auto& p:created){require(members(p).empty(),"remaining owned tasks");require(rmdir(p.c_str())==0,"cpuset child removal");}
        created.clear();require(groups().empty(),"remaining cpuset children");
        // KEEP_ROOT: init's scaffold survives graceful stop, recovery and restart.
    }
};
}
