// Value types shared by the accepted engine and the bounded rule manager.
#pragma once
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>
namespace ZUIopt {
inline void require(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
struct Snapshot {
    std::string name;int pid=0,uid=0,flags=0,importance=0,state=0;bool focused=false;
    std::vector<std::string> packages;
};
}
