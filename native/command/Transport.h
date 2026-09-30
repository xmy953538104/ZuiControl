// Finite command transport only. AppPolicy semantics remain in system_server.
#pragma once
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <map>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace command {
inline void require(bool value, const char* why) { if (!value) throw std::runtime_error(why); }
constexpr size_t LIMIT = 262144;
constexpr size_t REQUEST_LIMIT = 131072; // Leaves room for the terminal receipt in LIMIT.
inline bool alphabet(const std::string& s, const std::string& allowed, size_t min, size_t max) {
    return s.size() >= min && s.size() <= max && s.find_first_not_of(allowed) == std::string::npos;
}
inline bool id(const std::string& s) { return alphabet(s,"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-",1,64); }
inline bool digest(const std::string& s) { return alphabet(s,"0123456789abcdef",64,64); }
inline bool transaction(const std::string& s) { return alphabet(s,"0123456789abcdef-",36,36); }
inline std::vector<std::string> split(const std::string& s, char delimiter) {
    std::vector<std::string> result; size_t start=0;
    for (;;) { size_t end=s.find(delimiter,start); result.push_back(s.substr(start,end-start));
        if(end==std::string::npos) return result; start=end+1; }
}

// FIPS 180-4 SHA-256; no external command or platform-private crypto ABI.
inline std::string sha256(const std::string& input) {
    static constexpr uint32_t k[] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    uint32_t h[]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    std::string data=input; data.push_back(char(0x80)); while(data.size()%64!=56)data.push_back(0);
    uint64_t bits=uint64_t(input.size())*8; for(int i=7;i>=0;--i)data.push_back(char(bits>>(i*8)));
    auto r=[](uint32_t v,int n){return (v>>n)|(v<<(32-n));};
    for(size_t off=0;off<data.size();off+=64){
        uint32_t w[64];for(int i=0;i<16;++i){w[i]=0;for(int j=0;j<4;++j)w[i]=(w[i]<<8)|uint8_t(data[off+i*4+j]);}
        for(int i=16;i<64;++i){auto a=w[i-15],b=w[i-2];w[i]=w[i-16]+(r(a,7)^r(a,18)^(a>>3))+w[i-7]+(r(b,17)^r(b,19)^(b>>10));}
        uint32_t a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],v=h[7];
        for(int i=0;i<64;++i){uint32_t t=v+(r(e,6)^r(e,11)^r(e,25))+((e&f)^(~e&g))+k[i]+w[i];
            uint32_t u=(r(a,2)^r(a,13)^r(a,22))+((a&b)^(a&c)^(b&c));v=g;g=f;f=e;e=d+t;d=c;c=b;b=a;a=t+u;}
        uint32_t z[]={a,b,c,d,e,f,g,v};for(int i=0;i<8;++i)h[i]+=z[i];
    }
    std::string out;for(auto word:h)for(int i=7;i>=0;--i)out.push_back("0123456789abcdef"[(word>>(4*i))&15]);return out;
}

struct Request {
    std::string text, requestId, command;
    explicit Request(const std::string& value):text(value){
        require(!value.empty()&&value.size()<=REQUEST_LIMIT&&value.find_first_of("\r\n")==std::string::npos&&value.find('\0')==std::string::npos,"request bounds");
        auto f=split(value,'|');require(f.size()==5&&id(f[0])&&!f[1].empty()&&f[2].empty(),"request format");requestId=f[0];command=f[1];
    }
    void authenticate(const std::string& expectedId,const std::string& hash) const {
        require(id(expectedId)&&digest(hash)&&requestId==expectedId&&sha256(text)==hash,"admitted identity/hash");
    }
};

