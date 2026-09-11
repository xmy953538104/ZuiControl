// ZUIopt core: dependency-free explicit config, generation-safe proc access and placement.
#pragma once
#include "ZUIopt_model.h"
#include <algorithm>
#include <array>
#include <bitset>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <functional>
#include <iomanip>
#include <map>
#include <set>
#include <sstream>
#include <sys/stat.h>
#include <sched.h>
#include <unistd.h>

namespace ZUIopt {
// Local control flow, never a daemon fatal. Callback threads only publish epochs.
struct StaleAuthorityScan {};
struct AuthorityFence {
    std::function<bool()> current,foreground;
    bool stale=false,background=false,revalidated=false;
    void check(){if(stale||!current()){stale=true;throw StaleAuthorityScan{};}}
    void drift(){
        check();
        if(!revalidated){revalidated=true;bool valid=foreground();check();
            if(!valid){background=true;stale=true;throw StaleAuthorityScan{};}}
    }
};
inline void fence(AuthorityFence* authority){if(authority)authority->check();}
using Mask=uint64_t;
inline int64_t now(){return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();}
inline bool ZUIOPT_debug=false;
#ifdef ZUIOPT_PRODUCTION
inline void productionNote(const char* event){
    // One bounded lifecycle diagnostic per second; never emit task names/IDs.
    static int64_t last=-1000;
    if(strcmp(event,"READY")&&strcmp(event,"FATAL")&&strcmp(event,"STOPPED")&&
       strcmp(event,"RELOAD_OK")&&strcmp(event,"RELOAD_REJECTED")&&
       strcmp(event,"RELEASE_BLOCKER")&&strcmp(event,"CLEANUP_DEFERRED"))return;
    if(now()-last<1000)return;
    last=now();printf("ZUIopt %.24s\n",event);
}
// sizeof type-checks diagnostics without evaluating or allocating their detail strings.
#define ZUIOPT_NOTE(event, detail) do { (void)sizeof(detail); productionNote(event); } while(false)
#else
inline void note(const char* event,const std::string& detail){
    if(!ZUIOPT_debug&&(std::string(event)=="DISCOVER"||std::string(event)=="RENAME_CHECK"||std::string(event)=="PLACE"||std::string(event)=="RELEASE"||std::string(event)=="SCAN"||std::string(event)=="SNAPSHOT"||std::string(event)=="EVENT"||std::string(event)=="REPRESENTATIVE"))return;
    printf("%lld %s %s\n",static_cast<long long>(now()),event,detail.c_str());
}
#define ZUIOPT_NOTE(event, detail) note(event, detail)
#endif
struct ProcError:std::runtime_error {
    int code;
    ProcError(int value,const char* why):std::runtime_error(why),code(value){}
    bool permission() const noexcept {return code==EACCES||code==EPERM;}
};
inline std::string read(const std::string& path,bool strict=false){
    int fd=open(path.c_str(),O_RDONLY|O_CLOEXEC);if(fd<0){int error=errno;if(strict&&error!=ENOENT&&error!=ESRCH)throw ProcError(error,"proc identity open failed");return {};}
    std::string out;char b[4096];ssize_t n;int error=0;
    while((n=::read(fd,b,sizeof(b)))!=0){if(n<0&&errno==EINTR)continue;if(n<0){error=errno;break;}out.append(b,n);if(out.size()>65536){out.clear();n=-1;error=EOVERFLOW;break;}}
    close(fd);if(strict&&n!=0){if(error==ENOENT||error==ESRCH)return {};throw ProcError(error,"proc identity read failed");}return out;
}
inline bool write(const std::string& path,const std::string& value,AuthorityFence* authority=nullptr){
    int fd=open(path.c_str(),O_WRONLY|O_CLOEXEC);if(fd<0)return false;
    try{fence(authority);}catch(...){close(fd);throw;}
    ssize_t n=::write(fd,value.data(),value.size());int e=errno;close(fd);errno=e;return n==static_cast<ssize_t>(value.size());
}
inline std::string trim(std::string s){auto p=s.find_last_not_of(" \r\n\t");if(p==s.npos)return {};s.erase(p+1);p=s.find_first_not_of(" \r\n\t");return s.substr(p);}
inline int number(const std::string& s){size_t n=0;int v=std::stoi(s,&n);require(n==s.size(),"integer syntax");return v;}
inline Mask cpus(std::string s){
    s=trim(s);require(!s.empty(),"empty CPUs");Mask mask=0;std::istringstream in(s);std::string x;
    while(std::getline(in,x,',')){
        auto at=x.find('-');int a=number(x.substr(0,at)),b=at==x.npos?a:number(x.substr(at+1));
        require(a>=0&&b>=a&&b<64,"CPU range");for(int c=a;c<=b;c++)mask|=Mask(1)<<c;
    }require(mask!=0&&s.back()!=',',"invalid CPUs");return mask;
}
inline std::string cpuText(Mask m){std::string s;for(int i=0;i<64;i++)if(m&(Mask(1)<<i)){if(!s.empty())s+=',';s+=std::to_string(i);}return s;}
inline Mask affinity(int tid){cpu_set_t set;CPU_ZERO(&set);if(sched_getaffinity(tid,sizeof(set),&set))return 0;Mask m=0;for(int i=0;i<64;i++)if(CPU_ISSET(i,&set))m|=Mask(1)<<i;return m;}
inline bool setAffinity(int tid,Mask m){cpu_set_t set;CPU_ZERO(&set);for(int i=0;i<64;i++)if(m&(Mask(1)<<i))CPU_SET(i,&set);return sched_setaffinity(tid,sizeof(set),&set)==0;}
inline std::vector<int> ids(const std::string& path){
    std::vector<int> v;DIR* d=opendir(path.c_str());if(!d){require(errno==ENOENT||errno==ESRCH,"proc enumeration open failed");return v;}
    int error=0;
    for(;;){errno=0;auto* e=readdir(d);if(!e){error=errno;break;}std::string s=e->d_name;if(!s.empty()&&s.find_first_not_of("0123456789")==s.npos)v.push_back(number(s));}
    closedir(d);require(error==0,"proc enumeration read failed");std::sort(v.begin(),v.end());return v;
}
struct Identity {uint64_t start=0,ticks=0;char state=0;};
inline Identity statIdentity(const std::string& s){
    Identity out;auto n=s.rfind(") ");if(n==s.npos)return out;std::istringstream in(s.substr(n+2));std::vector<std::string> a;std::string t;
    while(in>>t)a.push_back(t);if(a.size()<20)return out;
    try{out.start=std::stoull(a[19]);out.ticks=std::stoull(a[11])+std::stoull(a[12]);out.state=a[0][0];}catch(...){return {};}
    if(out.state=='Z'||out.state=='X')out.start=0;return out;
}
inline Identity identity(int pid,int tid=0){auto text=read("/proc/"+std::to_string(pid)+(tid?"/task/"+std::to_string(tid):"")+"/stat",true);auto id=statIdentity(text);require(text.empty()||id.start||id.state=='Z'||id.state=='X',"proc identity parse failed");return id;}
inline std::string group(int tid){std::istringstream in(read("/proc/"+std::to_string(tid)+"/cgroup"));std::string s;while(std::getline(in,s)){auto at=s.find(":cpuset:");if(at!=s.npos)return s.substr(at+8);}return {};}
inline bool normalGroup(const std::string& s){return !s.empty()&&s[0]=='/'&&s.find("..") == s.npos&&s.find("/ZUIopt")==s.npos&&s.find_first_not_of("/abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.")==s.npos;}
inline int uid(int pid){struct stat s{};if(::stat(("/proc/"+std::to_string(pid)).c_str(),&s)){int error=errno;if(error!=ENOENT&&error!=ESRCH)throw ProcError(error,"proc UID stat failed");return -1;}return s.st_uid;}
inline std::string processName(int pid,bool strict=false){auto s=read("/proc/"+std::to_string(pid)+"/cmdline",strict);s.resize(s.find('\0')==s.npos?s.size():s.find('\0'));return s;}
inline std::string packageName(std::string s){auto at=s.find(':');if(at!=s.npos)s.resize(at);return s;}
inline bool match(const std::string& kind,const std::string& pattern,const std::string& value){
    if(kind=="exact")return value==pattern;if(kind=="prefix")return value.rfind(pattern,0)==0;if(kind=="contains")return value.find(pattern)!=value.npos;return false;
}
inline bool matchKind(const std::string& s){return s=="exact"||s=="prefix"||s=="contains";}
struct GlobToken {char kind=0;std::bitset<128> chars;};
inline std::vector<GlobToken> compileGlob(const std::string& pattern){
    require(!pattern.empty()&&pattern.size()<=64,"glob size");std::vector<GlobToken> tokens;
    for(size_t i=0;i<pattern.size();i++){
        unsigned char c=pattern[i];require(c>=32&&c<127&&c!='\\',"glob character");GlobToken t;
        if(c=='*'||c=='?')t.kind=c;
        else if(c=='['){
            t.kind='c';bool any=false;
            while(++i<pattern.size()&&pattern[i]!=']'){
                unsigned char a=pattern[i],b=a;require(a>=32&&a<127&&a!='['&&a!='\\'&&a!='!'&&a!='^',"glob class");
                if(i+1<pattern.size()&&pattern[i+1]=='-'){
                    require(i+2<pattern.size()&&pattern[i+2]!=']',"glob range");b=pattern[i+2];i+=2;
                    require(b>=a&&b<127&&b!='['&&b!='\\',"glob range order");
                }
                for(unsigned n=a;n<=b;n++)t.chars.set(n);any=true;
            }require(i<pattern.size()&&any,"unclosed/empty glob class");
        }else{require(c!=']',"unpaired glob close");t.kind='c';t.chars.set(c);}
        tokens.push_back(t);
    }return tokens;
}
inline bool globMatch(const std::vector<GlobToken>& tokens,const std::string& value){
    if(value.size()>64)return false;
    // Bounded DP, O(64*64), no backtracking or regular-expression engine.
    std::array<bool,65> prev{};prev[0]=true;
    for(auto& t:tokens){std::array<bool,65> next{};next[0]=t.kind=='*'&&prev[0];
        for(size_t j=1;j<=value.size();j++){unsigned char c=value[j-1];
            next[j]=t.kind=='*'?(prev[j]||next[j-1]):prev[j-1]&&(t.kind=='?'||(c<128&&t.chars[c]));
        }prev=next;
    }return prev[value.size()];
}
inline bool label(const std::string& s){return !s.empty()&&s.size()<=128&&s.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-")==s.npos;}
inline bool packageLabel(const std::string& s){
    if(s.empty()||s.size()>255||s.find('.')==s.npos)return false;
    bool first=true;
    for(char c:s){if(c=='.'){if(first)return false;first=true;continue;}
        if(first&&!(c>='a'&&c<='z')&&!(c>='A'&&c<='Z'))return false;
        if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'))return false;
        first=false;
    }return !first;
}
inline bool authoritativeIdentity(const Snapshot& s,const std::vector<std::string>& uidPackages){
    if(s.uid<10000||s.uid>=20000||s.packages.size()!=1||uidPackages.size()!=1)return false; // USER0_ONLY, nonisolated ordinary App IDs.
    auto& p=s.packages[0];if(!packageLabel(p)||uidPackages[0]!=p)return false;
    if(s.name==p)return true;
    return s.name.rfind(p+":",0)==0&&label(s.name.substr(p.size()+1));
}
struct Rule {std::string cls,kind,pattern;int priority=0;Mask mask=0;unsigned rank=0;std::vector<GlobToken> glob;};
struct Profile {Mask general=0;std::vector<Rule> rules;};
struct Mapping {std::string kind="exact",package,profile;int priority=0;};
struct Config {
    bool enabled=true,debug=false;std::map<std::string,Profile> profiles;std::vector<Mapping> packages;
    const Profile* find(const std::string& package) const {if(!enabled)return nullptr;for(auto& r:packages)if(match(r.kind,r.package,package))return &profiles.at(r.profile);return nullptr;}
};
inline bool eligibleSnapshot(const Snapshot& s,const Config& config){
    // Binder-only rejection before any proc identity/cmdline/UID or package-manager read.
    return s.uid>=10000&&s.uid<20000&&s.packages.size()==1&&
        packageLabel(s.packages[0])&&config.find(s.packages[0]);
}
inline Identity managedIdentity(const Snapshot& s,const Config& config,
                                Identity (*probe)(int,int)=identity){
    if(!eligibleSnapshot(s,config))return {};
    return probe(s.pid,0);
}
inline Config parseConfig(const std::string& text,Mask available){
    require(!text.empty()&&text.size()<=65536&&text.find('\0')==text.npos,"config size/NUL");Config c;bool seenEnabled=false,seenSchema=false,seenDebug=false;std::istringstream all(text);std::string line;
    while(std::getline(all,line)){
        line=trim(line);if(line.empty()||line[0]=='#')continue;require(line.size()<1024,"line length");
        std::istringstream in(line);std::string kind,extra;in>>kind;
        if(kind=="schema"){std::string v;in>>v;require(!seenSchema&&v=="2","schema version");seenSchema=true;}
        else if(kind=="enabled") {std::string v;in>>v;require(!seenEnabled&&(v=="true"||v=="false"),"enabled value");seenEnabled=true;c.enabled=v=="true";}
        else if(kind=="debug"){std::string v;in>>v;require(!seenDebug&&(v=="true"||v=="false"),"debug value");seenDebug=true;c.debug=v=="true";}
        else if(kind=="profile"){
            std::string name,cpu;in>>name>>cpu;require(label(name)&&!c.profiles.count(name),"duplicate/invalid profile");c.profiles[name].general=cpus(cpu);
        }else if(kind=="thread"){
            std::string name,cpu,priority,selector;Rule r;in>>name>>r.cls>>r.kind>>std::quoted(r.pattern)>>selector>>priority>>cpu;
            require(c.profiles.count(name)&&label(r.cls)&&(matchKind(r.kind)||r.kind=="glob")&&(!r.pattern.empty()||r.kind=="contains")&&r.pattern.size()<=64,"thread rule");
            require(r.pattern.find_first_of("\r\n\t") == r.pattern.npos,"thread control character");
            if(selector!="selector=all"){
                require(selector.rfind("selector=rank:",0)==0,"explicit selector required");int rank=number(selector.substr(14));
                require(rank>=1&&rank<=1024,"rank bound");r.rank=static_cast<unsigned>(rank);
            }
            if(r.kind=="glob")r.glob=compileGlob(r.pattern);
            r.priority=number(priority);r.mask=cpus(cpu);auto& rules=c.profiles[name].rules;
            for(auto& old:rules)require(old.priority!=r.priority&&(old.cls!=r.cls||(old.mask==r.mask&&old.rank==r.rank)),"ambiguous thread rules/group selector");rules.push_back(r);
        }else if(kind=="package"){
            Mapping r;std::string priority;in>>r.kind>>r.package>>r.profile>>priority;
            require(matchKind(r.kind)&&label(r.package)&&(r.kind!="exact"||packageLabel(r.package))&&c.profiles.count(r.profile),"package mapping");r.priority=number(priority);
            for(auto& old:c.packages)require(old.priority!=r.priority&&(old.kind!=r.kind||old.package!=r.package),"package priority tie/duplicate");c.packages.push_back(r);
        }else throw std::runtime_error("unknown config directive: "+kind);
        require(!(in>>extra),"extra config fields");require(c.packages.size()<=512&&c.profiles.size()<=64,"config bound");
    }
    require(seenSchema&&seenEnabled,"missing schema/enabled header");
    for(auto& [_,p]:c.profiles){require((p.general&available)==p.general,"unavailable default CPU");require(p.rules.size()<=32,"rule count");for(auto& r:p.rules)require((r.mask&available)==r.mask,"unavailable override CPU");std::sort(p.rules.begin(),p.rules.end(),[](auto& a,auto& b){return a.priority>b.priority;});}
    std::sort(c.packages.begin(),c.packages.end(),[](auto& a,auto& b){return a.priority>b.priority;});return c;
}
inline const Rule* classify(const Profile& p,const std::string& name){for(auto& r:p.rules)if(r.kind=="glob"?globMatch(r.glob,name):match(r.kind,r.pattern,name))return &r;return nullptr;}
struct RankedTask {uint64_t score=0;int tid=0;};
inline int representative(std::vector<RankedTask>& candidates,unsigned rank){
    if(rank==0||candidates.size()<rank)return 0; // SAFE_INTENTIONAL_LEGACY_DEVIATION: no arbitrary fallback.
    std::sort(candidates.begin(),candidates.end(),[](auto& a,auto& b){return a.score!=b.score?a.score>b.score:a.tid<b.tid;});
    return candidates[rank-1].tid;
}
struct Counters {uint64_t events=0,snapshots=0,scans=0,comm=0,schedstat=0,placements=0,releases=0,wakeups=0,reloads=0;};
struct Task {
    uint64_t generation=0;std::string comm,savedGroup;Mask savedMask=0,appliedMask=0;int64_t discovered=0,renamed=0,verified=0;unsigned renameStage=0;bool owned=false;
};
struct BaselineCandidate {
    std::string group;Mask mask=0;int64_t firstStableAt=0,lastStableAt=0;
    uint64_t generation=0,epoch=0;int uid=0;
};
inline constexpr std::array<int,14> coherenceSchedule={100,250,500,1000,1500,2000,2500,3000,3500,4000,4500,5000,5500,6000};
inline constexpr std::array<int,4> repairSchedule={100,250,500,1000};
enum class ReleaseCause {AUTHORITY_BACKGROUND,PROCESS_DEATH,RELOAD_OR_CONTROLLED_STOP,CRASH_RECOVERY,COHERENCE_RELINQUISH};
inline constexpr std::array<int,5> backgroundReleaseSchedule={0,50,100,250,500};
struct ProcessState {
    int pid=0,uid=0;std::string name,package;uint64_t generation=0;
    bool activity_foreground=false,alive=true,managed=false;int foreground_service_state=0;
    // managed means a durable lease, never just a foreground acquisition request.
    bool acquiring=false,acquireBlocked=false,acquireFinalConfirmation=false;int64_t acquireStarted=0;size_t acquireStep=0;
    BaselineCandidate baselineCandidate;uint64_t acquisitionEpoch=0;
    unsigned coherenceEpisodes=0;bool coherenceReleasing=false;
    bool backgroundReleasing=false,releaseParked=false,releaseBlocked=false,releaseRearmed=false,releaseAuthorityForeground=false;
    int64_t releaseStarted=0;size_t releaseStep=0;
    int64_t repairedAt=0;size_t repairStep=repairSchedule.size();
    int64_t activated=0,next=0;uint64_t ownershipFloor=0;size_t burst=0;std::string androidGroup;Mask androidMask=0;
    std::map<int,Task> tasks;
};
inline void beginAcquisition(ProcessState& p,int64_t time){
    if(p.backgroundReleasing||p.managed||p.acquiring||p.acquireBlocked)return;
    p.baselineCandidate={};++p.acquisitionEpoch;
    p.acquiring=true;p.acquireFinalConfirmation=false;p.acquireStarted=time;p.acquireStep=0;p.next=time;
}
inline void discardAcquisition(ProcessState& p){
    require(!p.managed,"discard committed ownership");
    for(auto& [_,t]:p.tasks)require(!t.owned,"uncommitted owned task");
    p.tasks.clear();p.acquiring=false;p.acquireFinalConfirmation=false;p.acquireStarted=0;p.acquireStep=0;p.next=0;
    p.androidGroup.clear();p.androidMask=0;p.ownershipFloor=0;
    p.baselineCandidate={};p.burst=coherenceSchedule.size();p.repairStep=repairSchedule.size();
}
// Reuse the finite discovery deadlines; never arm from a periodic snapshot.
inline void armCoherence(ProcessState& p,int64_t time){
    if(!p.managed||p.backgroundReleasing)return;
    p.activated=time;p.burst=0;p.next=time;p.repairStep=repairSchedule.size();
}
inline bool forceCoherence(const ProcessState& p){return p.managed&&!p.backgroundReleasing&&(p.burst<coherenceSchedule.size()||p.repairStep<repairSchedule.size());}
inline void finishScan(ProcessState& p,int64_t time,bool repaired=false){
    // Arm only after successful placement; keep the original horizon and episode budget.
    if(repaired){p.repairedAt=time;p.repairStep=0;}
    while(p.burst<coherenceSchedule.size()&&p.activated+coherenceSchedule[p.burst]<=time)p.burst++;
    while(p.repairStep<repairSchedule.size()&&p.repairedAt+repairSchedule[p.repairStep]<=time)p.repairStep++;
    p.next=p.burst<coherenceSchedule.size()?p.activated+coherenceSchedule[p.burst]:time+1000;
    // One existing reactor deadline, not a second timer or a catch-up loop.
    if(p.repairStep<repairSchedule.size())p.next=std::min(p.next,p.repairedAt+repairSchedule[p.repairStep]);
}
// Shared by the real reactor and native lifecycle fixtures. No observation or
// placement here: a fresh authority can only resume the OLD durable release.
template<class Runtime> void activateAuthority(ProcessState& p,bool wanted,bool newScene,int64_t time,Runtime& runtime){
    bool fresh=wanted&&!p.releaseAuthorityForeground;p.releaseAuthorityForeground=wanted;
    if(!wanted){runtime.release(p);return;}
    if(p.backgroundReleasing){
        // A real foreground edge may arrive just BEFORE the old window parks.
        // Give that epoch its one window too; a delayed scene cannot extend it.
        if(fresh||(p.releaseParked&&newScene)){
            p.releaseParked=false;p.releaseBlocked=false;p.releaseRearmed=true;
            p.releaseStarted=time;p.releaseStep=0;p.next=time;
        }
        return;
    }
    if(newScene)armCoherence(p,time);
    beginAcquisition(p,time);
}
enum class BaselineResult {STABLE,DEFER,STALE};
template<class Runtime> void advanceAcquisition(ProcessState& p,int64_t time,Runtime& runtime){
    // Performance certification ends at 1000ms; safety/liveness does not.
    static constexpr std::array<int,11> schedule={0,100,250,500,750,1000,1250,1500,2000,2500,3000};
    if(!p.acquiring||p.next>time)return;
    // Every due opportunity probes, even when the reactor runs late. Never
    // manufacture DEFER from scheduling latency or replay expired probes.
    auto result=runtime.acquire(p);
    if(result==BaselineResult::STABLE){require(p.managed&&!p.acquiring,"acquisition commit state");return;}
    if(result==BaselineResult::STALE){runtime.release(p);p.alive=false;return;}
    auto& candidate=p.baselineCandidate;
    bool valid=!candidate.group.empty()&&candidate.mask&&candidate.generation==p.generation&&
        candidate.uid==p.uid&&candidate.epoch==p.acquisitionEpoch;
    int64_t confirmation=candidate.firstStableAt+250;
    if(time>=p.acquireStarted+schedule.back()){
        // A late valid candidate gets exactly one confirmation, never a moving
        // hard cap. These are scheduled deadlines, not OS latency guarantees.
        if(!p.acquireFinalConfirmation&&valid&&confirmation>time&&confirmation<=p.acquireStarted+3500){
            p.acquireFinalConfirmation=true;p.next=confirmation;return;
        }
        runtime.release(p);p.acquireBlocked=true;runtime.baselineBlocked();return;
    }
    while(p.acquireStep<schedule.size()&&p.acquireStarted+schedule[p.acquireStep]<=time)++p.acquireStep;
    p.next=p.acquireStarted+schedule[p.acquireStep];
    if(valid&&confirmation>time)p.next=std::min(p.next,confirmation);
}
#ifndef ZUIOPT_PRODUCTION
inline void selftest(){
    require(cpus("2-6")==0x7c&&cpus("7")==0x80,"CPU fixture");
    require(packageName("org.example.app:worker")=="org.example.app","subprocess fixture");
    auto c=parseConfig("schema 2\nenabled true\nprofile G 2-6\nthread G C1 contains Render selector=rank:1 20 2-4\nthread G C2 prefix Game selector=rank:2 10 7\npackage exact org.example.app G 100\n",255);
    require(c.find("org.example.app")&&!c.find("org.example.app.extra"),"exact package fixture");
    require(classify(*c.find("org.example.app"),"RenderThread")->mask==0x1c,"class fixture");
    require(!classify(*c.find("org.example.app"),"Audio"),"default fixture");
    for(auto bad:{"", "enabled true\nprofile G 64\n", "enabled true\nprofile G 2-6\nunknown true\n"}){bool rejected=false;try{parseConfig(bad,255);}catch(...){rejected=true;}require(rejected,"bad config fixture");}
    require(match("prefix","org.","org.a")&&match("contains","Render","WorkerRender")&&!normalGroup("/../unsafe")&&!normalGroup("/ZUIopt/7c"),"match/release fixture");
    puts("ZUIOPT_SELFTEST=PASS");
}
#endif
}
