// Portable logical scan model with verbatim production same/apply/physicalRevoke,
// authority/ownership types and finishScan. Not a real Linux proc or device test.
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <locale>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef _WIN32
#include <io.h>
#endif
void require(bool b,const char* why){if(!b)throw std::runtime_error(why);}
#include "definitions.h"
namespace fs=std::filesystem;
enum Op {STAT,UID,GROUP,AFFINITY,SCHEDSTAT,ENUM,OP_COUNT};
struct Observations {
    std::array<int,OP_COUNT> n{};int finalIdentity=0,initialIdentity=0;
    int total()const{int sum=0;for(int v:n)sum+=v;return sum;}
    int mandatory()const{return initialIdentity+finalIdentity+n[GROUP]+n[AFFINITY];}
};
namespace Kernel {
struct Live {uint64_t start=10;std::string group="/ZUIopt/7c";Mask mask=0x7c;};
std::map<int,Live> tasks;
int user=10001,writes=0,openStreams=0,maxOpenStreams=0,probeReads=0;
uint64_t epoch=1;bool physicalRead=false,fileBacked=false;
Observations ops;std::function<void()> afterEarlyIdentity,beforePhysical,afterPhysical,beforeTarget;
fs::path sample;volatile uint64_t sink=0;
void observe(Op op){
    ++ops.n[op];
    if(op==STAT||op==UID){
        if(physicalRead)++ops.finalIdentity;
        if(probeReads>0){++ops.initialIdentity;--probeReads;}
        if(op==UID&&physicalRead)physicalRead=false;
    }
    if(!fileBacked)return;
    // Identical cached host file backing for each logical read. UID models a
    // metadata query; getaffinity stays synthetic (never modifies host affinity).
    if(op==UID){require(fs::is_regular_file(fs::status(sample)),"metadata fixture");return;}
    if(op==AFFINITY){sink=sink+1;return;}
    if(op==ENUM){for(const auto& item:fs::directory_iterator(sample.parent_path()))sink=sink+item.path().native().size();return;}
    {std::ifstream in(sample,std::ios::binary);require(bool(in),"observation open");
        ++openStreams;maxOpenStreams=std::max(maxOpenStreams,openStreams);
        char bytes[256];in.read(bytes,sizeof(bytes));sink=sink+static_cast<uint64_t>(in.gcount());}
    --openStreams;
}
void reset(int n){
    tasks.clear();for(int i=0;i<n;i++)tasks.emplace(42+i,Live{});
    user=10001;epoch=1;writes=0;physicalRead=false;probeReads=0;ops={};afterEarlyIdentity={};beforePhysical={};afterPhysical={};beforeTarget={};
}
}
Identity identity(int pid,int tid=0){
    Kernel::observe(STAT);auto it=Kernel::tasks.find(tid?tid:pid);
    if(!Kernel::tasks.count(pid)||it==Kernel::tasks.end())return {};
    // Real Candidate1 parser on a proc-stat record, current synthetic generation.
    std::string text=std::to_string(tid?tid:pid)+" (fixture) S";
    for(int i=1;i<19;i++)text+=" 0";
    text+=' ';text+=std::to_string(it->second.start);
    return statIdentity(text);
}
int uid(int pid){
    Kernel::observe(UID);auto result=Kernel::tasks.count(pid)?Kernel::user:-1;
    auto callback=Kernel::afterEarlyIdentity;Kernel::afterEarlyIdentity={};if(callback)callback();return result;
}
std::string group(int tid){
    auto callback=Kernel::beforePhysical;Kernel::beforePhysical={};if(callback)callback();
    Kernel::observe(GROUP);auto it=Kernel::tasks.find(tid);return it==Kernel::tasks.end()?"":it->second.group;
}
Mask affinity(int tid){
    Kernel::observe(AFFINITY);auto callback=Kernel::afterPhysical;Kernel::afterPhysical={};if(callback)callback();
    Kernel::physicalRead=true;auto it=Kernel::tasks.find(tid);return it==Kernel::tasks.end()?0:it->second.mask;
}
int64_t now(){return 100000;}
std::string cpuText(Mask m){return std::to_string(m);}
bool write(const std::string&,const std::string&,AuthorityFence* authority){fence(authority);++Kernel::writes;return true;}
bool setAffinity(int,Mask){++Kernel::writes;return true;}
#define ZUIOPT_NOTE(a,b) do {if(false)std::cerr<<(a)<<(b);}while(0)
struct Journal {
    std::map<int,OwnerRecord> entries;
    std::string boot="boot",currentBoot="boot";
    const std::string& currentBootId()const{return currentBoot;}
#include "journal_same.h"
};
struct PlacementBase {
    Journal& journal;Counters count;
    explicit PlacementBase(Journal& j):journal(j){}
    std::string target(Mask m){auto callback=Kernel::beforeTarget;Kernel::beforeTarget={};if(callback)callback();return "/dev/cpuset/ZUIopt/"+std::to_string(m);}
};
struct Candidate1:PlacementBase {using PlacementBase::PlacementBase;
#include "baseline.h"
};
struct Candidate2:PlacementBase {using PlacementBase::PlacementBase;
#include "candidate.h"
};
struct Fixture {
    ProcessState p;Journal journal;
    explicit Fixture(int n){
        Kernel::reset(n);p.pid=42;p.uid=10001;p.generation=10;
        p.ownership=Ownership::ZUIOPT_OWNED;p.activity_foreground=true;
        p.burst=coherenceSchedule.size();p.repairStep=repairSchedule.size();
        for(const auto& [tid,_]:Kernel::tasks){Task t;t.generation=10;t.owned=true;t.appliedMask=0x7c;t.verified=now();t.comm="mature";
            p.tasks[tid]=t;journal.entries[tid]=OwnerRecord{42,10001,tid,10,10,"game","game","/top-app",255};}
    }
};
// Pinned Core::scan mature branch: entry identity, enum, discovery identities,
// R live rank reads, in-memory prune/prepare, real apply, real finishScan. Rank
// selection/renames and full reactor/release are qualified by existing Linux CI.
template<class Owner> Observations scan(Fixture& f,int ranked){
    Owner owner(f.journal);const auto token=Kernel::epoch;
    AuthorityFence guard{[&]{return Kernel::epoch==token;},[]{return true;}};
    Kernel::ops={};Kernel::physicalRead=false;guard.check();
    require(identity(f.p.pid).start==f.p.generation,"scan entry identity");
    Kernel::observe(ENUM);
    for(const auto& [tid,t]:f.p.tasks){guard.check();require(identity(f.p.pid,tid).start==t.generation,"discovery identity");if(ranked-->0)Kernel::observe(SCHEDSTAT);}
    guard.check();require(!forceCoherence(f.p),"not mature");guard.check();
    for(const auto& [tid,t]:f.p.tasks){const auto& r=f.journal.entries.at(tid);
        require(r.pid==f.p.pid&&r.user==f.p.uid&&r.processStart==f.p.generation&&r.threadStart==t.generation,"prepare tuple");}
    guard.check();
    for(auto& [tid,t]:f.p.tasks){guard.check();Kernel::probeReads=3;owner.apply(f.p,tid,t,0x7c,"default",&guard);}
    finishScan(f.p,now());require(f.p.next==now()+1000,"scan period changed");
    require(Kernel::writes==0&&Kernel::openStreams==0,"steady write/FD leak");return Kernel::ops;
}
void counts(){
    for(int n:{1,64,128,256,320})for(int rank:{0,1}){
        Fixture a(n);auto x=scan<Candidate1>(a,rank);
        Fixture b(n);auto y=scan<Candidate2>(b,rank);
        require(x.n==std::array<int,OP_COUNT>{1+7*n,3*n,n,n,rank,1},"baseline observation count");
        require(y.n==std::array<int,OP_COUNT>{1+5*n,2*n,n,n,rank,1},"candidate observation count");
        require(x.mandatory()==8*n&&y.mandatory()==8*n&&x.finalIdentity==3*n&&y.finalIdentity==3*n,"fresh fence count changed");
        std::cout<<"COUNT N="<<n<<" R="<<rank<<" BASELINE=";for(int v:x.n)std::cout<<v<<',';
        std::cout<<" CANDIDATE=";for(int v:y.n)std::cout<<v<<',';
        std::cout<<" TOTAL="<<x.total()<<','<<y.total()<<" MANDATORY="<<x.mandatory()<<','<<y.mandatory()<<'\n';
    }
}
template<class Owner> std::string race(int mode,bool writePath){
    Fixture f(2);Owner owner(f.journal);const auto token=Kernel::epoch;
    AuthorityFence guard{[&]{return Kernel::epoch==token;},[]{return true;}};
    bool injected=false;
    auto inject=[&]{injected=true;switch(mode){
        case 0:Kernel::tasks.erase(42);break;
        case 1:Kernel::tasks.at(42).start++;break;
        case 2:Kernel::tasks.at(43).start++;break;
        case 3:Kernel::user++;break;
        case 4:case 5:case 6:case 7:++Kernel::epoch;break;
        case 8:f.journal.entries.at(43).threadStart++;break;
        case 9:Kernel::tasks.at(43).group="/background";Kernel::tasks.at(43).mask=3;break;
    }};
    // Inject durable corruption just after the early identity's UID read, not
    // before the scan. Physical changes occur before fresh physical sampling.
    if(mode==8)Kernel::afterEarlyIdentity=inject;
    else if(mode==9)Kernel::beforePhysical=inject;
    else Kernel::afterPhysical=inject;
    std::string outcome="returned";
    try{owner.apply(f.p,43,f.p.tasks.at(43),writePath?0x80:0x7c,"race",&guard);guard.check();}
    catch(const PhysicalRevoke&){outcome="revoked";}
    catch(const StaleAuthorityScan&){outcome="epoch_cancelled";}
    catch(const std::runtime_error& e){require(std::string(e.what())=="write without durable owner identity","unexpected exception");outcome="journal_rejected";}
    require(injected&&Kernel::writes==0,"missed injection or unsafe write");
    if(mode<4)require(Kernel::ops.finalIdentity>0,"missing final live identity");
    if(mode>=4&&mode<=7)require(outcome=="epoch_cancelled","authority did not cancel");
    if(mode==8)require(outcome=="journal_rejected","journal mismatch ignored");
    if(mode==9)require(outcome=="revoked"&&f.p.revoked()&&!f.p.next,"physical change ignored");
    require(f.journal.entries.size()==2,"journal prematurely cleared");
    return outcome;
}
void temporal(){
    const char* names[]={"PID_EXIT_AFTER_SNAPSHOT","PID_REUSE_STARTTIME","TID_REUSE","UID_CHANGE","AUTHORITY_EPOCH_CHANGE","HOME_REVOKE","BACKGROUND_TRANSITION","SLEEP_WAKE","JOURNAL_GENERATION_MISMATCH","PHYSICAL_STATE_CHANGE_AFTER_EARLY_OBSERVATION"};
    for(int mode=0;mode<10;mode++)for(bool writePath:{false,true}){
        auto x=race<Candidate1>(mode,writePath),y=race<Candidate2>(mode,writePath);
        require(x==y,"baseline/candidate temporal mismatch");
        std::cout<<names[mode]<<"=PASS PATH="<<(writePath?"would_write":"cached")<<" OUTCOME="<<y<<'\n';
    }
    // All original write fences also remain live after target creation.
    for(int mode=0;mode<4;mode++){
        Fixture f(2);Candidate2 owner(f.journal);AuthorityFence guard{[]{return Kernel::epoch==1;},[]{return true;}};
        Kernel::beforeTarget=[&]{if(mode==0)Kernel::tasks.at(42).start++;if(mode==1)Kernel::tasks.at(43).start++;if(mode==2)Kernel::user++;if(mode==3)++Kernel::epoch;};
        try{owner.apply(f.p,43,f.p.tasks.at(43),0x80,"write",&guard);}catch(const StaleAuthorityScan&){}
        require(Kernel::writes==0,"post-target identity/epoch bypass");
    }
    // Second invocation never retains the previous valid identity observation.
    for(int mode=0;mode<4;mode++){
        Fixture f(2);Candidate2 owner(f.journal);require(!owner.physicalRevoke(f.p,43,f.p.tasks.at(43)),"initial probe");
        auto before=Kernel::ops.n[STAT];
        if(mode==0)Kernel::tasks.erase(42);if(mode==1)Kernel::tasks.at(42).start++;if(mode==2)Kernel::tasks.at(43).start++;if(mode==3)Kernel::user++;
        require(!owner.physicalRevoke(f.p,43,f.p.tasks.at(43))&&Kernel::ops.n[STAT]>before,"cross-invocation identity cache");
    }
    for(bool maskOnly:{false,true}){
        Fixture f(2);Candidate2 owner(f.journal);
        Kernel::beforePhysical=[&]{if(maskOnly)Kernel::tasks.at(43).mask=3;else Kernel::tasks.at(43).group="/background";};
        require(owner.physicalRevoke(f.p,43,f.p.tasks.at(43))&&f.p.revoked()&&Kernel::writes==0,"physical component cache");
    }
    std::cout<<"TEMPORAL_CASES=10;PATH_VARIANTS=20;POST_TARGET_FENCES=4;CROSS_SCAN_CACHE=NO;FAILED_TEMPORAL_CASES=NONE\n";
}
unsigned handles(){
#ifdef _WIN32
    // Count actual CRT file descriptors, not unrelated Windows loader/security
    // event/thread handles (which can initialize asynchronously during a run).
    auto previous=_set_invalid_parameter_handler([](const wchar_t*,const wchar_t*,const wchar_t*,unsigned,uintptr_t){});
    unsigned n=0;for(int fd=0;fd<_getmaxstdio();fd++)if(_get_osfhandle(fd)!=-1)++n;
    _set_invalid_parameter_handler(previous);return n;
#else
    unsigned n=0;for(const auto& entry:fs::directory_iterator("/proc/self/fd")){(void)entry;++n;}return n;
#endif
}
template<class Owner> double measured(Fixture& f){
    auto start=std::chrono::steady_clock::now();for(int i=0;i<3;i++)scan<Owner>(f,1);
    return std::chrono::duration<double,std::micro>(std::chrono::steady_clock::now()-start).count()/3;
}
void benchmark(const fs::path& out){
    Kernel::sample=out/"synthetic-observation.txt";
    {std::ofstream file(Kernel::sample);file<<"42 (fixture) S 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 10\n";}
    Kernel::fileBacked=true;
    {Fixture warm(64);scan<Candidate1>(warm,1);scan<Candidate2>(warm,1);}
    auto before=handles();
    for(int n:{64,128,256,320})for(int round=0;round<7;round++){
        Fixture f(n);double a=0,b=0;
        if(round%2){b=measured<Candidate2>(f);a=measured<Candidate1>(f);}
        else{a=measured<Candidate1>(f);b=measured<Candidate2>(f);}
        require(a>0&&b>0,"invalid benchmark round");
        std::cout<<"BENCH N="<<n<<" ROUND="<<round<<" BASE_US="<<a<<" CAND_US="<<b<<" HANDLES="<<handles()<<'\n';
    }
    auto after=handles();std::cout<<"FD_OR_HANDLE_DIAGNOSTIC="<<before<<','<<after<<" OPEN="<<Kernel::openStreams<<" MAX_OPEN="<<Kernel::maxOpenStreams<<'\n';
    require(after<=before&&Kernel::openStreams==0&&Kernel::maxOpenStreams==1,"FD growth");
    std::cout<<"FD_OR_HANDLE_COUNT="<<before<<','<<after<<";PERSISTENT_OBSERVATION_FD=0;HOST_SCAN_BENCHMARK_IS_NOT_DEVICE_CPU_CLAIM=YES\n";
}
int main(int argc,char** argv){try{
    std::cout<<std::unitbuf;require(argc==2,"evidence directory argument");counts();temporal();benchmark(argv[1]);
    std::cout<<"CANDIDATE2_NEW_TESTS=PASS\n";return 0;
}catch(const std::exception& e){std::cerr<<"SCAN_OBSERVATION_FAIL "<<e.what()<<'\n';return 1;}}
