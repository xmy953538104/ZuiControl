// Finite Root component comparison; never a production recovery qualification.
#include "../../native/zuiopt/ZUIopt_runtime.h"
#include <atomic>
#include <fstream>
#include <thread>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <linux/futex.h>
#include <poll.h>
using namespace ZUIopt;
struct Shared {
    std::atomic<int> ticket{0},ack{0},ready{0},stop{0};
    std::atomic<uint64_t> samples[3][8]{};
    std::atomic<int> phase{0};
};
static void wake(std::atomic<int>& value){syscall(SYS_futex,&value,FUTEX_WAKE,1,nullptr,nullptr,0);}
int main(int argc,char** argv){
    require((argc==2||argc==3)&&getuid()==0,"Root isolated comparison");const std::string path=argv[1];auto began=now();
    Mask targetMask=argc==3?number(argv[2]):255;require(targetMask==255||targetMask==252||targetMask==128,"audited target affinity scope");
    require(cpus(read("/sys/devices/system/cpu/possible"))==255&&cpus(read("/sys/devices/system/cpu/online"))==255,"adapter topology");
    auto oldQuery=trim(read("/proc/sys/walt/sched_task_read_pid"));auto parentMask=affinity(gettid());
    auto* shared=new(mmap(nullptr,sizeof(Shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0))Shared;
    int child=fork();require(child>=0,"finite target");
    if(child==0){
        alarm(8);prctl(PR_SET_NAME,"ADCB-E2-WAKE",0,0,0);rlimit limit{100000,200000};setrlimit(RLIMIT_RTTIME,&limit);
        shared->ready=1;int previous=0;
        while(!shared->stop){
            int current=shared->ticket.load();
            if(current==previous){timespec wait{0,20000000};syscall(SYS_futex,&shared->ticket,FUTEX_WAIT,current,&wait,nullptr,0);continue;}
            int phase=shared->phase.load(),cpu=sched_getcpu();
            if(phase>=0&&phase<3&&cpu>=0&&cpu<8)shared->samples[phase][cpu]++;
            auto end=std::chrono::steady_clock::now()+std::chrono::microseconds(100);
            while(std::chrono::steady_clock::now()<end){}previous=current;shared->ack=current;
        }_exit(0);
    }
    auto birth=identity(child).start;auto same=[&]{return identity(child).start==birth;};
    int done[2];require(pipe(done)==0,"independent selector guard");int guard=fork();require(guard>=0,"local guard");
    if(guard==0){close(done[1]);pollfd p{done[0],POLLIN|POLLHUP,0};char token=0;if(poll(&p,1,5000)>0)::read(done[0],&token,1);
        if(token!='C'){shared->stop=1;wake(shared->ticket);if(trim(read("/proc/sys/walt/sched_task_read_pid"))==std::to_string(child))write("/proc/sys/walt/sched_task_read_pid",oldQuery);}
        _exit(token=='C'?0:2);}
    close(done[0]);
    SchedulerOwnership scheduler;SchedulerKernel sk;int raw=0,limited=0,normalized=0;bool liveOff=false,selectorRestored=false;std::string error;
    auto persist=[&]{std::ostringstream text;text<<child<<' '<<birth<<' '<<scheduler.active<<' ';schedulerText(text,scheduler.original);text<<' ';schedulerText(text,scheduler.expected);
        auto bytes=text.str();int fd=open((path+"/scheduler-before.txt").c_str(),O_WRONLY|O_CREAT|O_TRUNC,0600);
        require(fd>=0&&::write(fd,bytes.data(),bytes.size())==static_cast<ssize_t>(bytes.size())&&fsync(fd)==0,"durable scheduler boundary");close(fd);};
    WaltKernel wk{[&](int tid){return same()&&write("/proc/sys/walt/sched_task_read_pid",std::to_string(tid));}};
    auto sample=[&](int phase){
        shared->phase=phase;
        for(int round=0;round<8;round++)for(int cpu=0;cpu<8;cpu++){
            if(!(parentMask&(1<<cpu)))continue;
            require(setAffinity(gettid(),1<<cpu),"pin only experiment waker");
            int ticket=shared->ticket.fetch_add(1)+1;wake(shared->ticket);auto deadline=now()+100;
            while(shared->ack.load()!=ticket){require(same()&&now()<deadline,"local wake barrier");usleep(100);}
        }
    };
    try{
        auto deadline=now()+300;while(!shared->ready){require(now()<deadline&&same(),"target ready");usleep(100);}
        require(setAffinity(child,targetMask)&&affinity(child)==targetMask,"candidate component target affinity");
        require(wk.get(2,child,raw)&&raw==-1,"raw -1");
        {std::ofstream boundary(path+"/walt-before.txt");boundary<<child<<' '<<birth<<' '<<raw<<' '<<oldQuery<<'\n';boundary.close();
            int fd=open((path+"/walt-before.txt").c_str(),O_RDONLY);require(fd>=0&&fsync(fd)==0,"WALT prevalue durable");close(fd);}
        require(applyRealtime(scheduler,child,sk,persist,same)==SchedulerResult::APPLIED,"candidate RR1");
        sample(0);
        require(wk.set(2,child,128)&&wk.get(2,child,limited)&&limited==128,"reduce128 boundary");sample(1);
        require(wk.set(2,child,255)&&wk.get(2,child,normalized)&&normalized==255,"normalized255 boundary");sample(2);
        require(restoreScheduler(scheduler,child,sk,persist,same)==SchedulerResult::RESTORED,"live RR withdrawal");
        SchedulerState current;liveOff=same()&&schedulerState(child,current)&&schedulerControlSame(current,scheduler.original);require(liveOff,"same target fair after disable");
    }catch(const std::exception& e){error=e.what();try{restoreScheduler(scheduler,child,sk,persist,same);}catch(...){} }
    require(setAffinity(gettid(),parentMask)&&affinity(gettid())==parentMask,"waker exact affinity restored");
    auto currentQuery=trim(read("/proc/sys/walt/sched_task_read_pid"));selectorRestored=currentQuery==oldQuery;
    if(currentQuery==std::to_string(child))selectorRestored=write("/proc/sys/walt/sched_task_read_pid",oldQuery)&&trim(read("/proc/sys/walt/sched_task_read_pid"))==oldQuery;
    shared->stop=1;wake(shared->ticket);int status=0;waitpid(child,&status,0);
    char token='C';::write(done[1],&token,1);close(done[1]);int guardStatus=0;waitpid(guard,&guardStatus,0);
    bool finished=WIFEXITED(status)&&WEXITSTATUS(status)==0&&WIFEXITED(guardStatus)&&WEXITSTATUS(guardStatus)==0;std::ofstream out(path+"/RESULTS.json");
    out<<"{\"case\":\"WALT_RR1_futex_wake_same_live_target\",\"scope\":\"Root_candidate_component\",\"status\":"<<std::quoted(error.empty()&&liveOff&&selectorRestored&&finished?"PASS_COMPONENT_MEASUREMENT":"FAIL")
        <<",\"local_elapsed_ms\":"<<now()-began<<",\"raw_original\":"<<raw<<",\"restricted\":"<<limited<<",\"normalized\":"<<normalized
        <<",\"semantic_equivalence_qualified\":false,\"raw_bytes_restored\":false,\"scheduler_restored_while_alive\":"<<(liveOff?"true":"false")
        <<",\"query_restored\":"<<(selectorRestored?"true":"false")<<",\"waker_original_mask\":"<<parentMask<<",\"target_affinity\":"<<targetMask<<",\"cpu_samples\":[";
    for(int phase=0;phase<3;phase++){out<<(phase?",[":"[");for(int cpu=0;cpu<8;cpu++)out<<(cpu?",":"")<<shared->samples[phase][cpu].load();out<<']';}
    out<<"],\"error\":"<<std::quoted(error)<<"}\n";
    return error.empty()&&liveOff&&selectorRestored&&finished?0:1;
}
