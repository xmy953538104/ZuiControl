// Existing RuleStore fixture; the inline envelope must preserve normal transaction failure semantics.
std::string inlineValue(const std::string& body,const std::string& generation){
    uLongf size=compressBound(body.size());std::string packed(size,'\0');
    require(compress2(reinterpret_cast<Bytef*>(packed.data()),&size,reinterpret_cast<const Bytef*>(body.data()),body.size(),Z_DEFAULT_COMPRESSION)==Z_OK,"fixture zlib");packed.resize(size);
    return "zlib-user-v1:"+generation+":"+std::to_string(body.size())+":"+sha256(body)+":"+base64(packed);
}
void inlineCommitTests(const fs::path& parent){
    auto path=parent/"inline-commit";require(mkdir(path.c_str(),0700)==0,"inline fixture directory");
    RuleStore store(path.string(),BASE);store.initialize();auto original=store.current();auto generation=generationOf(original.effective);
    require(store.apply("commit",randomId(),inlineValue(OPEN,generation)).find("generation=g")==0,"inline ordinary commit");
    require(store.current().user==dumpRules(rules(OPEN))&&store.current().upstream==original.upstream,"inline preserves upstream");
    auto accepted=store.current().effective;rejects([&]{store.apply("commit",randomId(),inlineValue(BASE,generation));});
    require(store.current().effective==accepted,"inline stale CAS does not overwrite");generation=generationOf(accepted);
    auto valid=inlineValue(BASE,generation);
    for(auto value:{valid+"AAAA",std::string("zlib-user-v1:")+generation+":65537:"+sha256(BASE)+":AAAA",valid.substr(0,valid.size()-4),inlineValue(std::string("schema 2\nenabled true\n"),generation),inlineValue(std::string("schema 3\ninvalid x\n"),generation)}){
        rejects([&]{store.apply("commit",randomId(),value);});require(store.current().effective==accepted,"inline invalid leaves current");
    }
    auto hashBad=valid;auto hashAt=hashBad.find(sha256(BASE));hashBad[hashAt]=hashBad[hashAt]=='0'?'1':'0';rejects([&]{store.apply("commit",randomId(),hashBad);});
    auto owner=randomId();store.apply("begin",owner,"user:"+std::to_string(BASE.size())+":"+sha256(BASE)+":-:0:"+generation);
    rejects([&]{store.apply("commit",randomId(),valid);});
    store.apply("chunk",owner+":0",base64(BASE));require(store.apply("validate",owner,"").find("validation=PASS")==0,"foreign pending upload retained");store.apply("abort",owner,"");
    RuleStore::testBeforeCommit=true;rejects([&]{store.apply("commit",randomId(),valid);});RuleStore::testBeforeCommit=false;
    require(store.current().effective==accepted,"inline pre-commit failure preserves active generation");
    store.apply("commit",randomId(),valid);accepted=store.current().effective;generation=generationOf(accepted);
    RuleStore::testAfterCommit=true;rejects([&]{store.apply("commit",randomId(),inlineValue(OPEN,generation));});RuleStore::testAfterCommit=false;
    require(store.current().user==dumpRules(rules(OPEN)),"inline indeterminate post-commit retains committed truth");
    auto active=generationOf(store.current().effective);store.apply("rollback",active,"");require(store.current().user==dumpRules(rules(BASE)),"inline forward-generation rollback");
    require(store.state().find("settings_pending=\n")==0,"inline leaves no settings lease");
    puts("ZUIOPT_INLINE_COMMIT=PASS;CAS_HASH_SCHEMA_FOREIGN_UPLOAD_PRE_POST_COMMIT_ROLLBACK");
}
