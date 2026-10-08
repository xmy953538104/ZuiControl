// Private Binder notification channel; no property waiter or polling thread.
#pragma once
#include "ZUIopt_binder.h"

namespace ZUIopt {
struct SceneObserver {
    static constexpr int REGISTER=1001,UNREGISTER=1002,ACK=1003,CURRENT=1004;
    Observer::Holder state;
    AIBinder* service=nullptr;AIBinder* callback=nullptr;bool registered=false;
    static binder_status_t transact(AIBinder* binder,transaction_code_t code,const AParcel* in,AParcel* out){
        if(code!=1&&code!=2)return STATUS_UNKNOWN_TRANSACTION;
        if(AIBinder_getCallingUid()!=1000)return STATUS_PERMISSION_DENIED;
        if(code==2){
            try{
                auto* holder=static_cast<Observer::Holder*>(AIBinder_getUserData(binder));if(!holder||!*holder)return STATUS_BAD_VALUE;
                auto keep=*holder;if(!keep->supportsRules())return STATUS_UNKNOWN_TRANSACTION;
                if(!out)return STATUS_BAD_VALUE;
                std::string argument;
                checked(AParcel_readString(in,&argument,[](void* p,int32_t size,char** data){
                    if(size<1||size>129)return false;
                    try{auto& s=*static_cast<std::string*>(p);s.resize(size);*data=s.data();return true;}catch(...){return false;}
                }));
                require(!argument.empty()&&argument.back()==0,"rules read terminator");argument.pop_back();
                require(argument.find('\0')==std::string::npos&&AParcel_getDataPosition(in)==AParcel_getDataSize(in),"rules read trailing fields");
                require(argument=="state"||(argument.size()==34&&argument.rfind("snapshot|g",0)==0&&
                    argument.substr(10).find_first_not_of("0123456789abcdef")==std::string::npos),"rules read argument");
                auto result=keep->readRules(argument);require(result.size()<=200000,"rules reply bound");
                return AParcel_writeString(out,result.data(),static_cast<int32_t>(result.size()));
            }catch(const BinderError& e){return e.status;}
            catch(const std::bad_alloc&){return STATUS_NO_MEMORY;}
            catch(...){return STATUS_FAILED_TRANSACTION;}
        }
        int64_t seq=-1;
        if(AParcel_readInt64(in,&seq)||seq<0||AParcel_getDataPosition(in)!=AParcel_getDataSize(in))return STATUS_BAD_VALUE;
        try{auto* holder=static_cast<Observer::Holder*>(AIBinder_getUserData(binder));if(!holder||!*holder)return STATUS_BAD_VALUE;auto keep=*holder;keep->deliverScene(seq);}
        catch(const std::bad_alloc&){return STATUS_NO_MEMORY;}
        catch(...){return STATUS_FAILED_TRANSACTION;}
        return STATUS_OK;
    }
    explicit SceneObserver(std::function<void(int64_t)> fn,std::function<std::string(const std::string&)> read={}):state(std::make_shared<CallbackState>(std::move(fn),std::move(read))){
        try{
            auto check=reinterpret_cast<AIBinder*(*)(const char*)>(dlsym(RTLD_DEFAULT,"AServiceManager_checkService"));
            require(check,"scene service API");service=check("zui_control");require(service,"scene service absent");
            static auto* sc=AIBinder_Class_define("android.zui.IZuiControl",Observer::create,Observer::destroy,transact);
            require(AIBinder_associateClass(service,sc),"scene service descriptor");
            static auto* cc=AIBinder_Class_define("com.zui.server.control.IZuioptSceneCallback",Observer::create,Observer::destroy,transact);
            callback=AIBinder_new(cc,&state);require(callback&&AIBinder_getUserData(callback),"scene callback allocation");
            registered=true;request(REGISTER); // Mark before RPC: malformed reply still requires unregister.
        }catch(...){shutdown();throw;}
    }
    void request(int code,int64_t seq=-1){
        Parcel in,out;checked(AIBinder_prepareTransaction(service,&in.p));
        checked(AParcel_writeStrongBinder(in.p,callback));
        if(code==ACK)checked(AParcel_writeInt64(in.p,seq));
        checked(AIBinder_transact(service,code,&in.p,&out.p,0));out.status();
        require(AParcel_getDataPosition(out.p)==AParcel_getDataSize(out.p),"scene reply trailing fields");
    }
    void ack(int64_t seq){request(ACK,seq);}
    SceneAuthority current(){
        Parcel in,out;checked(AIBinder_prepareTransaction(service,&in.p));
        checked(AParcel_writeStrongBinder(in.p,callback));
        checked(AIBinder_transact(service,CURRENT,&in.p,&out.p,0));out.status();
        SceneAuthority result;result.sequence=out.wide();result.user=out.integer();
        int valid=out.integer();result.package=out.str();result.valid=valid==1;
        require(result.sequence>=0&&result.user>=0&&(valid==0||valid==1)&&
                result.valid==!result.package.empty()&&result.package.size()<=255&&
                AParcel_getDataPosition(out.p)==AParcel_getDataSize(out.p),"scene authority reply");
        return result;
    }
    void shutdown()noexcept{
        if(!state)return;
        state->stopAccepting();
        if(registered){try{request(UNREGISTER);}catch(...){}registered=false;}
        state->drain();
        if(callback){AIBinder_decStrong(callback);callback=nullptr;}
        if(service){AIBinder_decStrong(service);service=nullptr;}
        state.reset();
    }
    // The primary Observer links activity-service death in the same system_server;
    // that event fails Core closed. Init restarts, and registration replays latest.
    ~SceneObserver(){shutdown();}
};
}
