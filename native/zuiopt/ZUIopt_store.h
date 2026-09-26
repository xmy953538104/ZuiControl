// Root-only, descriptor-relative transactions. effective.conf is the sole commit record.
#pragma once
#include "ZUIopt_rules.h"
#include <sys/file.h>
#include <sys/random.h>

namespace ZUIopt {
inline std::string randomId(){
    unsigned char bytes[12];size_t at=0;while(at<sizeof(bytes)){auto n=getrandom(bytes+at,sizeof(bytes)-at,0);if(n<0&&errno==EINTR)continue;require(n>0,"random identity");at+=n;}
    std::string out;const char* digits="0123456789abcdef";for(auto c:bytes){out+=digits[c>>4];out+=digits[c&15];}return out;
}
inline bool generationId(const std::string& s){return s.size()==25&&s[0]=='g'&&hex(s.substr(1),24);}
inline std::string generationOf(const std::string& effective){
    const std::string prefix="# ZUIOPT_GENERATION ";require(effective.rfind(prefix,0)==0,"generation header");
    auto id=effective.substr(prefix.size(),effective.find('\n')-prefix.size());require(generationId(id),"generation ID");return id;
}
class PrivateDir {
    static void name(const std::string& s){require(label(s)&&s!="."&&s!="..","private entry name");}
    static void directory(int fd){struct stat st{};require(fd>=0&&fstat(fd,&st)==0&&S_ISDIR(st.st_mode)&&st.st_uid==geteuid()&&(st.st_mode&0077)==0,"private directory ownership/mode");}
public:
    int fd=-1;
    static void file(int fd){struct stat st{};require(fd>=0&&fstat(fd,&st)==0&&S_ISREG(st.st_mode)&&st.st_uid==geteuid()&&(st.st_mode&0077)==0&&st.st_nlink==1,"private regular file identity");}
    explicit PrivateDir(const std::string& path){
        require(!path.empty()&&path[0]=='/',"absolute store directory");int next=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC);
        try{std::istringstream input(path);std::string component;
            while(std::getline(input,component,'/')){if(component.empty())continue;require(component!="."&&component!="..","store ancestor");
                int child=openat(next,component.c_str(),O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);require(child>=0,"store ancestor open");close(next);next=child;}
            int leaf=openat(next,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);close(next);next=leaf;
            directory(next);fd=next;
        }catch(...){if(next>=0)close(next);throw;}
    }
    PrivateDir(const PrivateDir& parent,const std::string& child,bool create=false){
        name(child);if(create)require(mkdirat(parent.fd,child.c_str(),0700)==0||errno==EEXIST,"private directory create");
        int opened=openat(parent.fd,child.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
        try{directory(opened);fd=opened;}catch(...){if(opened>=0)close(opened);throw;}
    }
    PrivateDir(const PrivateDir&)=delete;PrivateDir& operator=(const PrivateDir&)=delete;
    ~PrivateDir(){if(fd>=0)close(fd);}
    bool exists(const std::string& entry) const {name(entry);struct stat st{};if(fstatat(fd,entry.c_str(),&st,AT_SYMLINK_NOFOLLOW)==0)return true;require(errno==ENOENT,"private stat");return false;}
    std::string get(const std::string& entry,size_t limit,bool optional=false) const {
        name(entry);int input=openat(fd,entry.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);
        if(input<0){require(optional&&errno==ENOENT,"private file open");return {};}
        try{file(input);struct stat st{};require(fstat(input,&st)==0&&st.st_size>=0&&static_cast<uint64_t>(st.st_size)<=limit,"private file size");
            std::string out;char buffer[4096];for(;;){auto n=::read(input,buffer,sizeof(buffer));if(n<0&&errno==EINTR)continue;require(n>=0,"private file read");if(!n)break;out.append(buffer,n);require(out.size()<=limit,"private read bound");}close(input);return out;
        }catch(...){close(input);throw;}
    }
    void sync() const {require(fsync(fd)==0,"private directory sync");}
    void put(const std::string& entry,const std::string& data,bool exclusive=false) const {
        name(entry);if(exclusive)require(!exists(entry),"immutable generation exists");
        if(exists(entry)){int old=openat(fd,entry.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);try{file(old);}catch(...){if(old>=0)close(old);throw;}close(old);}
        const auto temp="tmp-"+randomId();int out=openat(fd,temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);require(out>=0,"private staging create");
        try{putAll(out,data);require(fsync(out)==0,"private file sync");close(out);out=-1;
            require(renameat(fd,temp.c_str(),fd,entry.c_str())==0,"private atomic rename");sync();
        }catch(...){if(out>=0)close(out);unlinkat(fd,temp.c_str(),0);throw;}
    }
    void remove(const std::string& entry) const {
        name(entry);if(!exists(entry))return;int input=openat(fd,entry.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);
        try{file(input);}catch(...){if(input>=0)close(input);throw;}close(input);require(unlinkat(fd,entry.c_str(),0)==0,"private unlink");sync();
    }
    std::vector<std::string> names() const {
        int copy=openat(fd,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);require(copy>=0,"directory duplicate");DIR* dir=fdopendir(copy);if(!dir){close(copy);throw std::runtime_error("directory enumeration");}
        std::vector<std::string> result;int error=0;
        for(;;){errno=0;auto* entry=readdir(dir);if(!entry){error=errno;break;}std::string n=entry->d_name;if(n!="."&&n!="..")result.push_back(n);if(result.size()>64){closedir(dir);throw std::runtime_error("directory entry bound");}}
        closedir(dir);require(error==0,"directory enumeration read");return result;
    }
};
// This receipt is emitted only after the existing engine has accepted and reconciled a config.
// It adds no timer, process scan, scheduling decision or new runtime owner.
inline void recordLoadedGeneration(const std::string& root,const std::string& bytes) noexcept {
    try{PrivateDir d(root);d.put("loaded-generation.v2",generationOf(bytes)+":"+
        trim(read("/proc/sys/kernel/random/boot_id"))+":"+std::to_string(getpid())+":"+
        std::to_string(identity(getpid()).start)+":"+sha256(bytes)+"\n");}catch(...){}
}
struct RuleState {std::map<std::string,Pack> packs;std::set<std::string> enabled;std::string user="schema 2\nenabled true\n",effective,transaction,provenance,source;bool canonical=false;};
inline std::string base64(const std::string& s){
    const std::string alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";std::string out;
    for(size_t i=0;i<s.size();i+=3){uint32_t n=uint32_t(static_cast<unsigned char>(s[i]))<<16;if(i+1<s.size())n|=uint32_t(static_cast<unsigned char>(s[i+1]))<<8;if(i+2<s.size())n|=static_cast<unsigned char>(s[i+2]);out+=alphabet[n>>18];out+=alphabet[(n>>12)&63];out+=i+1<s.size()?alphabet[(n>>6)&63]:'=';out+=i+2<s.size()?alphabet[n&63]:'=';}return out;
}
inline std::string unbase64(const std::string& s,size_t limit=8192){
    require(!s.empty()&&s.size()<=(limit+2)/3*4&&s.size()%4==0,"chunk base64 size");const std::string alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";std::string out;
    for(size_t i=0;i<s.size();i+=4){uint32_t n=0;int count=3;
        for(int j=0;j<4;j++){auto at=alphabet.find(s[i+j]);if(s[i+j]=='='){require(i+4==s.size()&&j>=2,"base64 padding");if(j==2)require(s[i+3]=='=',"base64 padding order");at=0;count--;}else require(at!=alphabet.npos,"base64 alphabet");n=(n<<6)|at;}
        out+=static_cast<char>(n>>16);if(count>=2)out+=static_cast<char>(n>>8);if(count==3)out+=static_cast<char>(n);
    }require(out.size()<=limit&&base64(out)==s,"noncanonical base64");return out;
}
inline std::vector<std::string> fields(const std::string& text,char delimiter){std::vector<std::string> out;size_t start=0;for(;;){auto end=text.find(delimiter,start);out.push_back(text.substr(start,end-start));if(end==text.npos)break;start=end+1;}return out;}

class RuleStore {
    PrivateDir root,generations;int lockFd=-1;std::string factory,factorySha;
    RuleState load(const std::string& effective){
        RuleState state;if(effective.empty())return state;rules(effective);auto id=generationOf(effective);PrivateDir d(generations,id);
        require(d.get("effective.conf",RULE_LIMIT)==effective,"generation commit content");state.effective=effective;
        if(d.exists("canonical_state.v2")){
            auto meta=fields(d.get("canonical_state.v2",2048),'\n');
            require(meta.size()==9&&meta[0]=="ZUIOPT_CANONICAL_STATE_V2"&&meta[1]==id
                &&hex(meta[2],24)&&hex(meta[3],64)&&meta[4]=="schema2-1"
                &&hex(meta[5],64)&&(meta[6]=="none"||generationId(meta[6]))&&hex(meta[7],64)&&meta[8].empty(),"canonical metadata");
            state.user=dumpRules(rules(effective));state.canonical=true;state.transaction=meta[2];
            state.provenance=d.get("provenance.txt",16384);state.source=d.get("source.bin",PACK_LIMIT);require(sha256(state.source)==meta[7],"canonical source digest");
            require(sha256(state.user)==meta[3]&&sha256(state.provenance)==meta[5],"canonical digest");
            auto previous=d.get("last_good.conf",RULE_LIMIT);
            require((previous.empty()?"none":generationOf(previous))==meta[6],"canonical previous generation");
            return state;
        }
        auto metadata=d.get("pack_state.conf",8192);auto rows=fields(metadata,'\n');require(rows.size()>=5&&rows[0]=="ZUIOPT_RULE_STATE_V1"&&rows.back().empty(),"state header");
        auto legacyFactory=root.get("migration_factory.conf",RULE_LIMIT,true);if(legacyFactory.empty())legacyFactory=factory;
        require(rows[1]=="factory "+sha256(legacyFactory),"factory migration required");
        auto tx=fields(rows[2],' '),user=fields(rows[3],' ');require(tx.size()==2&&hex(tx[1],24)&&user.size()==2&&hex(user[1],64),"state identity");state.transaction=tx[1];
        state.user=d.get("user_rules.conf",RULE_LIMIT);require(sha256(state.user)==user[1],"stored user digest");rules(state.user);PrivateDir packs(d,"imported_packs");
        for(size_t i=4;i+1<rows.size();i++){auto p=fields(rows[i],' ');require(p.size()==4&&p[0]=="pack"&&packId(p[1])&&(p[2]=="0"||p[2]=="1")&&hex(p[3],64)&&!state.packs.count(p[1])&&state.packs.size()<PACK_COUNT,"stored pack state");
            auto data=packs.get(p[1]+".zip",PACK_LIMIT);require(sha256(data)==p[3],"stored archive digest");auto pack=unpackPack(data);require(pack.manifest.at("pack_id").text==p[1],"stored pack ID");state.packs.emplace(p[1],std::move(pack));if(p[2]=="1")state.enabled.insert(p[1]);}
        std::vector<Pack> selected;for(const auto& p:state.enabled)selected.push_back(state.packs.at(p));
        require(effective=="# ZUIOPT_GENERATION "+id+"\n"+mergeRules(legacyFactory,selected,state.user),"stored effective derivation");return state;
    }
    std::string commit(RuleState state,const std::string& transaction,const std::string& expected){
        require(hex(transaction,24),"commit transaction");
        const auto active=root.get("effective.conf",RULE_LIMIT,true);
        require((active.empty()?"none":generationOf(active))==expected&&active==state.effective,"generation conflict");
        auto canonical=dumpRules(rules(state.user));std::string id='g'+randomId();
        auto effective="# ZUIOPT_GENERATION "+id+"\n"+canonical;rules(effective);
        require(!generations.exists(id),"generation collision");PrivateDir d(generations,id,true);
        // Retain every legacy generation and failed prepared generation. Retention is a separate owner action.
        std::string provenance=state.provenance;
        if(provenance.empty())provenance="source=canonical\n";
        if(state.source.empty())state.source=state.user;
        const auto metadata="ZUIOPT_CANONICAL_STATE_V2\n"+id+"\n"+transaction+"\n"+sha256(canonical)
            +"\nschema2-1\n"+sha256(provenance)+"\n"+expected+"\n"+sha256(state.source)+"\n";
        d.put("source.bin",state.source,true);d.put("last_good.conf",active,true);d.put("provenance.txt",provenance,true);
        d.put("canonical_state.v2",metadata,true);d.put("effective.conf",effective,true);d.sync();generations.sync();
#ifdef ZUIOPT_TEST
        if(testBeforeCommit)throw std::runtime_error("injected before commit");
#endif
        // Rename is the sole commit point. A following fsync error is indeterminate; query exact bytes.
        root.put("effective.conf",effective);
#ifdef ZUIOPT_TEST
        if(testAfterCommit)throw std::runtime_error("injected after commit");
#endif
        require(root.get("effective.conf",RULE_LIMIT)==effective,"committed readback");return id;
    }
    void clearUpload(){
        // Preserve successful and failed input receipts; only the active transport slots are cleared.
        auto meta=root.get("upload.meta",512,true);
        if(!meta.empty()){auto f=fields(trim(meta),':');require(!f.empty()&&hex(f[0],24),"upload receipt identity");
            PrivateDir receipts(root,"uploads",true);receipts.put(f[0]+".meta",meta);receipts.put(f[0]+".bin",root.get("upload.bin",PACK_LIMIT,true));}
        root.remove("upload.meta");root.remove("upload.bin");
    }
    std::vector<std::string> upload(const std::string& id){
        require(hex(id,24),"transaction ID");auto f=fields(trim(root.get("upload.meta",512)),':');
        require(f.size()==9&&f[0]==id&&(f[1]=="pack"||f[1]=="user"||f[1]=="appopt")&&rx(f[2],"[0-9]{1,6}")&&number(f[2])>0&&number(f[2])<=static_cast<int>(f[1]=="pack"?PACK_LIMIT:RULE_LIMIT)&&hex(f[3],64)&&(f[4]=="-"||packId(f[4]))&&rx(f[5],"-?[0-9]{1,7}"),"upload metadata");
        auto boot=trim(read("/proc/sys/kernel/random/boot_id"));require(generationId(f[6])&&f[7]==boot&&rx(f[8],"[0-9]{1,16}"),"expired upload boot");auto begun=std::stoll(f[8]);require(begun<=now()&&now()-begun<=600000,"expired upload window");return f;
    }
public:
#ifdef ZUIOPT_TEST
    inline static bool testBeforeCommit=false;
    inline static bool testAfterCommit=false;
#endif
    RuleStore(const std::string& path,const std::string& factoryText):root(path),generations(root,"generations",true),factory(factoryText){
        rules(factory);factorySha=sha256(factory);lockFd=openat(root.fd,"manager.lock",O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600);
        try{PrivateDir::file(lockFd);require(flock(lockFd,LOCK_EX)==0,"rule manager lock");}catch(...){if(lockFd>=0)close(lockFd);lockFd=-1;throw;}
    }
    ~RuleStore(){if(lockFd>=0)close(lockFd);}
    RuleState current(){return load(root.get("effective.conf",RULE_LIMIT,true));}
    void initialize(){
        auto s=current();if(s.canonical)return;
        // load() above verifies exact V1 merge once. Its immutable inputs remain untouched.
        s.provenance="migration=V1_to_V2\nfactory_sha256="+factorySha+"\nold_effective_sha256="+sha256(s.effective)+"\n";
        if(s.effective.empty()){s.user=dumpRules(rules(factory));s.provenance+="source=factory_seed\n";}
        else{
            s.provenance+="legacy_generation="+generationOf(s.effective)+"\nuser_sha256="+sha256(s.user)+"\n";
            for(const auto& [name,p]:s.packs)s.provenance+="pack="+name+"|"+sha256(p.archive)+"\n";
            s.user=dumpRules(rules(s.effective));
        }
        // Snapshot factory bytes as well as its digest, even after a future ROM replacement.
        if(!root.exists("migration_factory.conf"))root.put("migration_factory.conf",factory,true);
        commit(s,randomId(),s.effective.empty()?"none":generationOf(s.effective));
    }
    std::string state(){
        auto s=current();require(!s.effective.empty(),"store not initialized");std::ostringstream out;
        out<<"settings_pending="<<trim(root.get("settings.pending",64,true))<<"\nstore_schema=ZUIOPT_CANONICAL_STATE_V2\ncanonical_sha256="<<sha256(s.user)<<"\nloaded_generation="<<trim(root.get("loaded-generation.v2",256,true))<<"\ngeneration="<<generationOf(s.effective)<<"\ntransaction="<<s.transaction<<"\nfactory_sha256="<<factorySha<<"\nuser_size="<<s.user.size()<<"\nuser_sha256="<<sha256(s.user)<<"\neffective_sha256="<<sha256(s.effective)<<'\n';
        for(const auto& [id,p]:s.packs)out<<"pack="<<id<<'|'<<p.manifest.at("pack_version").text<<'|'<<p.manifest.at("pack_priority").text<<'|'<<s.enabled.count(id)<<'|'<<p.manifest.at("source_type").text<<'\n';
        require(out.str().size()<=4096,"state response bound");return out.str();
    }
    bool loaded(){
        auto effective=current().effective;auto receipt=fields(trim(root.get("loaded-generation.v2",256,true)),':');
        if(receipt.size()!=5||receipt[0]!=generationOf(effective)||receipt[1]!=trim(read("/proc/sys/kernel/random/boot_id"))
            ||!rx(receipt[2],"[0-9]{1,10}")||!rx(receipt[3],"[0-9]{1,20}")||receipt[4]!=sha256(effective))return false;
        return identity(number(receipt[2])).start!=0&&std::to_string(identity(number(receipt[2])).start)==receipt[3];
    }
    std::string userChunk(const std::string& generation,const std::string& offsetText){
        require(rx(offsetText,"[0-9]{1,6}"),"read offset");size_t offset=number(offsetText);auto s=current();require(generationOf(s.effective)==generation&&offset<=s.user.size(),"state changed/read offset");
        return generation+":"+offsetText+":"+base64(s.user.substr(offset,8192));
    }
    std::string settings(const std::string& action,const std::string& tx,const std::string& value){
        require(hex(tx,24),"settings transaction");auto s=current();require(s.canonical,"canonical required");
        auto lease=trim(root.get("settings.pending",64,true));
        const auto before="settings-"+tx+"-before.conf",target="settings-"+tx+"-target.conf";
        if(action=="settings_prepare"){
            auto colon=value.find(':');require(colon!=std::string::npos,"settings prepare metadata");
            auto expected=value.substr(0,colon),data=unbase64(value.substr(colon+1),RULE_LIMIT);
            require(data.size()<=RULE_LIMIT&&dumpRules(rules(data))==data,"settings canonical bytes");
            require(lease.empty()||lease==tx,"settings busy");
            if(lease==tx){require(root.get(target,RULE_LIMIT)==data,"settings prepare replay");}
            else{
                require(generationOf(s.effective)==expected,"settings generation CAS");
                // Retained stage inputs precede the durable freeze; no visible rule changes here.
                if(root.exists(before))require(root.get(before,RULE_LIMIT)==s.user&&root.get(target,RULE_LIMIT)==data,"settings immutable stage");
                else {root.put(before,s.user,true);root.put(target,data,true);}
                root.put("settings.pending",tx+"\n");
            }
            return "prepared="+sha256(data);
        }
        require(lease==tx|| (lease.empty()&&action=="settings_finish"),"settings lease");
        if(action=="settings_finish"){root.remove("settings.pending");return "settings=finished";}
        require(action=="settings_apply"||action=="settings_revert","settings action");
        auto old=root.get(before,RULE_LIMIT),next=root.get(target,RULE_LIMIT);
        require(s.user==old||s.user==next,"settings unknown active rules");
        auto wanted=action=="settings_apply"?next:old;
        require(value==sha256(wanted),"settings wanted digest");
        if(s.user!=wanted){auto expected=generationOf(s.effective);s.user=wanted;s.source=wanted;
            s.provenance="source=settings_restore\ntransaction="+tx+"\n";commit(s,randomId(),expected);}
        return "canonical_sha256="+sha256(wanted);
    }
    std::string apply(const std::string& action,const std::string& id,const std::string& value){
        auto s=current();require(s.canonical,"canonical migration required");
        require(!root.exists("settings.pending"),"settings restore recovery required");
        if(action=="begin"){
            require(hex(id,24),"upload ID");auto f=fields(value,':');require(f.size()==6&&generationId(f[5])&&f[5]==generationOf(s.effective)&&(f[0]=="pack"||f[0]=="user"||f[0]=="appopt")&&rx(f[1],"[0-9]{1,6}")&&hex(f[2],64)&&(f[3]=="-"||packId(f[3]))&&rx(f[4],"-?[0-9]{1,7}"),"begin metadata");
            int size=number(f[1]),priority=number(f[4]);require(size>0&&size<=static_cast<int>(f[0]=="pack"?PACK_LIMIT:RULE_LIMIT)&&priority>=-1000000&&priority<=1000000&&(f[0]!="appopt"||packId(f[3])),"begin bounds");
            if(root.exists("upload.meta")){auto old=fields(trim(root.get("upload.meta",512)),':');bool active=false;try{if(!old.empty()){upload(old[0]);active=true;}}catch(...){}
                if(active){require(old[0]==id&&trim(root.get("upload.meta",512)).rfind(id+":"+value+":",0)==0,"upload busy");return "upload=resumed";}clearUpload();}
            root.put("upload.bin","");root.put("upload.meta",id+":"+value+":"+trim(read("/proc/sys/kernel/random/boot_id"))+":"+std::to_string(now())+"\n");return "upload=begun";
        }
        if(action=="abort"){if(root.exists("upload.meta")){upload(id);clearUpload();}return "upload=aborted";}
        if(action=="chunk"){
            auto key=fields(id,':');require(key.size()==2&&rx(key[1],"[0-9]{1,6}"),"chunk key");auto f=upload(key[0]);size_t offset=number(key[1]);auto bytes=unbase64(value),data=root.get("upload.bin",PACK_LIMIT);
            require(offset<=data.size()&&offset+bytes.size()<=static_cast<size_t>(number(f[2])),"chunk offset/declared size");
            if(offset<data.size()){require(offset+bytes.size()<=data.size()&&data.substr(offset,bytes.size())==bytes,"conflicting chunk replay");return "chunk=replayed";}
            root.put("upload.bin",data+bytes);return "received="+std::to_string(data.size()+bytes.size());
        }
        if(action=="commit"){
            auto f=upload(id);std::string result;
            try{require(f[6]==generationOf(s.effective),"generation conflict");auto data=root.get("upload.bin",PACK_LIMIT);require(data.size()==static_cast<size_t>(number(f[2]))&&sha256(data)==f[3],"upload length/SHA256");
                if(f[1]=="user")s.user=dumpRules(rules(data));
                else{auto pack=f[1]=="pack"?unpackPack(data,s.user):importAppOpt(data,f[4],number(f[5]));s.user=dumpRules(pack.config);}
                s.source=data;s.provenance="source="+f[1]+"\nsource_sha256="+sha256(data)+"\nbase_generation="+f[6]+"\n";
                result=commit(s,id,f[6]);
            }catch(...){try{clearUpload();}catch(...){}throw;}
            try{clearUpload();}catch(...){}return "generation="+result;
        }
        if(action=="enable"||action=="disable")throw std::runtime_error("canonical store has no live pack layers");
        if(action=="rollback"){
            require(generationId(id)&&id==generationOf(s.effective),"generation conflict");
            PrivateDir d(generations,id);auto old=d.get("last_good.conf",RULE_LIMIT);require(!old.empty(),"no last good generation");
            auto restored=load(old);restored.user=dumpRules(rules(old));restored.effective=s.effective;
            restored.provenance="source=rollback\nselected="+generationOf(old)+"\n";
            return "generation="+commit(restored,randomId(),id);
        }
        throw std::runtime_error("unknown rule manager action");
    }
    bool failed(){return root.exists("failure.v1");}
    std::string bootState(){return failed()?"FAILSAFE":"READY_TO_START";}
    std::string failureState(){return std::string("thread_manager=ZUIOPT\nfailure=")+(failed()?"1":"0")+"\n";}
    void resetFailure(){
        require(failed(),"no persistent failure");
        // Validate both before deleting either. Clear failure last; partial I/O
        // failure therefore remains safe. Never signal/restart the running boot.
        root.get("failure.v1",128);root.get("crashes.v1",256,true);
        root.remove("crashes.v1");root.remove("failure.v1");
    }
    void failure(){root.put("failure.v1","ZUIOPT_FAILURE_V1\n"+trim(read("/proc/sys/kernel/random/boot_id"))+"\n");}
    bool crash(){
        auto boot=trim(read("/proc/sys/kernel/random/boot_id"));auto lines=fields(root.get("crashes.v1",256,true),'\n');std::vector<int64_t> times;
        if(!lines.empty()&&lines[0]==boot)for(size_t i=1;i<lines.size();i++)if(!lines[i].empty()){require(rx(lines[i],"[0-9]{1,16}")&&times.size()<3,"crash history");auto t=std::stoll(lines[i]);if(t<=now()&&now()-t<=60000)times.push_back(t);}
        times.push_back(now());if(times.size()>=3){failure();return true;}
        std::string data=boot+"\n";for(auto t:times)data+=std::to_string(t)+"\n";root.put("crashes.v1",data);return false;
    }
};
}
