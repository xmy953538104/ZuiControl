// Existing SettingsBackup recovery callback transport. No backup decisions here.
#pragma once
#include "Projection.h"
#include <poll.h>
#include <signal.h>
#include <sys/wait.h>
#include <time.h>

namespace command {
inline int64_t monotonicMs(){timespec t{};clock_gettime(CLOCK_MONOTONIC,&t);return int64_t(t.tv_sec)*1000+t.tv_nsec/1000000;}
inline std::string nativeRules(const std::string& action,const std::string& key,const std::string& value){
    int pipes[2];require(pipe2(pipes,O_CLOEXEC)==0,"rules pipe");Fd input(pipes[0]),output(pipes[1]);
    pid_t child=fork();require(child>=0,"rules fork");
    if(child==0){dup2(output.value,STDOUT_FILENO);dup2(output.value,STDERR_FILENO);close(input.value);close(output.value);
        execl("/system/bin/ZUIopt","ZUIopt","--control",action.c_str(),key.c_str(),value.c_str(),static_cast<char*>(nullptr));_exit(127);}
    close(output.value);output.value=-1;std::string text;auto deadline=monotonicMs()+8000;
    try{
        for(;;){auto left=deadline-monotonicMs();require(left>0,"rules timeout; query recovery");pollfd p{input.value,POLLIN,0};
            int ready=poll(&p,1,int(left));if(ready<0&&errno==EINTR)continue;require(ready>0,"rules read timeout");
            char buffer[4096];auto n=read(input.value,buffer,sizeof(buffer));if(n<0&&errno==EINTR)continue;require(n>=0,"rules read");if(!n)break;
            require(text.size()+size_t(n)<=100000,"rules output bound");text.append(buffer,size_t(n));
        }
        int status=0;for(;;){auto done=waitpid(child,&status,WNOHANG);if(done==child)break;require(done>=0||errno==EINTR,"rules wait");
            require(monotonicMs()<deadline,"rules exit timeout");poll(nullptr,0,10);}
        child=-1;require(WIFEXITED(status)&&WEXITSTATUS(status)==0,"native rules rejected");
        while(!text.empty()&&(text.back()=='\n'||text.back()=='\r'||text.back()==' '))text.pop_back();return text;
    }catch(...){if(child>0){kill(child,SIGKILL);while(waitpid(child,nullptr,0)<0&&errno==EINTR){}}throw;}
}
inline std::string field(const std::string& text,const std::string& key){for(auto& row:split(text,'\n'))if(row.rfind(key+"=",0)==0)return row.substr(key.size()+1);return {};}
inline std::string unbase64(const std::string& input){
    static const std::string alphabet64="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    require(input.size()%4==0&&input.size()<=90000,"base64 size");std::string result;
    for(size_t i=0;i<input.size();i+=4){uint32_t n=0;unsigned count=0;bool pad=false;
        for(unsigned j=0;j<4;++j){char c=input[i+j];n<<=6;if(c=='='){pad=true;require(j>=2&&i+4==input.size(),"base64 padding");}
            else{require(!pad,"base64 data after padding");auto v=alphabet64.find(c);require(v!=std::string::npos,"base64 alphabet");n|=uint32_t(v);++count;}}
        require(count>=2,"base64 length");result.push_back(char(n>>16));if(count>2)result.push_back(char(n>>8));if(count>3)result.push_back(char(n));
    }
    require(base64(result)==input,"base64 canonical");return result;
}
inline std::string settingsProjection(const std::string& action,const std::string& text){
    if(action=="settings_snapshot"){
        require(text.empty(),"snapshot argument");auto state=nativeRules("state","","");auto gen=field(state,"generation");
        auto count=field(state,"user_size");require(alphabet(count,"0123456789",1,6),"rules size");int size=std::stoi(count);require(size>0&&size<=65536,"canonical size");std::string bytes;
        while(bytes.size()<size_t(size)){auto reply=nativeRules("read",gen,std::to_string(bytes.size()));auto parts=split(reply,':');
            require(parts.size()==3&&parts[0]==gen&&parts[1]==std::to_string(bytes.size()),"canonical read generation");auto chunk=unbase64(parts[2]);
            require(!chunk.empty()&&bytes.size()+chunk.size()<=size_t(size),"canonical chunk");bytes+=chunk;}
        require(sha256(bytes)==field(state,"user_sha256")&&nativeRules("state","","")==state,"canonical snapshot changed");
        auto pending=field(state,"settings_pending");require(alphabet(gen,"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-",1,128)&&alphabet(pending,"0123456789abcdef",0,24),"canonical snapshot fields");
        return "{\"data\":\""+base64(bytes)+"\",\"generation\":\""+gen+"\",\"pending\":\""+pending+"\"}";
    }
    if(action=="settings_finish"){require(alphabet(text,"0123456789abcdef",24,24),"settings transaction");return nativeRules(action,text,"");}
    auto r=Json::parse(text);auto tx=r.get("tx").string();require(alphabet(tx,"0123456789abcdef",24,24),"settings transaction");
    if(action=="settings_prepare"){
        r.keys({"data","generation","tx"});auto gen=r.get("generation").string(),data=r.get("data").string();
        require(alphabet(gen,"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-",1,128)&&unbase64(data).size()<=65536,"rules prepare bound");
        return nativeRules(action,tx,gen+":"+data);
    }
    require(action=="settings_apply"||action=="settings_revert","settings action");r.keys({"hash","tx"});auto hash=r.get("hash").string();require(digest(hash),"rules hash");return nativeRules(action,tx,hash);
}
}
