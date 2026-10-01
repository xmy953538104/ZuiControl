// Stdio substitutes only Binder transport. Receipts and projection are production cores.
#include "../../native/command/Projection.h"
#include "../../native/command/Rules.h"
#include <iostream>
using namespace command;
int main(int argc,char** argv) {
    try {
        require(argc==7,"fixture arguments");
        Disk root(argv[1]); Disk disk(root,argv[2]);
        Disk runtime(disk,"runtime");
        if(!runtime.exists("effective_powermode.txt"))runtime.put("effective_powermode.txt","balance\n");
        Projection projection(std::string(argv[1])+"/"+argv[2]+"/runtime");
        auto rpc=[&](const std::string& operation){
            std::cout<<operation<<std::endl; std::string line;
            while(std::getline(std::cin,line)) {
                auto fields=split(line,'\t');
                if(fields.size()==2&&fields[0]=="RESULT")return unbase64(fields[1]);
                if(fields.size()==1&&fields[0]=="LOST")throw std::runtime_error("lost reply");
                require(fields.size()==3&&fields[0]=="PROJECTION","fixture frame");
                std::cout<<"PROJECTED\t"<<base64(projection.call(fields[1],unbase64(fields[2])))<<std::endl;
            }
            throw std::runtime_error("fixture disconnected");
        };
        Request request(std::string(argv[4])+"|policy|||e30=");
        request.bind(argv[5],argv[6]);
        Receipts receipts{disk,[&](const std::string& value){
            disk.put("observed_ack",value);std::cout<<"PUBLISH\t"<<base64(value)<<std::endl;
        }};
        receipts.point=[&](const char* point){
            std::cout<<"STATE\t"<<point<<"\t"<<base64(disk.read("active_request_claim"))<<"\t"
                     <<base64(disk.read("last_request_receipt"))<<"\t"<<base64(projection.stage("active.json"))<<std::endl;
            if(std::string(argv[3])==point)_exit(73);
        };
        receipts.reconcile=[&](const Request& prior){require(prior.stored()==request.stored(),"fixture exact identity");return rpc("RECONCILE");};
        receipts.run(request,[&]{return rpc("EXECUTE");});
        std::cout<<"FINAL\t"<<base64(disk.read("active_request_claim"))<<"\t"
                 <<base64(disk.read("last_request_receipt"))<<std::endl;
        return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<std::endl;return 74;}
}
