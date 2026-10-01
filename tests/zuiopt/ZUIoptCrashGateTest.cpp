// Real RuleStore lock and crash accounting, isolated host directory only.
#define ZUIOPT_TEST 1
#include "../../native/zuiopt/ZUIopt_store.h"
#include <sys/wait.h>
#include <filesystem>
#include <chrono>
#include <cstdio>
using namespace ZUIopt;
int main(){
    char temporary[]="/tmp/zuiopt-crash-gate-XXXXXX";
    require(mkdtemp(temporary)!=nullptr,"fixture directory");
    const std::string factory="schema 2\nenabled true\nprofile G 2-6\npackage exact org.example.game G 100\n";
    int ready[2],result[2];require(pipe(ready)==0&&pipe(result)==0,"fixture pipes");
    // Lock in another process: child must not inherit the flock description.
    pid_t holder=fork();require(holder>=0,"holder fork");
    if(holder==0){RuleStore store(temporary,factory);char c='1';require(write(ready[1],&c,1)==1,"ready");usleep(1500000);_exit(0);}
    char c=0;require(::read(ready[0],&c,1)==1,"holder ready");
    auto start=std::chrono::steady_clock::now();
    pid_t gate=fork();require(gate>=0,"gate fork");
    if(gate==0){RuleStore store(temporary,factory);bool failed=store.crash();char value=failed?'1':'0';require(write(result[1],&value,1)==1,"gate result");_exit(0);}
    require(::read(result[0],&c,1)==1,"crash result");
    auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
    int status;waitpid(gate,&status,0);require(WIFEXITED(status)&&WEXITSTATUS(status)==0,"gate exit");waitpid(holder,&status,0);
    printf("MANAGER_LOCK_HOLD_MS=1500 CRASH_GATE_ELAPSED_MS=%lld FAILSAFE=%c\n",static_cast<long long>(elapsed),c);fflush(stdout);
    std::filesystem::remove_all(temporary);
    require(elapsed<500,"crash gate stalled on ordinary rule manager lock");
    return 0;
}
