// Finite MAC policy queries, not a production-domain read or a permission change.
#include <cstdio>
#include <cerrno>
#include <dlfcn.h>
#include <initializer_list>

int main() {
    void *library=dlopen("/system/lib64/libselinux.so",RTLD_NOW);
    if(!library)return 2;
    auto context=reinterpret_cast<int (*)(char**)>(dlsym(library,"getcon"));
    auto label=reinterpret_cast<int (*)(const char*,char**)>(dlsym(library,"getfilecon"));
    auto linkLabel=reinterpret_cast<int (*)(const char*,char**)>(dlsym(library,"lgetfilecon"));
    auto release=reinterpret_cast<void (*)(char*)>(dlsym(library,"freecon"));
    auto access=reinterpret_cast<int (*)(const char*,const char*,const char*,const char*,void*)>(dlsym(library,"selinux_check_access"));
    if(!context || !label || !linkLabel || !release || !access)return 3;
    char *domain=nullptr;
    if(context(&domain)<0)return 4;
    std::printf("EXECUTION_DOMAIN=%s\nQUERY_DOMAIN=u:r:system_server:s0\n",domain);release(domain);
    int positive=access("u:r:system_server:s0","u:object_r:system_file:s0","file","read",nullptr);
    std::printf("POSITIVE_SYSTEM_FILE_READ_ALLOWED=%d\n",positive==0);
    for(const char *permission:{"create","bind","read","setopt"}) {
        errno=0;int rc=access("u:r:system_server:s0","u:r:system_server:s0","netlink_kobject_uevent_socket",permission,nullptr);
        std::printf("UEVENT_PERMISSION=%s ALLOWED=%d ERRNO=%d\n",permission,rc==0,errno);
    }
    unsigned denied=0,unreadable=0,checked=0;
    for(const char *path:{"/sys/class/power_supply","/sys/class/power_supply/battery","/sys/class/power_supply/usb","/sys/class/power_supply/wireless"}) {
        char *target=nullptr;
        if(label(path,&target)<0)return 6;
        errno=0;int rc=access("u:r:system_server:s0",target,"dir","search",nullptr);
        std::printf("DIRECTORY=%s LABEL=%s SEARCH_ALLOWED=%d ERRNO=%d\n",path,target,rc==0,errno);release(target);
        if(linkLabel(path,&target)<0)return 6;
        errno=0;rc=access("u:r:system_server:s0",target,"lnk_file","read",nullptr);
        std::printf("CLASS_LINK=%s LABEL=%s READ_ALLOWED=%d ERRNO=%d\n",path,target,rc==0,errno);release(target);
    }
    for(const char *path:{"/sys/class/power_supply/battery/charge_full","/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/cycle_count","/sys/class/power_supply/battery/soh",
            "/sys/class/power_supply/usb/real_type","/sys/class/power_supply/usb/voltage_now","/sys/class/power_supply/usb/current_now",
            "/sys/class/power_supply/wireless/voltage_now","/sys/class/power_supply/wireless/current_now",
            "/sys/class/power_supply/ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi1/online",
            "/sys/class/power_supply/ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi2/online"}) {
        char *target=nullptr;
        if(label(path,&target)<0){std::printf("LABEL_UNAVAILABLE=%s\n",path);unreadable++;continue;}
        for(const char *permission:{"open","read","getattr"}) {
            errno=0;int rc=access("u:r:system_server:s0",target,"file",permission,nullptr),error=errno;
            std::printf("FILE=%s LABEL=%s PERMISSION=%s ALLOWED=%d ERRNO=%d\n",path,target,permission,rc==0,error);
            checked++;if(rc){if(error==EACCES)denied++;else unreadable++;}
        }
        release(target);
    }
    errno=0;int negative=access("u:r:system_server:s0","u:object_r:kernel:s0","security","setenforce",nullptr);
    bool negativeDenied=negative!=0 && errno==EACCES;
    std::printf("NEGATIVE_SETENFORCE_QUERY_DENIED=%d\nCHECKED=%u DENIED=%u UNREADABLE=%u POLICY_WRITES=0 POWER_WRITES=0\n",negativeDenied,checked,denied,unreadable);
    dlclose(library);
    return positive!=0 || !negativeDenied || !checked || unreadable?5:0;
}
