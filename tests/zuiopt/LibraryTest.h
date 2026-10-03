// Included in the existing native suite; production parser/merge/store, temporary fixture only.
inline std::string libraryBytes(const std::string& old,const std::string& next,const std::string& choices="# defaults\n",const std::string& manual="schema 2\nenabled true\ndebug false\n",bool appopt=false){
    Manifest m;for(const auto& [k,v]:std::map<std::string,std::string>{{"kind",appopt?"APPOPT":"UPSTREAM"},{"source","fixture"},{"sourceVersion","1"},{"sourceDate",""},{"sourceCommit",""},{"sourceEvidence","host-controlled"},{"targetSoC","SM8650"},{"targetTopology","0-7"},{"oldUpstreamHash",sha256(old)},{"newUpstreamHash",appopt?sha256(old):sha256(next)},{"rulesHash",sha256(next)},{"manualHash",sha256(manual)},{"decisionsHash",sha256(choices)}})m[k]={v,false};
    m["schema"]={"1",true};return packRuleFiles({{"manifest.json",jsonText(m)},{"rules.conf",next},{"manual.conf",manual},{"decisions.conf",choices}});
}
inline void stageLibrary(RuleStore& store,const std::string& bytes,const std::string& tx){
    store.apply("begin",tx,"library:"+std::to_string(bytes.size())+":"+sha256(bytes)+":-:0:"+generationOf(store.current().effective));
    for(size_t i=0;i<bytes.size();i+=8192)store.apply("chunk",tx+":"+std::to_string(i),base64(bytes.substr(i,8192)));
}
inline void libraryTests(const fs::path& parent){
    for(bool after:{false,true}){
        auto v2path=parent/(after?"canonical-v2-after":"canonical-v2-before");require(mkdir(v2path.c_str(),0700)==0,"V82 fixture path");
        PrivateDir root(v2path.string()),generations(root,"generations",true);auto id="g"+randomId();PrivateDir oldDir(generations,id,true);
        auto canonical=dumpRules(rules(OPEN)),provenance=std::string("source=V82-fixture\n"),effective="# ZUIOPT_GENERATION "+id+"\n"+canonical;
        auto meta="ZUIOPT_CANONICAL_STATE_V2\n"+id+"\n"+randomId()+"\n"+sha256(canonical)+"\nschema2-1\n"+sha256(provenance)+"\nnone\n"+sha256(canonical)+"\n";
        oldDir.put("canonical_state.v2",meta);oldDir.put("source.bin",canonical);oldDir.put("provenance.txt",provenance);
        oldDir.put("last_good.conf","");oldDir.put("effective.conf",effective);root.put("effective.conf",effective);root.put("migration_factory.conf",BASE);
        RuleStore legacy(v2path.string(),OPEN);require(legacy.current().canonical&&!legacy.current().library,"real V82 canonical accepted");
        RuleStore::testBeforeCommit=!after;RuleStore::testAfterCommit=after;rejects([&]{legacy.initialize();});RuleStore::testBeforeCommit=RuleStore::testAfterCommit=false;
        legacy.initialize();auto upgraded=legacy.current();
        require(upgraded.library&&upgraded.user==canonical&&upgraded.upstream==dumpRules(rules(BASE)),"V82 seed preserves effective and immutable factory across interrupted migration");
        require(oldDir.get("canonical_state.v2",2048)==meta&&oldDir.get("effective.conf",RULE_LIMIT)==effective,"V82 historical bytes untouched");
        auto stable=upgraded.effective;legacy.initialize();require(legacy.current().effective==stable,"V82 migration idempotence");
    }
    auto old=rules(BASE),next=rules(OPEN);auto untouched=mergeLibrary(old,old,next);
    require(behavior(appBehavior(rules(untouched.canonical),"org.example.game"))==behavior(appBehavior(next,"org.example.game")),"untouched adopts upstream");
    auto changed=old;changed.profiles.at("G").general=cpus("0-4");
    auto conflict=mergeLibrary(old,changed,next);require(conflict.changes[0].conflict&&conflict.changes[0].decision=="KEEP_MINE","default conflict keep mine");
    require(appBehavior(rules(conflict.canonical),"org.example.game")->general==cpus("0-4"),"modified preserved");
    require(!mergeLibrary(old,changed,old).changes[0].conflict,"modified unchanged upstream");
    auto rename=old;rename.profiles["Renamed"]=rename.profiles.at("G");rename.profiles.erase("G");rename.packages[0].profile="Renamed";
    require(!mergeLibrary(old,old,rename).changes[0].changed,"profile name not semantics");
    require(mergeLibrary(old,old,rename).changes[0].mappingChanged,"profile rename visible as mapping-only change");
    auto shared=old;shared.packages.push_back({"exact","org.shared.sibling","G",80});
    auto sharedNext=shared;sharedNext.profiles.at("G").general=cpus("7");
    auto sharedOutput=rules(mergeLibrary(shared,shared,sharedNext).canonical);
    require(appBehavior(sharedOutput,"org.example.game")->general==cpus("7")&&appBehavior(sharedOutput,"org.shared.sibling")->general==cpus("7"),"shared upstream profile updates both untouched Apps");
    auto use=mergeLibrary(old,changed,next,{{"org.example.game","USE_UPSTREAM"}});
    require(appBehavior(rules(use.canonical),"org.example.game")->general==cpus("2-7"),"explicit upstream");
    auto manual=old;manual.profiles.at("G").general=cpus("7");
    auto custom=mergeLibrary(old,changed,next,{{"org.example.game","MANUAL_MERGE"}},&manual);
    require(appBehavior(rules(custom.canonical),"org.example.game")->general==cpus("7"),"manual per App merge");
    Config empty;auto deleted=mergeLibrary(old,changed,empty);require(deleted.changes[0].conflict&&appBehavior(rules(deleted.canonical),"org.example.game"),"deleted modified conflict");
    require(!appBehavior(rules(mergeLibrary(old,old,empty).canonical),"org.example.game"),"deleted untouched removed");
    changed.packages.push_back({"exact","org.user.created","G",90});
    auto preserved=rules(mergeLibrary(old,changed,next).canonical);require(appBehavior(preserved,"org.user.created"),"user created preserved");
    auto partial=rules(mergeLibrary(old,changed,importAppOpt("org.imported.new=0-7","compat",0).config,{},nullptr,true).canonical);
    require(appBehavior(partial,"org.user.created")&&appBehavior(partial,"org.example.game")&&appBehavior(partial,"org.imported.new"),"AppOpt cannot wipe unrelated Apps");
    auto path=parent/"library";require(mkdir(path.c_str(),0700)==0,"library fixture path");
    RuleStore store(path.string(),BASE);store.initialize();auto initial=store.current();
    require(initial.library&&initial.upstream==dumpRules(old),"upstream seed binds factory");
    auto tx=randomId(),bytes=libraryBytes(initial.upstream,dumpRules(next));stageLibrary(store,bytes,tx);
    auto preview=store.apply("preview",tx,"0");require(preview.find("USE_UPSTREAM")!=std::string::npos&&store.current().effective==initial.effective,"preview no effective change");
    store.apply("commit",tx,"");auto updated=store.current();require(updated.upstream==dumpRules(next)&&updated.user==untouched.canonical,"one generation updates both");
    require(store.state().find("previous_generation="+generationOf(initial.effective)+"\n")!=std::string::npos,"only current and previous accepted point exposed");
    auto stale=randomId();stageLibrary(store,bytes,stale);rejects([&]{store.apply("preview",stale,"0");});store.apply("abort",stale,"");
    store.apply("rollback",generationOf(updated.effective),"");require(store.current().user==initial.user&&store.current().upstream==initial.upstream,"whole update rollback");
    uploadData(store,"user",dumpRules(changed),randomId());auto before=store.current();
    store.apply("restore_app",generationOf(before.effective),"org.example.game:"+sha256(before.upstream));
    require(appBehavior(rules(store.current().user),"org.example.game")->general==cpus("2-6")&&appBehavior(rules(store.current().user),"org.user.created"),"per App restore retains unrelated");
    auto appopt=libraryBytes(store.current().upstream,"org.example.game=7\n", "org.example.game USE_UPSTREAM\n","schema 2\nenabled true\ndebug false\n",true);
    tx=randomId();stageLibrary(store,appopt,tx);store.apply("preview",tx,"0");auto upstream=store.current().upstream;store.apply("commit",tx,"");
    require(store.current().upstream==upstream&&appBehavior(rules(store.current().user),"org.example.game")->general==cpus("7")&&appBehavior(rules(store.current().user),"org.user.created"),"AppOpt explicit merge preserves baseline/other Apps");
    for(bool after:{false,true}){
        before=store.current();tx=randomId();auto candidate=libraryBytes(before.upstream,dumpRules(next));stageLibrary(store,candidate,tx);store.apply("preview",tx,"0");
        RuleStore::testBeforeCommit=!after;RuleStore::testAfterCommit=after;rejects([&]{store.apply("commit",tx,"");});RuleStore::testBeforeCommit=RuleStore::testAfterCommit=false;
        auto actual=store.current();require(after?actual.upstream==dumpRules(next):actual.effective==before.effective,"library crash whole generation");
    }
    puts("ZUIOPT_UPSTREAM_CONFLICT_MATRIX_APPOPT_PRESERVATION_ROLLBACK=PASS");
}
