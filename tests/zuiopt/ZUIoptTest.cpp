// Host/isolated Android fixture only. Never linked into the production binary.
#define ZUIOPT_TEST 1
#include "../../native/zuiopt/ZUIopt_store.h"
#include "../../native/zuiopt/ZUIopt_owner.h"
#include "../../native/zuiopt/ZUIopt_lifecycle.h"
#include <csignal>
#include <filesystem>
#include <functional>
#include <fstream>
#include <iostream>
using namespace ZUIopt;
namespace fs=std::filesystem;
const std::string BASE="schema 2\nenabled true\nprofile G 2-6\npackage exact org.example.game G 100\n";
const std::string OPEN="schema 2\nenabled true\nprofile G 2-7\nthread G ALL glob \"Job.worker[AB]*\" selector=all 30 2-4\nthread G R1 prefix Top selector=rank:1 20 7\nthread G R2 contains Runner selector=rank:2 10 5-6\npackage exact org.example.game G 100\n";
void rejects(const std::function<void()>& action){bool rejected=false;try{action();}catch(const std::exception&){rejected=true;}require(rejected,"expected rejection");}
std::string readPack(const char* path){std::ifstream input(path,std::ios::binary);require(input.good(),"fixture input open");std::string data(PACK_LIMIT+1,'\0');input.read(data.data(),data.size());data.resize(input.gcount());require(data.size()<=PACK_LIMIT&&input.eof(),"fixture input bound");return data;}
void uploadData(RuleStore& store,const std::string& kind,const std::string& data,const std::string& id,const std::string& pack="-"){
    store.apply("begin",id,kind+":"+std::to_string(data.size())+":"+sha256(data)+":"+pack+":0");
    for(size_t offset=0;offset<data.size();offset+=8192)store.apply("chunk",id+":"+std::to_string(offset),base64(data.substr(offset,8192)));
    store.apply("commit",id,"");
}
int procProbeReads=0;
Identity countedIdentity(int,int){procProbeReads++;return Identity{1,0,'S'};}
void earlyFilterTests(){
    auto config=rules(BASE);
    Snapshot managed{"org.example.game",42,10001,0,0,2,false,{"org.example.game"}};
    std::vector<Snapshot> rejected;
    for(int user:{0,1000,2000,9999,20000,110001}){auto s=managed;s.uid=user;rejected.push_back(s);}
    for(auto packages:std::vector<std::vector<std::string>>{{},{"org.example.game","org.other.app"},{"org.other.app"},{"invalid"},{"org..invalid"}}){auto s=managed;s.packages=packages;rejected.push_back(s);}
    for(const auto& s:rejected){procProbeReads=0;require(!managedIdentity(s,config,countedIdentity).start&&procProbeReads==0,"impossible snapshot must not read proc");}
    config.enabled=false;procProbeReads=0;require(!managedIdentity(managed,config,countedIdentity).start&&procProbeReads==0,"disabled config skips proc");config.enabled=true;
    for(int user:{10000,10001,19999}){managed.uid=user;procProbeReads=0;require(managedIdentity(managed,config,countedIdentity).start==1&&procProbeReads==1,"eligible USER0 app reaches one identity probe");}
    puts("ZUIOPT_RESOLVE_EARLY_FILTER=PASS;REJECTED_PROC_IDENTITY_READ_COUNT=0");
}
void lifecycleTests(const fs::path& parent){
    auto root=parent/"lifecycle";require(mkdir(root.c_str(),0700)==0,"lifecycle fixture directory");
    const std::string boot="11111111-2222-3333-4444-555555555555";
    require(!fs::exists(root/"startup.v1")&&!fs::exists(root/"fatal.v1"),"no receipt before daemon starts");
    std::string previous;
    for(auto stage:{StartupStage::CORE_CONSTRUCTED,StartupStage::OBSERVER_OK,StartupStage::SNAPSHOT_OK,
                    StartupStage::PACKAGE_ABI_OK,StartupStage::PLACEMENT_OK,StartupStage::RECONCILE_OK,StartupStage::READY}){
        int old=previous.empty()?-1:open((root/"startup.v1").c_str(),O_RDONLY|O_CLOEXEC);
        require(recordLifecycle(root.string(),boot,stage),"startup durable receipt");
        struct stat st{};require(lstat((root/"startup.v1").c_str(),&st)==0&&S_ISREG(st.st_mode)&&(st.st_mode&07777)==0600&&st.st_uid==geteuid()&&st.st_nlink==1&&st.st_size<1024,"bounded private startup file");
        if(old>=0){char bytes[1024]{};auto n=::read(old,bytes,sizeof(bytes));close(old);require(n>=0&&std::string(bytes,static_cast<size_t>(n))==previous,"atomic replacement preserves existing reader");}
        previous=read((root/"startup.v1").string());require(previous.find("stage="+std::string(stageName(stage))+"\nstate=OK\nreason=none\n")!=previous.npos,"startup stage receipt");
    }
    require(!fs::exists(root/"fatal.v1"),"success does not invent fatal");
    std::runtime_error fatal("proc identity open failed");
    require(recordLifecycle(root.string(),boot,StartupStage::RECONCILE,&fatal),"fatal persisted");
    auto data=read((root/"fatal.v1").string());
    require(data.find("stage=RECONCILE\nstate=FAIL\nreason=proc_identity_open_failed\n")!=data.npos&&data.size()<1024,"exact fatal stage and reason");
    require(fatalReason(std::runtime_error("parcel/status=-13"))=="binder_status_-13","bounded Binder status");
    require(fatalReason(std::runtime_error("journal release failed TID=12345"))=="journal_release_failed","release TID redacted");
    require(fatalReason(std::runtime_error("RECOVERY_UNKNOWN_TASK_FAIL_CLOSED tid=12345"))=="recovery_unknown_task_fail_closed","unknown task TID redacted");
    for(auto text:{std::string("org.private.app RenderThread pid=12345\n"),std::string(10000,'x'),std::string("parcel/status=org.private.app"),std::string("parcel/status=123456789012345")}){
        auto reason=fatalReason(std::runtime_error(text));require(reason=="unclassified_exception"&&reason.size()<=96,"private or unbounded exception redacted");
    }
    auto startupBefore=read((root/"startup.v1").string());
    require(!recordLifecycle(root.string(),"private invalid boot",StartupStage::READY),"invalid boot diagnostic rejected nonfatally");
    require(read((root/"startup.v1").string())==startupBefore,"failed diagnostic preserves prior receipt");
    fs::remove(root/"fatal.v1");fs::create_symlink(root/"startup.v1",root/"fatal.v1");
    static_assert(noexcept(recordLifecycle(std::declval<const std::string&>(),std::declval<const std::string&>(),StartupStage::STOP,nullptr)),"diagnostic cannot escape fatal handler");
    require(!recordLifecycle(root.string(),boot,StartupStage::RECONCILE,&fatal),"receipt write failure is nonfatal");
    require(read((root/"startup.v1").string())==startupBefore,"diagnostic symlink target unchanged");
    require(!recordLifecycle((root/"absent").string(),boot,StartupStage::READY),"unavailable directory is nonfatal");
    RuntimeBlocker blocker;
    require(blocker.record(root.string(),boot,RuntimeBlockerReason::PROC_READ_PERMISSION),"runtime blocker persisted");
    auto blockerPath=root/"runtime_blocker.v1";struct stat st{};
    require(lstat(blockerPath.c_str(),&st)==0&&(st.st_mode&07777)==0600&&st.st_uid==geteuid()&&st.st_size<1024,"runtime blocker privacy/mode/bound");
    auto blockerData=read(blockerPath.string());auto inode=st.st_ino;
    require(blockerData=="ZUIOPT_RUNTIME_BLOCKER_V1\nboot="+boot+"\nstage=APP_ACCESS\nstate=BLOCKED\nreason=proc_read_permission\n","runtime blocker fixed private format");
    for(int i=0;i<1000;i++)require(!blocker.record(root.string(),boot,RuntimeBlockerReason::PROC_READ_PERMISSION),"repeat reason no steady writes");
    require(lstat(blockerPath.c_str(),&st)==0&&st.st_ino==inode&&read(blockerPath.string())==blockerData,"repeat receipt inode/content unchanged");
    require(blocker.record(root.string(),boot,RuntimeBlockerReason::PROC_UID_PERMISSION),"first distinct reason recorded");
    fs::remove(blockerPath);fs::create_symlink(root/"startup.v1",blockerPath);
    require(!blocker.record(root.string(),boot,RuntimeBlockerReason::PACKAGE_AUTHORITY_PERMISSION),"runtime write error nonfatal");
    require(read((root/"startup.v1").string())==startupBefore,"runtime receipt cannot write through symlink");
    fs::remove(blockerPath);
    require(!blocker.record(root.string(),boot,RuntimeBlockerReason::PACKAGE_AUTHORITY_PERMISSION),"failed write is not retried every event");
    puts("ZUIOPT_RUNTIME_BLOCKER_BOUNDED_PRIVATE_DEDUP_NONFATAL=PASS");
    puts("ZUIOPT_STARTUP_FATAL_RECEIPTS=PASS;WRITE_FAILURE_IS_NONFATAL=PASS");
}
void journalTests(const fs::path& parent){
    require(getuid()==0,"journal fixture runs as isolated root");
    auto path=parent/"journal";Journal journal(path.string());PrivateDir directory(path.string());
    const auto current=journal.currentBootId();
    rejects([&]{Journal second(path.string());});
    auto put=[&](const std::string& magic,const std::string& body){directory.put("owner_state.v1",magic+" "+std::to_string(checksum(body))+"\n"+body);};
    for(auto bad:{"broken","ZUIOPT_OWNER_STATE_V9 0\n","ZUIOPT_OWNER_STATE_V2 0\ncorrupt\n"}){
        directory.put("owner_state.v1",bad);rejects([&]{journal.load();});require(journal.entries.empty()&&journal.leases.empty(),"corrupt journal does not acquire ownership");
    }
    put("ZUIOPT_OWNER_STATE_V2",current+"\nX 1 1 10000 1 1 p n /background 1\n");rejects([&]{journal.load();});
    put("ZUIOPT_OWNER_STATE_V2",current+"\nT 0 1 10000 1 1 p n /background 1\n");rejects([&]{journal.load();});
    const std::string previous="00000000-0000-0000-0000-000000000000";
    put("ZUIOPT_OWNER_STATE_V2",previous+"\n");journal.load();
    require(journal.boot==previous&&journal.currentBootId()==current,"diagnostic boot is immutable across old-journal load");
    require(!journal.same(OwnerRecord{999999,10000,999999,1,1,"org.example.game","org.example.game","/background",1}),"old boot cannot authorize live ownership");
    journal.commit();require(!directory.exists("owner_state.v1")&&journal.boot==current,"empty journal durable cleanup");
    put("ZUIOPT_OWNER_STATE_V1",current+"\n");journal.load();require(journal.entries.empty(),"empty legacy journal compatibility");
    fs::remove(path/"owner_state.v1");fs::create_symlink(path/"owner.lock",path/"owner_state.v1");rejects([&]{journal.load();});fs::remove(path/"owner_state.v1");
    puts("ZUIOPT_CRASH_JOURNAL_FORMAT_LOCK_BOOT_GUARDS=PASS");
}
int main(int argc,char** argv){
    signal(SIGPIPE,SIG_IGN);
    try{
        if(argc>=3){
            std::string command=argv[1];
            if(command=="pack"){std::cout<<jsonText(unpackPack(readPack(argv[2])).manifest);return 0;}
            if(command=="rules"){std::cout<<dumpRules(rules(read(argv[2])));return 0;}
            if(command=="merge"&&argc>=4){std::vector<Pack> packs;for(int i=4;i<argc;i++)packs.push_back(unpackPack(readPack(argv[i])));std::cout<<mergeRules(read(argv[2]),packs,read(argv[3]));return 0;}
            if(command=="appopt"&&argc==5){auto pack=importAppOpt(read(argv[2]),argv[3],number(argv[4]));std::cout.write(pack.archive.data(),pack.archive.size());return 0;}
            throw std::runtime_error("unknown fixture adapter");
        }
        require(argc==2,"fixture factory argument");
        require(sha256("")=="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","empty digest vector");
        require(sha256("abc")=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","digest vector");
        require(checksum("123456789")==0xcbf43926u,"journal CRC vector");selftest();
        earlyFilterTests();
        struct stat scaffold{};scaffold.st_mode=S_IFDIR|0755;
        require(validCpusetScaffold(scaffold),"root-owned 0755 scaffold");
        for(mode_t mode:{S_IFREG|0755,S_IFLNK|0755,S_IFDIR|0777,S_IFDIR|0700,S_IFDIR|0555,S_IFDIR|04755}){
            scaffold.st_mode=mode;require(!validCpusetScaffold(scaffold),"unsafe scaffold mode rejected");
        }
        scaffold.st_mode=S_IFDIR|0755;scaffold.st_uid=1000;require(!validCpusetScaffold(scaffold),"foreign scaffold UID rejected");
        scaffold.st_uid=0;scaffold.st_gid=1000;require(!validCpusetScaffold(scaffold),"foreign scaffold GID rejected");
        puts("ZUIOPT_CPUSET_SCAFFOLD_STAT_TESTS=PASS");
        auto factory=rules(read(argv[1]));require(factory.profiles.size()==27&&factory.packages.size()==316,"factory 27/316");
        auto config=rules(OPEN);auto* profile=config.find("org.example.game");
        require(profile&&classify(*profile,"Job.workerABCD")->rank==0&&classify(*profile,"Top")->rank==1&&classify(*profile,"AnyRunner")->rank==2,"ALL/rank semantics");
        std::vector<RankedTask> candidates={{100,3},{200,2},{200,1}};
        require(representative(candidates,1)==1&&representative(candidates,2)==2&&representative(candidates,4)==0,"rank tie/shortage");
        for(auto pattern:{"[","[]","[z-a]","[a-]","[!a]","[a","a]","\\*"})rejects([&]{compileGlob(pattern);});
        require(globMatch(compileGlob("a?[0-9]*"),"ab2tail")&&!globMatch(compileGlob("a?[0-9]*"),"abZtail"),"glob positive/negative");
        require(authoritativeIdentity(Snapshot{"org.example.game:worker",10,10001,0,0,0,false,{"org.example.game"}},{"org.example.game"}),"user0 identity");
        require(!authoritativeIdentity(Snapshot{"org.example.game",10,110001,0,0,0,false,{"org.example.game"}},{"org.example.game"}),"secondary user excluded");
        for(auto bad:{std::string(),std::string(65537,'#'),BASE+"unknown true\n",OPEN+"debug true\n",BASE+"package exact org.other G 100\n"})rejects([&]{rules(bad);});
        require(unbase64(base64(std::string(8192,'x')))==std::string(8192,'x'),"chunk max roundtrip");
        for(auto bad:{"A===","AAAA=","AA?=","AA=A","AB=="})rejects([&]{unbase64(bad);});
        auto pack=importAppOpt("org.example.game=0-6\norg.example.game{Job.worker*}=7","appopt-test",0);
        require(unpackPack(pack.archive).ruleText==pack.ruleText,"pack roundtrip");
        auto damaged=pack.archive;damaged[50]^=1;rejects([&]{unpackPack(damaged);});
        rejects([&]{unpackPack(std::string(PACK_LIMIT+1,'x'));});
        rejects([&]{ManifestJson("{\"pack_id\":\"a\",\"pack_id\":\"b\"}").parse();});
        for(auto bad:{"org.example.game{X}=7","org.example.game=0-8","org.example.game=0-7\norg.example.game=2-6"})rejects([&]{importAppOpt(bad,"appopt-test",0);});
        auto pack2=importAppOpt("org.example.game=2-7","other",0);
        rejects([&]{mergeRules(BASE,{pack,pack2},"");});
        auto merged=rules(mergeRules(BASE,{pack},OPEN));require(merged.find("org.example.game")->general==cpus("2-7"),"user over pack over factory");

        const char* temp=getenv("TMPDIR");std::string prefix=std::string(temp?temp:"/tmp")+"/ZUIopt-fixture-XXXXXX";
        std::vector<char> path(prefix.begin(),prefix.end());path.push_back(0);require(mkdtemp(path.data()),"fixture directory");auto root=fs::path(path.data());
        lifecycleTests(root);
        journalTests(root);
        {
            RuleStore store(root.string(),BASE);store.initialize();auto initial=store.current().effective;
            uploadData(store,"pack",pack.archive,randomId());require(store.current().enabled.empty(),"new pack disabled");
            store.apply("enable","appopt-test","");auto enabled=store.current().effective;
            require(rules(enabled).find("org.example.game")->general==cpus("0-6"),"pack enabled");
            rejects([&]{uploadData(store,"pack","broken",randomId());});require(store.current().effective==enabled,"invalid archive preserves LKG");
            uploadData(store,"user",OPEN,randomId());require(rules(store.current().effective).find("org.example.game")->general==cpus("2-7"),"user commit");
            store.apply("rollback","","");require(rules(store.current().effective).find("org.example.game")->general==cpus("0-6"),"rollback");
            store.apply("rollback","","");require(rules(store.current().effective).find("org.example.game")->general==cpus("2-7"),"repeat rollback valid");
            auto before=store.current().effective;RuleStore::testBeforeCommit=true;rejects([&]{store.apply("disable","appopt-test","");});RuleStore::testBeforeCommit=false;
            require(store.current().effective==before,"interrupted generation does not publish");store.apply("disable","appopt-test","");
            require(std::distance(fs::directory_iterator(root/"generations"),fs::directory_iterator())==2,"bounded generation cleanup");
            before=store.current().effective;RuleStore::testAfterCommit=true;rejects([&]{store.apply("enable","appopt-test","");});RuleStore::testAfterCommit=false;
            require(store.current().effective!=before&&store.current().enabled.count("appopt-test"),"post-commit failure is not a rollback; refresh truth");
            auto id=randomId();store.apply("begin",id,"user:"+std::to_string(BASE.size())+":"+sha256(BASE)+":-:0");
            rejects([&]{store.apply("begin",randomId(),"user:1:"+sha256("x")+":-:0");});
            store.apply("chunk",id+":0",base64(BASE));store.apply("chunk",id+":0",base64(BASE));
            rejects([&]{store.apply("chunk",id+":0",base64("x"));});rejects([&]{store.apply("abort",randomId(),"");});store.apply("commit",id,"");
            require(!fs::exists(root/"upload.meta")&&!fs::exists(root/"upload.bin"),"upload commit cleanup");
            id=randomId();before=store.current().effective;store.apply("begin",id,"user:1:"+std::string(64,'0')+":-:0");store.apply("chunk",id+":0",base64("x"));
            rejects([&]{store.apply("commit",id,"");});require(store.current().effective==before&&!fs::exists(root/"upload.meta"),"digest failure rollback/cleanup");
            PrivateDir dir(root.string());
            require(store.bootState()=="READY_TO_START","normal single owner boot");
            // Initialization, crash and reset preserve packs, user rules and LKG.
            auto snapshot=[&]{std::map<std::string,std::string> result;
                for(const auto& entry:fs::recursive_directory_iterator(root/"generations"))if(entry.is_regular_file())result[entry.path().string()]=read(entry.path().string());
                result["effective.conf"]=dir.get("effective.conf",RULE_LIMIT);return result;};
            const auto oldRules=snapshot();const auto oldState=store.state();
            store.initialize();require(snapshot()==oldRules&&store.state()==oldState,"initialization preserves rules byte/semantic state");
            require(!store.crash()&&store.bootState()=="READY_TO_START","isolated crash restarts");
            require(!store.crash()&&store.crash(),"three crashes in bounded window");
            require(store.bootState()=="FAILSAFE","persistent Android default failsafe");
            store.initialize();require(store.failed(),"boot never clears persistent failure");
            store.resetFailure();require(!dir.exists("failure.v1")&&!dir.exists("crashes.v1")&&store.bootState()=="READY_TO_START","explicit next boot reset");
            require(snapshot()==oldRules&&store.state()==oldState,"reset preserves rules and LKG bytes");
            rejects([&]{store.resetFailure();});
            puts("ZUIOPT_SINGLE_OWNER_FAILSAFE_RESET=PASS");
            fs::create_symlink(root/"effective.conf",root/"upload.bin");rejects([&]{store.apply("begin",randomId(),"user:1:"+sha256("x")+":-:0");});
            require(store.current().effective==before,"symlink target unchanged");fs::remove(root/"upload.bin");
            fs::create_hard_link(root/"effective.conf",root/"upload.bin");rejects([&]{dir.get("upload.bin",RULE_LIMIT);});fs::remove(root/"upload.bin");
            require(!initial.empty()&&!store.state().empty(),"state response");
        }
        // Only the exclusively created fixture directory, never a provided state path.
        require(root.filename().string().rfind("ZUIopt-fixture-",0)==0&&!fs::is_symlink(root),"fixture cleanup boundary");fs::remove_all(root);
        puts("ZUIOPT_NATIVE_RULE_MANAGER_TRANSACTION_TESTS=PASS");return 0;
    }catch(const std::exception& e){fprintf(stderr,"FIXTURE_FAIL %s\n",e.what());return 1;}
}
