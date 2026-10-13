// Single temporary daemon, finite local supervisor, and original init resumption.
#include "../../native/zuiopt/ZUIopt_core.h"
#include <csignal>
#include <fstream>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <poll.h>
using namespace ZUIopt;
void property(const char* action,const char* service){int child=fork();require(child>=0,"init command fork");if(child==0){execl("/system/bin/setprop","setprop",action,service,nullptr);_exit(127);}int s=0;waitpid(child,&s,0);require(WIFEXITED(s)&&WEXITSTATUS(s)==0,"init command failed");}
int main(int argc,char** argv){require(argc==3&&getuid()==0,"development executable and session directory");std::string exe=argv[1],path=argv[2];
    int child=-1,fd=-1,status=-1;bool requested=false,resumed=false;std::string error;
    try{property("ctl.stop","zui_zuiopt");auto deadline=now()+3000;
    bool any=true;while(any&&now()<deadline){any=false;for(int pid:ids("/proc"))if(processName(pid)=="/system/bin/ZUIopt")any=true;if(any)usleep(20000);}
    require(!any,"installed daemon did not stop; no second owner");
    bool faultLoop=access((path+"/fault-loop").c_str(),F_OK)==0;auto end=now()+(faultLoop?450000:90000);int starts=0;
    for(;;){
        child=fork();require(child>=0,"candidate fork");if(child==0){execl(exe.c_str(),exe.c_str(),nullptr);_exit(127);}
        fd=static_cast<int>(syscall(SYS_pidfd_open,child,0));require(fd>=0,"candidate held pidfd");
        {std::ofstream out(path+"/candidate.pid");out<<child<<'\n';}
        starts++;pollfd p{fd,POLLIN,0};bool exited=false;
        while(now()<end){if(access((path+"/finish").c_str(),F_OK)==0){requested=true;break;}if(poll(&p,1,20)>0){exited=true;break;}}
        if(!faultLoop||!exited||requested||starts>55||now()>=end)break;
        require(waitpid(child,&status,0)==child,"fault candidate reap before restart");child=-1;close(fd);fd=-1;
        {std::ofstream out(path+"/supervisor-events.jsonl",std::ios::app);out<<"{\"event\":\"manual_candidate_restart\",\"start_count\":"<<starts<<",\"wait_status\":"<<status<<"}\n";}
    }
    pollfd p{fd,POLLIN,0};
    // A normal termination lets the candidate execute its production withdrawal.
    syscall(SYS_pidfd_send_signal,fd,SIGTERM,nullptr,0);if(poll(&p,1,5000)==0)syscall(SYS_pidfd_send_signal,fd,SIGKILL,nullptr,0);
    require(waitpid(child,&status,0)==child,"candidate wait");child=-1;close(fd);fd=-1;
    }catch(const std::exception& e){error=e.what();if(child>0){kill(child,SIGKILL);waitpid(child,&status,0);child=-1;}if(fd>=0)close(fd);}
    // An older installed binary cannot parse a live V6 record. Preserve it and
    // report HOLD instead of starting an incompatible recovery owner.
    auto journal=read("/data/vendor/zui_control/zuiopt/owner_state.v1");bool residue=!trim(read("/dev/cpuset/ZUIopt/tasks")).empty();
    DIR* groups=opendir("/dev/cpuset/ZUIopt");require(groups,"handoff resource inventory");while(auto* entry=readdir(groups)){
        std::string name=entry->d_name;if(name=="."||name=="..")continue;struct stat st{};auto group="/dev/cpuset/ZUIopt/"+name;
        if(lstat(group.c_str(),&st)==0&&S_ISDIR(st.st_mode)&&!trim(read(group+"/tasks")).empty())residue=true;}closedir(groups);
    if(!residue&&journal.rfind("ZUIOPT_OWNER_STATE_V6 ",0)!=0&&journal.rfind("ZUIOPT_OWNER_STATE_V7 ",0)!=0){try{property("ctl.start","zui_zuiopt");resumed=true;}catch(const std::exception& e){error=e.what();}}
    else error="LIVE_RESIDUE_REQUIRES_CANDIDATE_RECOVERY";
    std::ofstream out(path+"/supervisor-result.json");out<<"{\"requested_finish\":"<<(requested?"true":"false")<<",\"candidate_wait_status\":"<<status
        <<",\"original_init_start_requested\":"<<(resumed?"true":"false")<<",\"Uperf_stop_requests\":0,\"error\":"<<std::quoted(error)<<"}\n";
    return resumed&&error.empty()?0:1;
}
