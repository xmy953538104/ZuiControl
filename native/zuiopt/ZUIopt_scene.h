// Private Binder notification channel; no property waiter or polling thread.
#pragma once
#include "ZUIopt_binder.h"

namespace ZUIopt {
struct SceneObserver {
    static constexpr int REGISTER=1001,UNREGISTER=1002,ACK=1003;
    Observer::Holder state;
    AIBinder* service=nullptr;AIBinder* callback=nullptr;bool registered=false;
    static binder_status_t transact(AIBinder* binder,transaction_code_t code,const AParcel* in,AParcel*){
        if(code!=1)return STATUS_UNKNOWN_TRANSACTION;
        if(AIBinder_getCallingUid()!=1000)return STATUS_PERMISSION_DENIED;
        int64_t seq=-1;
        if(AParcel_readInt64(in,&seq)||seq<0||AParcel_getDataPosition(in)!=AParcel_getDataSize(in))return STATUS_BAD_VALUE;
        try{auto* holder=static_cast<Observer::Holder*>(AIBinder_getUserData(binder));if(!holder||!*holder)return STATUS_BAD_VALUE;auto keep=*holder;keep->deliverScene(seq);}
        catch(const std::bad_alloc&){return STATUS_NO_MEMORY;}
        catch(...){return STATUS_FAILED_TRANSACTION;}
        return STATUS_OK;
    }
    explicit SceneObserver(std::function<void(int64_t)> fn):state(std::make_shared<CallbackState>(std::move(fn))){
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
