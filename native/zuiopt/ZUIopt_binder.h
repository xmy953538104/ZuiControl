// Scoped ZUIopt native Binder adapter. Parcel ABI is frozen to actual ZUI 072.
#pragma once
#include "ZUIopt_model.h"
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <dlfcn.h>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>
#include <cstdint>
#include <condition_variable>
#include <memory>
#include <mutex>

namespace ZUIopt {
inline void checked(binder_status_t s){if(s!=STATUS_OK)throw std::runtime_error("parcel/status="+std::to_string(s));}
struct Parcel {
    AParcel* p=nullptr;
    ~Parcel(){if(p)AParcel_delete(p);}
    int32_t integer(){int32_t n;checked(AParcel_readInt32(p,&n));return n;}
    int64_t wide(){int64_t n;checked(AParcel_readInt64(p,&n));return n;}
    std::string str(){
        std::string s;
        checked(AParcel_readString(p,&s,[](void* x,int32_t n,char** out){
            if(n<0){if(out)*out=nullptr;return true;}
            if(n>65536)return false;
            try {auto& s=*static_cast<std::string*>(x);s.resize(n);*out=s.data();return true;}
            catch(...) {return false;}
        }));
        if(!s.empty()&&s.back()==0)s.pop_back();return s;
    }
    void status(){AStatus* s=nullptr;checked(AParcel_readStatusHeader(p,&s));bool ok=AStatus_isOk(s);AStatus_delete(s);require(ok,"remote exception");}
    std::vector<std::string> strings(){int n=integer();require(n>=-1&&n<=1024,"array length");std::vector<std::string> v;for(int i=0;i<n;i++)v.push_back(str());return v;}
};
// Binder owns a shared state, not a pointer into the Observer object.
struct CallbackState {
    std::mutex mutex;std::condition_variable drained;
    bool accepting=true;unsigned inflight=0;
    std::function<void(int,int,int,int)> handler;
    explicit CallbackState(std::function<void(int,int,int,int)> f):handler(std::move(f)){}
    void deliver(int code,int pid,int user,int value){
        {std::lock_guard<std::mutex> lock(mutex);if(!accepting)return;inflight++;}
        try {handler(code,pid,user,value);}
        catch(...) {finish();throw;}
        finish();
    }
    void finish(){std::lock_guard<std::mutex> lock(mutex);if(--inflight==0)drained.notify_all();}
    void stopAccepting(){std::lock_guard<std::mutex> lock(mutex);accepting=false;}
    void drain(){std::unique_lock<std::mutex> lock(mutex);drained.wait(lock,[this]{return inflight==0;});handler={};}
};
struct Observer {
    using Holder=std::shared_ptr<CallbackState>;
    AIBinder* manager=nullptr;AIBinder* packages=nullptr;AIBinder* callback=nullptr;AIBinder_DeathRecipient* death=nullptr;
    Holder state;Holder* deathCookie=nullptr;
    bool registered=false,linked=false;
    static void* create(void* p){return new(std::nothrow) Holder(*static_cast<Holder*>(p));}
    static void destroy(void* p){delete static_cast<Holder*>(p);}
    static binder_status_t transact(AIBinder* b,transaction_code_t code,const AParcel* in,AParcel*){
        if(code<1||code>3)return STATUS_UNKNOWN_TRANSACTION;
        if(AIBinder_getCallingUid()!=1000)return STATUS_PERMISSION_DENIED;
        int32_t pid=0,user=0,value=0;
        if(AParcel_readInt32(in,&pid)||AParcel_readInt32(in,&user))return STATUS_BAD_VALUE;
        if(code!=3&&AParcel_readInt32(in,&value))return STATUS_BAD_VALUE;
        if(pid<=0||user<0||(code==1&&value!=0&&value!=1)||AParcel_getDataPosition(in)!=AParcel_getDataSize(in))return STATUS_BAD_VALUE;
        try {auto state=*static_cast<Holder*>(AIBinder_getUserData(b));state->deliver(code,pid,user,value);}
        catch(...) {return STATUS_NO_MEMORY;}
        return STATUS_OK;
    }
    explicit Observer(std::function<void(int,int,int,int)> fn):state(std::make_shared<CallbackState>(std::move(fn))){
        try {
            auto check=reinterpret_cast<AIBinder*(*)(const char*)>(dlsym(RTLD_DEFAULT,"AServiceManager_checkService"));
            auto max=reinterpret_cast<bool(*)(uint32_t)>(dlsym(RTLD_DEFAULT,"ABinderProcess_setThreadPoolMaxThreadCount"));
            auto start=reinterpret_cast<void(*)()>(dlsym(RTLD_DEFAULT,"ABinderProcess_startThreadPool"));
            require(check&&max&&start,"ABI_GATE platform Binder exports");
            require(max(1),"binder pool config");start();
            manager=check("activity");require(manager,"ABI_GATE activity service absent");
            static auto* mc=AIBinder_Class_define("android.app.IActivityManager",create,destroy,transact);
            require(AIBinder_associateClass(manager,mc),"ABI_GATE IActivityManager descriptor");
            packages=check("package");require(packages,"ABI_GATE package service absent");
            static auto* pc=AIBinder_Class_define("android.content.pm.IPackageManager",create,destroy,transact);
            require(AIBinder_associateClass(packages,pc),"ABI_GATE IPackageManager descriptor");
            static auto* oc=AIBinder_Class_define("android.app.IProcessObserver",create,destroy,transact);
            callback=AIBinder_new(oc,&state);require(callback&&AIBinder_getUserData(callback),"observer allocation");
            death=AIBinder_DeathRecipient_new([](void* p){
                try {auto s=*static_cast<Holder*>(p);s->deliver(0,0,0,0);}catch(...){}
            });
            require(death,"death recipient allocation");
            AIBinder_DeathRecipient_setOnUnlinked(death,[](void* p){delete static_cast<Holder*>(p);});
            deathCookie=new Holder(state);
            checked(AIBinder_linkToDeath(manager,death,deathCookie));linked=true;
            // The remote may accept registration even if its reply is malformed.
            registered=true;registration(120);
        }catch(...){shutdown();throw;}
    }
    void registration(int code){
        Parcel in,out;checked(AIBinder_prepareTransaction(manager,&in.p));
        checked(AParcel_writeStrongBinder(in.p,callback));
        checked(AIBinder_transact(manager,code,&in.p,&out.p,0));out.status();
        require(AParcel_getDataPosition(out.p)==AParcel_getDataSize(out.p),"ABI_GATE registration trailing fields");
    }
    std::vector<std::string> packagesForUid(int user){
        Parcel in,out;checked(AIBinder_prepareTransaction(packages,&in.p));
        checked(AParcel_writeInt32(in.p,user));
        checked(AIBinder_transact(packages,20,&in.p,&out.p,0));out.status();
        auto result=out.strings();require(AParcel_getDataPosition(out.p)==AParcel_getDataSize(out.p),"ABI_GATE UID package trailing fields");return result;
    }
    std::vector<Snapshot> snapshot(){
        Parcel in,out;checked(AIBinder_prepareTransaction(manager,&in.p));
        checked(AIBinder_transact(manager,86,&in.p,&out.p,0));out.status();
        return parseSnapshot(out);
    }
    static std::vector<Snapshot> parseSnapshot(Parcel& out){
        int n=out.integer();require(n>=0&&n<=4096,"ABI_GATE snapshot list bound");
        std::vector<Snapshot> result;
        for(int i=0;i<n;i++){
            require(out.integer()==1,"ABI_GATE null process");Snapshot r;
            r.name=out.str();r.pid=out.integer();r.uid=out.integer();r.packages=out.strings();out.strings();
            r.flags=out.integer();out.integer();r.importance=out.integer();out.integer();out.integer();out.integer();
            if(!out.str().empty())out.str();
            out.integer();r.state=out.integer();r.focused=out.integer()!=0;out.wide();
            require(r.pid>0&&r.uid>=0&&!r.name.empty()&&r.name.size()<256&&r.state>=0&&r.state<=30,"ABI_GATE process sanity");result.push_back(r);
        }
        require(AParcel_getDataPosition(out.p)==AParcel_getDataSize(out.p),"ABI_GATE trailing fields");return result;
    }
    void shutdown() noexcept {
        if(!state)return;
        state->stopAccepting();
        if(registered){try{registration(121);}catch(...){}registered=false;}
        if(linked&&manager&&death){AIBinder_unlinkToDeath(manager,death,deathCookie);linked=false;}
        // onUnlinked owns the cookie even when unlink/delete returns before notification.
        if(death){AIBinder_DeathRecipient_delete(death);death=nullptr;}
        state->drain();
        if(callback){AIBinder_decStrong(callback);callback=nullptr;}
        if(manager){AIBinder_decStrong(manager);manager=nullptr;}
        if(packages){AIBinder_decStrong(packages);packages=nullptr;}
        state.reset();
    }
    ~Observer(){shutdown();}
};
}
