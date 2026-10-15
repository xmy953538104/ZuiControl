// Finite read-only physical-port fixture. No thermal, charger or gauge writes.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <thread>
#include <time.h>
#include <vector>

static long long bootNs() {
    timespec t{};
    if (clock_gettime(CLOCK_BOOTTIME, &t)) std::abort();
    return static_cast<long long>(t.tv_sec) * 1000000000LL + t.tv_nsec;
}
static std::string value(const std::string &path) {
    std::ifstream in(path);
    std::string text;
    if (!std::getline(in, text) || text.size() > 256) return "null";
    std::string quoted="\"";
    for (unsigned char c:text) {
        if (c=='\"' || c=='\\') quoted+='\\';
        if (c<32 || c>126) return "null";
        quoted+=static_cast<char>(c);
    }
    return quoted+'\"';
}
int main(int argc,char **argv) {
    if (argc!=2) return 2;
    char *end=nullptr;
    long seconds=std::strtol(argv[1],&end,10);
    if (!end || *end || seconds<1 || seconds>240) return 2;
    std::vector<std::pair<std::string,std::string>> fields;
    auto add=[&](const std::string &supply,const std::string &keys) {
        size_t begin=0;
        while (begin<keys.size()) {
            size_t next=keys.find(' ',begin);
            std::string key=keys.substr(begin,next==std::string::npos?next:next-begin);
            fields.emplace_back(supply+"."+key,"/sys/class/power_supply/"+supply+"/"+key);
            if (next==std::string::npos) break;
            begin=next+1;
        }
    };
    add("battery","status capacity voltage_now current_now charge_counter temp charge_control_limit soh");
    add("usb","online voltage_now current_now real_type input_current_limit");
    add("wireless","online voltage_now current_now input_current_limit");
    for (int i=1;i<=2;i++) add("ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi"+std::to_string(i),"online voltage_now usb_type");
    for (const auto &key:{"fg1_current","fg2_current","fg1_voltage","fg2_voltage"})
        fields.emplace_back(std::string("gauge.")+key,std::string("/sys/class/qcom-battery/")+key);
    for (const auto &key:{"pm8550b_iin_fb","pm8550b_ichg_fb"})
        fields.emplace_back(std::string("adc.")+key,std::string("/sys/bus/iio/devices/iio:device0/in_current_")+key+"_input");
    const long long start=bootNs(),deadline=start+seconds*1000000000LL;
    unsigned rows=0;
    do {
        long long before=bootNs();
        std::string row="{\"read_start_boot_ns\":"+std::to_string(before);
        for (const auto &field:fields) {
            long long started=bootNs();
            row+=",\""+field.first+"\":"+value(field.second);
            if (field.first=="battery.current_now" || field.first.rfind("gauge.",0)==0)
                row+=",\""+field.first+".read_start_ns\":"+std::to_string(started)+",\""+field.first+".read_end_ns\":"+std::to_string(bootNs());
        }
        row+=",\"read_end_boot_ns\":"+std::to_string(bootNs())+"}";
        if (std::printf("%s\n",row.c_str())<0 || std::fflush(stdout)) return 3;
        rows++;
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    } while (bootNs()<deadline);
    std::fprintf(stderr,"COMPLETE rows=%u elapsed_ms=%lld writes_to_power_nodes=0\n",rows,(bootNs()-start)/1000000);
    return 0;
}
