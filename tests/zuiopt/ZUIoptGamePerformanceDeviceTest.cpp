// Local real-workload ABBA measurement. Candidate owns every scheduler write.
#define main unusedFunctionalCaseMain
#include "ZUIoptLocalDeviceTest.cpp"
#undef main
#include <cmath>
namespace {
std::string game="com.kurogame.mingchao",layer;int gamePid=0;
int64_t nanos(){timespec t{};clock_gettime(CLOCK_MONOTONIC,&t);return int64_t(t.tv_sec)*1000000000+t.tv_nsec;}
int integerField(const std::string& text,const std::string& name){auto at=text.find('"'+name+"\":");require(at!=text.npos,"current runtime field missing");int value=0;require(bool(std::istringstream(text.substr(at+name.size()+3))>>value),"runtime integer field");return value;}
std::string gameRuntime(){auto text=read("/data/vendor/zui_control/zuiopt/runtime-status.v1");auto current=state();
    require(runtimeOwnerMatches(text,trim(read("/proc/sys/kernel/random/boot_id")),current.at("loaded_generation")),"performance current Native receipt");
    size_t from=0;while(true){auto package=text.find("\"package\":\""+game+"\"",from);if(package==text.npos)break;
        auto start=text.rfind('{',package),end=text.find('}',package);require(start!=text.npos&&end!=text.npos,"current game runtime syntax");auto row=text.substr(start,end-start+1);from=end+1;
        if(integerField(row,"C1")&&integerField(row,"C2")&&(!gamePid||integerField(row,"pid")==gamePid))return row;}
    throw std::runtime_error("current main game representatives unavailable");}
std::string fixedConditions(){std::istringstream dump(command({"/system/bin/dumpsys","zui_control"}));std::string line,out;
    for(const auto* key:{"uperfMode=","physicalDisplayHz=","gpuMode=","gpuRequestedMinLevel=","gpuRequestedMaxLevel=","gpuOwnedMinLevel=","gpuOwnedMaxLevel=","gpuScene="}){
        dump.clear();dump.seekg(0);bool found=false;while(std::getline(dump,line))if(line.rfind(key,0)==0){out+=line+'\n';found=true;break;}require(found,"fixed condition readback missing");}
    return out;
}
double percentile(std::vector<double> values,double fraction){if(values.empty())return 0;std::sort(values.begin(),values.end());return values.at(static_cast<size_t>(std::ceil(values.size()*fraction))-1);}
}
int main(int argc,char** argv){
    require(argc==2&&getuid()==0,"Root local performance output");outDir=argv[1];began=now();signal(SIGPIPE,SIG_IGN);alarm(220);
    candidateExecutable=trim(read(outDir+"/candidate.path"));require(candidateExecutable.rfind("/data/local/tmp/",0)==0,"temporary candidate path");
    original=read(outDir+"/original.conf");grantOff=read(outDir+"/off.conf");grant=read(outDir+"/on.conf");auto baseline=state();require(baseline.at("user_sha256")==sha256(original),"performance canonical baseline");
    auto rr=trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"));auto uperf=command({"/system/bin/pidof","uperf"});
    int stop[2];require(pipe(stop)==0,"performance local recovery pipe");int guard=fork();require(guard>=0,"performance guard fork");
    if(!guard){close(stop[1]);pollfd wait{stop[0],POLLIN|POLLHUP,0};char token=0;if(poll(&wait,1,210000)>0)::read(stop[0],&token,1);
        try{rollbackOwn();event(token=='C'?"performance-guard-confirmed":"performance-tool-rescue-intervened");_exit(token=='C'?0:2);}catch(...){_exit(3);}}
    close(stop[0]);std::vector<std::string> phases;std::string error;bool restored=false,pass=false;
    try{
        auto names=command({"/system/bin/dumpsys","SurfaceFlinger","--list"});std::string name;std::istringstream lines(names);
        while(std::getline(lines,name))if(name.rfind("SurfaceView["+game+"/",0)==0&&name.find("](BLAST)#")!=name.npos){require(layer.empty(),"unique current game layer");layer=name;}
        require(!layer.empty(),"live game surface layer missing");auto conditions=fixedConditions();require(conditions.find("physicalDisplayHz=120\n")!=conditions.npos,"120 Hz scope");
        int pid=integerField(gameRuntime(),"pid");gamePid=pid;auto birth=identity(pid).start;require(birth,"live game identity");
        int native=number(fields(state().at("loaded_generation"),':').at(2));auto nativeBirth=identity(native).start;
        std::ofstream critical(outDir+"/critical-before.txt");critical<<"game="<<pid<<":"<<birth<<"\nNative="<<native<<":"<<nativeBirth<<"\n"<<conditions<<"\ninitial-journal="<<read("/data/vendor/zui_control/zuiopt/owner_state.v1");critical.close();
        int proof=open((outDir+"/critical-before.txt").c_str(),O_RDONLY|O_CLOEXEC);require(proof>=0&&fsync(proof)==0,"performance critical originals sync");close(proof);
        for(int work=0;work<3;work++)for(int order=0;order<4;order++){
            bool rt=order==1||order==2;auto desired=rt?grant:grantOff;auto current=state().at("user_sha256");if(current!=sha256(desired))commit(desired,current);
            std::string runtime;until([&]{try{runtime=gameRuntime();return integerField(runtime,"pid")==pid&&integerField(runtime,"requestedRt")==int(rt)&&integerField(runtime,"effectiveRt")==int(rt);}catch(...){return false;}},3000,"game runtime RT acknowledgement timeout");
            int c1=integerField(runtime,"C1"),c2=integerField(runtime,"C2");require(c1&&c2,"actual game representatives required");
            SchedulerState s1{},s2{};require(schedulerState(c1,s1)&&schedulerState(c2,s2)&&s1.policy==unsigned(rt?SCHED_RR:SCHED_OTHER)&&s2.policy==unsigned(rt?SCHED_RR:SCHED_OTHER)&&(!rt||(s1.priority==1&&s2.priority==1)) ,"actual game scheduler readback");
            require(fixedConditions()==conditions&&command({"/system/bin/pidof","uperf"})==uperf,"fixed Uperf/display/GPU policy changed");
            std::this_thread::sleep_for(std::chrono::seconds(1));auto begin=nanos();auto ticks=identity(native).ticks;auto temperature=trim(read("/sys/class/power_supply/battery/temp"));
            std::set<int64_t> presented;int samples=0;while(nanos()<begin+10000000000LL){
                require(identity(pid).start==birth&&identity(native).start==nativeBirth,"performance process changed");
                if(work){bool reverse=samples%2;std::vector<std::string> input{"/system/bin/input","swipe"};
                    if(work==1){input.insert(input.end(),{reverse?"2000":"1550","650",reverse?"1550":"2000","650","250"});}
                    else input.insert(input.end(),{"1800",reverse?"900":"450","1800",reverse?"450":"900","250"});command(input);}
                auto raw=command({"/system/bin/dumpsys","SurfaceFlinger","--latency",layer});event("frame-sample",raw);std::istringstream frames(raw);int64_t display=0,a=0,p=0,r=0;frames>>display;
                while(frames>>a>>p>>r)if(p>=begin&&p>0&&p<INT64_MAX)presented.insert(p);
                samples++;std::this_thread::sleep_for(std::chrono::milliseconds(400));
            }
            auto end=nanos();std::vector<double> durations;int64_t previous=0;for(auto t:presented){if(t>end)continue;if(previous)durations.push_back((t-previous)/1000000.0);previous=t;}
            require(durations.size()>=100,"insufficient actual game presentation samples");
            require(fixedConditions()==conditions&&identity(native).start==nativeBirth,"phase condition or Native changed");
            std::ostringstream row;row<<"{\"workload\":"<<jsonQuote(work==0?"city_idle":work==1?"city_horizontal_camera":"city_vertical_camera")<<",\"order\":"<<order<<",\"rt\":"<<int(rt)<<",\"frames\":"<<durations.size()
                <<",\"present_interval_p95_ms\":"<<percentile(durations,.95)<<",\"present_interval_p99_ms\":"<<percentile(durations,.99)<<",\"intervals_over_25_ms\":"<<std::count_if(durations.begin(),durations.end(),[](double v){return v>25;})
                <<",\"phase_begin_ns\":"<<begin<<",\"phase_end_ns\":"<<end<<",\"native_ticks_before\":"<<ticks<<",\"native_ticks_after\":"<<identity(native).ticks<<",\"ticks_per_second\":"<<sysconf(_SC_CLK_TCK)
                <<",\"temperature_before_tenths_C\":"<<temperature<<",\"temperature_after_tenths_C\":"<<trim(read("/sys/class/power_supply/battery/temp"))<<",\"native_cpu_percent\":"<<100.0*(identity(native).ticks-ticks)/(sysconf(_SC_CLK_TCK)*double(end-begin)/1000000000.0)
                <<",\"battery_net_current_uA\":"<<trim(read("/sys/class/power_supply/battery/current_now"))<<",\"device_power_valid\":false,\"input_response_latency_valid\":false,\"c1_tid\":"<<c1<<",\"c2_tid\":"<<c2
                <<",\"c1_cpuset\":"<<jsonQuote(group(c1))<<",\"c2_cpuset\":"<<jsonQuote(group(c2))<<",\"runtime\":"<<runtime<<",\"present_timestamps_ns\":[";
            bool comma=false;for(auto t:presented){row<<(comma?",":"")<<t;comma=true;}row<<"]}";phases.push_back(row.str());event("performance-phase-complete",row.str());
        }
        rollbackOwn();until([&]{try{auto runtime=gameRuntime();return integerField(runtime,"effectiveRt")==0&&trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))==rr;}catch(...){return false;}},3000,"live gameplay RT restore timeout");
        restored=true;pass=true;
    }catch(const std::exception& e){error=e.what();event("performance-failed",error);try{rollbackOwn();restored=state().at("user_sha256")==sha256(original);}catch(...){} }
    char token='C';::write(stop[1],&token,1);close(stop[1]);int status=0;waitpid(guard,&status,0);pass=pass&&WIFEXITED(status)&&WEXITSTATUS(status)==0;alarm(0);
    std::ofstream out(outDir+"/RESULTS.json");out<<"{\"schema\":\"ZUIOPT_GAME_PERFORMANCE_R2\",\"scope\":\"Root_candidate_real_gameplay_development_NOT_release\",\"status\":"<<jsonQuote(pass?"PASS_MEASUREMENT":"FAIL")<<",\"local_elapsed_ms\":"<<now()-began
        <<",\"canonical_restored\":"<<(restored?"true":"false")<<",\"error\":"<<jsonQuote(error)<<",\"layer\":"<<jsonQuote(layer)<<",\"phases\":[";
    for(size_t i=0;i<phases.size();i++)out<<(i?",":"")<<phases[i];out<<"]}\n";return pass?0:1;
}
