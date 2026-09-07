// Production paths are fixed. Management is invoked only by the authenticated root command plane.
#include "ZUIopt_daemon.h"
#include "ZUIopt_store.h"
#include <sys/system_properties.h>
#include <sys/syscall.h>

namespace {
constexpr const char* ROOT="/data/vendor/zui_control/zuiopt";
constexpr const char* FACTORY="/system/etc/zuiopt/factory_rules.conf";
std::string property(const char* name){char value[PROP_VALUE_MAX]{};__system_property_get(name,value);return value;}
bool selected(){return property("ro.zui_control.task_owner")=="ZUIOPT"&&property("sys.zui_control.zuiopt_failed")=="0";}
void reload() noexcept {
    if(!selected())return;
    try{for(int pid:ZUIopt::ids("/proc")){
        if(pid==getpid())continue;int fd=static_cast<int>(syscall(SYS_pidfd_open,pid,0));if(fd<0)continue;
        char exe[512]{};auto n=readlink(("/proc/"+std::to_string(pid)+"/exe").c_str(),exe,sizeof(exe)-1);
        if(n>0&&std::string(exe)=="/system/bin/ZUIopt")syscall(SYS_pidfd_send_signal,fd,SIGHUP,nullptr,0);
        close(fd);
    }}catch(...){} // A committed generation remains valid; init restart also reloads it.
}
}
int main(int argc,char** argv){
    setvbuf(stdout,nullptr,_IOLBF,0);
    signal(SIGPIPE,SIG_IGN);
    try{
        ZUIopt::require(getuid()==0&&geteuid()==0,"root identity required");
        if(argc==1){ZUIopt::require(selected(),"not this boot's task owner");ZUIopt::Core core(std::string(ROOT)+"/effective.conf",ROOT);return core.run();}
        if(argc==2&&std::string(argv[1])=="--recover"){
            ZUIopt::require(property("ro.zui_control.task_owner")=="ZUIOPT"&&property("sys.zui_control.zuiopt_failed")=="1","recovery only after fail-safe stop");
            ZUIopt::Journal journal(ROOT);ZUIopt::Counters counters;ZUIopt::Placement placement(counters,journal);placement.cleanup();return 0;
        }
        ZUIopt::require(argc==2||(argc==5&&std::string(argv[1])=="--control"),"invalid production command");
        ZUIopt::RuleStore store(ROOT,ZUIopt::read(FACTORY));
        if(argc==2){
            auto command=std::string(argv[1]);
            if(command=="--boot"){store.initialize();puts(store.nextOwner().c_str());return 0;}
            if(command=="--crash"){bool failed=true;try{failed=store.crash();}catch(...){store.failure();}puts(failed?"1":"0");return 0;}
            throw std::runtime_error("unknown production command");
        }
        std::string command=argv[2],key=argv[3],value=argv[4],result;
        ZUIopt::require(command.size()<=16&&key.size()<=128&&value.size()<=10924,"command bound");
        if(command=="state")result=store.state()+store.ownerState();
        else if(command=="owner")result=store.ownerState();
        else if(command=="read")result=store.userChunk(key,value);
        else if(command=="next"){store.nextOwner(value);result=store.ownerState();}
        else{result=store.apply(command,key,value);if(command=="commit"||command=="enable"||command=="disable"||command=="rollback")reload();}
        printf("%s\n",result.c_str());return 0;
    }catch(const std::exception& e){fprintf(stderr,"ZUIopt rejected: %.96s\n",e.what());return 1;}
}
