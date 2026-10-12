// Temporary Root entry: reuse the exact production main; notify its current owner.
#define main productionMain
#include "../../native/zuiopt/ZUIopt.cpp"
#undef main
#include <atomic>
#include <thread>
int main(int argc,char** argv){
    if(argc!=5||std::string(argv[1])!="--control"||std::string(argv[2])!="commit")return productionMain(argc,argv);
    using namespace ZUIopt;
    std::atomic<bool> done{false};
    auto loaded=fields(trim(read("/data/vendor/zui_control/zuiopt/loaded-generation.v2")),':');
    require(getuid()==0&&loaded.size()==5&&loaded[1]==trim(read("/proc/sys/kernel/random/boot_id")),"development current owner");
    int pid=number(loaded[2]);uint64_t birth=std::stoull(loaded[3]);
    char self[1024]{},owner[1024]{};auto ownSize=readlink("/proc/self/exe",self,sizeof(self)-1),size=readlink(("/proc/"+std::to_string(pid)+"/exe").c_str(),owner,sizeof(owner)-1);
    require(ownSize>0&&size==ownSize&&std::string(self,ownSize)==std::string(owner,size)&&identity(pid).start==birth&&uid(pid)==0,"one exact development executable owner");
    int fd=static_cast<int>(syscall(SYS_pidfd_open,pid,0));require(fd>=0,"development owner pidfd");
    std::thread notify([&]{while(!done){auto bytes=read("/data/vendor/zui_control/zuiopt/effective.conf");
        if(!bytes.empty()&&generationOf(bytes)!=loaded[0]){if(identity(pid).start==birth)syscall(SYS_pidfd_send_signal,fd,SIGHUP,nullptr,0);break;}
        std::this_thread::sleep_for(std::chrono::milliseconds(20));}});
    int result=productionMain(argc,argv);done=true;notify.join();close(fd);return result;
}
