// Isolated Root component experiment; not a production-daemon recovery result.
#include "../../native/zuiopt/ZUIopt_runtime.h"
#include <atomic>
#include <fstream>
#include <csignal>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <poll.h>
#include <sys/wait.h>
#include <thread>
using namespace ZUIopt;
struct Shared{std::atomic<int> phase{0};std::atomic<bool> stop{false};std::atomic<uint64_t> cpus[3][8]{};};
int main(int argc,char** argv){
    require(argc==2&&getuid()==0,"Root experiment output");std::string path=argv[1];int64_t began=now();
    require(cpus(read("/sys/devices/system/cpu/possible"))==255&&cpus(read("/sys/devices/system/cpu/online"))==255,"eight CPU adapter scope");
    require(trim(read("/proc/sys/kernel/sched_rt_period_us"))=="1000000"&&trim(read("/proc/sys/kernel/sched_rt_runtime_us"))=="950000","finite unchanged RT budget");
    auto oldQuery=trim(read("/proc/sys/walt/sched_task_read_pid"));require(!oldQuery.empty(),"saved query selector");
    Shared* shared=new(mmap(nullptr,sizeof(Shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0))Shared;
    pid_t target=fork();require(target>=0,"finite target");
    if(target==0){alarm(6);prctl(PR_SET_NAME,"ADCB-E2",0,0,0);rlimit limit{100000,200000};setrlimit(RLIMIT_RTTIME,&limit);
        while(!shared->stop){int phase=shared->phase.load(),cpu=sched_getcpu();if(phase>=0&&phase<3&&cpu>=0&&cpu<8)shared->cpus[phase][cpu]++;
            auto end=now()+1;while(now()<end){}std::this_thread::sleep_for(std::chrono::milliseconds(3));}_exit(0);}
    auto birth=identity(target).start;SchedulerOwnership scheduler;SchedulerKernel sk;
    int done[2];require(pipe(done)==0,"local selector backstop");pid_t guard=fork();require(guard>=0,"independent selector guard");
    if(guard==0){close(done[1]);pollfd p{done[0],POLLIN|POLLHUP,0};int ready=poll(&p,1,4000);char token=0;if(ready>0)::read(done[0],&token,1);close(done[0]);
        if(token!='C'){shared->stop=true;auto q=trim(read("/proc/sys/walt/sched_task_read_pid"));if(q==std::to_string(target))write("/proc/sys/walt/sched_task_read_pid",oldQuery);}_exit(token=='C'?0:2);}
    close(done[0]);
    auto same=[&]{return identity(target).start==birth;};
    auto persist=[&]{std::ostringstream data;data<<target<<' '<<birth<<' '<<scheduler.active<<' ';schedulerText(data,scheduler.original);data<<' ';schedulerText(data,scheduler.expected);
        int fd=open((path+"/scheduler-before.txt").c_str(),O_WRONLY|O_CREAT|O_TRUNC,0600);auto bytes=data.str();require(fd>=0&&::write(fd,bytes.data(),bytes.size())==static_cast<ssize_t>(bytes.size())&&fsync(fd)==0,"durable isolated scheduler");close(fd);};
    WaltKernel wk{[&](int tid){return same()&&write("/proc/sys/walt/sched_task_read_pid",std::to_string(tid));}};
    int raw=0,restricted=0,normalized=0;bool pass=false,alive=false,restored=false;std::string error;
    try{
        require(wk.get(2,target,raw)&&raw==-1,"raw original negative mask");
        require(applyRealtime(scheduler,target,sk,persist,same)==SchedulerResult::APPLIED,"candidate scheduler apply");
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
        require(wk.set(2,target,128)&&wk.get(2,target,restricted)&&restricted==128,"candidate restricted mask write/read");shared->phase=1;
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
        require(wk.set(2,target,255)&&wk.get(2,target,normalized)&&normalized==255,"candidate semantic-normalization experiment");shared->phase=2;
        std::this_thread::sleep_for(std::chrono::milliseconds(250));alive=same();require(alive,"normalization while same target remains live");
        require(restoreScheduler(scheduler,target,sk,persist,same)==SchedulerResult::RESTORED,"candidate RT withdrawal");
        SchedulerState final;restored=schedulerState(target,final)&&schedulerControlSame(final,scheduler.original);require(restored&&same(),"live scheduler original restored");pass=true;
    }catch(const std::exception& e){error=e.what();try{restoreScheduler(scheduler,target,sk,persist,same);}catch(...){} }
    // Preserve a foreign selector; this experiment owns only its target selection.
    auto query=trim(read("/proc/sys/walt/sched_task_read_pid"));bool selectorRestored=query==oldQuery;
    if(query==std::to_string(target))selectorRestored=write("/proc/sys/walt/sched_task_read_pid",oldQuery)&&trim(read("/proc/sys/walt/sched_task_read_pid"))==oldQuery;
    char token='C';::write(done[1],&token,1);close(done[1]);int guardStatus=0;waitpid(guard,&guardStatus,0);pass=pass&&WIFEXITED(guardStatus)&&WEXITSTATUS(guardStatus)==0;
    shared->stop=true;int status=0;waitpid(target,&status,0);
    std::ofstream out(path+"/E2-result.json");out<<"{\"case\":\"WALT_reduce_negative_vs_255\",\"scope\":\"Root_candidate_component_experiment\",\"product_recovery_qualified\":false,\"status\":"<<std::quoted(pass&&selectorRestored?"PASS_COMPONENT_MEASUREMENT":"FAIL")<<",\"local_elapsed_ms\":"<<now()-began<<",\"raw_original\":"<<raw<<",\"restricted\":"<<restricted<<",\"normalized\":"<<normalized<<",\"target_alive_at_disable\":"<<(alive?"true":"false")<<",\"scheduler_restored_while_alive\":"<<(restored?"true":"false")<<",\"query_restored\":"<<(selectorRestored?"true":"false")<<",\"cpu_samples\":[";
    for(int phase=0;phase<3;phase++){out<<(phase?",[":"[");for(int cpu=0;cpu<8;cpu++)out<<(cpu?",":"")<<shared->cpus[phase][cpu].load();out<<']';}out<<"],\"error\":"<<std::quoted(error)<<"}\n";
    return pass&&selectorRestored?0:1;
}
