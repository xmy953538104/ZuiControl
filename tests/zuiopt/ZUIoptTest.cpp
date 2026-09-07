// Host/isolated Android fixture only. Never linked into the production binary.
#define ZUIOPT_TEST 1
#include "../../native/zuiopt/ZUIopt_store.h"
#include "../../native/zuiopt/ZUIopt_owner.h"
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
            require(store.nextOwner()=="ASOULOPT","missing selector fallback");store.nextOwner("ZUIOPT");require(store.nextOwner()=="ZUIOPT","next selector");
            require(!store.crash()&&!store.crash()&&store.crash(),"three crashes in bounded window");require(store.nextOwner()=="ASOULOPT","failure next boot fallback");
            store.nextOwner("ZUIOPT");PrivateDir dir(root.string());dir.put("next_owner.v1","corrupt");require(store.nextOwner()=="ASOULOPT","corrupt selector fallback");
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
