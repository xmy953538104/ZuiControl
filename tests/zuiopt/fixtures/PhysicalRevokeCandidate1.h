    bool physicalRevoke(ProcessState& p,int tid,const Task& t){
        if(!p.writable())return p.revoked();
        if(!t.owned||!t.appliedMask||!same(p,tid,t))return false;
        auto entry=journal.entries.find(tid);
        require(entry!=journal.entries.end()&&journal.same(entry->second),"write without durable owner identity");
        auto g=group(tid);auto m=affinity(tid);
        if(!same(p,tid,t))return false;
        std::ostringstream expected;expected<<"/ZUIopt/"<<std::hex<<t.appliedMask;
        if(g==expected.str()&&m==t.appliedMask)return false;
        require(normalGroup(g)||g==expected.str(),"invalid physical owner");
        require(m!=0,"physical affinity unavailable");
        p.transition(Ownership::REVOKE_PENDING);p.next=0;
        return true;
    }
