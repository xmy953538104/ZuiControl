// Device-local noRT transaction/withdrawal test. Only ZUIopt writes product state.
#include "../../native/zuiopt/ZUIopt_store.h"
#include "../../native/zuiopt/ZUIopt_runtime.h"
#include "../../native/zuiopt/ZUIopt_owner.h"
#include <csignal>
#include <fstream>
#include <poll.h>
#include <sys/wait.h>
#include <thread>
using namespace ZUIopt;
namespace {
std::string outDir,original,grant,grantOff;
std::string candidateExecutable="/system/bin/ZUIopt";
int64_t began=0;
std::string jsonQuote(const std::string& text){
    std::string out="\"";const char* hex="0123456789abcdef";
    for(unsigned char c:text){if(c=='"'||c=='\\'){out+='\\';out+=c;}else if(c<32){out+="\\u00";out+=hex[c>>4];out+=hex[c&15];}else out+=c;}return out+'"';
}
void event(const std::string& name,const std::string& detail=""){
    std::ofstream out(outDir+"/events.jsonl",std::ios::app);
    require(bool(out),"event output");out<<"{\"local_ms\":"<<now()-began<<",\"event\":"<<jsonQuote(name)<<",\"detail\":"<<jsonQuote(detail)<<"}\n";
}
std::string command(const std::vector<std::string>& args){
    int fd[2];require(pipe(fd)==0,"command pipe");pid_t pid=fork();require(pid>=0,"command fork");
    if(pid==0){close(fd[0]);dup2(fd[1],STDOUT_FILENO);dup2(fd[1],STDERR_FILENO);close(fd[1]);alarm(4);
        std::vector<char*> av;for(const auto& s:args)av.push_back(const_cast<char*>(s.c_str()));av.push_back(nullptr);execv(av[0],av.data());_exit(127);}
    close(fd[1]);std::string text;char buf[4096];ssize_t n;
    while((n=::read(fd[0],buf,sizeof(buf)))>0){text.append(buf,n);require(text.size()<262144,"bounded command output");}
    close(fd[0]);int status=0;require(waitpid(pid,&status,0)==pid,"command reap");
    if(!WIFEXITED(status)||WEXITSTATUS(status)!=0)throw std::runtime_error("local command failed: "+text);return trim(text);
}
std::string control(const std::string& action,const std::string& key="",const std::string& value=""){
    return command({candidateExecutable,"--control",action,key,value});
}
std::map<std::string,std::string> state(){std::map<std::string,std::string> out;std::istringstream in(control("state"));std::string line;
    while(std::getline(in,line)){auto i=line.find('=');if(i!=line.npos)require(out.emplace(line.substr(0,i),line.substr(i+1)).second,"duplicate state");}return out;}
void commit(const std::string& data,const std::string& expectedHash){
    auto s=state();require(s.at("user_sha256")==expectedHash&&s.at("settings_pending").empty(),"current canonical CAS changed");
    auto tx=randomId(),digest=sha256(data);event("transaction-begin",tx);
    control("begin",tx,"user:"+std::to_string(data.size())+":"+digest+":-:0:"+s.at("generation"));
    try{
        for(size_t at=0;at<data.size();at+=8192)control("chunk",tx+":"+std::to_string(at),base64(data.substr(at,8192)));
        auto valid=control("validate",tx);require(valid.find("validation=PASS")!=valid.npos&&valid.find("normalized_sha256="+digest)!=valid.npos,"normalized transaction");
        control("commit",tx);auto after=state();require(after.at("user_sha256")==digest&&after.at("transaction")==tx&&after.at("generation")!=s.at("generation"),"committed exact CAS");
        event("transaction-committed",after.at("generation"));
    }catch(...){try{control("abort",tx);}catch(...){}throw;}
}
void rollbackOwn(){auto s=state();if(s.at("user_sha256")==sha256(original))return;
    auto digest=s.at("user_sha256");require(digest==sha256(grant)||(!grantOff.empty()&&digest==sha256(grantOff)),"foreign current state: preserve");commit(original,digest);}
struct Target{int pid,tid;uint64_t processBirth,threadBirth;SchedulerState scheduler;std::string cpuset;Mask mask;};
Target target(int pid,const std::string& name){
    for(int tid:ids("/proc/"+std::to_string(pid)+"/task")){
        if(trim(read("/proc/"+std::to_string(tid)+"/comm"))!=name)continue;
        Target t{pid,tid,identity(pid).start,identity(pid,tid).start,{},group(tid),affinity(tid)};
        require(t.processBirth&&t.threadBirth&&schedulerState(tid,t.scheduler),"ready target identity");return t;
    }
    throw std::runtime_error("fixed target not ready");
}
bool same(const Target& t){return identity(t.pid).start==t.processBirth&&identity(t.pid,t.tid).start==t.threadBirth;}
bool applied(const Target& t,Mask mask,bool rt=false){SchedulerState s;auto expected=rt?realtimeState(t.scheduler):t.scheduler;return same(t)&&affinity(t.tid)==mask&&schedulerState(t.tid,s)&&schedulerControlSame(s,expected)&&group(t.tid).rfind("/ZUIopt/",0)==0;}
bool withdrawn(const Target& t){SchedulerState s;auto g=group(t.tid);return same(t)&&normalGroup(g)&&schedulerState(t.tid,s)&&schedulerControlSame(s,t.scheduler)&&affinity(t.tid)!=0;}
std::string currentLabEvents(int user){
    int fd=open("/data/user/0/org.zuicontrol.adcblab/files/parent.jsonl",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return {};
    struct stat st{};if(fstat(fd,&st)!=0||!S_ISREG(st.st_mode)||st.st_uid!=static_cast<unsigned>(user)||st.st_nlink!=1){close(fd);throw std::runtime_error("dedicated Lab event file identity");}
    // The ledger is historical and append-only. Read a bounded tail; do not
    // clear history or raise the production proc-reader's unrelated 64 KiB cap.
    auto offset=std::max<off_t>(0,st.st_size-32768);require(lseek(fd,offset,SEEK_SET)==offset,"Lab bounded tail seek");
    std::string text;char bytes[4096];ssize_t n=0;while(text.size()<32768&&(n=::read(fd,bytes,std::min(sizeof(bytes),32768-text.size())))>0)text.append(bytes,n);
    close(fd);require(n>=0,"Lab bounded tail read");if(offset){auto end=text.find('\n');text=end==text.npos?"":text.substr(end+1);}return text;
}
void snapshot(const Target& t,const std::string& phase){SchedulerState s;require(same(t)&&schedulerState(t.tid,s),"live target readback");std::ostringstream row;
    row<<"pid="<<t.pid<<" tid="<<t.tid<<" birth="<<t.threadBirth<<" mask="<<affinity(t.tid)<<" group="<<group(t.tid)<<" scheduler=";schedulerText(row,s);event(phase,row.str());}
template<class F>void until(F f,int ms,const char* why){auto end=now()+ms;while(!f()){require(now()<end,why);std::this_thread::sleep_for(std::chrono::milliseconds(30));}}
void injectOrdinaryRecordLoss(const Target& target,bool unbound=false){
    const std::string directory="/data/vendor/zui_control/zuiopt",path=directory+"/owner_state.v1";
    auto originalJournal=read(path);auto split=originalJournal.find('\n');require(split!=originalJournal.npos,"fault journal header");
    std::istringstream header(originalJournal.substr(0,split));std::string version;uint32_t crc=0;require(bool(header>>version>>crc)&&(version=="ZUIOPT_OWNER_STATE_V5"||version=="ZUIOPT_OWNER_STATE_V6")&&checksum(originalJournal.substr(split+1))==crc,"exact candidate fault journal");
    std::istringstream rows(originalJournal.substr(split+1));std::ostringstream retained;std::string row;bool removed=false,leaseSeen=false;
    while(std::getline(rows,row)){
        std::istringstream in(row);char type=0;int pid=0,user=0,tid=0;uint64_t pb=0,tb=0;
        if(!(in>>type>>pid>>pb>>user>>tid>>tb)||(type!='L'&&type!='T')){retained<<row<<'\n';continue;}
        if(type=='L'&&pid==target.pid){require(pb==target.processBirth&&uid(pid)==user&&target.threadBirth<tb,"pre-lease ordinary target provenance");leaseSeen=true;if(unbound)continue;}
        if(type=='T'&&(unbound?pid==target.pid:tid==target.tid)){
            std::string package,name,group,token;Mask mask=0,prior=0,appliedMask=0;int active=0,waltActive=0,kind=0,raw=0,expected=0;SchedulerState before,after;
            require(bool(in>>std::quoted(package)>>std::quoted(name)>>std::quoted(group)>>mask>>prior>>appliedMask>>active)&&parseScheduler(in,before)&&parseScheduler(in,after)&&bool(in>>waltActive>>kind>>raw>>expected>>token),"fault record fields");
            require((unbound||!removed)&&pid==target.pid&&pb==target.processBirth&&(unbound||tb==target.threadBirth)&&user==uid(pid)&&package=="org.zuicontrol.adcblab"&&group==target.cpuset&&mask==target.mask&&!active&&!waltActive&&same(target),"only dedicated noRT/noWALT placement may lose record");removed=true;continue;
        }retained<<row<<'\n';
    }
    require(removed&&leaseSeen,"one pre-lease ordinary row selected");
    int evidence=open((outDir+"/journal-before-injection.txt").c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    require(evidence>=0,"fault preimage output");writeAll(evidence,originalJournal);require(fsync(evidence)==0,"durable journal fault preimage");close(evidence);
    auto body=retained.str(),modified=version+" "+std::to_string(checksum(body))+"\n"+body;
    int dir=open(directory.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);require(dir>=0,"fault journal directory");
    int fd=openat(dir,"owner_state.v1.r2-fault",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);require(fd>=0,"fault exclusive staging");
    writeAll(fd,modified);require(fsync(fd)==0,"fault staging durable");close(fd);
    require(renameat(dir,"owner_state.v1.r2-fault",dir,"owner_state.v1")==0&&fsync(dir)==0,"fault atomic journal row loss");close(dir);
    event(unbound?"injected-dedicated-noRT-lease-record-loss":"injected-one-noRT-placement-record-loss",std::to_string(target.tid)+":"+std::to_string(target.threadBirth));
}
}
int main(int argc,char** argv){
    if(argc==2&&std::string(argv[1])=="--selftest"){
        require(jsonQuote("x\n\t\"\\")=="\"x\\u000a\\u0009\\\"\\\\\"","JSON control escape");
        puts("LOCAL_DEVICE_SELFTEST=PASS");return 0;
    }
    std::string action=argc==3?argv[2]:"";bool development=action.rfind("--dev-",0)==0;
    bool unboundQuarantine=action=="--dev-provenance-unbound";
    bool recordLoss=action=="--dev-provenance-loss"||unboundQuarantine;
    bool hang=development&&(action.find("hang")!=action.npos||recordLoss);
    bool crash=development||action=="--crash"||action=="--rt-crash";
    bool rt=action=="--rt"||action=="--rt-crash"||action=="--mode2-rt"||action=="--mode1-rt"||(development&&action.find("-rt-")!=action.npos);
    bool mode1=action=="--mode1"||action=="--mode1-rt";
    bool mode2=argc==3&&(std::string(argv[2])=="--mode2"||std::string(argv[2])=="--mode2-rt");
    require((argc==2||crash||rt||mode1||mode2)&&getuid()==0,"root device-local output directory");outDir=argv[1];began=now();signal(SIGPIPE,SIG_IGN);
    auto override=trim(read(outDir+"/candidate.path"));if(!override.empty()){require(override.rfind("/data/local/tmp/",0)==0&&access(override.c_str(),X_OK)==0,"legal temporary candidate path");candidateExecutable=override;}
    require(!development||candidateExecutable.rfind("/data/local/tmp/",0)==0,"manual faults only temporary candidate");
    original=read(outDir+"/original.conf");grant=read(outDir+"/grant.conf");require(!original.empty()&&!grant.empty()&&original!=grant,"saved input bodies");
    grantOff=read(outDir+"/grant-off.conf");if(rt)require(!grantOff.empty()&&grantOff!=grant&&dumpRules(rules(grantOff))==grantOff,"RT-off candidate");
    require(dumpRules(rules(original))==original&&dumpRules(rules(grant))==grant,"canonical inputs");
    auto baseline=state();require(baseline.at("user_sha256")==sha256(original),"fresh baseline");
    auto rrBefore=trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"));
    int stop[2];require(pipe(stop)==0,"independent local guard");pid_t guard=fork();require(guard>=0,"guard fork");
    if(guard==0){close(stop[1]);pollfd p{stop[0],POLLIN|POLLHUP,0};int rc=poll(&p,1,16000);char token=0;
        if(rc>0)::read(stop[0],&token,1);close(stop[0]);
        try{rollbackOwn();event(token=='C'?"guard-confirmed":"guard-rescue-intervened");_exit(token=='C'?0:2);}catch(const std::exception& e){event("guard-recovery-failed",e.what());_exit(3);}}
    close(stop[0]);bool pass=false,recovered=false;std::string error;int pid=0;int64_t readyMs=0,applyMs=0,releaseMs=0;int c1=0,c2=0,injections=0,stopSignals=0,killSignals=0,newNative=0;
    try{
        command({"/system/bin/am","force-stop","org.zuicontrol.adcblab"});
        command({"/system/bin/am","start","-W","-n","org.zuicontrol.adcblab/android.app.NativeActivity"});
        pid=number(command({"/system/bin/pidof","org.zuicontrol.adcblab"}));
        Target a{},b{};until([&]{try{a=target(pid,"ADCB-C1");b=target(pid,"ADCB-C2");return true;}catch(...){return false;}},1500,"fixed ready timeout");
        c1=a.tid;c2=b.tid;readyMs=now()-began;event("fixed-targets-ready",std::to_string(c1)+","+std::to_string(c2));
        std::ostringstream critical;critical<<"boot="<<trim(read("/proc/sys/kernel/random/boot_id"))<<"\npid="<<pid<<"\nbirth="<<a.processBirth<<"\n";
        for(const auto& t:{a,b}){critical<<"tid="<<t.tid<<" birth="<<t.threadBirth<<" group="<<t.cpuset<<" mask="<<t.mask<<" scheduler=";schedulerText(critical,t.scheduler);critical<<'\n';}
        int saved=open((outDir+"/critical-before.txt").c_str(),O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);auto bytes=critical.str();
        require(saved>=0&&::write(saved,bytes.data(),bytes.size())==static_cast<ssize_t>(bytes.size())&&fsync(saved)==0,"durable fixed original targets");close(saved);
        bool wide=mode1||(mode2&&!rt);
        commit(grant,sha256(original));until([&]{return applied(a,wide?252:28,rt)&&applied(b,wide?252:128,rt);},2000,"target apply timeout");applyMs=now()-began;event("mode-targets-applied");
        if(mode2){auto receipt=read("/data/vendor/zui_control/zuiopt/runtime-status.v1");auto expected="\"package\":\"org.zuicontrol.adcblab\",\"requestedMode\":2,\"effectiveMode\":"+std::string(rt?"0":"2");
            require(receipt.find(expected)!=receipt.npos&&receipt.find("\"pid\":"+std::to_string(pid)+",\"processStart\":"+std::to_string(a.processBirth))!=receipt.npos,"current target mode2 receipt");
            if(rt)require(receipt.find("WALT_ORIGINAL_UNRESTORABLE")!=receipt.npos,"honest negative-WALT fallback reason");
            else {int tid=0,value=0;std::istringstream walt(read("/proc/sys/walt/sched_per_task_boost"));require(bool(walt>>tid>>value)&&tid==b.tid&&value==2,"actual representative WALT boost readback");}
            event("mode2-runtime-receipt",receipt);}
        if(rt)require(trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))=="3","RT global applied");
        snapshot(a,"applied-C1");snapshot(b,"applied-C2");
        Target auxiliary{};bool hasAuxiliary=false;
        if(development&&(action.find("-fork")!=action.npos||action.find("-clone")!=action.npos)){
            bool forkCase=action.find("-fork")!=action.npos;auto commandPath="/data/user/0/org.zuicontrol.adcblab/files/command.txt";
            int cmd=open(commandPath,O_WRONLY|O_TRUNC|O_CLOEXEC|O_NOFOLLOW);struct stat st{};
            require(cmd>=0&&fstat(cmd,&st)==0&&S_ISREG(st.st_mode)&&st.st_uid==static_cast<unsigned>(uid(pid))&&st.st_nlink==1,"dedicated App command file");
            auto text=std::string(forkCase?"fork\n":"clone\n");require(::write(cmd,text.data(),text.size())==static_cast<ssize_t>(text.size()),"App auxiliary request");close(cmd);
            until([&]{try{if(!forkCase){auxiliary=target(pid,"ADCB-Clone");return true;}
                int child=0;std::string row;std::istringstream ledger(currentLabEvents(uid(pid)));
                auto actor="\"pid\":"+std::to_string(pid)+",\"tid\":"+std::to_string(a.tid)+",\"birth\":"+std::to_string(a.threadBirth)+",";
                while(std::getline(ledger,row))if(row.find("\"role\":\"fork-child\"")!=row.npos&&row.find(actor)!=row.npos){auto at=row.find("\"child\":");if(at!=row.npos)std::istringstream(row.substr(at+8))>>child;}
                if(!child||uid(child)!=uid(pid))return false;auto executable=processName(child);const std::string suffix="/libadcb_child.so";
                if(executable.size()<suffix.size()||executable.compare(executable.size()-suffix.size(),suffix.size(),suffix)!=0)return false;
                auxiliary={child,child,identity(child).start,identity(child).start,{},group(child),affinity(child)};
                return auxiliary.processBirth&&schedulerState(child,auxiliary.scheduler);}catch(...){return false;}},1000,"auxiliary fork/clone ready timeout");
            hasAuxiliary=true;require(auxiliary.scheduler.policy==SCHED_OTHER&&auxiliary.scheduler.priority==0,"RESET_ON_FORK actual child policy");snapshot(auxiliary,forkCase?"actual-fork-exec":"actual-clone");
        }
        if(crash){
            auto old=number(command({"/system/bin/pidof","ZUIopt"}));auto birth=identity(old).start;
            require(birth&&uid(old)==0&&processName(old)==candidateExecutable,"exact current Native fault target");
            int fd=static_cast<int>(syscall(SYS_pidfd_open,old,0));require(fd>=0&&identity(old).start==birth,"original Native pidfd");
            event("fault-boundary",std::to_string(old)+":"+std::to_string(birth));
            int boundary=open((outDir+"/fault-boundary.txt").c_str(),O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);
            auto text=std::to_string(old)+":"+std::to_string(birth)+"\n";require(boundary>=0&&::write(boundary,text.data(),text.size())==static_cast<ssize_t>(text.size())&&fsync(boundary)==0,"durable fault boundary");close(boundary);
            if(hang){require(syscall(SYS_pidfd_send_signal,fd,SIGSTOP,nullptr,0)==0,"finite hang injection");stopSignals++;injections++;event("finite-hang-start");usleep(250000);
                if(recordLoss){try{require(!rt,"no RT ownership in placement-loss experiment");injectOrdinaryRecordLoss(a,unboundQuarantine);}catch(...){syscall(SYS_pidfd_send_signal,fd,SIGCONT,nullptr,0);close(fd);throw;}}}
            require(syscall(SYS_pidfd_send_signal,fd,SIGKILL,nullptr,0)==0,"one directed fault episode");killSignals++;if(!hang)injections++;pollfd dead{fd,POLLIN,0};require(poll(&dead,1,2000)>0,"old Native exit");close(fd);
            until([&]{try{auto s=state();auto loaded=fields(s.at("loaded_generation"),':');if(loaded.size()!=5)return false;
                int candidate=number(loaded[2]);if(candidate==old||!identity(candidate).start||std::to_string(identity(candidate).start)!=loaded[3])return false;
                if(processName(candidate)!=candidateExecutable)return false;
                if(command({"/system/bin/getprop","init.svc.zui_zuiopt"})!=(development?"stopped":"running"))return false;
                newNative=candidate;return applied(a,28,rt)&&applied(b,128,rt);}catch(...){return false;}},8500,development?"manual supervisor current Native recovery/reapply timeout":"real init current Native recovery/reapply timeout");
            event(unboundQuarantine?"manual-supervisor-quarantine-current-owner":development?"manual-supervisor-recovered-and-reapplied":"init-recovered-and-reapplied",std::to_string(newNative));snapshot(a,"restart-C1");snapshot(b,"restart-C2");
            if(unboundQuarantine){auto ownerBirth=identity(newNative).start;auto receipt=read("/data/vendor/zui_control/zuiopt/recovery-quarantine.json");
                require(receipt.find("\"owner_pid\":"+std::to_string(newNative)+",")!=receipt.npos&&receipt.find("\"owner_birth\":"+std::to_string(ownerBirth)+",")!=receipt.npos&&receipt.find("HELD_NO_TRUSTED_ORIGINAL")!=receipt.npos,"current owner quarantine receipt");
                event("current-unbound-quarantine-receipt",receipt);auto end=now()+3500;
                while(now()<end){require(identity(newNative).start==ownerBirth&&same(a)&&same(b)&&applied(a,28)&&applied(b,128),"unbound ordinary task caused restart or unsafe rewrite");usleep(50000);}
                require(read("/data/vendor/zui_control/zuiopt/owner_state.v1").find("L "+std::to_string(pid)+" ")==std::string::npos,"held target was silently re-leased");event("quarantined-owner-stable-no-new-lease");
            }
            else if(recordLoss){auto receipt=read("/data/vendor/zui_control/zuiopt/normalized-placement.v1");auto prefix=trim(read("/proc/sys/kernel/random/boot_id"))+" "+std::to_string(pid)+" "+std::to_string(a.processBirth)+" "+std::to_string(a.tid)+" "+std::to_string(a.threadBirth)+" ";
                require(receipt.find(prefix)!=receipt.npos&&receipt.find("DEGRADED_ANDROID_APP_RETURN")!=receipt.npos,"current live target normalization receipt");event("candidate-degraded-ordinary-return",receipt);}
        }
        if(rt){commit(grantOff,sha256(grant));until([&]{return applied(a,28)&&applied(b,128)&&trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))==rrBefore;},2000,"live RT-off/global original restore timeout");event("live-RT-off-originals");snapshot(a,"RT-off-C1");snapshot(b,"RT-off-C2");}
        command({"/system/bin/am","start","-W","-n","com.zui.zuicontrol/.MainActivity"});
        until([&]{return withdrawn(a)&&withdrawn(b);},2000,"live background withdrawal timeout");releaseMs=now()-began;event("same-live-targets-withdrawn");
        snapshot(a,"withdrawn-C1");snapshot(b,"withdrawn-C2");
        if(hasAuxiliary){require(withdrawn(auxiliary),"same live auxiliary not withdrawn");snapshot(auxiliary,"withdrawn-live-auxiliary");}
        rollbackOwn();recovered=state().at("user_sha256")==sha256(original);require(recovered&&withdrawn(a)&&withdrawn(b)&&trim(read("/proc/sys/kernel/sched_rr_timeslice_ms"))==rrBefore,"live final rollback");pass=true;
    }catch(const std::exception& e){error=e.what();event("case-failed",error);try{rollbackOwn();recovered=true;}catch(const std::exception& x){event("rollback-failed",x.what());}}
    char token='C';::write(stop[1],&token,1);close(stop[1]);int gs=0;waitpid(guard,&gs,0);pass=pass&&WIFEXITED(gs)&&WEXITSTATUS(gs)==0;
    try{command({"/system/bin/am","force-stop","org.zuicontrol.adcblab"});command({"/system/bin/am","start","-W","-n","com.zui.zuicontrol/.MainActivity"});}catch(const std::exception& e){pass=false;error+=e.what();}
    std::string caseName=development?action.substr(2):mode2?(rt?"mode2_rt1_honest_negative_WALT_fallback_live_off":"mode2_rt0_WALT_boost_live_withdrawal"):mode1?(rt?"mode1_Kana_fc_fc_fc_rt1_live_off":"mode1_Kana_fc_fc_fc_live_withdrawal"):rt?(crash?"mode0_rt1_init_crash_live_off":"mode0_rt1_live_off"):(crash?"mode0_rt0_init_crash_live_reapply_withdrawal":"mode0_rt0_live_withdrawal");
    std::ofstream out(outDir+"/RESULTS.json");out<<"{\"schema\":\"ZUIOPT_DEVICE_LOCAL_R2\",\"case\":"<<std::quoted(caseName)<<",\"status\":"<<std::quoted(pass?"PASS":"FAIL")<<",\"local_elapsed_ms\":"<<now()-began<<",\"ready_ms\":"<<readyMs<<",\"apply_ms\":"<<applyMs<<",\"withdraw_ms\":"<<releaseMs<<",\"pid\":"<<pid<<",\"C1_tid\":"<<c1<<",\"C2_tid\":"<<c2<<",\"canonical_restored\":"<<(recovered?"true":"false")<<",\"product_writer\":"<<jsonQuote(candidateExecutable)<<",\"test_kernel_writes\":0,\"fault_injections\":"<<injections<<",\"hang_injections\":"<<stopSignals<<",\"kill_injections\":"<<killSignals<<",\"init_new_native\":"<<(development?0:newNative)<<",\"manual_new_native\":"<<(development?newNative:0)<<",\"error\":"<<jsonQuote(error)<<"}\n";
    return pass?0:1;
}
