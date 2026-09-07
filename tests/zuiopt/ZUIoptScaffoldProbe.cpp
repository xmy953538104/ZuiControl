// TEST ONLY. Scoped target-device fixture; never linked/staged as /system/bin/ZUIopt.
// Caller precreates an EMPTY init-equivalent scaffold and a private temporary state dir.
#include "../../native/zuiopt/ZUIopt_owner.h"
#include <csignal>
#include <grp.h>
#include <linux/capability.h>
#include <sys/syscall.h>
#include <sys/wait.h>
using namespace ZUIopt;
constexpr const char* SCAFFOLD="/dev/cpuset/ZUIopt";

void dropDac(){
    require(getuid()==0&&geteuid()==0,"root test identity");
    gid_t groups[]={0,1000,3009};require(setgroups(3,groups)==0,"production supplementary groups");
    __user_cap_header_struct h{_LINUX_CAPABILITY_VERSION_3,0};
    __user_cap_data_struct data[2]{};
    data[0].effective=data[0].permitted=1u<<CAP_SYS_NICE;
    require(syscall(SYS_capset,&h,data)==0,"drop all capabilities except sys_nice");
    require(syscall(SYS_capget,&h,data)==0&&data[0].effective==(1u<<CAP_SYS_NICE)&&
            data[0].permitted==(1u<<CAP_SYS_NICE)&&!data[0].inheritable&&
            !data[1].effective&&!data[1].permitted&&!data[1].inheritable,"capability readback");
    puts("CAP_DAC_OVERRIDE=ABSENT; CAP_DAC_READ_SEARCH=ABSENT; EFFECTIVE=SYS_NICE_ONLY");
}
ProcessState state(int pid){
    ProcessState p;p.pid=pid;p.uid=uid(pid);p.generation=identity(pid).start;
    p.package="org.example.zuioptfixture";p.name=processName(pid);p.alive=true;p.managed=true;
    p.androidGroup=ZUIopt::group(pid);p.androidMask=affinity(pid);p.ownershipFloor=p.generation;
    Task task;task.generation=identity(pid,pid).start;p.tasks[pid]=task;return p;
}
void emptyScaffold(){
    struct stat st{};require(lstat(SCAFFOLD,&st)==0&&validCpusetScaffold(st),"KEEP_ROOT");
    require(trim(read(std::string(SCAFFOLD)+"/tasks")).empty(),"empty root tasks");
    DIR* dir=opendir(SCAFFOLD);require(dir,"probe root read");
    while(auto* e=readdir(dir)){
        std::string name=e->d_name;if(name=="."||name=="..")continue;
        struct stat child{};require(lstat((std::string(SCAFFOLD)+"/"+name).c_str(),&child)==0,"child stat");
        require(!S_ISDIR(child.st_mode),"child remains");
    }closedir(dir);
}
template<class F> void rejected(F action,const std::string& reason){
    try{action();}catch(const std::exception& e){require(std::string(e.what()).find(reason)!=std::string::npos,"wrong rejection");return;}
    throw std::runtime_error("expected "+reason);
}
int main(int argc,char** argv){
    setvbuf(stdout,nullptr,_IOLBF,0);int child=-1,worker=-1;
    try{
        require(argc==3,"probe MODE STATE");std::string mode=argv[1],path=argv[2];
        require(path.rfind("/data/local/tmp/ZUIopt_scaffold_",0)==0&&path.find("..") == path.npos,"isolated test state only");
        struct stat st{};require(lstat(path.c_str(),&st)==0&&S_ISDIR(st.st_mode)&&st.st_uid==0&&(st.st_mode&07777)==0700,"private state precreated");
        dropDac();
        if(mode=="missing"){
            require(lstat(SCAFFOLD,&st)<0&&errno==ENOENT,"missing fixture precondition");
            Journal j(path);Counters c;rejected([&]{Placement p(c,j);},"scaffold absent or unsafe");
            require(lstat(SCAFFOLD,&st)<0&&errno==ENOENT,"daemon created missing root");
            puts("MISSING_SCAFFOLD_FAIL_CLOSED=PASS");return 0;
        }
        require(mode=="lifecycle","unknown probe mode");emptyScaffold();
        child=fork();require(child>=0,"fork controlled task");if(!child){for(;;)pause();}
        auto original=state(child);require(normalGroup(original.androidGroup)&&original.androidMask,"controlled task baseline");
        Mask mask=original.androidMask&(~original.androidMask+1);
        {
            Journal j(path);Counters c;
            require(chmod(SCAFFOLD,0700)==0,"set unsafe mode fixture");
            rejected([&]{Placement p(c,j);},"scaffold absent or unsafe");
            require(chmod(SCAFFOLD,0755)==0,"restore scaffold mode");
            Placement p(c,j);auto controlled=state(child);p.prepare(controlled);
            p.apply(controlled,child,controlled.tasks.at(child),mask,"fixture");
            require(ZUIopt::group(child).rfind("/ZUIopt/",0)==0&&affinity(child)==mask,"real placement");
            p.release(controlled);p.cleanup();emptyScaffold();
            require(ZUIopt::group(child)==original.androidGroup&&affinity(child)==original.androidMask,"graceful release exact");
            require(j.entries.empty()&&j.leases.empty(),"journal graceful clear");
        }
        puts("CHILD_MEMS_CPUS_PLACE_RELEASE_KEEP_ROOT=PASS");
        {
            int fd=open((path+"/owner_state.v1").c_str(),O_WRONLY|O_CREAT|O_EXCL,0600);require(fd>=0,"corrupt fixture");writeAll(fd,"corrupt\n");close(fd);
            Journal j(path);Counters c;rejected([&]{Placement p(c,j);},"journal version/header");
            require(ZUIopt::group(child)==original.androidGroup&&affinity(child)==original.androidMask,"corrupt journal did not mutate task");
            require(unlink((path+"/owner_state.v1").c_str())==0,"remove fixture corrupt journal");
        }
        {
            require(write(std::string(SCAFFOLD)+"/tasks",std::to_string(child)),"unknown task fixture");
            Journal j(path);Counters c;rejected([&]{Placement p(c,j);},"RECOVERY_UNKNOWN_TASK_FAIL_CLOSED");
            require(ZUIopt::group(child)=="/ZUIopt","unknown task must not be guessed/restored");
            require(write("/dev/cpuset"+original.androidGroup+"/tasks",std::to_string(child))&&setAffinity(child,original.androidMask),"explicit test task cleanup");
        }
        puts("CORRUPT_JOURNAL_UNKNOWN_TASK_FAIL_CLOSED=PASS");
        int ready[2];require(pipe(ready)==0,"ready pipe");
        worker=fork();require(worker>=0,"fork owner");
        if(!worker){
            close(ready[0]);Journal j(path);Counters c;Placement p(c,j);auto controlled=state(child);
            p.prepare(controlled);p.apply(controlled,child,controlled.tasks.at(child),mask,"fixture");
            require(::write(ready[1],"1",1)==1,"ready write");close(ready[1]);for(;;)pause();
        }
        close(ready[1]);char token=0;require(::read(ready[0],&token,1)==1&&token=='1',"ready acknowledgement");close(ready[0]);
        require(ZUIopt::group(child).rfind("/ZUIopt/",0)==0&&affinity(child)==mask,"pre-kill owned state");
        require(kill(worker,SIGKILL)==0,"SIGKILL owner");int status=0;require(waitpid(worker,&status,0)==worker&&WIFSIGNALED(status)&&WTERMSIG(status)==SIGKILL,"actual SIGKILL completion");worker=-1;
        {
            Journal j(path);Counters c;Placement p(c,j);
            require(ZUIopt::group(child)==original.androidGroup&&affinity(child)==original.androidMask,"restart restored exact journal baseline");
            require(j.entries.empty()&&j.leases.empty()&&c.releases>0,"recovery journal clear");p.cleanup();emptyScaffold();
        }
        puts("SIGKILL_JOURNAL_RECOVERY_KEEP_ROOT=PASS");
        require(rmdir(SCAFFOLD)!=0&&(errno==EACCES||errno==EPERM),"no DAC bypass on 0555 parent");emptyScaffold();
        require(kill(child,SIGTERM)==0&&waitpid(child,nullptr,0)==child,"controlled task exit");child=-1;
        puts("ZUIOPT_SCAFFOLD_NATIVE_DEVICE_GATE=PASS; SELINUX_DOMAIN=POST_FLASH_RUNTIME_REQUIRED");return 0;
    }catch(const std::exception& e){
        // No guessed recovery on failure. Stop only our forked fixture processes; retain state.
        if(worker>0){kill(worker,SIGKILL);waitpid(worker,nullptr,0);}
        if(child>0){kill(child,SIGKILL);waitpid(child,nullptr,0);}
        fprintf(stderr,"SCAFFOLD_FIXTURE_FAIL %s\n",e.what());return 1;
    }
}
