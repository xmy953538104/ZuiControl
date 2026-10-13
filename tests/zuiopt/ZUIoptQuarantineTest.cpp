// Production quarantine decisions; only proc/cpuset are synthetic.
#define ZUIOPT_ACQUISITION_LIBRARY 1
#include "ZUIoptAcquisitionTest.cpp"
void unknownGroup(int tid,int number){
    std::ostringstream name;name<<"/ZUIopt/"<<std::hex<<number;
    for(auto file:{"tasks","mems","cpus"})Kernel::put(Kernel::virtualRoot+"/dev/cpuset"+name.str()+"/"+file,file==std::string("mems")?"0":"0-7");
    Kernel::tasks[tid]={20,name.str(),255,tid,10002,"org.other.game"};
}
void quarantineCycles(){
    AcquisitionFixture f;auto daemon=getpid();
    for(int i=0;i<40;i++){
        f.app.state=2;f.app.flags=4;f.start(2);f.confirm();f.place();
        unknownGroup(400,256+i);f.background();
        require(f.owner->recoveryHeld(),"occupied quarantine not retained");
        auto moves=Kernel::moves,affinities=Kernel::affinities;
        f.owner->cleanup();require(f.owner->recoveryHeld(),"occupied group was cleared");
        require(moves==Kernel::moves&&affinities==Kernel::affinities,"foreign task modified");
        Kernel::tasks.at(400).group="/foreground"; // Android return, not product recovery.
        f.owner->cleanup();f.empty();
        require(!f.owner->recoveryHeld(),"empty quarantine remains held");
        require(f.owner->takeQuarantineRelease()&&!f.owner->takeQuarantineRelease(),"acquire/cleanup drain must notify parked release exactly once");
        require(f.owner->deferredWaltProbeReady(),"drained quarantine permanently blocked WALT probe");
        require(read(Kernel::root+"/state/recovery-quarantine.json").find("\"tasks\":[]")!=std::string::npos,"stale quarantine diagnosis");
        require(getpid()==daemon,"quarantine restarted owner");Kernel::tasks.erase(400);
    }
    puts("QUARANTINE_SAME_OWNER_DIFFERENT_PATHS=40;OCCUPIED_PRESERVED=PASS;EMPTY_RECLAIMED=PASS;MIXED_LEASE=PASS");
}
void quarantineCapacity(){
    AcquisitionFixture f;f.owner.reset();
    for(int i=0;i<40;i++)unknownGroup(400+i,256+i);
    Placement recovery(f.count,*f.journal);
    require(recovery.recoveryHeld()&&Kernel::moves==0&&Kernel::affinities==0,"capacity lost live responsibility");
    for(int i=0;i<40;i++)Kernel::tasks.at(400+i).group="/foreground";
    recovery.cleanup();require(!recovery.recoveryHeld(),"capacity quarantine not reclaimed");
    require(recovery.deferredWaltProbeReady(),"capacity drain lost deferred probe");
    require(recovery.probeWaltCapability()=="WALT_ADAPTER_UNKNOWN"&&!recovery.deferredWaltProbeReady(),"deferred probe must be bounded, not retried per read");
    puts("QUARANTINE_40_CONCURRENT_PATHS=PASS;CAPACITY_GLOBAL_FATAL=0");
}
void quarantineRestart(){
    AcquisitionFixture f;f.owner.reset();unknownGroup(400,256);
    {Placement recovery(f.count,*f.journal);require(recovery.recoveryHeld(),"startup missing quarantine");}
    Kernel::tasks.erase(400);
    {Placement recovery(f.count,*f.journal);recovery.cleanup();require(!recovery.recoveryHeld(),"restart stale empty quarantine");}
    puts("QUARANTINE_RESTART_EMPTY=PASS");
}
void journalSemanticVersion(){
    AcquisitionFixture f;f.start(2);f.confirm();f.place();
    auto& owned=f.journal->entries.at(43).walt;owned={true,2,-1,128,true};f.journal->commit();
    auto path=Kernel::root+"/state",text=read(path+"/owner_state.v1");
    require(text.rfind("ZUIOPT_OWNER_STATE_V7 ",0)==0&&text.find("255 SEMANTIC_RESTORE")!=std::string::npos,"V7 semantic intent not durable");
    f.owner.reset();f.journal.reset();f.journal=std::make_unique<Journal>(path);f.journal->load();
    require(f.journal->semanticVersion&&f.journal->entries.at(43).walt.semantic&&f.journal->entries.at(43).walt.original==-1,"V7 upgrade/reload loses raw identity");
    f.journal->entries.at(43).walt.active=false;f.journal->commit();
    require(read(path+"/owner_state.v1").rfind("ZUIOPT_OWNER_STATE_V6 ",0)==0,"withdrawn journal cannot downgrade");
    f.journal.reset();f.journal=std::make_unique<Journal>(path);f.journal->load();
    require(!f.journal->semanticVersion&&!f.journal->entries.at(43).walt.active,"downgrade reactivated semantic control");
    puts("JOURNAL_V5_TO_V7_RAW_EXPECTED_SEMANTIC_RELOAD_TO_V6=PASS;DOWNGRADE_ONLY_AFTER_WITHDRAWAL=PASS");
}
void quarantineOrphanLease(){
    AcquisitionFixture f;f.start(2);f.confirm();f.place();
    Kernel::tasks.at(43).mask=67;f.journal->entries.erase(43);f.journal->commit();f.owner.reset();
    f.journal.reset();f.journal=std::make_unique<Journal>(Kernel::root+"/state");
    f.owner=std::make_unique<Placement>(f.count,*f.journal);
    require(f.owner->recoveryHeld()&&f.journal->leases.count(42),"occupied orphan lease not retained");
    for(auto& [_,task]:Kernel::tasks){task.group="/top-app";task.mask=255;}
    ProcessState pending;pending.pid=42;pending.uid=10001;pending.generation=10;pending.name=pending.package="org.example.game";beginAcquisition(pending,Kernel::time);
    AcquisitionFixture::TimedProc proc;
    require(f.owner->acquire(pending,proc)==BaselineResult::DEFER&&f.journal->leases.empty(),"empty orphan lease failed local retirement");
    require(f.owner->deferredWaltProbeReady(),"orphan lease blocks deferred WALT probe");
    puts("DRAINED_ORPHAN_LEASE_REACQUISITION_NO_GLOBAL_FATAL=PASS");
}
void quarantineProbeWithPlacementOnly(){
    AcquisitionFixture f;f.start(2);f.confirm();f.place();unknownGroup(400,256);f.background();
    Kernel::tasks.at(400).group="/foreground";f.owner->cleanup();
    f.app.state=2;f.app.flags=4;f.start(2);f.confirm();f.place();
    require(!f.journal->entries.empty()&&!f.journal->leases.empty()&&f.owner->deferredWaltProbeReady(),"placement-only lease starves WALT reprobe");
    f.journal->entries.at(43).scheduler.active=true;require(!f.owner->deferredWaltProbeReady(),"probe during active RT");f.journal->entries.at(43).scheduler.active=false;
    f.journal->entries.at(43).walt.active=true;require(!f.owner->deferredWaltProbeReady(),"probe during active WALT");f.journal->entries.at(43).walt.active=false;
    auto entries=f.journal->entries.size(),leases=f.journal->leases.size();f.owner->probeWaltCapability();
    require(!f.owner->deferredWaltProbePending()&&f.journal->entries.size()==entries&&f.journal->leases.size()==leases,"probe lost placement lease or repeats per read");
    puts("DEFERRED_PROBE_PLACEMENT_ONLY_SAFE_RT_WALT_BLOCKED_ONCE=PASS");
}
int main(){std::cout<<std::unitbuf;int failed=0;
    for(auto test:{quarantineCycles,quarantineCapacity,quarantineRestart,journalSemanticVersion,quarantineOrphanLease,quarantineProbeWithPlacementOnly})try{timed("QUARANTINE",test);}catch(const std::exception& e){std::cerr<<"QUARANTINE_FAIL "<<e.what()<<'\n';failed++;}
    return failed?1:0;
}
