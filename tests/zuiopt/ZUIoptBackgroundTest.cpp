// Baseline-only reproduction against unchanged V57 production code.
#define ZUIOPT_ACQUISITION_LIBRARY 1
#include "ZUIoptAcquisitionTest.cpp"
int main(){try{
    std::cout<<std::unitbuf;
    int primary=0,cleanup=0;
    for(int percent:{25,50,75,100}){
        AcquisitionFixture f;
        for(int tid=44;tid<242;tid++)Kernel::tasks[tid]={10,"/top-app",255};
        f.start(2);f.place();require(f.p().tasks.size()==200,"200 committed tasks");
        // A previous in-flight coherence relinquishment stays frozen through
        // exceptions. Normal background and catch cleanup both reuse it in V57.
        f.p().coherenceReleasing=true;
        int moved=0;
        for(auto& [tid,t]:Kernel::tasks)if(moved++<2*percent){t.group=tid%2?"/foreground":"/background";t.mask=tid%2?31:67;}
        try{f.background();}catch(const std::exception& e){
            require(std::string(e.what())=="coherence unresolved external affinity","baseline primary class");
            require(fatalReason(e)=="unclassified_exception","V57 missing diagnostic mapping");primary++;
        }
        try{f.owner->release(f.p());}catch(const std::exception& e){
            require(std::string(e.what())=="coherence unresolved external affinity","baseline cleanup class");cleanup++;
        }
        require(!f.journal->entries.empty(),"V57 failing release preserved journal");
    }
    require(primary==4&&cleanup==4,"equivalent status3 class not reproduced");
    std::cout<<"V57_BACKGROUND_CLASS_REPRODUCED=4;PRIMARY=coherence_unresolved_external_affinity;CLEANUP_SAME=4;CORE_EQUIVALENT_STATUS3=4;DEVICE_EXACT_THROW=NOT_PROVEN\n";
    return 0;
}catch(const std::exception& e){std::cerr<<"BACKGROUND_BASELINE_FAIL "<<e.what()<<'\n';return 1;}}
