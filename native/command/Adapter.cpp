#include "Projection.h"
#include "Rules.h"
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <android/log.h>
#include <dlfcn.h>
#include <mutex>
#include <memory>
#include <time.h>
#include <cstdio>

using namespace command;
namespace {
std::string requestId;
void mark(const char* phase){struct timespec now{};clock_gettime(CLOCK_BOOTTIME,&now);
    __android_log_print(ANDROID_LOG_INFO,"ZuiControlTiming","id=%s phase=%s ns=%lld",requestId.c_str(),phase,
        static_cast<long long>(now.tv_sec)*1000000000LL+now.tv_nsec);
    const char* alias=std::strcmp(phase,"after_root_receipt")==0?"T7":std::strcmp(phase,"after_terminal_ack")==0?"T8":nullptr;
    if(alias)__android_log_print(ANDROID_LOG_INFO,"ZuiControlTiming","id=%s phase=%s ns=%lld",requestId.c_str(),alias,
        static_cast<long long>(now.tv_sec)*1000000000LL+now.tv_nsec);
}
void checked(binder_status_t status){require(status==STATUS_OK,"Binder transport");}
std::string string(const AParcel* parcel){std::string out;
    checked(AParcel_readString(parcel,&out,[](void* object,int32_t size,char** data){
        if(size<1||size>int32_t(LIMIT+1))return false;
        try{auto& s=*static_cast<std::string*>(object);s.resize(size);*data=s.data();return true;}catch(...){return false;}
    }));require(!out.empty()&&out.back()==0,"Binder string terminator");out.pop_back();require(out.find('\0')==std::string::npos,"Binder embedded NUL");return out;
}
void string(AParcel* parcel,const std::string& s){require(s.size()<=LIMIT,"Binder string bound");checked(AParcel_writeString(parcel,s.data(),int32_t(s.size())));}
struct Parcel{AParcel* p=nullptr;~Parcel(){if(p)AParcel_delete(p);}};
struct Callback {
    std::unique_ptr<Projection> projection;
    std::mutex mutex;
    static void* create(void* p){return p;}
    static void destroy(void*){}
    static binder_status_t transact(AIBinder* binder,transaction_code_t code,const AParcel* in,AParcel* out){
        if(code!=1)return STATUS_UNKNOWN_TRANSACTION;
        if(AIBinder_getCallingUid()!=1000)return STATUS_PERMISSION_DENIED;
        try{
            auto action=string(in),argument=string(in);require(AParcel_getDataPosition(in)==AParcel_getDataSize(in),"callback trailing data");
            auto* self=static_cast<Callback*>(AIBinder_getUserData(binder));std::lock_guard<std::mutex> lock(self->mutex);
            if(!self->projection){self->projection=std::make_unique<Projection>("/data/vendor/zui_control/uperf");self->projection->point=mark;}
            auto result=action.rfind("settings_",0)==0?settingsProjection(action,argument):self->projection->call(action,argument);AStatus* status=AStatus_newOk();
            auto rc=AParcel_writeStatusHeader(out,status);AStatus_delete(status);checked(rc);string(out,result);return STATUS_OK;
        }catch(const std::exception& e){
            AStatus* status=AStatus_fromExceptionCodeWithMessage(EX_ILLEGAL_STATE,e.what());
            auto rc=AParcel_writeStatusHeader(out,status);AStatus_delete(status);return rc;
        }
    }
};
struct Binder {
    AIBinder* remote=nullptr;AIBinder* callback=nullptr;
    Callback owner;
    Binder(){
        auto check=reinterpret_cast<AIBinder*(*)(const char*)>(dlsym(RTLD_DEFAULT,"AServiceManager_checkService"));
        auto max=reinterpret_cast<bool(*)(uint32_t)>(dlsym(RTLD_DEFAULT,"ABinderProcess_setThreadPoolMaxThreadCount"));
        auto start=reinterpret_cast<void(*)()>(dlsym(RTLD_DEFAULT,"ABinderProcess_startThreadPool"));
        require(check&&max&&start&&max(1),"Binder platform ABI");start();remote=check("zui_control");require(remote,"policy service unavailable");
        static auto* service=AIBinder_Class_define("android.zui.IZuiControl",Callback::create,Callback::destroy,Callback::transact);
        require(AIBinder_associateClass(remote,service),"policy descriptor");
        static auto* projection=AIBinder_Class_define("com.zui.server.control.IPolicyProjection",Callback::create,Callback::destroy,Callback::transact);
        callback=AIBinder_new(projection,&owner);require(callback,"callback allocation");
    }
    std::string call(int code,const std::vector<std::string>& args,bool projection=false){
        Parcel in,out;checked(AIBinder_prepareTransaction(remote,&in.p));for(auto& arg:args)string(in.p,arg);
        if(projection)checked(AParcel_writeStrongBinder(in.p,callback));
        checked(AIBinder_transact(remote,code,&in.p,&out.p,0));AStatus* status=nullptr;checked(AParcel_readStatusHeader(out.p,&status));
        bool ok=AStatus_isOk(status);AStatus_delete(status);require(ok,"policy remote exception");
        auto result=string(out.p);require(AParcel_getDataPosition(out.p)==AParcel_getDataSize(out.p),"reply trailing data");return result;
    }
    // Process exit retires the callback and its Binder threads together.
};
int run(int argc,char** argv){
    if(argc==9&&std::string(argv[1])=="--transport"){
        require(geteuid()==0,"root transport arguments");Binder& b=*new Binder;
        auto result=b.call(1012,{argv[2],argv[3],argv[4],argv[5],argv[6],argv[7],argv[8]});
        require(std::fwrite(result.data(),1,result.size(),stdout)==result.size()&&std::fflush(stdout)==0,"transport output");return 0;
    }
    require(geteuid()==0&&argc==4,"root adapter arguments");requestId=argv[1];mark("T4");
    std::string hash=argv[2],kick=argv[3];require(id(requestId)&&digest(hash)&&id(kick),"kick metadata");
    auto parts=split(kick,'_');require(parts.size()==3&&!parts[0].empty()&&parts[0].front()=='u',"kick user envelope");
    std::string user=parts[0].substr(1),sequence=parts[1];
    // Process-owned: a lost Binder reply may leave an in-flight callback until exit.
    Binder& binder=*new Binder;Request request(binder.call(1012,{requestId,hash,sequence,user,kick,"read",""}));
    request.bind(user,sequence);request.authenticate(requestId,hash);mark("request_validated");
    if(request.command!="policy"){
        // Other commands keep their existing finite implementation and contracts.
        execl("/system/bin/sh","sh","/system/bin/zui_controld","--oneshot-request",argv[1],argv[2],sequence.c_str(),user.c_str(),kick.c_str(),static_cast<char*>(nullptr));
        throw std::runtime_error("legacy command exec");
    }
    Disk parent("/data/vendor/zui_control/zuicontrol"),disk(parent,"user-domain-v1");
    Receipts receipts{disk,[&](const std::string& ack){require(binder.call(1012,{requestId,hash,sequence,user,kick,"ack",ack})=="ok=1","ACK reply");}};
    receipts.point=mark;
    receipts.reconcile=[&](const Request& prior){require(!prior.user.empty(),"bound recovery required");return binder.call(1013,{prior.requestId,sha256(prior.text),prior.sequence,prior.user},true);};
    receipts.run(request,[&]{mark("T5");auto result=binder.call(1010,{requestId,hash,sequence,user},true);mark("T6");return result;});
    return 0;
}
}
int main(int argc,char** argv){
    int result=1;try{result=run(argc,argv);}catch(const std::exception& e){__android_log_print(ANDROID_LOG_ERROR,"ZuiControl","native command rejected: %s",e.what());}
    _exit(result); // Never leave Binder threads or a resident root process behind.
}
