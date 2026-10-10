// Bounded device test. Independent fair watchdog recovers production journal intent.
#define ZUIOPT_TEST 1
#include "../../native/zuiopt/ZUIopt_owner.h"
#include <csignal>
#include <sys/resource.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <thread>
using namespace ZUIopt;
constexpr const char* STATE="/data/local/tmp/adcb-runtime-owner-a1";
struct Targets {int pid,c1,c2;};
void exactRead(int fd,void* data,size_t size){
    size_t at=0;while(at<size){ssize_t n=::read(fd,static_cast<char*>(data)+at,size-at);require(n>0,"target pipe");at+=static_cast<size_t>(n);}
}
void recover(){
    Journal journal(STATE);journal.load();SchedulerKernel kernel;Counters count;Placement placement(count,journal,nullptr);
    for(auto& entry:journal.entries){auto& r=entry.second;
        bool hadScheduler=r.scheduler.active,hadWalt=r.walt.active;ProcessState p;p.pid=r.pid;
        auto waltResult=placement.walt(p,r.tid,0,0);require(waltResult!=SchedulerResult::UNAVAILABLE,"independent WALT recovery failed");
        if(hadWalt&&journal.same(r))printf("WALT_RESTORED tid=%d kind=%d original=%d expected=%d\n",r.tid,r.walt.kind,r.walt.original,r.walt.expected);
        auto result=restoreScheduler(r.scheduler,r.tid,kernel,[&]{journal.commit();},[&]{return journal.same(r);});
        require(result!=SchedulerResult::UNAVAILABLE,"independent recovery failed");
        SchedulerState observed;if(hadScheduler&&journal.same(r)){require(schedulerState(r.tid,observed)&&observed==r.scheduler.original,"final original mismatch");
            printf("RESTORED tid=%d policy=%u prio=%u flags=%llu nice=%d clamps=%u/%u\n",r.tid,observed.policy,observed.priority,static_cast<unsigned long long>(observed.flags),observed.nice,observed.utilMin,observed.utilMax);}
    }
    placement.restoreGlobals();
    journal.entries.clear();journal.commit();puts("INDEPENDENT_RECOVERY=PASS exact final scheduling and global state");
}
int main(int argc,char** argv){
    setvbuf(stdout,nullptr,_IOLBF,0);signal(SIGPIPE,SIG_IGN);alarm(20);
    try{
        require(getuid()==0,"Root harness");
        if(argc==2&&std::string(argv[1])=="--recover"){recover();return 0;}
        bool walt0=argc==2&&std::string(argv[1])=="--walt0",walt1=argc==2&&std::string(argv[1])=="--walt1";
        require(argc==1||walt0||walt1,"finite harness arguments");
        // Proof BEFORE enabling RT: independent recovery entry can load an empty journal.
        {Journal journal(STATE);journal.load();require(journal.entries.empty()&&journal.globals.empty(),"previous recovery required");}
        recover();
        int ready[2],stop[2];require(pipe(ready)==0&&pipe(stop)==0,"harness pipes");
        pid_t worker=fork();require(worker>=0,"worker fork");
        if(worker==0){
            close(ready[0]);close(stop[1]);alarm(12);
            rlimit limit{20000,50000};require(setrlimit(RLIMIT_RTTIME,&limit)==0,"RT CPU-time safety limit");
            std::atomic<int> a{0},b{0};std::atomic<bool> done{false};
            auto loop=[&](std::atomic<int>& tid,const char* name){prctl(PR_SET_NAME,name,0,0,0);tid=static_cast<int>(syscall(SYS_gettid));
                while(!done){auto until=std::chrono::steady_clock::now()+std::chrono::microseconds(300);
                    while(std::chrono::steady_clock::now()<until){}std::this_thread::sleep_for(std::chrono::milliseconds(2));}};
            std::thread first(loop,std::ref(a),"SyntheticMain"),second(loop,std::ref(b),"SyntheticRender");
            while(!a||!b)std::this_thread::yield();Targets targets{getpid(),a.load(),b.load()};
            require(::write(ready[1],&targets,sizeof(targets))==sizeof(targets),"ready signal");close(ready[1]);
            char value;ssize_t n=::read(stop[0],&value,1);(void)n;done=true;first.join();second.join();_exit(0);
        }
        close(ready[1]);close(stop[0]);Targets targets{};exactRead(ready[0],&targets,sizeof(targets));close(ready[0]);
        // Fair watchdog retains two little CPUs; worker's original mask is unaffected.
        require(setAffinity(getpid(),3),"watchdog affinity");SchedulerState watchdog;
        require(schedulerState(getpid(),watchdog)&&watchdog.policy==SCHED_OTHER,"watchdog remains fair");
        pid_t controller=fork();require(controller>=0,"controller fork");
        if(controller==0){
            Journal journal(STATE);Counters count;Placement placement(count,journal,nullptr);
            require(placement.finiteRtBudget(),"finite budget BEFORE RT");
            ProcessState p;p.pid=targets.pid;p.uid=uid(p.pid);p.generation=identity(p.pid).start;p.name=processName(p.pid);p.package="org.example.harness";p.transition(Ownership::ACQUIRING);p.transition(Ownership::ZUIOPT_OWNED);
            for(int tid:{targets.c1,targets.c2}){OwnerRecord record{p.pid,p.uid,tid,p.generation,identity(p.pid,tid).start,p.package,p.name,group(tid),affinity(tid)};
                journal.validate(record);journal.entries.emplace(tid,std::move(record));}
            journal.commit();if(!walt0)require(placement.prepareRealtimeGlobal(),"global preparation");
            if(walt0||walt1){
                require(qualifiedWaltIdentity(),"exact WALT module/kernel identity");
                auto result=placement.walt(p,targets.c2,walt1?2:1,walt1?128:2);
                if(walt1&&result==SchedulerResult::UNRESTORABLE){
                    require(!journal.entries.at(targets.c2).walt.active,"no unrestoreable WALT intent");
                    puts("REQUESTED_MODE=2 EFFECTIVE_MODE=0 REASON=WALT_ORIGINAL_UNRESTORABLE; target WALT unchanged");walt1=false;
                }else{require(result==SchedulerResult::APPLIED,"real WALT apply/readback");printf("WALT_APPLIED tid=%d kind=%d value=%d exact_module_identity=true\n",targets.c2,walt1?2:1,walt1?128:2);}
            }
            for(int tid:{targets.c1,targets.c2})if(!walt0&&(!walt1||tid==targets.c2)){
                require(placement.realtime(p,tid,true)==SchedulerResult::APPLIED,"real RT apply");SchedulerState observed;
                require(schedulerState(tid,observed)&&observed.policy==2&&observed.priority==1&&observed.flags==1,"real RR RESET readback");
                printf("APPLIED tid=%d policy=%u prio=%u flags=%llu\n",tid,observed.policy,observed.priority,static_cast<unsigned long long>(observed.flags));
            }
            // Abrupt controller exit: durable intent survives, target threads remain alive.
            _exit(0);
        }
        int status=0;require(waitpid(controller,&status,0)==controller&&WIFEXITED(status)&&WEXITSTATUS(status)==0,"controller failed; invoke --recover");
        std::this_thread::sleep_for(std::chrono::milliseconds(200));recover();
        char value='S';require(::write(stop[1],&value,1)==1,"worker stop");close(stop[1]);require(waitpid(worker,&status,0)==worker&&WIFEXITED(status)&&WEXITSTATUS(status)==0,"finite worker exit");
        puts("DEVICE_RT_THREADS_AND_INDEPENDENT_RECOVERY=PASS bounded synthetic scope only");return 0;
    }catch(const std::exception& e){fprintf(stderr,"HARNESS_FAIL %s; use --recover for exact same test state\n",e.what());return 1;}
}
