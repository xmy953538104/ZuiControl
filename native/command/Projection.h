#pragma once
#include "Transport.h"
#include <limits>
#include <dirent.h>

namespace command {
// Match PolicyCommand's US-ASCII String.trim() readback: init writes no newline.
inline bool modeReadbackMatches(const std::string& actual,const std::string& desired){
    size_t first=0,last=actual.size();
    while(first<last&&static_cast<unsigned char>(actual[first])<=0x20)++first;
    while(last>first&&static_cast<unsigned char>(actual[last-1])<=0x20)--last;
    return actual.substr(first,last-first)==desired;
}
// Accept the canonical JSON subset emitted by PolicyJson for projection messages.
// Preserve exact policy bytes; do not interpret rows, modes' GPU defaults or actions.
struct Json {
    std::string raw, text;
    char kind=0;
    std::map<std::string,Json> fields;
    static Json parse(const std::string& input){
        require(!input.empty()&&input.size()<=LIMIT,"JSON size");size_t at=0;Json out=value(input,at,0);
        if(at<input.size()&&input[at]=='\n')++at;require(at==input.size(),"JSON trailing bytes");return out;
    }
    static Json value(const std::string& s,size_t& at,unsigned depth){
        require(depth<=24&&at<s.size(),"JSON depth/truncated");Json out;size_t begin=at;out.kind=s[at++];
        if(out.kind=='"'){
            for(;;){require(at<s.size(),"JSON string truncated");unsigned char c=s[at++];if(c=='"')break;
                require(c>=32&&c<127,"projection ASCII string");
                if(c=='\\'){require(at<s.size(),"JSON escape");c=s[at++];
                    if(c=='u'){require(at+4<=s.size(),"JSON unicode");std::string hex=s.substr(at,4);at+=4;
                        require(hex.substr(0,2)=="00"&&alphabet(hex,"0123456789abcdef",4,4),"canonical control escape");
                        c=static_cast<unsigned char>(std::stoul(hex,nullptr,16));require(c<32,"canonical control escape");
                    }else require(c=='"'||c=='\\',"canonical string escape");
                }out.text.push_back(char(c));require(out.text.size()<=131072,"JSON string bound");
            }
        }else if(out.kind=='{'||out.kind=='['){
            char end=out.kind=='{'?'}':']';size_t count=0;std::string previous;
            require(at<s.size(),"JSON container");
            if(s[at]!=end)for(;;){
                require(++count<=4096,"JSON container bound");
                if(out.kind=='{'){
                    auto key=value(s,at,depth+1);require(key.kind=='"'&&at<s.size()&&s[at++]==':',"JSON key");
                    require(count==1||key.text>previous,"JSON sorted unique keys");previous=key.text;
                    out.fields.emplace(key.text,value(s,at,depth+1));
                }else value(s,at,depth+1);
                require(at<s.size(),"JSON delimiter");if(s[at]==end)break;require(s[at++]==',',"JSON delimiter");
            }
            ++at;
        }else{
            --at;size_t start=at;while(at<s.size()&&s[at]!=','&&s[at]!=']'&&s[at]!='}'&&s[at]!='\n')++at;
            auto token=s.substr(start,at-start);require(!token.empty()&&token.size()<=64,"JSON token");
            if(token!="null"&&token!="true"&&token!="false"){
                size_t offset=token.front()=='-'?1:0;require(offset<token.size(),"JSON number");
                require(alphabet(token.substr(offset),"0123456789",1,64)&&(token.size()==offset+1||token[offset]!='0')&&token!="-0","canonical integer");
            }
            out.text=token;
        }
        out.raw=s.substr(begin,at-begin);return out;
    }
    const Json& get(const std::string& name)const{require(kind=='{'&&fields.count(name)==1,"JSON field");return fields.at(name);}
    std::string string()const{require(kind=='"',"JSON string required");return text;}
    int64_t integer()const{require(kind=='-'||(kind>='0'&&kind<='9'),"JSON integer required");size_t used=0;auto n=std::stoll(text,&used);require(used==text.size(),"JSON integer");return n;}
    void keys(std::initializer_list<const char*> names)const{require(kind=='{'&&fields.size()==names.size(),"JSON keys");for(auto n:names)require(fields.count(n)==1,"JSON missing key");}
};
inline int64_t generation(const std::string& policy){auto p=Json::parse(policy);auto n=p.get("generation").integer();require(p.get("schema").integer()==2&&n>0,"projection generation/schema");return n;}
inline std::string base64(const std::string& s){
    static constexpr char table[]="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";std::string out;
    for(size_t i=0;i<s.size();i+=3){uint32_t v=uint8_t(s[i])<<16;size_t n=std::min(size_t(3),s.size()-i);
        if(n>1)v|=uint8_t(s[i+1])<<8;if(n>2)v|=uint8_t(s[i+2]);
        out+=table[(v>>18)&63];out+=table[(v>>12)&63];out+=n>1?table[(v>>6)&63]:'=';out+=n>2?table[v&63]:'=';
    }return out;
}
struct Projection {
    Disk runtime, stages;
    std::function<void(const char*)> point=[](const char*){};
    explicit Projection(const std::string& path):runtime(path),stages(runtime,"policy-projection"){}
    std::string stage(const std::string& name){
        // Android AtomicFile gives a retained .bak precedence after interruption.
        auto backup=stages.read(name+".bak");
        if(!backup.empty()){stages.put(name,backup);stages.remove(name+".bak");}
        return stages.read(name);
    }
    void prune(const std::string& current){
        int copy=dup(stages.fd.value);require(copy>=0,"retention directory dup");DIR* dir=fdopendir(copy);
        if(!dir){close(copy);throw std::runtime_error("retention directory");}
        std::vector<std::pair<int64_t,std::string>> names;
        try{while(auto* entry=readdir(dir)){std::string name=entry->d_name;
            if(name.size()!=41||name.substr(36)!=".json"||!transaction(name.substr(0,36))||name==current)continue;
            struct stat st{};require(fstatat(stages.fd.value,name.c_str(),&st,AT_SYMLINK_NOFOLLOW)==0,"retention stat");
            names.emplace_back(int64_t(st.st_mtim.tv_sec)*1000000000LL+st.st_mtim.tv_nsec,name);
        }closedir(dir);}catch(...){closedir(dir);throw;}
        std::sort(names.begin(),names.end());while(names.size()>8){stages.remove(names.front().second);names.erase(names.begin());}
    }
    std::string call(const std::string& action,const std::string& text){
        if(action=="legacy"){
            auto saved=runtime.read("cur_powermode.txt"),perapp=runtime.read("perapp_powermode.txt");
            require(!saved.empty()&&saved.size()<=65536&&perapp.size()<=65536,"legacy bound");
            return "{\"perapp\":\""+base64(perapp)+"\",\"saved\":\""+base64(saved)+"\"}";
        }
        auto request=Json::parse(text);auto tx=request.get("transaction").string();require(transaction(tx),"projection transaction");
        if(action=="prepare"){
            point("before_projection_prepare");request.keys({"policy","transaction"});auto policy=request.get("policy").raw+"\n";
            generation(policy);require(policy.size()<=98304,"policy size");auto old=stage(tx+".json");
            require(old.empty()||old==policy,"immutable projection");if(old.empty())stages.put(tx+".json",policy);
            point("after_projection_prepare");return sha256(policy);
        }
        require(action=="apply","projection action");request.keys({"desiredMode","generation","hash","transaction"});
        auto prepared=stage(tx+".json"),hash=sha256(prepared);auto gen=generation(prepared);
        require(hash==request.get("hash").string()&&gen==request.get("generation").integer(),"projection identity");
        auto active=stage("active.json");require(active.empty()||generation(active)<gen||(generation(active)==gen&&active==prepared),"projection generation CAS");
        auto desired=request.get("desiredMode").string();require(desired=="powersave"||desired=="balance"||desired=="performance"||desired=="fast","runtime mode");
        point("during_projection_apply");
        Fd file(openat(runtime.fd.value,"effective_powermode.txt",O_WRONLY|O_NOFOLLOW|O_CLOEXEC));Disk::identity(file.value,false);
        require(ftruncate(file.value,0)==0,"mode truncate");writeAll(file.value,desired+"\n");require(fsync(file.value)==0,"mode sync");
        require(modeReadbackMatches(runtime.read("effective_powermode.txt"),desired),"mode durable readback");
        stages.put("active.json",prepared);stages.put("applied.json",request.raw+"\n");point("after_projection_applied");
        try{prune(tx+".json");}catch(const std::exception&){point("projection_retention_unavailable");}return hash;
    }
};
}
