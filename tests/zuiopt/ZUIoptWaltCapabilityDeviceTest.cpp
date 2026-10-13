// Actual production capability probe, dedicated finite thread and private Journal.
// Root component result is separate from the daemon's production-domain result.
#define ZUIOPT_TEST 1
#include "../../native/zuiopt/ZUIopt_owner.h"
#include <iostream>
int main(int argc,char** argv){try{
    ZUIopt::require((argc==2||argc==3)&&std::string(argv[1]).rfind("/data/local/tmp/zuiopt-r3-",0)==0,"private probe directory");
    int repeats=argc==3?ZUIopt::number(argv[2]):1;
    ZUIopt::require(repeats>=1&&repeats<=2000,"finite probe repeat bound");alarm(120);
    ZUIopt::Journal journal(argv[1]);ZUIopt::Counters counters;ZUIopt::Placement placement(counters,journal,nullptr);
    auto began=ZUIopt::now();for(int iteration=0;iteration<repeats;iteration++){
    auto reason=placement.probeWaltCapability();
    ZUIopt::require(reason=="WALT_CAPABILITY_WRITE_READ_RESTORE_PASS","boost capability failed");
    auto receipt=ZUIopt::read(std::string(argv[1])+"/walt-capability.json");std::cout<<receipt;
    ZUIopt::require(receipt.find("\"negative_reduce_normalization_qualified\":true")!=std::string::npos,"semantic capability failed");
    ZUIopt::require(journal.entries.empty()&&journal.leases.empty()&&journal.globals.empty(),"probe ownership remains");
    }
    std::cout<<"CAPABILITY_LOCAL_MS="<<ZUIopt::now()-began<<";COMPLETED="<<repeats<<";LIVE_WALT_WITHDRAWAL=PASS;DOMAIN=ROOT_COMPONENT\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
