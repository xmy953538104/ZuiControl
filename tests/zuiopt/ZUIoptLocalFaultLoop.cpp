// One device-local seeded fault batch. Product writes remain in the candidate.
#define main singleCaseMain
#include "ZUIoptLocalDeviceTest.cpp"
#undef main
#include <sys/stat.h>
int main(int argc,char** argv){
    require(argc==2&&getuid()==0,"local fault loop output");std::string root=argv[1];alarm(420);
    auto started=now();auto exe=trim(read(root+"/candidate.path"));require(exe.rfind("/data/local/tmp/",0)==0,"temporary candidate only");
    auto initial=read(root+"/original.conf"),off=read(root+"/grant-off.conf"),on=read(root+"/grant-rt.conf");
    auto uperf=command({"/system/bin/pidof","uperf"});auto rr=trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"));
    const uint32_t seed=0xadcb0002;uint32_t random=seed;int passed=0,attempted=0,faults=0,hanges=0,kills=0;std::map<unsigned,int> preconditions;std::string stopReason;
    auto save=[&]{std::ostringstream data;data<<"{\"schema\":\"ZUIOPT_LOCAL_FAULT_LOOP_R2\",\"model_version\":\"R2_BOUNDED_LAB_ACK_V3\",\"seed\":"<<seed<<",\"planned\":50,\"attempted\":"<<attempted
        <<",\"passed\":"<<passed<<",\"actual_fault_episodes\":"<<faults<<",\"actual_SIGSTOP\":"<<hanges<<",\"actual_SIGKILL\":"<<kills
        <<",\"local_elapsed_ms\":"<<now()-started<<",\"execution_scope\":\"Root_candidate_manual_supervisor_NOT_init\",\"stop_reason\":"<<jsonQuote(stopReason)<<"}\n";
        int fd=open((root+"/RESULTS.json.new").c_str(),O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);auto text=data.str();require(fd>=0&&::write(fd,text.data(),text.size())==static_cast<ssize_t>(text.size())&&fsync(fd)==0,"batch result durable");close(fd);
        require(rename((root+"/RESULTS.json.new").c_str(),(root+"/RESULTS.json").c_str())==0,"batch result replace");};
    for(int i=0;i<60&&faults<50;i++){
        random=random*1664525u+1013904223u;unsigned kind=i<4?i:(random>>16)%4;
        random=random*1664525u+1013904223u;unsigned auxiliary=i<4?(i==1?1:i==2?2:0):(random>>16)%3;
        bool wantsRt=kind%2;std::string flag=wantsRt?"--dev-rt-":"--dev-";flag+=kind>=2?"hang":"crash";flag+=auxiliary==1?"-fork":auxiliary==2?"-clone":"";
        std::ostringstream name;name<<root<<"/case-"<<std::setw(3)<<std::setfill('0')<<i+1;auto dir=name.str();require(mkdir(dir.c_str(),0700)==0,"exclusive local case directory");
        for(auto [file,text]:std::array<std::pair<std::string,std::string>,4>{{{"original.conf",initial},{"grant.conf",wantsRt?on:off},{"grant-off.conf",off},{"candidate.path",exe}}}){
            std::ofstream output(dir+"/"+file);output<<text;require(bool(output),"local prepared input");}
        std::vector<char*> args{argv[0],dir.data(),flag.data()};int rc=singleCaseMain(3,args.data());attempted++;
        auto result=read(dir+"/RESULTS.json");require(!result.empty(),"completed case result");if(rc==0)passed++;
        auto count=[&](const char* key){auto at=result.find(std::string("\"")+key+"\":");require(at!=result.npos,"case count field");int value=0;std::istringstream(result.substr(at+strlen(key)+3))>>value;return value;};
        faults+=count("fault_injections");hanges+=count("hang_injections");kills+=count("kill_injections");
        {std::ofstream events(root+"/events.jsonl",std::ios::app);events<<"{\"iteration\":"<<i+1<<",\"recipe\":"<<jsonQuote(flag)<<",\"result\":"<<trim(result)<<"}\n";}
        bool safe=false;try{
            until([&]{auto s=state();auto journal=read("/data/vendor/zui_control/zuiopt/owner_state.v1");auto pid=count("pid");
                return s.at("user_sha256")==sha256(initial)&&s.at("settings_pending").empty()&&trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))==rr&&
                    journal.find("\nL "+std::to_string(pid)+" ")==journal.npos&&journal.find("\nT "+std::to_string(pid)+" ")==journal.npos;},1500,"previous case not converged");
            safe=command({"/system/bin/pidof","uperf"})==uperf&&trim(read("/sys/fs/selinux/enforce"))=="1";
        }catch(const std::exception& e){stopReason=e.what();}
        bool prerequisite=rc!=0&&(result.find("ready timeout")!=result.npos||result.find("target apply timeout")!=result.npos||result.find("recovery/reapply timeout")!=result.npos);
        if(prerequisite)preconditions[auxiliary]++;else if(rc==0)preconditions[auxiliary]=0;
        if(!safe){stopReason="UNSAFE_BATCH_BOUNDARY:"+stopReason;save();break;}
        if(preconditions[auxiliary]>=2){stopReason="TWO_SAME_PRECONDITION_FAILURES_CHANGE_MODEL";save();break;}
        save();
    }
    alarm(0);return passed==attempted&&faults==50?0:1;
}
