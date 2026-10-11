// Production Journal/Placement on the shared multi-TGID proc/cpuset boundary.
#define ZUIOPT_ACQUISITION_LIBRARY 1
#include "ZUIoptAcquisitionTest.cpp"

struct DescendantFixture:AcquisitionFixture {
    void boot(){start(2);place();owner->apply(p(),43,p().tasks.at(43),28,"fork_parent");}
    std::string owned(Mask mask=28){return placementGroup(journal->leases.at(42).placementLease,mask);}
    void child(int pid=150,Mask mask=28,int user=10001,uint64_t birth=0){
        if(!birth)birth=p().ownershipFloor+1;
        Kernel::tasks[pid]={birth,owned(),mask,pid,user,"/apk/libadcb_child.so"};
    }
    void background(int64_t time=0){Kernel::time=time;p().activity_foreground=false;owner->release(p(),ReleaseCause::AUTHORITY_BACKGROUND,time);}
    void reload(){owner.reset();journal.reset();journal=std::make_unique<Journal>(Kernel::root+"/state");owner=std::make_unique<Placement>(count,*journal);}
    void safe(int tid,Mask mask=255,const std::string& group="/top-app"){
        require(Kernel::tasks.at(tid).group==group&&Kernel::tasks.at(tid).mask==mask,"descendant placement residue");
    }
};
void forkExec(){
    DescendantFixture f;f.boot();f.child();auto birth=Kernel::tasks.at(150).birth;
    Kernel::tasks[151]={birth+1,f.owned(),28,150,10001,"renamed-worker"};
    require(identity(150).start==birth&&!identity(42,150).start,"fake kernel must have a distinct TGID");
    OwnerRecord r;auto& lease=f.journal->leases.at(42);lease.scheduler.active=lease.walt.active=true;
    require(f.journal->inherited(150,r)&&r.pid==150&&r.processStart==birth&&r.threadStart==birth&&r.placementLease==lease.placementLease,"own child identity/root link");
    require(!r.scheduler.active&&!r.walt.active,"parent scheduler/WALT ownership copied");lease.scheduler.active=lease.walt.active=false;
    Kernel::tasks.at(150).name="/apk/exec-replaced-name";int childWrites=0;
    Kernel::beforeWrite=[&](int tid,bool,Mask){if(tid==150||tid==151){auto it=f.journal->entries.find(tid);
        require(it!=f.journal->entries.end()&&it->second.pid==150&&it->second.processStart==birth&&f.journal->same(it->second),"child write before durable own birth");childWrites++;}};
    f.background();f.empty();f.safe(150);f.safe(151);require(childWrites==4,"fork/exec descendant was not fully restored");
    puts("DESCENDANT_FORK_EXEC_OWN_TGID_BIRTH_DURABLE_BEFORE_WRITE_NO_PARENT_RT_WALT_COPY=PASS");
}
void sameTickAndCreation(){
    {DescendantFixture f;f.boot();f.child(150,28,10001,f.p().ownershipFloor);
        Kernel::tasks[151]={f.p().ownershipFloor,f.owned(),28,150,10001,"same-tick-child-thread"};
        Kernel::tasks[44]={f.p().ownershipFloor,f.owned(),28,42,10001,"same-tick-root-thread"};
        f.background();f.empty();for(int tid:{150,151,44})f.safe(tid);}
    {DescendantFixture f;f.start(2);bool born=false;int childWrites=0;
        Kernel::hook=[&](const std::string& path){if(path!="/proc/uptime")return;Kernel::hook={};born=true;
            Kernel::tasks[150]={BaselineProc{}.floor(),"/top-app",255,150,10001,"prelease-fork"};};
        Kernel::beforeWrite=[&](int tid,bool,Mask){if(tid==150)childWrites++;};
        f.confirm();require(born&&Kernel::tasks.at(150).birth==f.p().ownershipFloor,"prelease same-tick construction missed");
        f.place();f.safe(150);require(!f.journal->entries.count(150),"parent TID placement retroactively adopted prelease fork");
        f.background();f.empty();f.safe(150);require(!childWrites,"unmanaged prelease fork was swept");}
    {DescendantFixture f;f.start(2);int creates=0;Kernel::beforeCreate=[&](const std::string&){creates++;};
        Kernel::journalWriteError=true;expectFailure([&]{f.confirm();},"journal write failed");
        require(!creates&&!Kernel::moves&&!Kernel::affinities&&!fs::exists(Kernel::root+"/state/owner_state.v1"),"failed root commit created or populated a group");
        Kernel::journalWriteError=false;f.reload();require(f.journal->entries.empty()&&f.journal->leases.empty(),"failed root commit became a live lease");}
    {DescendantFixture f;f.start(2);f.confirm();int creates=0;
        Kernel::beforeCreate=[&](const std::string& path){auto evidence=read(Kernel::root+"/state/owner_state.v1");
            auto newline=evidence.find('\n');require(newline!=evidence.npos,"group create before durable journal header");
            auto body=evidence.substr(newline+1);
            require(evidence.substr(0,newline)=="ZUIOPT_OWNER_STATE_V5 "+std::to_string(checksum(body)),"group create before valid durable V5 CRC");
            require(body.find("\nL 42 ")!=body.npos,"group create before durable root lease");
            for(int tid:{42,43}){const auto& r=f.journal->entries.at(tid);
                // T rows start with TGID; the separately recorded TID is field five.
                auto prefix="\nT "+std::to_string(r.pid)+' '+std::to_string(r.processStart)+' '+std::to_string(r.user)+' '+
                    std::to_string(r.tid)+' '+std::to_string(r.threadStart)+' ';
                require(body.find(prefix)!=body.npos,"group create before durable own PID/TID birth");}
            require(body.find(f.journal->leases.at(42).placementLease)!=body.npos,"group create before durable acquisition token");
            require(path.find(f.journal->leases.at(42).placementLease)!=path.npos,"created group not bound to durable lease");creates++;};
        f.place();require(creates==1,"expected one flat group creation");f.background();f.empty();}
    {DescendantFixture f;f.start(2);f.confirm();auto group=f.owned(124);
        for(auto file:{"tasks","mems","cpus"})Kernel::put(Kernel::virtualRoot+"/dev/cpuset"+group+"/"+file,file==std::string("cpus")?"2-6":"0");
        Kernel::tasks[150]={f.p().ownershipFloor+1,group,124,150,10001,"existing-resource"};auto writes=Kernel::moves+Kernel::affinities;
        expectFailure([&]{f.place();},"cpuset child create");
        require(Kernel::moves+Kernel::affinities==writes&&Kernel::tasks.at(150).group==group&&Kernel::tasks.at(150).mask==124,
                "EEXIST path rebound or populated");}
    puts("DESCENDANT_V5_SAME_TICK_FORK_THREAD_PASS_ROOT_COMMIT_BEFORE_MKDIR_EEXIST_NO_REBIND=PASS");
}
void parentDeathAndCrash(){
    for(bool reuse:{false,true}){
        DescendantFixture f;f.boot();f.child();Kernel::tasks.erase(42);Kernel::tasks.erase(43);
        if(reuse)Kernel::tasks[42]={f.p().ownershipFloor+10,"/background",67,42,10001,"foreign-reused-pid"};
        int rootWrites=0;Kernel::beforeWrite=[&](int tid,bool,Mask){if(tid==42)rootWrites++;
            if(tid==150)require(f.journal->entries.count(150)&&f.journal->same(f.journal->entries.at(150)),"crash child write before own record");};
        f.reload();f.safe(150);require(f.journal->entries.empty()&&f.journal->leases.empty()&&!rootWrites,"dead/reused root retained or overwritten");
        if(reuse)f.safe(42,67,"/background");
    }
    {DescendantFixture f;f.boot();f.child();Kernel::beforeWrite=[](int tid,bool,Mask){if(tid==42)throw std::runtime_error("fixture crash after child adoption");};
        expectFailure([&]{f.background();},"fixture crash after child adoption");
        require(f.journal->entries.count(150)&&f.journal->entries.at(150).pid==150,"crash lost durable child adoption");
        Kernel::beforeWrite={};f.reload();f.safe(150);require(f.journal->leases.empty(),"restart child lease leak");}
    {DescendantFixture f;f.boot();f.child();Kernel::tasks[250]={f.p().ownershipFloor+2,f.owned(),28,250,10001,"grandchild-after-exec"};
        Kernel::tasks.erase(150);Kernel::tasks.erase(42);Kernel::tasks.erase(43);
        f.reload();f.safe(250);require(f.journal->leases.empty(),"orphan resource lineage lost");}
    puts("DESCENDANT_PARENT_DEATH_EXEC_ROOT_PID_REUSE_AND_CRASH_AFTER_ADOPTION=PASS");
}
void durableFailure(){
    DescendantFixture f;f.boot();f.child();auto evidence=read(Kernel::root+"/state/owner_state.v1");
    auto writes=Kernel::moves+Kernel::affinities;auto commits=f.journal->commits;Kernel::journalWriteError=true;
    expectFailure([&]{f.background();},"journal write failed");
    require(Kernel::moves+Kernel::affinities==writes&&f.journal->commits==commits&&read(Kernel::root+"/state/owner_state.v1")==evidence,
            "child written before durable adoption or previous journal changed");
    require(Kernel::tasks.at(150).group==f.owned()&&Kernel::tasks.at(150).mask==28,"failed commit changed child");
    Kernel::journalWriteError=false;f.reload();f.safe(150);require(f.journal->leases.empty(),"failed adoption cannot recover original lease");
    puts("DESCENDANT_ADOPTION_WRITE_FAILURE_ZERO_KERNEL_WRITES_PRIOR_JOURNAL_IMMUTABLE=PASS");
}
void busyAndLateFork(){
    {DescendantFixture f;f.boot();Kernel::removeError=EBUSY;f.background();
        require(f.p().backgroundReleasing()&&f.journal->leases.count(42)&&fs::exists(Kernel::root+"/state/owner_state.v1"),"busy group cleared durable lease");
        Kernel::removeError=0;f.background(f.p().next);f.empty();}
    {DescendantFixture f;f.boot();bool injected=false;
        Kernel::beforeRemove=[&](const std::string& path){if(injected)return;injected=true;Kernel::beforeRemove={};
            auto text=trim(read(path+"/cpus"));auto mask=cpus(text);
            Kernel::tasks[150]={f.p().ownershipFloor+5,path.substr(11),mask,150,10001,"late-fork"};};
        f.background();require(injected&&f.p().backgroundReleasing()&&f.journal->leases.count(42),"late fork cleared lease before kernel rmdir fence");
        f.background(f.p().next);f.empty();f.safe(150);}
    puts("DESCENDANT_BUSY_RMDIR_LATE_FORK_RETAINS_LEASE_UNTIL_KERNEL_EMPTINESS=PASS");
}
void foreignAndReuse(){
    {DescendantFixture f;f.boot();f.child(150,2);f.background();f.empty();f.safe(150,2);}
    {DescendantFixture f;f.boot();f.child();bool changed=false;
        Kernel::beforeWrite=[&](int tid,bool move,Mask){if(!changed&&tid==42&&move){changed=true;Kernel::tasks.at(150).group="/background";Kernel::tasks.at(150).mask=67;}};
        f.background();f.empty();require(changed,"foreign handoff injection missed");f.safe(150,67,"/background");}
    for(bool owned:{false,true}){
        DescendantFixture f;f.boot();f.child();OwnerRecord r;require(f.journal->inherited(150,r),"reuse prestate");f.journal->entries[150]=r;f.journal->commit();
        auto nextBirth=r.processStart+10;Kernel::tasks.at(150).birth=nextBirth;Kernel::tasks.at(150).name="new-generation";
        if(!owned){Kernel::tasks.at(150).group="/background";Kernel::tasks.at(150).mask=67;}
        int writes=0;Kernel::beforeWrite=[&](int tid,bool,Mask){if(tid==150){writes++;
            require(f.journal->entries.at(150).processStart==nextBirth,"stale child PID/TID write");}};
        f.background();f.empty();if(owned){require(writes==2,"new owned birth not separately adopted");f.safe(150);}else{require(!writes,"normal foreign reuse was written");f.safe(150,67,"/background");}
    }
    puts("DESCENDANT_FOREIGN_AFFINITY_ANDROID_GROUP_AND_PID_TID_REUSE_PRESERVED=PASS");
}
void unknownFailClosed(){
    for(int kind=0;kind<4;kind++){
        DescendantFixture f;f.boot();f.child(150,28,kind==1?10002:10001,kind==0?f.p().ownershipFloor-1:0);
        if(kind==2){auto token=f.journal->leases.at(42).placementLease+"0";auto group=placementGroup(token,28);
            for(auto file:{"tasks","mems","cpus"})Kernel::put(Kernel::virtualRoot+"/dev/cpuset"+group+"/"+file,file==std::string("cpus")?"2-4":"0");Kernel::tasks.at(150).group=group;}
        if(kind==3){Kernel::tasks.at(150).tgid=42;Kernel::tasks.at(150).birth=f.p().ownershipFloor-1;}
        auto moves=Kernel::moves,affinity=Kernel::affinities;auto commits=f.journal->commits;
        expectFailure([&]{f.background();},"background unknown owned task");
        require(Kernel::moves==moves&&Kernel::affinities==affinity&&f.journal->commits==commits&&f.journal->leases.count(42),"unknown descendant wrote kernel or cleared evidence");
    }
    puts("DESCENDANT_PRELEASE_PROCESS_THREAD_WRONG_UID_UNBOUND_PATH_FAIL_CLOSED_ZERO_WRITES=PASS");
}
void twoBaselines(){
    DescendantFixture f;f.boot();f.child(150,124);Kernel::tasks.at(150).group=f.owned(124);
    Kernel::tasks[200]={20,"/background",67,200,10002,"org.other.game"};Kernel::tasks[201]={21,"/background",67,200,10002,"worker"};
    ProcessState second;second.pid=200;second.uid=10002;second.generation=20;second.name=second.package="org.other.game";second.activity_foreground=true;
    beginAcquisition(second,1000);AcquisitionFixture::TimedProc proc;Kernel::time=1000;require(f.owner->acquire(second,proc)==BaselineResult::DEFER,"second baseline first pass");
    Kernel::time=1250;require(f.owner->acquire(second,proc)==BaselineResult::STABLE,"second baseline confirmation");
    std::map<int,Mask> masks{{200,124},{201,124}};f.owner->stageMasks(second,masks);for(auto& [tid,t]:second.tasks)f.owner->apply(second,tid,t,124,"second");
    auto group=placementGroup(f.journal->leases.at(200).placementLease,124);Kernel::tasks[300]={second.ownershipFloor+1,group,124,300,10002,"second-child"};
    f.background();f.safe(150);require(f.journal->leases.count(200)&&Kernel::tasks.at(300).group==group&&Kernel::tasks.at(300).mask==124,"first release overwrote second owner");
    second.activity_foreground=false;f.owner->release(second,ReleaseCause::AUTHORITY_BACKGROUND,1250);
    f.safe(300,67,"/background");f.safe(200,67,"/background");require(f.journal->entries.empty()&&f.journal->leases.empty(),"two-baseline journal leak");
    puts("DESCENDANT_EQUAL_MASK_DIFFERENT_ORIGINAL_BASELINES_NO_CROSS_OWNER_RESTORE=PASS");
}
void legacyRead(){
    for(int version=1;version<=4;version++)for(bool fork:{false,true}){
        DescendantFixture f;f.boot();auto legacy=placementGroup({},124);
        for(auto file:{"tasks","mems","cpus"})Kernel::put(Kernel::virtualRoot+"/dev/cpuset"+legacy+"/"+file,file==std::string("cpus")?"2-6":"0");
        for(auto& [_,t]:Kernel::tasks){t.group=legacy;t.mask=124;}
        f.journal->placementVersion=false;f.journal->runtimeVersion=version>=3;f.journal->waltVersion=version>=4;
        for(auto& [_,r]:f.journal->entries)r.placementLease.clear();for(auto& [_,r]:f.journal->leases)r.placementLease.clear();f.journal->commit();
        if(version==1){auto text=read(Kernel::root+"/state/owner_state.v1");std::istringstream rows(text.substr(text.find('\n')+1));std::string body,line;
            std::getline(rows,line);body=line+'\n';while(std::getline(rows,line))if(line.rfind("T ",0)==0)body+=line.substr(2)+'\n';
            Kernel::put(Kernel::root+"/state/owner_state.v1","ZUIOPT_OWNER_STATE_V1 "+std::to_string(checksum(body))+"\n"+body);}
        if(fork)Kernel::tasks[150]={f.p().ownershipFloor+1,legacy,124,150,10001,"legacy-unproved-fork"};
        auto writes=Kernel::moves+Kernel::affinities;auto evidence=read(Kernel::root+"/state/owner_state.v1");
        if(fork){expectFailure([&]{f.reload();},"RECOVERY_UNKNOWN_TASK_FAIL_CLOSED");require(Kernel::moves+Kernel::affinities==writes&&read(Kernel::root+"/state/owner_state.v1")==evidence,"legacy unknown was guessed or journal changed");}
        else{f.reload();f.safe(42);f.safe(43);require(f.journal->entries.empty()&&f.journal->leases.empty(),"legacy known recovery changed");}
    }
    puts("DESCENDANT_V1_V2_V3_V4_READ_KNOWN_RESTORE_UNKNOWN_FORK_IMMUTABLE_FAIL_CLOSED=PASS");
}
void formatGuards(){
    for(int kind=0;kind<3;kind++){
        DescendantFixture f;f.boot();f.child();OwnerRecord r;require(f.journal->inherited(150,r),"format child prestate");
        f.journal->entries[150]=r;f.journal->commit();auto text=read(Kernel::root+"/state/owner_state.v1");
        std::istringstream rows(text.substr(text.find('\n')+1));std::string body,line;auto token=r.placementLease;
        while(std::getline(rows,line)){
            if((kind==0&&line.rfind("T 150 ",0)==0)||kind==1){auto at=line.find(token);if(at!=line.npos)line.replace(at,token.size(),kind==0?token+"0":"l2b"+token.substr(3));}
            if(kind==2&&line.rfind("T 150 ",0)==0){auto old="T 150 "+std::to_string(r.processStart)+" ";line.replace(0,old.size(),"T 150 "+std::to_string(f.p().ownershipFloor-1)+" ");}
            body+=line+'\n';
        }
        auto evidence="ZUIOPT_OWNER_STATE_V5 "+std::to_string(checksum(body))+"\n"+body;Kernel::put(Kernel::root+"/state/owner_state.v1",evidence);
        auto writes=Kernel::moves+Kernel::affinities;expectFailure([&]{f.reload();},kind==0?"journal identity":"journal inheritance lease");
        require(Kernel::moves+Kernel::affinities==writes&&read(Kernel::root+"/state/owner_state.v1")==evidence,"invalid V5 link wrote kernel or changed evidence");
    }
    for(int kind=0;kind<3;kind++){
        DescendantFixture f;f.boot();f.journal->placementVersion=f.journal->runtimeVersion=f.journal->waltVersion=false;
        for(auto& [_,r]:f.journal->entries)r.placementLease.clear();for(auto& [_,r]:f.journal->leases)r.placementLease.clear();
        if(kind==1)f.journal->entries.clear();if(kind==2){f.journal->entries.clear();f.journal->leases.clear();f.journal->runtimeVersion=true;f.journal->globals["rr_timeslice_ms"]={100,3};}
        f.journal->commit();auto evidence=read(Kernel::root+"/state/owner_state.v1");auto writes=Kernel::moves+Kernel::affinities;auto commits=f.journal->commits;
        ProcessState pending;pending.pid=200;pending.uid=10002;pending.generation=20;pending.name=pending.package="org.other.game";beginAcquisition(pending,1000);
        AcquisitionFixture::TimedProc proc;expectFailure([&]{f.owner->acquire(pending,proc);},"uncommitted journal state");
        require(!f.journal->placementVersion&&Kernel::moves+Kernel::affinities==writes&&f.journal->commits==commits&&read(Kernel::root+"/state/owner_state.v1")==evidence,
                "live legacy journal silently upgraded or modified");
    }
    puts("DESCENDANT_V5_ROOTLINK_AND_BIRTH_VALIDATED_NONEMPTY_LEGACY_NEVER_RELABELED=PASS");
}
int main(){try{std::cout<<std::unitbuf;require(getuid()==0,"isolated root descendant fixture");
    timed("DESCENDANT_FORK_EXEC",forkExec);timed("DESCENDANT_DEATH_CRASH",parentDeathAndCrash);timed("DESCENDANT_BUSY_LATE",busyAndLateFork);
    timed("DESCENDANT_FOREIGN_REUSE",foreignAndReuse);timed("DESCENDANT_UNKNOWN",unknownFailClosed);timed("DESCENDANT_TWO_BASELINES",twoBaselines);timed("DESCENDANT_LEGACY",legacyRead);
    timed("DESCENDANT_DURABLE_FAILURE",durableFailure);timed("DESCENDANT_FORMAT_GUARDS",formatGuards);
    timed("DESCENDANT_SAME_TICK_AND_CREATION",sameTickAndCreation);
    puts("ZUIOPT_DESCENDANT_NATIVE=PASS");return 0;
}catch(const std::exception& e){std::cerr<<"DESCENDANT_FAIL "<<e.what()<<'\n';return 1;}}
