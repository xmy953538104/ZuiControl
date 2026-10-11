#include "../../native/zuiopt/ZUIopt_rules.h"
#include "../../native/zuiopt/ZUIopt_runtime.h"
using namespace ZUIopt;
struct FakeKernel {
    SchedulerState state;bool readDenied=false,writeDenied=false;int writes=0,reads=0;
    std::function<void(int)> beforeRead;
    bool get(int,SchedulerState& out){reads++;if(beforeRead)beforeRead(reads);if(readDenied)return false;out=state;return true;}
    bool set(int,const SchedulerState& value){if(writeDenied)return false;writes++;state=value;return true;}
};
void schedulerCases(){
    // A different original policy/nice/clamp is restored exactly, never blanket OTHER.
    for(unsigned policy:{0u,1u,2u,3u,5u}){
        FakeKernel kernel;kernel.state.policy=policy;kernel.state.priority=(policy==1||policy==2)?5:0;
        kernel.state.nice=7;kernel.state.utilMin=100;kernel.state.utilMax=900;auto original=kernel.state;
        SchedulerOwnership owner;SchedulerOwnership durable;int commits=0;
        auto persist=[&]{durable=owner;commits++;};auto same=[]{return true;};
        require(applyRealtime(owner,17,kernel,persist,same)==SchedulerResult::APPLIED,"RT apply");
        require(commits==1&&durable.active&&durable.original==original&&kernel.state==durable.expected,"durable before apply");
        require(kernel.state.policy==2&&kernel.state.priority==1&&kernel.state.flags==1,"RR1 RESET");
        require(applyRealtime(owner,17,kernel,persist,same)==SchedulerResult::UNCHANGED&&kernel.writes==1,"no repeated scheduler write");
        // Simulate crash: recovery has ONLY the durable record, no original reactor RAM.
        require(restoreScheduler(durable,17,kernel,[&]{commits++;},same)==SchedulerResult::RESTORED,"independent crash restore");
        require(kernel.state==original&&!durable.active,"exact original readback");
    }
    {
        FakeKernel k;SchedulerOwnership o;bool committed=false;
        auto persist=[&]{committed=true;throw std::runtime_error("fsync fault");};
        try{applyRealtime(o,9,k,persist,[]{return true;});require(false,"missing fsync fault");}catch(const std::runtime_error&){}
        require(committed&&k.writes==0,"no write before durable evidence");
    }
    {
        FakeKernel k;k.writeDenied=true;auto original=k.state;SchedulerOwnership o;
        require(applyRealtime(o,9,k,[]{},[]{return true;})==SchedulerResult::UNAVAILABLE&&o.active,"failed intent retained");
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::RESTORED&&k.state==original&&k.writes==0,"crash-before-write restore");
    }
    {
        FakeKernel k;SchedulerOwnership o;applyRealtime(o,9,k,[]{},[]{return true;});k.state.nice=9;auto foreign=k.state;
        require(applyRealtime(o,9,k,[]{},[]{return true;})==SchedulerResult::UNCHANGED&&o.active,"OEM nice update retains RR ownership");
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::RESTORED&&k.state.policy==0&&k.state.priority==0&&k.state.flags==0&&!o.active,"OEM nice change does not strand RR");
        require(k.state.nice==foreign.nice,"later OEM nice preserved");
    }
    {
        FakeKernel k;k.state.nice=-10;SchedulerOwnership o;applyRealtime(o,9,k,[]{},[]{return true;});
        k.state.nice=0;k.state.utilMin=17;k.state.utilMax=800;auto later=k.state;
        require(applyRealtime(o,9,k,[]{},[]{return true;})==SchedulerResult::UNCHANGED&&o.active,"foreground OEM metadata does not revoke RR ownership");
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::RESTORED,"background RR withdrawal");
        require(k.state.policy==0&&k.state.flags==0&&k.state.priority==0&&k.state.nice==later.nice&&k.state.utilMin==later.utilMin&&k.state.utilMax==later.utilMax,"captured device nice transition and later clamps preserved");
    }
    for(unsigned policy:{0u,1u,2u,3u,5u}){
        FakeKernel k;SchedulerOwnership o;applyRealtime(o,9,k,[]{},[]{return true;});k.state.policy=policy;k.state.priority=(policy==1||policy==2)?7:0;auto foreign=k.state;
        auto result=restoreScheduler(o,9,k,[]{},[]{return true;});
        require((result==SchedulerResult::CONFLICT||result==SchedulerResult::RESTORED)&&k.state==foreign&&!o.active,"foreign policy/priority never overwritten");
    }
    {
        FakeKernel k;SchedulerOwnership o;int commits=0;
        auto persist=[&]{if(++commits==1){k.state.nice=8;k.state.utilMin=19;k.state.utilMax=700;}};
        require(applyRealtime(o,9,k,persist,[]{return true;})==SchedulerResult::APPLIED&&commits==2,"OEM update during journal commit refreshes durable intent");
        require(k.state.nice==8&&k.state.utilMin==19&&k.state.utilMax==700&&k.state==o.expected,"grant preserves latest OEM fields");
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::RESTORED&&k.state.nice==8&&k.state.utilMin==19&&k.state.utilMax==700,"withdrawal preserves fields changed before grant");
    }
    {
        FakeKernel k;SchedulerOwnership o;
        k.beforeRead=[&](int read){if(read==4)k.state.nice=6;};
        require(applyRealtime(o,9,k,[]{},[]{return true;})==SchedulerResult::APPLIED&&o.active&&k.state.nice==6,"OEM update at grant readback retains ownership");
        int readback=k.reads+3;k.beforeRead=[&](int read){if(read==readback){k.state.nice=2;k.state.utilMax=800;}};
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::RESTORED&&!o.active&&k.state.nice==2&&k.state.utilMax==800,"OEM update at withdrawal readback is preserved");
    }
    {
        FakeKernel k;SchedulerOwnership o;applyRealtime(o,9,k,[]{},[]{return true;});int writes=k.writes,race=k.reads+2;
        k.beforeRead=[&](int read){if(read==race)k.state.nice=3;};
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::UNAVAILABLE&&o.active&&k.writes==writes,"prewrite race retains journal without overwriting OEM");
        k.beforeRead={};
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::RESTORED&&k.state.nice==3,"retry uses newest OEM field");
    }
    {
        FakeKernel k;SchedulerOwnership o;applyRealtime(o,9,k,[]{},[]{return true;});int writes=k.writes;
        require(restoreScheduler(o,9,k,[]{},[]{return false;})==SchedulerResult::DEAD&&k.writes==writes&&!o.active,"TID reuse no write");
    }
    {
        FakeKernel k;k.state=realtimeState(k.state);SchedulerOwnership o;
        require(applyRealtime(o,9,k,[]{},[]{return true;})==SchedulerResult::UNCHANGED&&!o.active,"external same RT not claimed");
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::UNCHANGED&&k.state.policy==2,"external RT preserved when off");
    }
    {
        FakeKernel k;SchedulerOwnership o;applyRealtime(o,9,k,[]{},[]{return true;});k.readDenied=true;
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::UNAVAILABLE&&o.active,"unresolved retains journal");
    }
}
void grammarCases(){
    auto old=rules("schema 2\nenabled true\nprofile G 0-7\nthread G C2 contains Work selector=rank:2 10 5-6\npackage exact org.example.app G 100\n");
    require(old.schema==2&&old.mode==0&&old.rt==0&&old.profiles.at("G").general==255,"Schema2 original");
    require(dumpRules(old).rfind("schema 2\n",0)==0,"no automatic Schema2 upgrade");
    std::string base="schema 3\nenabled true\nruntime_defaults 1 1\nprofile G 2-6\nruntime G -1 0 preset double\nthread G C2 contains Work selector=rank:1 10 7\npackage exact org.example.app G 100\n";
    auto c=rules(base);require(resolvedProfile(c.profiles.at("G"),c).runtime.mode==1&&resolvedProfile(c.profiles.at("G"),c).runtime.rt==0,"global/perApp override");
    require(dumpRules(rules(dumpRules(c)))==dumpRules(c),"schema3 canonical roundtrip");
    for(auto bad:{base+"runtime_defaults 0 0\n",base+"runtime G 0 1 explicit none\n",base+"opt 1\n",std::string("schema 2\nenabled true\nruntime_defaults 0 0\n")}){
        bool rejected=false;try{rules(bad);}catch(...){rejected=true;}require(rejected,"new grammar rejects invalid");
    }
    for(int mode=0;mode<3;mode++)for(int rt=0;rt<2;rt++){
        Profile p;p.general=3;p.runtime={mode,rt,true,"none"};Config cfg;
        auto plan=runtimePlan(p,cfg,true,mode==2);require(plan.requestedRt==rt&&plan.effectiveMode==mode,"six runtime variants");
        require(plan.general==(mode?252:124)&&plan.c1==(mode?252:28)&&plan.c2==128,"proved topology masks");
        p.runtime.preset=false;plan=runtimePlan(p,cfg,true,true);require(plan.general==3&&plan.effectiveMode==0,"explicit masks win");
    }
    Profile p;p.runtime={2,1,true,"none"};Config cfg;
    auto plan=runtimePlan(p,cfg,true,false);require(plan.effectiveMode==0&&plan.reason=="WALT_UNQUALIFIED","mode2 honest fallback");
    plan=runtimePlan(p,cfg,false,true);require(plan.effectiveMode==0&&plan.reason=="TOPOLOGY_UNQUALIFIED","topology fallback");
    std::vector<RankedTask> candidates={{10,12},{100,11},{100,9}};
    auto reps=runtimeRepresentatives({},candidates,"dummy");require(reps.c2==11&&!reps.c1,"dummy first demoted");
    reps=runtimeRepresentatives({},candidates,"double");require(reps.c2==9&&reps.c1==11,"double two exact reps");
    reps=runtimeRepresentatives({},{{10,5}},"double");require(reps.c2==5&&!reps.c1,"no arbitrary fallback");
}
struct FakeWalt {
    int boost=0,affinity=255,writes=0;bool readDenied=false,writeDenied=false;
    bool get(int kind,int,int& value){if(readDenied)return false;value=kind==1?boost:affinity;return true;}
    bool set(int kind,int,int value){if(writeDenied)return false;writes++;(kind==1?boost:affinity)=value;return true;}
};
void waltCases(){
    for(int kind:{1,2}){
        FakeWalt kernel;WaltOwnership owner,durable;int commits=0;
        auto persist=[&]{durable=owner;commits++;};int target=kind==1?2:128,original=kind==1?0:255;
        require(applyWalt(owner,kind,17,target,kernel,persist,[]{return true;})==SchedulerResult::APPLIED,"WALT apply");
        require(commits==1&&durable.active&&durable.original==original&&durable.expected==target,"WALT durable original+intent");
        require(restoreWalt(durable,17,kernel,[]{},[]{return true;})==SchedulerResult::RESTORED,"WALT independent restore");
        int observed=0;require(kernel.get(kind,17,observed)&&observed==original,"WALT exact original readback");
    }
    {FakeWalt k;WaltOwnership o;applyWalt(o,1,17,2,k,[]{},[]{return true;});k.boost=3;
        require(restoreWalt(o,17,k,[]{},[]{return true;})==SchedulerResult::CONFLICT&&k.boost==3,"WALT foreign Owner preserved");}
    {FakeWalt k;WaltOwnership o;k.writeDenied=true;
        require(applyWalt(o,2,17,128,k,[]{},[]{return true;})==SchedulerResult::UNAVAILABLE&&o.active,"WALT rejected write evidence");
        require(restoreWalt(o,17,k,[]{},[]{return true;})==SchedulerResult::RESTORED,"WALT no-write rollback");}
    {FakeWalt k;WaltOwnership o;try{applyWalt(o,1,17,2,k,[]{throw std::runtime_error("fsync");},[]{return true;});}catch(const std::runtime_error&){}
        require(k.writes==0,"WALT fsync before write");}
    {FakeWalt k;WaltOwnership o;applyWalt(o,1,17,2,k,[]{},[]{return true;});int writes=k.writes;
        require(restoreWalt(o,17,k,[]{},[]{return false;})==SchedulerResult::DEAD&&k.writes==writes,"WALT dead/reused target no write");}
    {FakeWalt k;WaltOwnership o;k.affinity=-1;
        require(applyWalt(o,2,17,128,k,[]{},[]{return true;})==SchedulerResult::UNRESTORABLE&&!o.active&&k.writes==0,"negative default rejected before WALT mutation");}
}
void runtimeEnvelopeCases(){
    const std::string boot="00000000-0000-0000-0000-000000000000";
    const std::string owner="g"+std::string(24,'a')+":"+boot+":17:123:"+std::string(64,'b');
    std::map<int,std::string> states;
    auto pending=runtimeEnvelope(boot,owner,true,states);
    require(pending.find("\"processes\":[],\"truncated\":false")!=std::string::npos&&
        pending.find("\"status\":\"PENDING_OWNER_ACK\"")!=std::string::npos&&runtimeOwnerMatches(pending,boot,owner),"new owner pending envelope");
    auto idle=runtimeEnvelope(boot,owner,false,states);
    require(idle.find("\"status\":\"NO_RUNTIME_OBSERVATIONS\"")!=std::string::npos&&runtimeOwnerMatches(idle,boot,owner),"empty captured observation envelope");
    for(int i=0;i<4096;i++)states[i]="{\"pid\":"+std::to_string(i)+",\"package\":\""+std::string(128,'a')+"\"}";
    auto text=runtimeEnvelope(boot,owner,false,states);
    require(text.size()<=RUNTIME_STATUS_LIMIT&&text.find("\"totalProcesses\":4096")!=std::string::npos&&text.find("],\"truncated\":true}")!=std::string::npos&&runtimeOwnerMatches(text,boot,owner),"runtime observation bound/truncation");
    states.clear();states[42]="{\"pid\":42}";
    auto complete=runtimeEnvelope(boot,owner,false,states);
    require(complete.find("\"processes\":[{\"pid\":42}],\"truncated\":false")!=std::string::npos&&complete.find("\"status\":\"ACKNOWLEDGED_RUNTIME\"")!=std::string::npos&&runtimeOwnerMatches(complete,boot,owner),"complete owner-bound runtime envelope");
    for(auto wrong:std::vector<std::string>{owner+"x","g"+std::string(24,'c')+owner.substr(25),owner.substr(0,owner.size()-1)+"c"})
        require(!runtimeOwnerMatches(complete,boot,wrong),"wrong rule generation/hash owner rejected");
    require(!runtimeOwnerMatches(complete,"10000000-0000-0000-0000-000000000000",owner)&&
        !runtimeOwnerMatches(complete,boot,"")&&!runtimeOwnerMatches(complete+"\n",boot,owner)&&
        !runtimeOwnerMatches(complete.substr(0,complete.size()-1),boot,owner)&&
        !runtimeOwnerMatches("{\"schema\":1,\"boot\":\""+boot+"\",\"processes\":[]}\n",boot,owner),"stale/malformed runtime header rejected");
}
int main(){schedulerCases();grammarCases();waltCases();runtimeEnvelopeCases();puts("RUNTIME_CONTRACT_AND_FAULTS=PASS fake-kernel fixture; not physical RT proof");}
