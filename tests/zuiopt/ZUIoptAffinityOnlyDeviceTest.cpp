// Affinity-only prototype measurement. A writer exit is not an init recovery test.
#include "../../native/zuiopt/ZUIopt_runtime.h"
#include <fstream>
#include <sys/wait.h>
#include <thread>
using namespace ZUIopt;
std::string cmd(std::vector<std::string> args){int f[2];require(pipe(f)==0,"pipe");int child=fork();require(child>=0,"fork");
    if(child==0){close(f[0]);dup2(f[1],1);dup2(f[1],2);close(f[1]);alarm(3);std::vector<char*> a;for(auto& s:args)a.push_back(s.data());a.push_back(nullptr);execv(a[0],a.data());_exit(127);}
    close(f[1]);std::string text;char b[1024];ssize_t n;while((n=::read(f[0],b,sizeof(b)))>0)text.append(b,n);close(f[0]);int status=0;waitpid(child,&status,0);require(WIFEXITED(status)&&WEXITSTATUS(status)==0,"command exit");return trim(text);}
int main(int argc,char** argv){require(argc==2&&getuid()==0,"Root experiment path");auto started=now();std::string path=argv[1];
    cmd({"/system/bin/am","force-stop","org.zuicontrol.adcblab"});cmd({"/system/bin/am","start","-W","-n","org.zuicontrol.adcblab/android.app.NativeActivity"});
    int pid=number(cmd({"/system/bin/pidof","org.zuicontrol.adcblab"})),tid=0;auto end=now()+1000;
    while(!tid){for(int t:ids("/proc/"+std::to_string(pid)+"/task"))if(trim(read("/proc/"+std::to_string(t)+"/comm"))=="ADCB-C2")tid=t;require(now()<end,"fixed target barrier");usleep(10000);}
    auto birth=identity(pid,tid).start;auto groupBefore=group(tid);auto before=affinity(tid);require(uid(pid)>=10000&&normalGroup(groupBefore),"ordinary App unmapped normal group");
    require((cpus(read("/dev/cpuset"+groupBefore+"/cpus"))&128)!=0,"CPU7 reachable without private cpuset");
    // Child exits after the candidate core syscall. No cpuset/journal/global writes.
    int writer=fork();require(writer>=0,"prototype writer");if(writer==0){_exit(identity(pid,tid).start==birth&&setAffinity(tid,128)&&affinity(tid)==128?0:1);}
    int status=0;waitpid(writer,&status,0);require(WIFEXITED(status)&&WEXITSTATUS(status)==0,"candidate affinity write");
    usleep(300000);require(identity(pid,tid).start==birth,"same live target");auto afterWriter=affinity(tid);auto writerGroup=group(tid);
    cmd({"/system/bin/am","start","-W","-n","com.zui.zuicontrol/.MainActivity"});
    end=now()+1500;while(group(tid)==groupBefore&&now()<end)usleep(10000);auto groupAfter=group(tid);auto afterBackground=affinity(tid);
    bool live=identity(pid,tid).start==birth,reset=live&&normalGroup(groupAfter)&&afterBackground!=128;
    bool prototypeRescue=false;if(live&&afterBackground==128){prototypeRescue=true;require(setAffinity(tid,before&cpus(read("/dev/cpuset"+groupAfter+"/cpus"))),"prototype cleanup");}
    std::ofstream out(path+"/E3-result.json");out<<"{\"case\":\"affinity_only_writer_exit_and_background\",\"scope\":\"Root_candidate_component_ordinary_App\",\"product_recovery_qualified\":false,\"local_elapsed_ms\":"<<now()-started<<",\"pid\":"<<pid<<",\"tid\":"<<tid<<",\"mask_before\":"<<before<<",\"mask_after_writer_exit\":"<<afterWriter<<",\"mask_after_background\":"<<afterBackground<<",\"group_before\":"<<std::quoted(groupBefore)<<",\"group_after_writer_exit\":"<<std::quoted(writerGroup)<<",\"group_after_background\":"<<std::quoted(groupAfter)<<",\"same_target_alive\":"<<(live?"true":"false")<<",\"background_reset\":"<<(reset?"true":"false")<<",\"automatic_withdrawal_on_writer_exit\":"<<(afterWriter!=128?"true":"false")<<",\"test_rescue\":"<<(prototypeRescue?"true":"false")<<"}\n";out.close();
    cmd({"/system/bin/am","force-stop","org.zuicontrol.adcblab"});return live?0:1;
}