struct Fd {
    int value=-1;
    explicit Fd(int n):value(n){require(n>=0,"file open");}
    Fd(const Fd&)=delete; Fd& operator=(const Fd&)=delete;
    ~Fd(){if(value>=0)close(value);}
};
inline void writeAll(int fd,const std::string& bytes){
    size_t offset=0;while(offset<bytes.size()){ssize_t n=write(fd,bytes.data()+offset,bytes.size()-offset);if(n<0&&errno==EINTR)continue;require(n>0,"file write");offset+=size_t(n);}
}
// Directory descriptors bind every subsequent operation; no path re-resolution.
struct Disk {
    Fd fd;
    static void identity(int file, bool directory){struct stat st{};require(fstat(file,&st)==0,"file stat");
        require(st.st_uid==geteuid()&&(st.st_mode&0022)==0,"file owner/mode");
        require(directory?S_ISDIR(st.st_mode):(S_ISREG(st.st_mode)&&st.st_nlink==1&&st.st_size>=0&&uint64_t(st.st_size)<=LIMIT),"file identity");}
    static int openDirectory(const std::string& path){
        require(!path.empty()&&path.front()=='/',"absolute directory");int current=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC);
        require(current>=0,"root directory");
        auto parts=split(path.substr(1),'/');size_t index=0;
        try{for(const auto& part:parts){require(!part.empty()&&part!="."&&part!="..","directory component");
            // Ancestors need search, not directory read permission (Android /data).
            int access=++index==parts.size()?O_RDONLY:O_PATH;
            int next=openat(current,part.c_str(),access|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);require(next>=0,"directory open");close(current);current=next;
            struct stat st{};require(fstat(current,&st)==0&&S_ISDIR(st.st_mode),"directory identity");}
            identity(current,true);return current;
        }catch(...){close(current);throw;}
    }
    explicit Disk(const std::string& path):fd(openDirectory(path)){}
    Disk(const Disk& parent,const std::string& name):fd(child(parent,name)){}
    static void nameCheck(const std::string& name){require(alphabet(name,"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-",1,100)&&name!="."&&name!="..","file name");}
    static int child(const Disk& parent,const std::string& name){nameCheck(name);require(mkdirat(parent.fd.value,name.c_str(),0700)==0||errno==EEXIST,"directory create");
        int n=openat(parent.fd.value,name.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);require(n>=0,"child directory");
        try{identity(n,true);require(fsync(parent.fd.value)==0,"parent sync");return n;}catch(...){close(n);throw;}}
    bool exists(const std::string& name)const{nameCheck(name);struct stat st{};if(fstatat(fd.value,name.c_str(),&st,AT_SYMLINK_NOFOLLOW)==0)return true;require(errno==ENOENT,"file existence");return false;}
    std::string read(const std::string& name) const {
        nameCheck(name);int raw=openat(fd.value,name.c_str(),O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        if(raw<0&&errno==ENOENT)return {};Fd file(raw);identity(file.value,false);std::string bytes;char block[4096];
        for(;;){ssize_t n=::read(file.value,block,sizeof(block));if(n<0&&errno==EINTR)continue;require(n>=0,"file read");if(!n)break;
            require(bytes.size()+size_t(n)<=LIMIT,"read bound");bytes.append(block,size_t(n));}identity(file.value,false);return bytes;
    }
    void put(const std::string& name,const std::string& bytes){
        nameCheck(name);require(bytes.size()<=LIMIT,"write bound");read(name); // Reject an unsafe existing target.
        std::string temporary=name+".new";nameCheck(temporary);
        // Retained .new is an interrupted write, never an authoritative result.
        read(temporary);require(unlinkat(fd.value,temporary.c_str(),0)==0||errno==ENOENT,"stale temporary");
        Fd file(openat(fd.value,temporary.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600));
        identity(file.value,false);writeAll(file.value,bytes);require(fsync(file.value)==0,"data sync");
        require(renameat(fd.value,temporary.c_str(),fd.value,name.c_str())==0&&fsync(fd.value)==0,"commit sync");require(read(name)==bytes,"durable readback");
    }
    void remove(const std::string& name){read(name);require(unlinkat(fd.value,name.c_str(),0)==0||errno==ENOENT,"file unlink");require(fsync(fd.value)==0,"unlink sync");}
};

inline std::string ack(const Request& r,const std::string& state,std::string detail){
    for(char& c:detail)if(c=='|'||c=='\r'||c=='\n')c=' ';
    return r.requestId+"|"+state+"|"+r.command+"|"+detail;
}
// The old claim boundary remains independent from the system_server journal.
struct Receipts {
    Disk& disk;
    std::function<void(const std::string&)> publish;
    std::function<void(const char*)> point=[](const char*){};
    std::function<std::string(const Request&)> reconcile={};
    std::string terminal(const Request& r, std::string result) {
        auto lines=split(result,'\n');
        require(result.size()<=4096&&!lines.empty()&&(lines[0]=="ok=1"||lines[0]=="ok=0"),"authority recovery pending");
        bool ok=lines[0]=="ok=1";
        while(!result.empty()&&result.back()=='\n')result.pop_back();
        std::replace(result.begin(),result.end(),'\n',';');
        return ack(r,ok?"done":"failed",result);
    }
    std::string resolved(const Request& r) {
        require(bool(reconcile),"authority reconciliation unavailable");
        return terminal(r,reconcile(r));
    }
    void complete(const Request& r,const std::string& terminal){
        point("before_root_receipt");disk.put("last_request_receipt",r.text+"\n"+terminal+"\n");point("after_root_receipt");
        if(disk.read("active_request_claim")==r.text+"\n")disk.remove("active_request_claim");
        point("before_terminal_ack");publish(terminal);point("after_terminal_ack");
    }
    bool recover(const Request& current){
        std::string raw=disk.read("last_request_receipt"), previous, terminal;
        if(!raw.empty()){
            auto lines=split(raw,'\n');require(lines.size()==3&&lines[2].empty(),"receipt lines");Request old(lines[0]);
            auto f=split(lines[1],'|');require(f.size()==4&&f[0]==old.requestId&&f[2]==old.command&&(f[1]=="done"||f[1]=="failed"),"receipt identity");
            previous=lines[0];terminal=lines[1];
        }
        auto claimed=disk.read("active_request_claim");
        require(!claimed.empty()||!disk.exists("active_request_claim"),"empty invalid claim retained");
        if(!claimed.empty()){
            require(claimed.back()=='\n',"claim newline");Request abandoned(claimed.substr(0,claimed.size()-1));
            if(abandoned.text==previous)disk.remove("active_request_claim");
            else{
                previous=abandoned.text;terminal=resolved(abandoned);
                disk.put("last_request_receipt",previous+"\n"+terminal+"\n");disk.remove("active_request_claim");
            }
        }
        if(previous==current.text){
            // Upgrade a retained V78 indeterminate receipt before exposing it again.
            if(terminal==ack(current,"failed","indeterminate_after_claim")) {
                terminal=resolved(current);disk.put("last_request_receipt",previous+"\n"+terminal+"\n");
            }
            publish(terminal);return true;
        }
        if(!previous.empty()&&Request(previous).requestId==current.requestId)return true; // Conflicting replay never executes or overwrites ACK.
        return false;
    }
    void run(const Request& r,const std::function<std::string()>& execute){
        if(recover(r))return;
        publish(ack(r,"processing","validating"));point("before_claim");
        require(!disk.exists("active_request_claim"),"claim busy");disk.put("active_request_claim",r.text+"\n");point("after_claim");
        point("before_binder");std::string result;
        try{result=execute();}catch(const std::exception&){result.clear();}
        point("after_binder");
        // A lost reply is not proof of failed mutation. Query the sole authority.
        complete(r,split(result,'\n')[0]=="ok=1"?terminal(r,result):resolved(r));
    }
};
}
