#include "../../native/command/Projection.h"
#include "../../native/command/Rules.h"
#include <filesystem>
#include <iostream>
#include <sys/wait.h>

using namespace command;
namespace fs=std::filesystem;
unsigned checks=0;
void check(bool b){++checks;require(b,"test assertion");}
template<class F> void rejects(F fn){bool threw=false;try{fn();}catch(const std::exception&){threw=true;}check(threw);}
struct Temp {
    std::string path;
    Temp(){char name[]="/tmp/zui-command-XXXXXX";auto* p=mkdtemp(name);require(p,"temporary fixture");path=p;}
    ~Temp(){fs::remove_all(path);}
};
std::string policy(int gen){return "{\"apps\":[],\"defaults\":[],\"generation\":"+std::to_string(gen)+",\"globals\":[],\"migration\":\"00000000-0000-0000-0000-000000000001\",\"schema\":2,\"users\":[]}\n";}
const std::string tx="00000000-0000-0000-0000-000000000002";
std::string prepare(int gen){auto p=policy(gen);p.pop_back();return "{\"policy\":"+p+",\"transaction\":\""+tx+"\"}";}
std::string apply(int gen,std::string mode="balance") {return "{\"desiredMode\":\""+mode+"\",\"generation\":"+std::to_string(gen)+",\"hash\":\""+sha256(policy(gen))+"\",\"transaction\":\""+tx+"\"}";}
void parsers(){
    check(sha256("")=="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    check(sha256("abc")=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    check(sha256(std::string(1000000,'a'))=="cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    Request r("request.1|policy|||e30=");r.authenticate(r.requestId,sha256(r.text));
    rejects([&]{r.authenticate("wrong",sha256(r.text));});rejects([&]{r.authenticate(r.requestId,std::string(64,'0'));});
    for(auto s:{"a|policy||", "../a|policy|||x", "a|policy|unexpected||x", "a|policy|||x\n", "a|policy|||x|extra"})rejects([&]{Request bad(s);});
    Request maximum(std::string("a|policy|||")+std::string(REQUEST_LIMIT-11,'a'));check(maximum.text.size()==REQUEST_LIMIT);
    rejects([]{Request bad(std::string("a|policy|||")+std::string(REQUEST_LIMIT-10,'a'));});
    for(auto s:{"{\"a\":1,\"a\":2}","{\"b\":1,\"a\":2}","{\"a\":01}","{\"a\":1e3}","{\"a\":1}x","{\"a\":\"\\ud800\"}","{\"a\":\"\\n\"}","[1,]"})rejects([&]{Json::parse(s);});
    check(Json::parse("{\"a\":\"\\u000a\"}").get("a").string()=="\n");
    check(Json::parse("{\"a\":9223372036854775807}").get("a").integer()==INT64_MAX);
    rejects([]{Json::parse("{\"a\":9223372036854775808}").get("a").integer();});
    rejects([]{Json::parse(std::string(26,'[')+"0"+std::string(26,']'));});
}
void security(){
    Temp t;Disk d(t.path);d.put("valid","abc");check(d.read("valid")=="abc");
    rejects([&]{d.read("../valid");});
    check(symlinkat("valid",d.fd.value,"link")==0);rejects([&]{d.read("link");});rejects([&]{d.put("link","bad");});
    check(linkat(d.fd.value,"valid",d.fd.value,"hard",0)==0);rejects([&]{d.read("hard");});unlinkat(d.fd.value,"hard",0);
    check(mkfifoat(d.fd.value,"fifo",0600)==0);rejects([&]{d.read("fifo");});
    chmod((t.path+"/valid").c_str(),0666);rejects([&]{d.read("valid");});chmod((t.path+"/valid").c_str(),0600);
    {Fd f(openat(d.fd.value,"huge",O_WRONLY|O_CREAT,0600));check(ftruncate(f.value,LIMIT+1)==0);}rejects([&]{d.read("huge");});
    if(geteuid()==0){chown((t.path+"/valid").c_str(),10001,10001);rejects([&]{d.read("valid");});chown((t.path+"/valid").c_str(),0,0);}
    check(symlink(t.path.c_str(),(t.path+"/directory-link").c_str())==0);rejects([&]{Disk bad(t.path+"/directory-link");});
    check(d.read("valid")=="abc");
}
void projections(){
    Temp t;Disk runtime(t.path);runtime.put("effective_powermode.txt","fast\n");Projection p(t.path);
    check(p.call("prepare",prepare(7))==sha256(policy(7)));check(runtime.read("effective_powermode.txt")=="fast\n");
    check(p.call("prepare",prepare(7))==sha256(policy(7)));rejects([&]{p.call("prepare",prepare(8));});
    check(p.call("apply",apply(7))==sha256(policy(7)));check(runtime.read("effective_powermode.txt")=="balance\n");
    check(p.call("apply",apply(7))==sha256(policy(7)));rejects([&]{p.call("apply",apply(8));});rejects([&]{p.call("apply",apply(7,"invalid"));});
    p.stages.put("active.json",policy(8));rejects([&]{p.call("apply",apply(7));});check(p.stages.read("active.json")==policy(8));
}
void replay(){
    Temp t;Disk d(t.path);Request r("test|policy|||e30=");unsigned calls=0;std::string last;
    Receipts receipts{d,[&](const std::string& a){last=a;}};
    auto execute=[&]{++calls;return "ok=1\ngpuRuntime=DEGRADED_FAIL_SAFE\npolicyGeneration=2\n";};
    receipts.run(r,execute);check(calls==1&&last=="test|done|policy|ok=1;gpuRuntime=DEGRADED_FAIL_SAFE;policyGeneration=2");
    std::string terminal=last;last="lost";receipts.run(r,execute);check(calls==1&&last==terminal);
    receipts.run(Request("test|policy|||changed"),execute);check(calls==1&&last==terminal);
    d.put("active_request_claim","other|policy|||x\n");receipts.run(Request("other|policy|||x"),execute);
    check(calls==1&&last=="other|failed|policy|indeterminate_after_claim");
    d.put("active_request_claim","corrupt");rejects([&]{receipts.run(Request("next|policy|||x"),execute);});check(calls==1);
}
void crashes(){
    for(auto phase:{"before_claim","after_claim","before_binder","after_binder","before_root_receipt","after_root_receipt","before_terminal_ack","after_terminal_ack"}){
        Temp t;Disk d(t.path);Request r("crash|policy|||x");
        pid_t child=fork();require(child>=0,"fork");
        if(child==0){Receipts receipts{d,[&](const std::string& a){d.put("observed-ack",a);}};
            receipts.point=[&](const char* p){if(std::string(p)==phase)_exit(73);};
            receipts.run(r,[&]{require(d.read("mutated").empty(),"duplicate mutation");d.put("mutated","generation=2");return "ok=1\npolicyGeneration=2";});_exit(0);}
        int status;require(waitpid(child,&status,0)==child,"wait");check(WIFEXITED(status)&&WEXITSTATUS(status)==73);
        unsigned replayCalls=0;Receipts recovered{d,[&](const std::string& a){d.put("observed-ack",a);}};
        recovered.run(r,[&]{++replayCalls;require(d.read("mutated").empty(),"duplicate generation");d.put("mutated","generation=2");return "ok=1\npolicyGeneration=2";});
        check(replayCalls==(std::string(phase)=="before_claim"?1U:0U));check(d.read("active_request_claim").empty());
        auto outcome=d.read("observed-ack");check(outcome.find("|done|")!=std::string::npos||outcome=="crash|failed|policy|indeterminate_after_claim");
        recovered.run(r,[&]()->std::string{throw std::runtime_error("replay executed");});check(d.read("observed-ack")==outcome);
    }
    for(auto phase:{"before_projection_prepare","after_projection_prepare","during_projection_apply","after_projection_applied"}){
        Temp t;Disk d(t.path);d.put("effective_powermode.txt","fast\n");Projection p(t.path);
        pid_t child=fork();require(child>=0,"fork");if(child==0){p.point=[&](const char* point){if(std::string(point)==phase)_exit(74);};p.call("prepare",prepare(2));p.call("apply",apply(2));_exit(0);}
        int status;waitpid(child,&status,0);check(WIFEXITED(status)&&WEXITSTATUS(status)==74);
        p.call("prepare",prepare(2));p.call("apply",apply(2));check(d.read("effective_powermode.txt")=="balance\n");check(p.stages.read("active.json")==policy(2));
    }
}
int main(int argc,char** argv){try{
    if(argc==2&&std::string(argv[1])=="--parity"){
        Temp t;Disk d(t.path);d.put("effective_powermode.txt","balance\n");Projection p(t.path);std::string line;unsigned messages=0;
        while(std::getline(std::cin,line)){auto fields=split(line,'\t');require(fields.size()==3,"fixture columns");
            auto argument=unbase64(fields[1]);check(p.call(fields[0],argument)==fields[2]);++messages;
            if(fields[0]=="apply"){auto request=Json::parse(argument);check(sha256(p.stages.read("active.json"))==fields[2]);check(d.read("effective_powermode.txt")==request.get("desiredMode").string()+"\n");}}
        check(messages==30);std::cout<<"JAVA_NATIVE_PROJECTION_PARITY_PASS messages="<<messages<<"\n";return 0;
    }
    parsers();security();projections();replay();crashes();std::cout<<"NATIVE_TRANSPORT_PASS checks="<<checks<<"\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<" checks="<<checks<<"\n";return 1;}}
