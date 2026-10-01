// Real RuleStore lock and crash accounting, isolated host directory only.
#define ZUIOPT_TEST 1
#include "../../native/zuiopt/ZUIopt_store.h"
#include <sys/wait.h>
#include <filesystem>
#include <chrono>
#include <cstdio>
using namespace ZUIopt;
int main(int argc,char** argv){
    require(argc<=2,"fixture temporary parent only");
    std::string pattern=std::string(argc==2?argv[1]:"/tmp")+"/zuiopt-crash-gate-XXXXXX";
    std::vector<char> buffer(pattern.begin(),pattern.end());buffer.push_back(0);char* temporary=buffer.data();
    require(mkdtemp(temporary)!=nullptr,"fixture directory");
    const std::string factory="schema 2\nenabled true\nprofile G 2-6\npackage exact org.example.game G 100\n";
    int ready[2],result[2];require(pipe(ready)==0&&pipe(result)==0,"fixture pipes");
    // Lock in another process: child must not inherit the flock description.
    pid_t holder=fork();require(holder>=0,"holder fork");
    if(holder==0){RuleStore store(temporary,factory);char c='1';require(write(ready[1],&c,1)==1,"ready");usleep(1500000);_exit(0);}
    char c=0;require(::read(ready[0],&c,1)==1,"holder ready");
    auto start=std::chrono::steady_clock::now();
    pid_t gate=fork();require(gate>=0,"gate fork");
    if(gate==0){bool failed=crashGate(temporary);char value=failed?'1':'0';require(write(result[1],&value,1)==1,"gate result");_exit(0);}
    require(::read(result[0],&c,1)==1,"crash result");
    auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
    int status;waitpid(gate,&status,0);require(WIFEXITED(status)&&WEXITSTATUS(status)==0,"gate exit");waitpid(holder,&status,0);
    printf("MANAGER_LOCK_HOLD_MS=1500 CRASH_GATE_ELAPSED_MS=%lld FAILSAFE=%c\n",static_cast<long long>(elapsed),c);fflush(stdout);
    require(elapsed<500,"crash gate stalled on ordinary rule manager lock");
    require(c=='0'&&!crashGate(temporary)&&crashGate(temporary),"three crash window preserves contended first event");
    {RuleStore store(temporary,factory);store.resetFailure();}
    pid_t ledger=fork();require(ledger>=0,"ledger fork");
    if(ledger==0){PrivateDir root(temporary);CrashLock lock(root);char value='1';require(write(ready[1],&value,1)==1,"ledger ready");usleep(1500000);_exit(0);}
    require(::read(ready[0],&c,1)==1,"ledger held");start=std::chrono::steady_clock::now();
    require(crashGate(temporary),"unavailable accounting must fail closed");
    elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
    require(elapsed<500,"crash accounting lock bounded");
    {PrivateDir root(temporary);require(root.get("failure.v1",128).find("CRASH_RETAINED")!=std::string::npos,"uncountable event persisted");}
    waitpid(ledger,&status,0);
    printf("ACCOUNTING_LOCK_ELAPSED_MS=%lld EVENT_RETAINED=PASS THREE_CRASH_WINDOW=PASS\n",static_cast<long long>(elapsed));
    std::filesystem::remove_all(temporary);
    return 0;
}
