// Finite, read-only scheduling baseline. Never elevates policies or changes masks.
#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>
struct Attr {uint32_t size,policy;uint64_t flags;int32_t nice;uint32_t priority;uint64_t runtime,deadline,period;uint32_t utilMin,utilMax;};
void sample(const char* name, int milliseconds){
    prctl(PR_SET_NAME,name,0,0,0);int tid=static_cast<int>(syscall(SYS_gettid));
    Attr attr{};attr.size=sizeof(attr);int code=static_cast<int>(syscall(SYS_sched_getattr,tid,&attr,sizeof(attr),0));
    cpu_set_t mask;CPU_ZERO(&mask);int affinity=sched_getaffinity(tid,sizeof(mask),&mask);uint64_t bits=0;
    for(int c=0;c<64;c++)if(CPU_ISSET(c,&mask))bits|=uint64_t(1)<<c;
    std::array<uint64_t,64> cpus{};uint64_t moves=0,total=0;int last=-1;
    auto until=std::chrono::steady_clock::now()+std::chrono::milliseconds(milliseconds);
    while(std::chrono::steady_clock::now()<until){
        // Bounded work/sleep: sampled CPUs are observations, not time-weighted residency.
        auto work=std::chrono::steady_clock::now()+std::chrono::microseconds(300);
        do{int cpu=sched_getcpu();if(cpu>=0&&cpu<64){cpus[cpu]++;total++;if(last>=0&&last!=cpu)moves++;last=cpu;}}
        while(std::chrono::steady_clock::now()<work);
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    static std::atomic_flag printing=ATOMIC_FLAG_INIT;while(printing.test_and_set())std::this_thread::yield();
    printf("{\"name\":\"%s\",\"pid\":%d,\"tid\":%d,\"attrCode\":%d,\"policy\":%u,\"priority\":%u,\"flags\":%llu,\"affinityCode\":%d,\"mask\":%llu,\"samples\":%llu,\"observedMigrations\":%llu,\"cpuSamples\":[",name,getpid(),tid,code,attr.policy,attr.priority,static_cast<unsigned long long>(attr.flags),affinity,static_cast<unsigned long long>(bits),static_cast<unsigned long long>(total),static_cast<unsigned long long>(moves));
    for(int c=0;c<8;c++)printf("%s%llu",c?",":"",static_cast<unsigned long long>(cpus[c]));
    puts("]}");fflush(stdout);printing.clear();
}
int main(){
    printf("PROBE_READY pid=%d duration_ms=10000 read_only=true\n",getpid());fflush(stdout);
    std::thread main([]{sample("SyntheticMain",10000);}),render([]{sample("SyntheticRender",10000);});
    sample("SyntheticIdle",10000);main.join();render.join();
}
