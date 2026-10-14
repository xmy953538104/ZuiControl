// Exact read-only store framing. Private fixture data; no production placement.
#include "../../native/zuiopt/ZUIopt_store.h"
#include <filesystem>
#include <iostream>
#include <unistd.h>
using namespace ZUIopt;
int main(int argc,char**argv){
    require(argc==2,"runtime reader private fixture path");std::string path=argv[1];require(mkdir(path.c_str(),0700)==0,"fresh private fixture");
    auto factory=path+"/factory.conf";int fd=open(factory.c_str(),O_CREAT|O_EXCL|O_WRONLY,0600);require(fd>=0,"fixture factory");
    const std::string factoryText="schema 3\nenabled true\ndebug false\nprofile P 0-7\npackage exact org.example.app P 100\n";
    putAll(fd,factoryText);close(fd);alarm(15);
    RuleState current;{RuleStore writer(path,factoryText);writer.initialize();current=writer.current();}auto generation=generationOf(current.effective),boot=trim(read("/proc/sys/kernel/random/boot_id"));
    auto owner=generation+":"+boot+":"+std::to_string(getpid())+":"+std::to_string(identity(getpid()).start)+":"+sha256(current.effective);
    PrivateDir directory(path);directory.put("loaded-generation.v2",owner+"\n");directory.put("runtime-status.v1",runtimeEnvelope(boot,owner,false,{}));
    auto fingerprint=[&]{std::map<std::string,std::string> files;for(const auto& entry:std::filesystem::recursive_directory_iterator(path))if(entry.is_regular_file())files[entry.path().string()]=sha256(read(entry.path().string()));return files;};
    auto before=fingerprint();int assertions=0;
    {RuleStore first(path,factoryText,true),second(path,factoryText,true);
        require(first.state()==second.state(),"concurrent shared readers differ");
        int writer=open((path+"/manager.lock").c_str(),O_RDWR|O_CLOEXEC);require(writer>=0,"writer lock fixture");
        require(flock(writer,LOCK_EX|LOCK_NB)!=0&&(errno==EWOULDBLOCK||errno==EAGAIN),"writer bypassed shared reader locks");
        close(writer);assertions++;}
    auto good=[&]{RuleStore reader(path,factoryText,true);auto result=reader.readReply("runtime|"+generation);auto frame=fields(result,'\n');require(frame.size()==4&&frame[0]=="ZUIOPT_RUNTIME_SNAPSHOT_V1"&&frame[1].rfind("state=",0)==0&&frame[2].rfind("runtime=",0)==0&&frame[3].empty(),"exact runtime frame");require(unbase64(frame[2].substr(8))==directory.get("runtime-status.v1",RUNTIME_STATUS_LIMIT),"unchanged actual source bytes");assertions++;};
    auto reject=[&](const std::string& argument){bool denied=false;try{RuleStore reader(path,factoryText,true);reader.readReply(argument);}catch(const std::exception&){denied=true;}require(denied,"invalid runtime read was accepted");assertions++;};
    good();require(fingerprint()==before,"read-only getter changed fixture data");assertions++;
    reject("runtime|g"+std::string(24,'0'));reject("runtime|"+generation+"x");reject("runtime");
    auto body=directory.get("runtime-status.v1",RUNTIME_STATUS_LIMIT);
    directory.put("runtime-status.v1",runtimeEnvelope(boot,"foreign-owner",false,{}));reject("runtime|"+generation);
    directory.put("runtime-status.v1",runtimeEnvelope("foreign-boot",owner,false,{}));reject("runtime|"+generation);
    directory.put("runtime-status.v1",body+"x");reject("runtime|"+generation);
    directory.put("runtime-status.v1",std::string(RUNTIME_STATUS_LIMIT+1,'x'));reject("runtime|"+generation);
    directory.put("runtime-status.v1",body);directory.put("loaded-generation.v2",generation+":"+boot+":1:1:"+sha256(current.effective)+"\n");reject("runtime|"+generation);
    directory.put("loaded-generation.v2",owner+"\n");good();require(fingerprint()==before,"negative cases did not restore only fixture inputs");assertions++;
    std::cout<<"{\"case\":\"exact_native_runtime_reader_private_fixture\",\"status\":\"PASS\",\"assertions\":"<<assertions<<",\"production_kernel_writes\":0,\"production_owner_data_writes\":0,\"production_domain_qualified\":false}\n";
}
