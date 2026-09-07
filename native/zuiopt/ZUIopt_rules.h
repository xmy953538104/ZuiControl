// Bounded Rule Pack v1 / AppOpt adapter. Scheduling semantics remain in core.h.
#pragma once
#include "ZUIopt_core.h"
#include <regex>
#include <sys/wait.h>
#include <zlib.h>

namespace ZUIopt {
constexpr size_t RULE_LIMIT=65536,PACK_LIMIT=131072,PACK_COUNT=8;
inline bool rx(const std::string& s,const char* pattern){return std::regex_match(s,std::regex(pattern));}
inline bool hex(const std::string& s,size_t count){return s.size()==count&&s.find_first_not_of("0123456789abcdef")==s.npos;}
inline bool packId(const std::string& s){return rx(s,"[a-z][a-z0-9_.-]{0,63}");}
inline void putAll(int fd,const std::string& s){
    size_t at=0;while(at<s.size()){auto n=::write(fd,s.data()+at,s.size()-at);if(n<0&&errno==EINTR)continue;require(n>0,"file write");at+=n;}
}
inline std::string sha256(const std::string& data){
    // Use Android's installed SHA-256 implementation, never a shell or user path.
    int input[2],output[2];require(pipe2(input,O_CLOEXEC)==0,"digest input pipe");
    if(pipe2(output,O_CLOEXEC)){close(input[0]);close(input[1]);throw std::runtime_error("digest output pipe");}
    pid_t child=fork();
    if(child==0){
        if(dup2(input[0],STDIN_FILENO)<0||dup2(output[1],STDOUT_FILENO)<0)_exit(126);
        close(input[0]);close(input[1]);close(output[0]);close(output[1]);
#ifdef __ANDROID__
        execl("/system/bin/sha256sum","sha256sum","-",static_cast<char*>(nullptr));
#else
        execl("/usr/bin/sha256sum","sha256sum","-",static_cast<char*>(nullptr));
#endif
        _exit(127);
    }
    close(input[0]);close(output[1]);
    if(child<0){close(input[1]);close(output[0]);throw std::runtime_error("digest fork");}
    bool wrote=true;try{putAll(input[1],data);}catch(...){wrote=false;}close(input[1]);
    char buffer[128];std::string result;ssize_t n;
    while((n=::read(output[0],buffer,sizeof(buffer)))!=0){if(n<0&&errno==EINTR)continue;if(n<0)break;result.append(buffer,n);if(result.size()>128)break;}
    close(output[0]);int status=0;pid_t done;do{done=waitpid(child,&status,0);}while(done<0&&errno==EINTR);
    require(wrote&&done==child&&WIFEXITED(status)&&WEXITSTATUS(status)==0&&result.size()>=65&&result[64]==' '&&hex(result.substr(0,64),64),"SHA256 tool failed");
    return result.substr(0,64);
}
inline std::string dumpRules(const Config& c){
    std::ostringstream out;out<<"schema 2\nenabled "<<(c.enabled?"true":"false")<<"\ndebug false\n";
    for(const auto& [name,p]:c.profiles){out<<"profile "<<name<<' '<<cpuText(p.general)<<'\n';
        for(const auto& r:p.rules)out<<"thread "<<name<<' '<<r.cls<<' '<<r.kind<<' '<<std::quoted(r.pattern)<<" selector="<<(r.rank?"rank:"+std::to_string(r.rank):"all")<<' '<<r.priority<<' '<<cpuText(r.mask)<<'\n';}
    for(const auto& r:c.packages)out<<"package "<<r.kind<<' '<<r.package<<' '<<r.profile<<' '<<r.priority<<'\n';
    auto text=out.str();parseConfig(text,255);return text;
}
inline Config rules(const std::string& text){
    for(unsigned char c:text)require(c==9||c==10||c==13||(c>=32&&c<127),"rules ASCII/control character");
    auto c=parseConfig(text,255);require(!c.debug,"production debug is disabled");return c;
}

struct JsonValue {std::string text;bool integer=false;};
using Manifest=std::map<std::string,JsonValue>;
class ManifestJson {
    const std::string& text;size_t at=0;
    void ws(){while(at<text.size()&&std::string(" \r\n\t").find(text[at])!=std::string::npos)at++;}
    char take(){require(at<text.size(),"JSON truncated");return text[at++];}
    void expect(char c){ws();require(take()==c,"JSON punctuation");}
    std::string string(){
        expect('"');std::string s;
        for(;;){char c=take();if(c=='"')break;
            if(c=='\\'){
                c=take();if(c=='u'){
                    unsigned value=0;for(int i=0;i<4;i++){char d=take();require(std::isxdigit(static_cast<unsigned char>(d)),"JSON escape");value=value*16+(d<='9'?d-'0':(d|32)-'a'+10);}
                    require(value>=32&&value<127,"manifest ASCII escape");c=static_cast<char>(value);
                }else require(c=='"'||c=='\\'||c=='/',"manifest escape");
            }
            require(c>=32&&c<127&&s.size()<128,"manifest ASCII/size");s+=c;
        }return s;
    }
public:
    explicit ManifestJson(const std::string& s):text(s){}
    Manifest parse(){
        require(!text.empty()&&text.size()<=8192,"manifest size");Manifest m;expect('{');ws();
        require(at<text.size()&&text[at]!='}',"empty manifest");
        for(;;){auto key=string();expect(':');ws();JsonValue value;
            require(at<text.size(),"JSON missing value");
            if(text[at]=='"')value.text=string();
            else{value.integer=true;size_t start=at;if(text[at]=='-')at++;while(at<text.size()&&text[at]>='0'&&text[at]<='9')at++;
                value.text=text.substr(start,at-start);require(rx(value.text,"-?(0|[1-9][0-9]{0,9})"),"manifest integer syntax");number(value.text);}
            require(m.emplace(key,value).second&&m.size()<=14,"duplicate/extra manifest key");ws();char end=take();if(end=='}')break;require(end==',',"JSON separator");
        }ws();require(at==text.size(),"JSON trailing content");return m;
    }
};
inline std::string jsonText(const Manifest& m){
    std::ostringstream out;out<<'{';bool comma=false;
    for(const auto& [key,value]:m){if(comma)out<<',';comma=true;out<<std::quoted(key)<<':';if(value.integer)out<<value.text;else out<<std::quoted(value.text);}
    return out.str()+"}";
}
struct Pack {Manifest manifest;std::string ruleText;Config config;std::string archive;};
inline Pack validatePack(Manifest m,const std::string& text,const std::string& archive={}){
    const std::set<std::string> ints={"schema_version","pack_priority","package_count","profile_count"};
    const std::set<std::string> fields={"schema_version","pack_id","pack_version","pack_priority","source_type","source_binary_sha256","source_binary_version","target_soc","target_topology","package_count","profile_count","generated_by","evidence_level","rules_sha256"};
    require(m.size()==fields.size(),"manifest field set");
    for(const auto& key:fields)require(m.count(key)&&m.at(key).integer==bool(ints.count(key)),"manifest field type");
    auto s=[&](const char* key)->const std::string&{return m.at(key).text;};
    require(s("schema_version")=="1"&&packId(s("pack_id"))&&rx(s("pack_version"),"[0-9]{1,6}(\\.[0-9]{1,6}){0,3}(-[A-Za-z0-9.-]{1,32})?"),"pack identity/version");
    int priority=number(s("pack_priority"));require(priority>=-1000000&&priority<=1000000,"pack priority");
    require(s("source_type")=="asoul_binary"||s("source_type")=="appopt"||s("source_type")=="zuiopt_native"||s("source_type")=="user_export","source type");
    require(s("source_binary_sha256").empty()||hex(s("source_binary_sha256"),64),"binary digest");
    require(s("evidence_level")=="STATIC_RECOVERED"||s("evidence_level")=="USER_DECLARED","evidence level");
    require(s("source_type")!="asoul_binary"||(!s("source_binary_sha256").empty()&&s("evidence_level")=="STATIC_RECOVERED"),"asoul provenance");
    require(s("target_soc")=="SM8650"&&s("target_topology")=="0-7","pack topology");
    require(hex(s("rules_sha256"),64)&&s("rules_sha256")==sha256(text),"rules digest");auto config=rules(text);
    require(config.enabled&&number(s("package_count"))==static_cast<int>(config.packages.size())&&number(s("profile_count"))==static_cast<int>(config.profiles.size()),"pack counts/enabled");
    return {std::move(m),text,std::move(config),archive};
}
inline uint32_t le(const std::string& s,size_t at,size_t bytes){
    require(at<=s.size()&&bytes<=s.size()-at&&bytes<=4,"ZIP field bounds");uint32_t n=0;
    for(size_t i=0;i<bytes;i++)n|=uint32_t(static_cast<unsigned char>(s[at+i]))<<(8*i);return n;
}
inline uint32_t crc(const std::string& s){return crc32(0,reinterpret_cast<const Bytef*>(s.data()),static_cast<uInt>(s.size()));}
inline Pack unpackPack(const std::string& data){
    require(data.size()>=22&&data.size()<=PACK_LIMIT,"archive size");size_t end=data.size()-22;
    while(le(data,end,4)!=0x06054b50||end+22+le(data,end+20,2)!=data.size()){require(end>0&&data.size()-end<65557,"ZIP end record");end--;}
    require(le(data,end+4,2)==0&&le(data,end+6,2)==0&&le(data,end+8,2)==2&&le(data,end+10,2)==2,"ZIP disk/member count");
    size_t central=le(data,end+16,4),centralEnd=central+le(data,end+12,4),at=central;
    require(centralEnd==end,"ZIP directory bounds");std::map<std::string,std::string> files;std::vector<std::pair<size_t,size_t>> spans;
    for(int i=0;i<2;i++){
        require(at+46<=centralEnd&&le(data,at,4)==0x02014b50,"ZIP central header");
        auto flags=le(data,at+8,2),method=le(data,at+10,2),expectedCrc=le(data,at+16,4),compressed=le(data,at+20,4),size=le(data,at+24,4);
        size_t names=le(data,at+28,2),extras=le(data,at+30,2),comments=le(data,at+32,2),local=le(data,at+42,4);
        require(at+46+names+extras+comments<=centralEnd&&le(data,at+34,2)==0,"ZIP member bounds/disk");
        std::string name=data.substr(at+46,names);require((name=="manifest.json"||name=="rules.conf")&&!files.count(name),"ZIP exact members");
        auto type=(le(data,at+38,4)>>16)&S_IFMT;
        require((type==0||type==S_IFREG)&&(flags&~0x080e)==0&&(method==0||method==8),"ZIP type/encryption/compression");
        require(size>0&&size<=(name=="manifest.json"?8192:RULE_LIMIT)&&compressed<=PACK_LIMIT,"ZIP expanded bound");
        require(local+30<=central&&le(data,local,4)==0x04034b50&&le(data,local+6,2)==flags&&le(data,local+8,2)==method,"ZIP local header");
        size_t localNames=le(data,local+26,2),localExtras=le(data,local+28,2),body=local+30+localNames+localExtras;
        require(body<=central&&compressed<=central-body&&data.substr(local+30,localNames)==name,"ZIP local name/size");
        auto checkExtra=[&](size_t start,size_t length){size_t limit=start+length;while(start<limit){require(start+4<=limit,"ZIP extra header");auto tag=le(data,start,2),n=le(data,start+2,2);require(tag!=1&&tag!=0x9901&&start+4+n<=limit,"ZIP64/encryption/extra");start+=4+n;}};
        checkExtra(at+46+names,extras);checkExtra(local+30+localNames,localExtras);
        size_t localEnd=body+compressed;
        if(flags&8){size_t descriptor=localEnd;if(le(data,descriptor,4)==0x08074b50)descriptor+=4;
            require(descriptor+12<=central&&le(data,descriptor,4)==expectedCrc&&le(data,descriptor+4,4)==compressed&&le(data,descriptor+8,4)==size,"ZIP data descriptor");localEnd=descriptor+12;
        }else require(le(data,local+14,4)==expectedCrc&&le(data,local+18,4)==compressed&&le(data,local+22,4)==size,"ZIP local integrity");
        std::string out;
        if(method==0){require(compressed==size,"stored size");out=data.substr(body,size);}
        else{out.resize(size+1);z_stream stream{};stream.next_in=reinterpret_cast<Bytef*>(const_cast<char*>(data.data()+body));stream.avail_in=compressed;stream.next_out=reinterpret_cast<Bytef*>(out.data());stream.avail_out=size+1;
            require(inflateInit2(&stream,-MAX_WBITS)==Z_OK,"ZIP inflate init");int rc=inflate(&stream,Z_FINISH);bool ok=rc==Z_STREAM_END&&stream.total_in==compressed&&stream.total_out==size;inflateEnd(&stream);require(ok,"ZIP inflate size/content");out.resize(size);}
        require(crc(out)==expectedCrc,"ZIP CRC mismatch");files.emplace(name,std::move(out));spans.emplace_back(local,localEnd);at+=46+names+extras+comments;
    }
    std::sort(spans.begin(),spans.end());require(at==centralEnd&&spans[0].first==0&&spans[0].second==spans[1].first&&spans[1].second==central,"ZIP overlapping/extra content");
    return validatePack(ManifestJson(files.at("manifest.json")).parse(),files.at("rules.conf"),data);
}
inline void appendLe(std::string& s,uint32_t n,size_t bytes){for(size_t i=0;i<bytes;i++)s+=static_cast<char>(n>>(i*8));}
inline std::string packBytes(const Manifest& manifest,const std::string& rulesText){
    std::string local,central;
    for(const auto& [name,content]:std::vector<std::pair<std::string,std::string>>{{"manifest.json",jsonText(manifest)},{"rules.conf",rulesText}}){
        auto offset=local.size();appendLe(local,0x04034b50,4);appendLe(local,20,2);appendLe(local,0,4);appendLe(local,0x00210000,4);appendLe(local,crc(content),4);appendLe(local,content.size(),4);appendLe(local,content.size(),4);appendLe(local,name.size(),2);appendLe(local,0,2);local+=name+content;
        appendLe(central,0x02014b50,4);appendLe(central,0x0314,2);appendLe(central,20,2);appendLe(central,0,4);appendLe(central,0x00210000,4);appendLe(central,crc(content),4);appendLe(central,content.size(),4);appendLe(central,content.size(),4);appendLe(central,name.size(),2);appendLe(central,0,4);appendLe(central,0,4);appendLe(central,uint32_t(S_IFREG|0600)<<16,4);appendLe(central,offset,4);central+=name;
    }
    std::string end;appendLe(end,0x06054b50,4);appendLe(end,0,4);appendLe(end,2,2);appendLe(end,2,2);appendLe(end,central.size(),4);appendLe(end,local.size(),4);appendLe(end,0,2);
    auto data=local+central+end;require(data.size()<=PACK_LIMIT,"pack output bound");return data;
}
inline Pack importAppOpt(const std::string& text,const std::string& id,int priority){
    require(!text.empty()&&text.size()<=RULE_LIMIT&&text.find('\0')==text.npos&&packId(id),"AppOpt input");
    std::map<std::string,Profile> packages;std::istringstream input(text);std::string line;
    while(std::getline(input,line)){
        line=trim(line);if(line.empty()||line[0]=='#')continue;std::smatch match;
        require(std::regex_match(line,match,std::regex("([^{}=\\s]+)(\\{([^{}\\r\\n]+)\\})?=([0-9,-]+)")),"unsupported AppOpt line");
        auto package=match[1].str(),pattern=match[3].str();require(packageLabel(package)&&package.size()<=128,"AppOpt package");
        auto mask=cpus(match[4].str());require((mask&255)==mask,"AppOpt CPU bound");auto& profile=packages[package];
        if(!match[2].matched){require(profile.general==0,"duplicate AppOpt default");profile.general=mask;}
        else{for(auto& old:profile.rules)require(old.pattern!=pattern,"duplicate AppOpt pattern");Rule r;r.cls="r"+std::to_string(profile.rules.size());r.kind="glob";r.pattern=pattern;r.glob=compileGlob(pattern);r.priority=1000-static_cast<int>(profile.rules.size());r.mask=mask;profile.rules.push_back(r);}
        require(packages.size()<=64&&profile.rules.size()<=32,"AppOpt bounds");
    }
    require(!packages.empty(),"empty AppOpt");Config config;int index=0;
    for(const auto& [package,profile]:packages){require(profile.general,"explicit AppOpt default required");std::ostringstream name;name<<'A'<<std::setw(3)<<std::setfill('0')<<index;config.profiles.emplace(name.str(),profile);config.packages.push_back({"exact",package,name.str(),10000-index++});}
    auto data=dumpRules(config);Manifest m;
    for(const auto& [key,value]:std::map<std::string,std::string>{{"pack_id",id},{"pack_version","1"},{"source_type","appopt"},{"source_binary_sha256",""},{"source_binary_version","not-applicable"},{"target_soc","SM8650"},{"target_topology","0-7"},{"generated_by","ZUIopt-V21"},{"evidence_level","USER_DECLARED"},{"rules_sha256",sha256(data)}})m[key]={value,false};
    m["schema_version"]={"1",true};m["pack_priority"]={std::to_string(priority),true};m["package_count"]={std::to_string(config.packages.size()),true};m["profile_count"]={std::to_string(config.profiles.size()),true};
    return unpackPack(packBytes(m,data));
}
inline bool overlap(const Mapping& a,const Mapping& b){
    if(a.kind=="exact")return match(b.kind,b.package,a.package);if(b.kind=="exact")return match(a.kind,a.package,b.package);
    if(a.kind=="prefix"&&b.kind=="prefix")return match("prefix",a.package,b.package)||match("prefix",b.package,a.package);return true;
}
inline std::string profileText(const Profile& p){Config c;c.profiles["P"]=p;return dumpRules(c);}
inline std::string mergeRules(const std::string& factory,const std::vector<Pack>& packs,const std::string& user){
    struct Layer {int tier,priority;std::string id;Config config;};
    std::vector<Layer> layers={{0,0,"factory",rules(factory)}};
    for(const auto& p:packs)layers.push_back({1,number(p.manifest.at("pack_priority").text),p.manifest.at("pack_id").text,p.config});
    if(!user.empty()){auto config=rules(user);require(config.enabled,"user must be enabled");layers.push_back({2,0,"user",config});}
    for(size_t i=0;i<layers.size();i++)for(size_t j=i+1;j<layers.size();j++){
        const auto& a=layers[i];const auto& b=layers[j];if(a.tier!=1||b.tier!=1||a.priority!=b.priority)continue;
        for(const auto& x:a.config.packages)for(const auto& y:b.config.packages)require(!overlap(x,y)||profileText(a.config.profiles.at(x.profile))==profileText(b.config.profiles.at(y.profile)),"same priority pack overlap");
    }
    Config result;result.enabled=layers[0].config.enabled;std::set<std::pair<std::string,std::string>> seen;
    std::sort(layers.begin(),layers.end(),[](const Layer& a,const Layer& b){return a.tier!=b.tier?a.tier>b.tier:a.priority!=b.priority?a.priority>b.priority:a.id<b.id;});
    for(const auto& layer:layers){std::map<std::string,std::string> aliases;
        for(const auto& m:layer.config.packages){if(!seen.emplace(m.kind,m.package).second)continue;
            if(!aliases.count(m.profile)){std::ostringstream name;name<<'p'<<std::setw(4)<<std::setfill('0')<<result.profiles.size();aliases[m.profile]=name.str();result.profiles[name.str()]=layer.config.profiles.at(m.profile);}
            result.packages.push_back({m.kind,m.package,aliases.at(m.profile),100000-static_cast<int>(result.packages.size())});
        }
    }return dumpRules(result);
}
}
