#include "../../native/zuiopt/ZUIopt_rules.h"
#include "../../native/zuiopt/ZUIopt_runtime.h"
using namespace ZUIopt;
struct FakeKernel {
    SchedulerState state;bool readDenied=false,writeDenied=false;int writes=0,reads=0;
    bool get(int,SchedulerState& out){reads++;if(readDenied)return false;out=state;return true;}
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
        require(restoreScheduler(o,9,k,[]{},[]{return true;})==SchedulerResult::CONFLICT&&k.state==foreign&&!o.active,"foreign scheduler preserved");
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
int main(){schedulerCases();grammarCases();waltCases();puts("RUNTIME_CONTRACT_AND_FAULTS=PASS fake-kernel fixture; not physical RT proof");}
