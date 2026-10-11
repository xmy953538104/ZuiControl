// Durable write-ahead ownership. Unknown stale tasks are never assigned a guessed owner.
#pragma once
#include "ZUIopt_runtime.h"
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
    Mask priorMask=0,appliedMask=0;
    SchedulerOwnership scheduler{};
    WaltOwnership walt{};
    std::string placementLease{};
};
inline bool placementToken(const std::string& token,std::array<uint64_t,5>* values=nullptr){
    if(token.empty()||token.front()!='l'||token.size()>100)return false;
    std::array<uint64_t,5> parsed{};std::istringstream in(token.substr(1));std::string field;
    size_t index=0;for(auto& value:parsed){
        if(!std::getline(in,field,'-')||field.empty()||field.size()>16||field.find_first_not_of("0123456789abcdef")!=field.npos)return false;
        try{size_t used=0;value=std::stoull(field,&used,16);if(used!=field.size()||(!value&&index!=2))return false;}catch(...){return false;}++index;
    }
    if(std::getline(in,field,'-')||token.back()=='-')return false;
    if(values)*values=parsed;return true;
}
inline std::string placementGroup(const std::string& lease,Mask mask){
    std::ostringstream out;out<<"/ZUIopt/";if(!lease.empty())out<<lease<<"-m";out<<std::hex<<mask;return out.str();
}
inline bool placementGroup(const std::string& group,std::string& lease,Mask& mask){
    if(group.rfind("/ZUIopt/",0)!=0)return false;auto name=group.substr(8);auto split=name.rfind("-m");
    if(split==name.npos)return false;lease=name.substr(0,split);auto field=name.substr(split+2);
    if(!placementToken(lease)||field.empty()||field.size()>16||field.find_first_not_of("0123456789abcdef")!=field.npos)return false;
    try{size_t used=0;mask=std::stoull(field,&used,16);return used==field.size()&&mask&&placementGroup(lease,mask)==group;}catch(...){return false;}
}
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
    // V5 binds each flat cpuset to one lease, including post-lease fork descendants.
    std::map<int,OwnerRecord> leases;
    std::string boot;
    bool runtimeVersion=false;
    bool waltVersion=false;
    bool placementVersion=false;
    struct GlobalOwner {int original=0,expected=0,prior=0;};
    std::map<std::string,GlobalOwner> globals;
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
    const OwnerRecord* rootLease(const std::string& token) const {
        for(const auto& [_,r]:leases)if(r.placementLease==token)return &r;return nullptr;
    }
    bool belongs(const OwnerRecord& r,const ProcessState& p) const {
        if(!placementVersion)return r.pid==p.pid&&r.processStart==p.generation;
        auto lease=leases.find(p.pid);return lease!=leases.end()&&lease->second.processStart==p.generation&&r.placementLease==lease->second.placementLease;
    }
    bool placementMatches(const OwnerRecord& r,const std::string& current) const {
        if(!placementVersion)return current.rfind("/ZUIopt/",0)==0;
        std::string token;Mask mask=0;return placementGroup(current,token,mask)&&token==r.placementLease;
    }
    bool inherited(int tid,OwnerRecord& out) const {
        int pid=0;std::istringstream status(read("/proc/"+std::to_string(tid)+"/status"));std::string line;
        while(std::getline(status,line))if(line.rfind("Tgid:",0)==0){std::istringstream(line.substr(5))>>pid;break;}
        if(placementVersion){
            std::string token;Mask allowed=0;auto current=group(tid);
            if(boot!=currentBoot||!placementGroup(current,token,allowed))return false;
            const OwnerRecord* lease=nullptr;for(const auto& [_,candidate]:leases)if(candidate.placementLease==token){lease=&candidate;break;}
            if(!lease)return false;auto process=identity(pid),thread=identity(pid,tid);
            if(!process.start||!thread.start||uid(pid)!=lease->user)return false;
            bool original=pid==lease->pid&&process.start==lease->processStart;
            // A unique group exists only after durable L/T commit; inheritance admits same-tick births.
            if(original?thread.start<lease->threadStart:process.start<lease->threadStart||thread.start<process.start)return false;
            auto cpuset=trim(read("/dev/cpuset"+current+"/cpus"));if(cpuset.empty()||cpus(cpuset)!=allowed)return false;
            out=*lease;out.pid=pid;out.processStart=process.start;out.tid=tid;out.threadStart=thread.start;
            // Kernel RESET_ON_FORK/WALT defaults are not copies of the parent's ownership.
            out.scheduler={};out.walt={};out.priorMask=out.appliedMask=0;
            auto mask=affinity(tid);if(mask==allowed)out.priorMask=out.appliedMask=mask;
            return same(out)&&group(tid)==current;
        }
        auto it=leases.find(pid);if(it==leases.end()||boot!=currentBoot)return false;
        const auto& lease=it->second;auto id=identity(pid,tid);
        if(!id.start||id.start<lease.threadStart||identity(pid).start!=lease.processStart||uid(pid)!=lease.user||processName(pid)!=lease.name)return false;
        if(group(tid).rfind("/ZUIopt/",0)!=0)return false;
        out=lease;out.tid=tid;out.threadStart=id.start;
        if(runtimeVersion){
            auto current=group(tid);auto mask=affinity(tid);std::ostringstream expected;expected<<"/ZUIopt/"<<std::hex<<mask;
            auto text=trim(read("/dev/cpuset"+current+"/cpus"));
            if(mask&&current==expected.str()&&!text.empty()&&cpus(text)==mask&&same(out))out.appliedMask=out.priorMask=mask;
        }
        return true;
    }
    void validate(const OwnerRecord& r) const {
        require(r.pid>0&&r.tid>0&&r.user>=0&&r.processStart&&r.threadStart,"journal identity");
        require(label(r.package)&&!r.name.empty()&&r.name.size()<256&&normalGroup(r.savedGroup),"journal owner fields");
        auto online=cpus(read("/sys/devices/system/cpu/online"));
        require(r.savedMask&&(r.savedMask&online)==r.savedMask,"journal affinity");
        require(access(("/dev/cpuset"+r.savedGroup+"/tasks").c_str(),F_OK)==0,"journal destination absent");
    }
    void validatePlacement() const {
        if(!placementVersion)return;std::set<std::string> tokens;
        for(const auto& [_,r]:leases){
            std::array<uint64_t,5> values{};
            require(placementToken(r.placementLease,&values)&&values[0]==static_cast<uint64_t>(r.pid)&&values[1]==r.processStart&&
                    values[2]==static_cast<uint64_t>(r.user)&&values[3]==r.threadStart&&tokens.insert(r.placementLease).second,"journal inheritance lease");
        }
        for(const auto& [_,r]:entries){
            auto lease=rootLease(r.placementLease);require(lease&&r.user==lease->user&&r.package==lease->package&&r.name==lease->name&&r.threadStart>=r.processStart,"journal identity");
            if(r.pid!=lease->pid||r.processStart!=lease->processStart)
                require(r.processStart>=lease->threadStart&&r.savedGroup==lease->savedGroup&&r.savedMask==lease->savedMask,"journal inheritance lease");
        }
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
            require(bool(header>>magic>>crc)&&(magic=="ZUIOPT_OWNER_STATE_V1"||magic=="ZUIOPT_OWNER_STATE_V2"||magic=="ZUIOPT_OWNER_STATE_V3"||magic=="ZUIOPT_OWNER_STATE_V4"||magic=="ZUIOPT_OWNER_STATE_V5")&&!(header>>extra),"journal version/header");
            placementVersion=magic=="ZUIOPT_OWNER_STATE_V5";waltVersion=placementVersion||magic=="ZUIOPT_OWNER_STATE_V4";runtimeVersion=waltVersion||magic=="ZUIOPT_OWNER_STATE_V3";
            std::string body=text.substr(newline+1);require(crc==checksum(body),"journal checksum mismatch");
            std::istringstream in(body);std::string key,line;
            require(bool(std::getline(in,boot))&&boot.size()==36,"journal boot identity");
            while(std::getline(in,line)){
                require(!line.empty()&&entries.size()<32768,"journal entry bound");
                OwnerRecord r;std::istringstream row(line);
                std::string type="T";if(magic!="ZUIOPT_OWNER_STATE_V1")row>>type;
                if(type=="G"){
                    std::string name;GlobalOwner g;
                    require(runtimeVersion&&bool(row>>name>>g.original>>g.expected),"global journal syntax");
                    if(waltVersion)require(bool(row>>g.prior)&&g.prior>=0&&g.prior<=4194304,"global prior intent");
                    require(!(row>>extra)&&
                        ((name=="rr_timeslice_ms"&&g.original>=1&&g.original<=1000&&g.expected==3)||
                        (waltVersion&&name=="walt_read_pid"&&g.original>=0&&g.original<=4194304&&g.expected>0&&g.expected<=4194304))&&globals.emplace(name,g).second,"global journal syntax");continue;
                }
                require(type=="L"||type=="T","journal record type");
                require(bool(row>>r.pid>>r.processStart>>r.user>>r.tid>>r.threadStart>>std::quoted(r.package)>>std::quoted(r.name)>>std::quoted(r.savedGroup)>>r.savedMask),"journal entry syntax");
                if(runtimeVersion&&type=="T"){
                    int active=0;
                    require(bool(row>>r.priorMask>>r.appliedMask>>active)&&(active==0||active==1)&&parseScheduler(row,r.scheduler.original)&&parseScheduler(row,r.scheduler.expected),"runtime journal syntax");r.scheduler.active=active;
                    require((r.priorMask&~Mask(255))==0&&(r.appliedMask&~Mask(255))==0,"runtime journal masks");
                    if(waltVersion){int waltActive=0;require(bool(row>>waltActive>>r.walt.kind>>r.walt.original>>r.walt.expected)&&(waltActive==0||waltActive==1),"WALT journal syntax");r.walt.active=waltActive;require(!r.walt.active||(waltValue(r.walt.kind,r.walt.original)&&waltValue(r.walt.kind,r.walt.expected)),"WALT journal values");}
                }
                if(placementVersion)require(bool(row>>r.placementLease),"journal inheritance lease");
                require(!(row>>extra),"journal extra fields");
                validate(r);
                if(type=="L"){require(r.tid==r.pid&&leases.size()<128,"journal inheritance lease");require(leases.emplace(r.pid,r).second,"duplicate process lease");}
                else require(entries.emplace(r.tid,r).second,"duplicate journal TID");
            }
            validatePlacement();
        }catch(...){if(fd>=0)close(fd);entries.clear();leases.clear();globals.clear();throw;}
    }
    void commit(){
        // CRC detects torn/corrupt storage; root-only DAC is the authenticity boundary.
        if(entries.empty()&&leases.empty()&&globals.empty()){
            require(unlinkat(dirFd,"owner_state.v1",0)==0||errno==ENOENT,"journal remove");
            require(fsync(dirFd)==0,"journal directory sync");boot=currentBoot;runtimeVersion=waltVersion=placementVersion=false;commits++;return;
        }
        validatePlacement();
        boot=currentBoot;std::ostringstream body;body<<boot<<'\n';
        for(const auto& [name,g]:globals){require(runtimeVersion&&((name=="rr_timeslice_ms"&&g.original>=1&&g.original<=1000&&g.expected==3)||(waltVersion&&name=="walt_read_pid"&&g.original>=0&&g.original<=4194304&&g.expected>0&&g.expected<=4194304)),"global journal fields");body<<"G "<<name<<' '<<g.original<<' '<<g.expected;if(waltVersion)body<<' '<<g.prior;body<<'\n';}
        for(auto& [_,r]:leases){validate(r);body<<"L "<<r.pid<<' '<<r.processStart<<' '<<r.user<<' '<<r.tid<<' '<<r.threadStart<<' '<<std::quoted(r.package)<<' '<<std::quoted(r.name)<<' '<<std::quoted(r.savedGroup)<<' '<<r.savedMask;if(placementVersion)body<<' '<<r.placementLease;body<<'\n';}
        for(auto& [_,r]:entries){
            validate(r);
            body<<"T "<<r.pid<<' '<<r.processStart<<' '<<r.user<<' '<<r.tid<<' '<<r.threadStart<<' '
                <<std::quoted(r.package)<<' '<<std::quoted(r.name)<<' '<<std::quoted(r.savedGroup)<<' '<<r.savedMask;
            if(runtimeVersion){body<<' '<<r.priorMask<<' '<<r.appliedMask<<' '<<r.scheduler.active<<' ';schedulerText(body,r.scheduler.original);body<<' ';schedulerText(body,r.scheduler.expected);}
            if(waltVersion)body<<' '<<r.walt.active<<' '<<r.walt.kind<<' '<<r.walt.original<<' '<<r.walt.expected;
            if(placementVersion)body<<' '<<r.placementLease;
            body<<'\n';
        }
        auto data=body.str();require(data.size()<=4*1024*1024,"journal size bound");
        data=std::string(placementVersion?"ZUIOPT_OWNER_STATE_V5 ":waltVersion?"ZUIOPT_OWNER_STATE_V4 ":runtimeVersion?"ZUIOPT_OWNER_STATE_V3 ":"ZUIOPT_OWNER_STATE_V2 ")+std::to_string(checksum(data))+"\n"+data;
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
enum class CoherenceResult {CLEAN,REPAIR,REACQUIRE,CONTESTED,REVOKED};
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
                std::string lease;Mask mask=0;
                bool legacy=!n.empty()&&n.find_first_not_of("0123456789abcdef")==n.npos;
                if((!legacy&&!placementGroup(p.substr(11),lease,mask))||paths.size()>=256){closedir(dir);throw std::runtime_error("unknown cpuset directory");}
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
    void restore(OwnerRecord& r){
        if(!journal.same(r))return;
        WaltKernel waltKernel{[&](int tid){return selectWalt(tid);}};
        auto waltResult=restoreWalt(r.walt,r.tid,waltKernel,[&]{journal.commit();},[&]{return journal.same(r);});
        require(waltResult!=SchedulerResult::UNAVAILABLE,"WALT recovery unresolved");
        SchedulerKernel kernel;
        auto result=restoreScheduler(r.scheduler,r.tid,kernel,[&]{journal.commit();},[&]{return journal.same(r);});
        require(result!=SchedulerResult::UNAVAILABLE,"scheduler recovery unresolved");
        auto current=group(r.tid);if(journal.placementVersion&&!normalGroup(current))require(journal.placementMatches(r,current),"invalid Android release owner");
        auto destination=normalGroup(current)?current:r.savedGroup;
        require(normalGroup(destination),"invalid Android release owner");
        bool moved=current==destination||(journal.same(r)&&(!journal.placementVersion||group(r.tid)==current)&&write("/dev/cpuset"+destination+"/tasks",std::to_string(r.tid)));
        bool restored=true;
        auto mask=affinity(r.tid);
        if(!journal.runtimeVersion){
            restored=journal.same(r)&&setAffinity(r.tid,r.savedMask);
        }else if(mask==r.appliedMask||mask==r.priorMask){
            auto allowed=cpus(read("/dev/cpuset"+destination+"/cpus"));auto restoreMask=r.savedMask&allowed;
            require(restoreMask!=0,"unsafe runtime affinity restore");
            restored=journal.same(r)&&setAffinity(r.tid,restoreMask);
        }
        if((!moved||!restored)&&journal.same(r))throw std::runtime_error("journal release failed TID="+std::to_string(r.tid));
        count.releases++;ZUIOPT_NOTE("RELEASE","pid="+std::to_string(r.pid)+" tid="+std::to_string(r.tid)+" group="+destination);
    }
    bool coverRemaining(){
        auto paths=groups();paths.insert(root);bool remaining=false,added=false;
        for(auto& p:paths)for(int tid:members(p)){
            auto it=journal.entries.find(tid);
            if(it!=journal.entries.end()&&journal.same(it->second)){
                if(journal.placementVersion&&!journal.placementMatches(it->second,group(tid)))throw std::runtime_error("RECOVERY_UNKNOWN_TASK_FAIL_CLOSED tid="+std::to_string(tid));remaining=true;continue;
            }
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
        require(!p.managed()&&!journal.leases.count(p.pid),"uncommitted lease state");
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
        restoreGlobals();journal.entries.clear();journal.leases.clear();journal.commit();
        ZUIOPT_NOTE("RECOVERY_OK","restored="+std::to_string(restored)+" discarded="+std::to_string(discarded)+" remaining_tasks=0");
        require(write(root+"/mems",trim(read("/dev/cpuset/mems")))&&
                write(root+"/cpus",trim(read("/dev/cpuset/cpus"))),"cpuset initialization");
    }
    bool finiteRtBudget() const {
        auto period=trim(read("/proc/sys/kernel/sched_rt_period_us")),runtime=trim(read("/proc/sys/kernel/sched_rt_runtime_us"));
        if(period.empty()||runtime.empty())return false;
        int p=number(period),r=number(runtime);return p>0&&p<=1500000&&r>0&&r<p&&int64_t(r)*100<=int64_t(p)*95;
    }
    void restoreGlobals(const std::string& only={}){
        if(journal.boot!=journal.currentBootId()){journal.globals.clear();return;}
        for(auto it=journal.globals.begin();it!=journal.globals.end();){
            if(!only.empty()&&it->first!=only){++it;continue;}
            auto path=it->first=="walt_read_pid"?"/proc/sys/walt/sched_task_read_pid":"/proc/sys/kernel/sched_rr_timeslice_ms";
            auto current=trim(read(path));require(!current.empty(),"global readback unavailable");
            if(number(current)==it->second.expected||(it->first=="walt_read_pid"&&number(current)==it->second.prior)){
                require(write(path,std::to_string(it->second.original))&&number(trim(read(path)))==it->second.original,"global recovery unresolved");
            }
            it=journal.globals.erase(it);journal.commit();
        }
    }
    bool selectWalt(int tid){
        if(tid<=0||tid>4194304)return false;
        const char* path="/proc/sys/walt/sched_task_read_pid";auto currentText=trim(read(path));if(currentText.empty())return false;
        int current=number(currentText);auto it=journal.globals.find("walt_read_pid");
        if(it!=journal.globals.end()&&current!=it->second.expected&&current!=it->second.prior)return false; // A foreign query Owner wins.
        if(current==tid)return true;
        journal.runtimeVersion=true;journal.waltVersion=true;
        if(it==journal.globals.end())it=journal.globals.emplace("walt_read_pid",Journal::GlobalOwner{current,tid,current}).first;
        else{it->second.prior=current;it->second.expected=tid;}
        journal.commit();
        if(trim(read(path))!=currentText)return false;
        return write(path,std::to_string(tid))&&trim(read(path))==std::to_string(tid);
    }
    SchedulerResult walt(ProcessState& p,int tid,int kind,int value,AuthorityFence* authority=nullptr){
        fence(authority);auto it=journal.entries.find(tid);
        if(it==journal.entries.end()&&!identity(p.pid,tid).start)return SchedulerResult::DEAD;
        require(it!=journal.entries.end(),"WALT without durable placement identity");auto& r=it->second;
        WaltKernel kernel{[&](int target){return selectWalt(target);}};
        if(!kind)return restoreWalt(r.walt,tid,kernel,[&]{journal.commit();},[&]{return journal.same(r);});
        if(!qualifiedWaltIdentity())return SchedulerResult::UNAVAILABLE;
        journal.runtimeVersion=true;journal.waltVersion=true;
        return applyWalt(r.walt,kind,tid,value,kernel,[&]{journal.commit();},[&]{
            fence(authority);auto task=p.tasks.find(tid);
            if(task!=p.tasks.end()&&physicalRevoke(p,tid,task->second))throw PhysicalRevoke{};
            return p.writable()&&journal.same(r)&&processName(p.pid)==p.name;
        });
    }
    bool prepareRealtimeGlobal(){
        if(!finiteRtBudget())return false;
        if(journal.globals.count("rr_timeslice_ms"))return trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))=="3";
        auto current=trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"));if(current.empty())return false;
        int original=number(current);if(original<1||original>1000)return false;if(original==3)return true;
        journal.runtimeVersion=true;journal.globals.emplace("rr_timeslice_ms",Journal::GlobalOwner{original,3});journal.commit();
        if(trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))!=current)return false;
        return write("/proc/sys/kernel/sched_rr_timeslice_ms","3")&&trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))=="3";
    }
    SchedulerResult realtime(ProcessState& p,int tid,bool requested,AuthorityFence* authority=nullptr){
        fence(authority);auto it=journal.entries.find(tid);
        if(it==journal.entries.end()&&!identity(p.pid,tid).start)return SchedulerResult::DEAD;
        require(it!=journal.entries.end(),"RT without durable placement identity");auto& r=it->second;
        SchedulerKernel kernel;auto same=[&]{
            fence(authority);auto task=p.tasks.find(tid);
            if(task!=p.tasks.end()&&physicalRevoke(p,tid,task->second))throw PhysicalRevoke{};
            return p.writable()&&journal.same(r)&&processName(p.pid)==p.name;
        };
        if(!requested)return restoreScheduler(r.scheduler,tid,kernel,[&]{journal.commit();},[&]{return journal.same(r);});
        journal.runtimeVersion=true;return applyRealtime(r.scheduler,tid,kernel,[&]{journal.commit();},same);
    }
    void retireRealtimeGlobal(){
        for(const auto& [_,r]:journal.entries)if(r.scheduler.active&&journal.same(r))return;
        restoreGlobals("rr_timeslice_ms");
    }
    void retireWaltGlobal(){
        for(const auto& [_,r]:journal.entries)if(r.walt.active&&journal.same(r))return;
        restoreGlobals("walt_read_pid");
    }
    std::string target(const ProcessState& owner,Mask m){
        auto lease=journal.leases.find(owner.pid);require(lease!=journal.leases.end()&&lease->second.processStart==owner.generation,"committed lease identity mismatch");
        auto p="/dev/cpuset"+placementGroup(lease->second.placementLease,m);
        if(!created.count(p)){
            require(mkdir(p.c_str(),0755)==0,"cpuset child create");created.insert(p);
            require(write(p+"/mems",trim(read(root+"/mems")))&&write(p+"/cpus",cpuText(m)),"cpuset child initialize");
        }return p;
    }
    template<class Proc> BaselineResult acquire(ProcessState& p,Proc& proc){
        requireUncommitted(p);require(p.acquiring(),"acquisition not pending");
        // Old live records must be retired by strict recovery, never relabeled V5.
        require(journal.placementVersion||(journal.entries.empty()&&journal.leases.empty()&&journal.globals.empty()),"uncommitted journal state");
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
        std::ostringstream token;token<<'l'<<std::hex<<p.pid<<'-'<<p.generation<<'-'<<p.uid<<'-'<<floor<<'-'<<p.acquisitionEpoch;
        journal.leases.at(p.pid).placementLease=token.str();journal.runtimeVersion=journal.waltVersion=journal.placementVersion=true;
        for(auto& [tid,t]:tasks){OwnerRecord r{p.pid,p.uid,tid,p.generation,t.generation,p.package,p.name,t.savedGroup,t.savedMask};r.placementLease=token.str();journal.entries[tid]=r;}
        journal.commit();
        p.androidGroup=std::move(baseline.group);p.androidMask=baseline.mask;p.ownershipFloor=floor;p.tasks=std::move(tasks);
        p.transition(Ownership::ZUIOPT_OWNED);p.acquireStarted=0;p.acquireStep=0;p.baselineCandidate={};armCoherence(p,time);
        return BaselineResult::STABLE;
    }
    BaselineResult acquire(ProcessState& p){BaselineProc proc;return acquire(p,proc);}
    void prepare(ProcessState& p,AuthorityFence* authority=nullptr){
        fence(authority);
        if(!p.writable())throw PhysicalRevoke{};
        bool changed=false;
        auto lease=journal.leases.find(p.pid);
        require(p.managed()&&lease!=journal.leases.end()&&lease->second.processStart==p.generation&&
                lease->second.user==p.uid&&lease->second.threadStart==p.ownershipFloor,"committed lease identity mismatch");
        for(auto it=journal.entries.begin();it!=journal.entries.end();){
            auto& r=it->second;auto t=p.tasks.find(it->first);
            if(r.pid==p.pid&&r.processStart==p.generation&&(t==p.tasks.end()||t->second.generation!=r.threadStart)){
                it=journal.entries.erase(it);changed=true;
            }else ++it;
        }
        try{for(auto& [tid,t]:p.tasks){
            fence(authority);
            if(t.owned){auto r=journal.entries.find(tid);require(r!=journal.entries.end()&&r->second.pid==p.pid&&r->second.processStart==p.generation&&r->second.user==p.uid&&r->second.threadStart==t.generation,"write without durable owner identity");continue;}
            if(!same(p,tid,t))continue;
            auto current=group(tid);auto mask=affinity(tid);
            fence(authority);
            if(!same(p,tid,t))continue;
            if(normalGroup(current)){t.savedGroup=current;t.savedMask=mask;}
            else {
                require(current.rfind("/ZUIopt/",0)==0,"foreign owner during acquire");
                OwnerRecord inherited;bool covered=journal.inherited(tid,inherited);if(!same(p,tid,t))continue;
                fence(authority);
                require(covered,"uncovered inherited task");
                t.savedGroup=inherited.savedGroup;t.savedMask=inherited.savedMask;
            }
            OwnerRecord r{p.pid,p.uid,tid,p.generation,t.generation,p.package,p.name,t.savedGroup,t.savedMask};
            if(journal.placementVersion)r.placementLease=lease->second.placementLease;
            journal.validate(r);journal.entries[tid]=r;t.owned=true;changed=true;
        }}catch(const StaleAuthorityScan&){if(changed)journal.commit();throw;}
        if(changed)journal.commit(); // One durable batch, before any explicit placement in this scan.
        fence(authority);
    }
    void stageMasks(ProcessState& p,const std::map<int,Mask>& masks,AuthorityFence* authority=nullptr){
        std::map<int,std::pair<Mask,Mask>> intents;
        for(const auto& [tid,mask]:masks){
            fence(authority);auto task=p.tasks.find(tid);if(task==p.tasks.end()||!task->second.owned||!same(p,tid,task->second))continue;
            auto& t=task->second;if(physicalRevoke(p,tid,t))throw PhysicalRevoke{};
            auto it=journal.entries.find(tid);require(it!=journal.entries.end()&&journal.same(it->second),"write without durable owner identity");
            const auto& r=it->second;if(r.appliedMask==mask)continue;
            intents.emplace(tid,std::make_pair(t.appliedMask?t.appliedMask:r.savedMask,mask));
        }
        // A stale authority or physical handoff during preflight leaves no
        // partially staged in-memory intents for a later scan to mistake as durable.
        for(const auto& [tid,intent]:intents){auto& r=journal.entries.at(tid);r.priorMask=intent.first;r.appliedMask=intent.second;}
        if(!intents.empty()&&journal.runtimeVersion)journal.commit(); // All intents durable before the first affinity syscall.
        fence(authority);
    }
    void apply(ProcessState& p,int tid,Task& t,Mask m,const std::string& cls,AuthorityFence* authority=nullptr){
        fence(authority);
        if(!p.writable())throw PhysicalRevoke{};
        if(physicalRevoke(p,tid,t))throw PhysicalRevoke{};
        // Every apply observes physical ownership, including cached default/rank
        // tasks. Only the current task can pass this check before an OS handoff.
        if(!forceCoherence(p)&&t.appliedMask==m&&now()-t.verified<30000)return;
        if(!t.owned||!same(p,tid,t))return;
        auto it=journal.entries.find(tid);require(it!=journal.entries.end()&&journal.same(it->second),"write without durable owner identity");
        // physicalRevoke already validated the last-applied physical state.
        // Do not take a second observation and then ignore its revocation signal.
        if(t.appliedMask==m){t.verified=now();return;}
        auto path=target(p,m);
        auto& r=it->second;
        if(r.appliedMask!=m){
            r.priorMask=t.appliedMask?t.appliedMask:r.savedMask;r.appliedMask=m;
            // Schema2 retains its proved original-value journal. Keep intents in
            // memory so a later runtime upgrade durably includes every live lease.
            if(journal.runtimeVersion)journal.commit();
        }
        fence(authority);
        bool moved=same(p,tid,t)&&write(path+"/tasks",std::to_string(tid),authority),placed=false;
        if(same(p,tid,t)){fence(authority);placed=setAffinity(tid,m);}
        if(placed)t.appliedMask=m; // Preserve exact residue even if authority changes inside the syscall.
        fence(authority);
        if((!moved||!placed)&&same(p,tid,t))throw std::runtime_error("placement failed");
        t.appliedMask=m;t.verified=now();
        count.placements++;ZUIOPT_NOTE("PLACE","pid="+std::to_string(p.pid)+" tid="+std::to_string(tid)+" class="+cls+" mask="+cpuText(m));
        // Catch a surviving handoff of the current task before starting a sibling.
        if(physicalRevoke(p,tid,t))throw PhysicalRevoke{};
    }
    // A physical mismatch is revocation, not permission to repair Android.
    // This probe is read-only; ownership state changes before returning control.
    bool physicalRevoke(ProcessState& p,int tid,const Task& t){
        if(!p.writable())return p.revoked();
        if(!t.owned||!t.appliedMask||!same(p,tid,t))return false;
        // Only this adjacent durable-record comparison shares the successful
        // same() observation. Never carry identity across the physical reads.
        struct ObservedIdentity {int pid,user,tid;uint64_t processStart,threadStart;};
        const ObservedIdentity observed{p.pid,p.uid,tid,p.generation,t.generation};
        auto entry=journal.entries.find(tid);
        require(entry!=journal.entries.end(),"write without durable owner identity");
        const auto& r=entry->second;
        require(journal.boot==journal.currentBootId()&&r.pid==observed.pid&&r.user==observed.user&&
                r.tid==observed.tid&&r.processStart==observed.processStart&&r.threadStart==observed.threadStart,
                "write without durable owner identity");
        auto g=group(tid);auto m=affinity(tid);
        if(!same(p,tid,t))return false;
        auto expected=placementGroup(r.placementLease,t.appliedMask);
        if(g==expected&&m==t.appliedMask)return false;
        require(normalGroup(g)||g==expected,"invalid physical owner");
        require(m!=0,"physical affinity unavailable");
        p.transition(Ownership::REVOKE_PENDING);p.next=0;
        return true;
    }
    CoherenceResult verifyCoherence(ProcessState& p,int64_t,AuthorityFence* authority=nullptr){
        fence(authority);
        if(!p.writable())return p.revoked()?CoherenceResult::REVOKED:CoherenceResult::CLEAN;
        if(!forceCoherence(p))return CoherenceResult::CLEAN;
        for(auto& [tid,t]:p.tasks)if(physicalRevoke(p,tid,t))return CoherenceResult::REVOKED;
        fence(authority);return CoherenceResult::CLEAN;
    }
    void relinquishCoherence(ProcessState& p){release(p,ReleaseCause::COHERENCE_RELINQUISH);}
    // One finite pass, never an acquisition baseline probe. Crash recovery above
    // deliberately retains its original strict restore/coverRemaining contract.
    bool backgroundPass(ProcessState& p,bool finalPass){
        auto lease=journal.leases.find(p.pid);
        require(p.managed()&&lease!=journal.leases.end()&&lease->second.processStart==p.generation&&
                lease->second.user==p.uid&&lease->second.threadStart==p.ownershipFloor,"committed lease identity mismatch");
        for(auto& [tid,t]:p.tasks)if(t.owned&&same(p,tid,t)){
            auto e=journal.entries.find(tid);
            // Compare durable identities, not a second live observation inside
            // the invariant: exit/reuse between two reads is not journal damage.
            bool durable=e!=journal.entries.end()&&e->second.pid==p.pid&&e->second.processStart==p.generation&&e->second.user==p.uid&&e->second.threadStart==t.generation;
            require(durable||!same(p,tid,t),"write without durable owner identity");
        }
        auto owned=[](const std::string& g){return g=="/ZUIopt"||g.rfind("/ZUIopt/",0)==0;};
        // Cross-check physical membership, including lease-covered children not
        // yet discovered by the foreground scan. Recheck stale enumeration before
        // declaring unknown ownership; Android may have moved/exited that TID.
        auto cover=[&](){
            std::set<int> ours;bool added=false;auto paths=groups();paths.insert(root);
            for(auto& path:paths)for(int tid:members(path)){
                auto id=identity(tid);if(!id.start)continue;
                auto current=group(tid);if(normalGroup(current))continue;
                if(!owned(current)){
                    if(identity(tid).start!=id.start)continue;
                    throw std::runtime_error("background physical owner unavailable");
                }
                auto e=journal.entries.find(tid);OwnerRecord r;
                if(e!=journal.entries.end()&&journal.same(e->second)){
                    r=e->second;require(!journal.placementVersion||journal.placementMatches(r,current),"background unknown owned task");
                }
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
                    if(identity(tid).start!=id.start||normalGroup(group(tid)))continue;
                    throw std::runtime_error("background unknown owned task");
                }
                if(journal.belongs(r,p))ours.insert(tid);
            }
            if(added)journal.commit(); // Durable before the first inherited write.
            return ours;
        };
        cover();
        bool pending=false;
        for(auto& entry:journal.entries){auto tid=entry.first;auto& r=entry.second;
            if(!journal.belongs(r,p)||!journal.same(r))continue;
            WaltKernel waltKernel{[&](int target){return selectWalt(target);}};
            auto waltResult=restoreWalt(r.walt,tid,waltKernel,[&]{journal.commit();},[&]{return journal.same(r);});
            require(waltResult!=SchedulerResult::UNAVAILABLE,"background WALT restore unresolved");
            SchedulerKernel kernel;
            auto schedule=restoreScheduler(r.scheduler,tid,kernel,[&]{journal.commit();},[&]{return journal.same(r);});
            require(schedule!=SchedulerResult::UNAVAILABLE,"background scheduler restore unresolved");
            journal.validate(r);
            auto g=group(tid);if(!journal.same(r))continue;
            if(owned(g)){
                // Open first, then revalidate. An Android handoff during open or
                // earlier observation must not move an external task backwards.
                int fd=open(("/dev/cpuset"+r.savedGroup+"/tasks").c_str(),O_WRONLY|O_CLOEXEC|O_NOFOLLOW);
                require(fd>=0,"background release destination open");
                bool moved=true;int moveError=0;
                try{if(journal.same(r)&&owned(group(tid))&&(!journal.placementVersion||journal.placementMatches(r,group(tid)))){
                    auto value=std::to_string(tid);auto n=::write(fd,value.data(),value.size());
                    moved=n==static_cast<ssize_t>(value.size());if(!moved)moveError=n<0?errno:EIO;
                }}catch(...){close(fd);throw;}
                close(fd);
                if(!moved&&journal.same(r)&&owned(group(tid))){
                    require(moveError==ESRCH||moveError==ENOENT||moveError==EINTR||moveError==EAGAIN||moveError==EBUSY,"background owned release failed");
                    pending=true;
                }
                g=group(tid);
            }
            if(!journal.same(r))continue;
            if(owned(g)){pending=true;continue;}
            if(g.empty()){pending=true;continue;}
            require(normalGroup(g),"invalid Android release owner");
            auto t=p.tasks.find(tid);
            // No known last-applied/inherited constraint: no guessed affinity write.
            auto mask=affinity(tid);if(!journal.same(r))continue;
            Mask applied=t!=p.tasks.end()&&t->second.generation==r.threadStart?t->second.appliedMask:
                journal.placementVersion&&mask==r.appliedMask?r.appliedMask:journal.placementVersion&&mask==r.priorMask?r.priorMask:0;
            if(!mask){pending=true;continue;}
            if(!applied||mask!=applied||mask==r.savedMask)continue;
            auto allowedText=read("/dev/cpuset"+g+"/cpus");
            if(trim(allowedText).empty()){pending=true;continue;}
            Mask allowed=cpus(allowedText);
            Mask restoreMask=r.savedMask&allowed&cpus(read("/sys/devices/system/cpu/online"));
            require(restoreMask!=0,"background unsafe affinity destination");
            // Restore only our exact residue, constrained by the CURRENT Android
            // group. A changed Android mask/group wins; no saved-cpuset rewrite.
            if(journal.same(r)&&group(tid)==g&&journal.same(r)&&affinity(tid)==applied){
                if(!setAffinity(tid,restoreMask)){
                    int error=errno;
                    if(journal.same(r)&&affinity(tid)==applied){
                        require(error!=EACCES&&error!=EPERM,"background affinity release denied");pending=true;
                    }
                }
            }
        }
        auto remaining=cover();
        bool residue=false;
        // Fresh read proves completion; never clear on the basis of successful
        // writes alone. Heterogeneous Android groups/masks are legitimate here.
        for(auto& [tid,r]:journal.entries){
            if(!journal.belongs(r,p)||!journal.same(r))continue;
            auto g=group(tid);auto mask=affinity(tid);if(!journal.same(r))continue;
            if(owned(g)){remaining.insert(tid);continue;}
            if(g.empty()||!mask){residue=true;continue;}
            require(normalGroup(g),"invalid Android release owner");
            auto t=p.tasks.find(tid);
            Mask applied=t!=p.tasks.end()&&t->second.generation==r.threadStart?t->second.appliedMask:
                journal.placementVersion&&mask==r.appliedMask?r.appliedMask:journal.placementVersion&&mask==r.priorMask?r.priorMask:0;
            if(applied&&mask==applied){
                auto allowedText=read("/dev/cpuset"+g+"/cpus");
                if(trim(allowedText).empty()){residue=true;continue;}
                Mask safe=r.savedMask&cpus(allowedText);
                require(safe!=0,"background unsafe affinity destination");
                if(mask!=safe)residue=true;
            }
        }
        if(finalPass)require(remaining.empty(),"background unrecoverable owned task");
        if(pending||!remaining.empty()||residue)return false;
        if(journal.placementVersion){
            // rmdir is the kernel's final emptiness fence; a late fork keeps the lease durable.
            const auto token=lease->second.placementLease;
            for(const auto& path:groups()){
                std::string owner;Mask mask=0;if(!placementGroup(path.substr(11),owner,mask)||owner!=token)continue;
                if(!members(path).empty())return false;
                if(rmdir(path.c_str())!=0){
                    int error=errno;if(error==EBUSY||error==EINTR||error==EAGAIN)return false;
                    require(error==ENOENT,"cpuset child removal");
                }
                created.erase(path);
            }
            for(const auto& path:groups()){
                std::string owner;Mask mask=0;if(placementGroup(path.substr(11),owner,mask)&&owner==token)return false;
            }
        }
        for(auto it=journal.entries.begin();it!=journal.entries.end();)
            if(journal.belongs(it->second,p))it=journal.entries.erase(it);else ++it;
        journal.leases.erase(p.pid);journal.commit();retireRealtimeGlobal();retireWaltGlobal();p.tasks.clear();p.transition(Ownership::ANDROID_OWNED);discardAcquisition(p);
        p.releaseParked=false;p.releaseBlocked=false;p.releaseRearmed=false;p.coherenceEpisodes=0;
        count.releases++;return true;
    }
    void release(ProcessState& p,ReleaseCause cause=ReleaseCause::RELOAD_OR_CONTROLLED_STOP,int64_t time=now()){
        if(cause==ReleaseCause::AUTHORITY_BACKGROUND&&!p.activity_foreground)p.releaseAuthorityForeground=false;
        if(p.managed()){
            if(!p.backgroundReleasing()){
                p.transition(Ownership::RELEASING);p.releaseParked=false;p.releaseBlocked=false;p.releaseRearmed=false;p.releaseStarted=time;p.releaseStep=0;p.next=time;
                p.acquireFinalConfirmation=false;p.baselineCandidate={};
                p.burst=coherenceSchedule.size();p.repairStep=repairSchedule.size();
            }
            bool terminal=cause!=ReleaseCause::AUTHORITY_BACKGROUND;
            if(!terminal&&(p.releaseParked||p.next>time))return;
            bool last=terminal||time>=p.releaseStarted+backgroundReleaseSchedule.back();
            if(backgroundPass(p,terminal))return;
            if(last){
                // A deadline alone is not evidence of permanent write failure.
                // Known journal-covered contention keeps the daemon alive, with
                // no further timer. Explicit permanent errors fail closed above.
                // Catch/controlled-stop may retain durable external-only
                // evidence for strict startup recovery. Do not turn a transient
                // observation delay into a second fatal/status3.
                p.releaseParked=true;p.releaseBlocked=p.releaseRearmed&&p.activity_foreground;return;
            }
            do{++p.releaseStep;}while(p.releaseStep<backgroundReleaseSchedule.size()&&p.releaseStarted+backgroundReleaseSchedule[p.releaseStep]<=time);
            p.next=p.releaseStarted+backgroundReleaseSchedule[p.releaseStep];return;
        }
        if(!p.managed()){requireUncommitted(p);discardAcquisition(p);p.coherenceEpisodes=0;return;}
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
