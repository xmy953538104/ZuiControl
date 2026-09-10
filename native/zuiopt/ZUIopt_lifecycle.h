// Bounded lifecycle/blocker receipts; reuse the authenticated atomic 0600 primitive.
#pragma once
#include "ZUIopt_store.h"
#include <system_error>

namespace ZUIopt {
enum class EventSubstage {NONE,PROCESS_EVENT,SCENE_RECONCILE,BACKGROUND_RELEASE,ACQUISITION,SCAN_COHERENCE,SCAN_PREPARE,SCAN_APPLY,RELOAD_RELEASE,STOP_RELEASE,CATCH_RELEASE_ALL,CLEANUP};
inline const char* substageName(EventSubstage stage){
    static constexpr const char* names[]={"NONE","PROCESS_EVENT","SCENE_RECONCILE","BACKGROUND_RELEASE","ACQUISITION","SCAN_COHERENCE","SCAN_PREPARE","SCAN_APPLY","RELOAD_RELEASE","STOP_RELEASE","CATCH_RELEASE_ALL","CLEANUP"};
    auto index=static_cast<unsigned>(stage);return index<sizeof(names)/sizeof(names[0])?names[index]:"UNKNOWN";
}
enum class StartupStage {
    CORE_CONSTRUCTED, OBSERVER, REGISTER_PROCESS_OBSERVER, OBSERVER_OK,
    SNAPSHOT, SNAPSHOT_OK, PACKAGE_ABI, PACKAGE_ABI_OK, PLACEMENT, PLACEMENT_OK,
    RECONCILE, RECONCILE_OK, READY, EVENT_LOOP, RELOAD, STOP
};
inline const char* stageName(StartupStage stage) {
    static constexpr const char* names[]={
        "CORE_CONSTRUCTED","OBSERVER","REGISTER_PROCESS_OBSERVER","OBSERVER_OK",
        "SNAPSHOT","SNAPSHOT_OK","PACKAGE_ABI","PACKAGE_ABI_OK","PLACEMENT","PLACEMENT_OK",
        "RECONCILE","RECONCILE_OK","READY","EVENT_LOOP","RELOAD","STOP"
    };
    auto index=static_cast<unsigned>(stage);
    return index<sizeof(names)/sizeof(names[0])?names[index]:"UNKNOWN";
}
inline std::string fatalReason(const std::exception& error) {
    // Only known source literals or bounded numeric status codes leave the process.
    // Never persist arbitrary what(): some ownership errors append a private TID.
    const char* text=error.what();
    if(!text)return "unclassified_exception";
    static constexpr const char* known[]={
        "ABI_GATE platform Binder exports","binder pool config",
        "ABI_GATE activity service absent","ABI_GATE IActivityManager descriptor",
        "ABI_GATE package service absent","ABI_GATE IPackageManager descriptor",
        "observer allocation","death recipient allocation","remote exception",
        "ABI_GATE registration trailing fields","array length",
        "ABI_GATE UID package trailing fields","ABI_GATE snapshot list bound",
        "ABI_GATE null process","ABI_GATE process sanity","ABI_GATE trailing fields",
        "proc identity open failed","proc identity read failed","proc identity parse failed",
        "proc UID stat failed","proc enumeration open failed","proc enumeration read failed",
        "integer syntax","empty CPUs","CPU range","invalid CPUs",
        "initial Android owner unavailable","ownership birth floor",
        "activity service died: fail closed","poll failed",
        "unsafe journal/lock file","journal open","journal too large","journal read",
        "journal truncated header","journal version/header","journal checksum mismatch",
        "journal boot identity","journal entry bound","journal record type","journal entry syntax",
        "journal inheritance lease","duplicate process lease","duplicate journal TID",
        "journal identity","journal owner fields","journal affinity","journal destination absent",
        "journal remove","journal directory sync","journal size bound","journal staging open",
        "journal truncate","journal write failed","journal file sync","journal atomic replace",
        "init cpuset scaffold absent or unsafe","cpuset directory read","unsafe cpuset entry",
        "unknown cpuset directory","cpuset task read open","cpuset task bound",
        "cpuset task read failed","cpuset task identity","cpuset tasks parse",
        "RECOVERY_BUSY_FAIL_CLOSED","invalid Android release owner","remaining root tasks",
        "remaining owned tasks","cpuset child removal","remaining cpuset children",
        "cpuset initialization","ambiguous original thread inheritance baseline",
        "foreign owner during acquire","uncovered inherited task",
        "write without durable owner identity","cpuset child create","cpuset child initialize",
        "placement failed","release unknown inherited task","release busy process",
        "committed lease identity mismatch","coherence unknown owned task",
        "coherence external owner unavailable","coherence unresolved external affinity",
        "coherence owned release failed","coherence affinity release failed","coherence release busy",
        "coherence authority absent","uncommitted owned task","uncommitted lease state",
        "uncommitted journal state","discard committed ownership","acquisition not pending","acquisition commit state",
        "background unknown owned task","background release destination open",
        "background unsafe affinity destination","background unrecoverable owned task","background cleanup incomplete",
        "ZUIopt requires root identity","state directory create","unsafe state directory",
        "owner lock open","another ZUIopt owner holds journal lock","boot identity","signal mask","event descriptors"
    };
    for(auto reason:known)if(strcmp(text,reason)==0){
        std::string value(reason);
        for(char& c:value){if(c>='A'&&c<='Z')c=static_cast<char>(c-'A'+'a');else if(!((c>='a'&&c<='z')||(c>='0'&&c<='9')))c='_';}
        return value;
    }
    constexpr const char* status="parcel/status=";
    if(strncmp(text,status,strlen(status))==0){
        const char* number=text+strlen(status);size_t n=strnlen(number,13);
        if(n>0&&n<=11){size_t at=number[0]=='-'?1:0;bool valid=at<n;
            for(size_t i=at;i<n;i++)valid=valid&&number[i]>='0'&&number[i]<='9';
            if(valid)return "binder_status_"+std::string(number,n);
        }
    }
    constexpr const char* release="journal release failed TID=";
    constexpr const char* unknown="RECOVERY_UNKNOWN_TASK_FAIL_CLOSED tid=";
    if(strncmp(text,release,strlen(release))==0)return "journal_release_failed";
    if(strncmp(text,unknown,strlen(unknown))==0)return "recovery_unknown_task_fail_closed";
    if(dynamic_cast<const std::bad_alloc*>(&error))return "allocation_failed";
    if(dynamic_cast<const std::invalid_argument*>(&error))return "invalid_argument";
    if(dynamic_cast<const std::out_of_range*>(&error))return "out_of_range";
    if(dynamic_cast<const std::length_error*>(&error))return "length_error";
    if(auto* system=dynamic_cast<const std::system_error*>(&error))return "system_error_"+std::to_string(system->code().value());
    return "unclassified_exception";
}
inline bool recordLifecycle(const std::string& root,const std::string& boot,
                            StartupStage stage,const std::exception* fatal=nullptr,
                            EventSubstage substage=EventSubstage::NONE,
                            const std::exception* cleanup=nullptr,EventSubstage cleanupStage=EventSubstage::NONE) noexcept {
    try {
        require(boot.size()==36&&boot.find_first_not_of("0123456789abcdef-")==boot.npos,"diagnostic boot bound");
        auto reason=fatal?fatalReason(*fatal):"none";
        require(reason.size()<=96&&reason.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789_-")==reason.npos,"diagnostic reason bound");
        std::string data=std::string(fatal?"ZUIOPT_FATAL_V1\n":"ZUIOPT_STARTUP_V1\n")+
            "boot="+boot+"\nstage="+stageName(stage)+"\nstate="+(fatal?"FAIL":"OK")+"\nreason="+reason+"\nsubstage="+substageName(substage)+"\n";
        if(cleanup){
            auto secondary=fatalReason(*cleanup);
            require(secondary.size()<=96&&secondary.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789_-")==secondary.npos,"diagnostic reason bound");
            data+="cleanup_substage="+std::string(substageName(cleanupStage))+"\ncleanup_reason="+secondary+"\n";
        }
        require(data.size()<1024,"diagnostic receipt bound");
        PrivateDir directory(root);
        directory.put(fatal?"fatal.v1":"startup.v1",data);
        return true;
    }catch(...){return false;} // Diagnostics must never suppress owner release or the init fail-safe.
}
enum class RuntimeBlockerReason {PROC_READ_PERMISSION,PROC_UID_PERMISSION,PACKAGE_AUTHORITY_PERMISSION,INHERITANCE_BASELINE_UNSTABLE,OWNERSHIP_CONTESTED,BACKGROUND_RELEASE_PENDING};
class RuntimeBlocker {
    unsigned seen=0;
public:
    bool record(const std::string& root,const std::string& boot,RuntimeBlockerReason reason) noexcept {
        // At most one attempt per reason per process lifetime, even if storage fails.
        // Retained evidence is historical, not a continuously refreshed health status.
        const unsigned index=static_cast<unsigned>(reason);
        if(index>=6||(seen&(1u<<index)))return false;
        seen|=1u<<index;
        try {
            require(boot.size()==36&&boot.find_first_not_of("0123456789abcdef-")==boot.npos,"diagnostic boot bound");
            static constexpr const char* names[]={"proc_read_permission","proc_uid_permission","package_authority_permission","inheritance_baseline_unstable","ownership_contested","background_release_pending"};
            std::string data="ZUIOPT_RUNTIME_BLOCKER_V1\nboot="+boot+"\nstage="+(index==5?"BACKGROUND_RELEASE":index==4?"COHERENCE_REACQUIRE":index==3?"ACQUIRING_BASELINE":"APP_ACCESS")+"\nstate=BLOCKED\nreason="+names[index]+"\n";
            require(data.size()<1024,"runtime diagnostic bound");
            PrivateDir directory(root);directory.put("runtime_blocker.v1",data);return true;
        }catch(...){return false;}
    }
};
}
