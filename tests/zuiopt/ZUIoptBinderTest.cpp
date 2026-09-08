// Runs the actual production parser against typed Parcel-call fixtures.
// Token cursors verify field order/bounds, not device wire bytes or SELinux.
#include "../../native/zuiopt/ZUIopt_binder.h"
#include <iostream>
struct Atom {char kind;int64_t number;std::string text;};
struct AParcel {std::vector<Atom> atoms;mutable size_t at=0;bool ok=true;};
struct AStatus {bool ok;};
extern "C" {
void AParcel_delete(AParcel* p){delete p;}
binder_status_t AParcel_readInt32(const AParcel* p,int32_t* out){
    if(p->at>=p->atoms.size()||p->atoms[p->at].kind!='i')return STATUS_BAD_VALUE;
    *out=static_cast<int32_t>(p->atoms[p->at++].number);return STATUS_OK;
}
binder_status_t AParcel_readInt64(const AParcel* p,int64_t* out){
    if(p->at>=p->atoms.size()||p->atoms[p->at].kind!='w')return STATUS_BAD_VALUE;
    *out=p->atoms[p->at++].number;return STATUS_OK;
}
binder_status_t AParcel_readString(const AParcel* p,void* context,bool(*allocate)(void*,int32_t,char**)){
    if(p->at>=p->atoms.size()||p->atoms[p->at].kind!='s')return STATUS_BAD_VALUE;
    const auto& value=p->atoms[p->at++].text;char* out=nullptr;
    if(!allocate(context,static_cast<int32_t>(value.size()+1),&out))return STATUS_NO_MEMORY;
    memcpy(out,value.c_str(),value.size()+1);return STATUS_OK;
}
binder_status_t AParcel_readStatusHeader(const AParcel* p,AStatus** out){*out=new AStatus{p->ok};return STATUS_OK;}
bool AStatus_isOk(const AStatus* p){return p->ok;}
void AStatus_delete(AStatus* p){delete p;}
int32_t AParcel_getDataPosition(const AParcel* p){return static_cast<int32_t>(p->at);}
int32_t AParcel_getDataSize(const AParcel* p){return static_cast<int32_t>(p->atoms.size());}
}
using namespace ZUIopt;
Atom integer(int value){return {'i',value,{}};}
Atom string(std::string value){return {'s',0,std::move(value)};}
std::vector<Atom> valid(){return {
    integer(1),integer(1),string("org.example.game"),integer(42),integer(10001),
    integer(1),string("org.example.game"),integer(0),
    integer(4),integer(0),integer(100),integer(0),integer(0),integer(0),
    string(""),integer(0),integer(2),integer(1),{'w',123,{}}
};}
std::vector<Snapshot> parse(std::vector<Atom> atoms,bool ok=true){Parcel p;p.p=new AParcel{std::move(atoms),0,ok};p.status();return Observer::parseSnapshot(p);}
void rejects(std::vector<Atom> atoms,const std::string& reason,bool ok=true){
    bool rejected=false;try{parse(std::move(atoms),ok);}catch(const std::exception& e){require(e.what()==reason,"unexpected ABI rejection reason");rejected=true;}
    require(rejected,"malformed Parcel accepted");
}
int main(){try{
    auto result=parse(valid());require(result.size()==1,"snapshot size");const auto& s=result[0];
    require(s.pid==42&&s.uid==10001&&s.name=="org.example.game"&&s.packages==std::vector<std::string>{"org.example.game"}&&s.flags==4&&s.importance==100&&s.state==2&&s.focused,"frozen snapshot field order");
    require(parse({integer(0)}).empty(),"empty list");
    auto component=valid();component[14]=string("org.example.other");component.insert(component.begin()+15,string("Component"));require(parse(component)[0].state==2,"optional component field order");
    for(int n:{-1,4097}){auto v=valid();v[0]=integer(n);rejects(v,"ABI_GATE snapshot list bound");}
    auto v=valid();v[1]=integer(0);rejects(v,"ABI_GATE null process");
    for(auto change:std::vector<std::pair<size_t,Atom>>{{2,string("")},{2,string(std::string(256,'x'))},{3,integer(0)},{4,integer(-1)},{16,integer(31)}}){v=valid();v[change.first]=change.second;rejects(v,"ABI_GATE process sanity");}
    v=valid();v[5]=integer(1025);rejects(v,"array length");
    v=valid();v.pop_back();rejects(v,"parcel/status=-22");
    v=valid();v.push_back(integer(1));rejects(v,"ABI_GATE trailing fields");
    v=valid();v[2]=string(std::string(65537,'x'));rejects(v,"parcel/status=-12");
    rejects(valid(),"remote exception",false);
    bool rejected=false;try{checked(-13);}catch(const std::exception& e){rejected=fatalReason(e)=="binder_status_-13";}require(rejected,"Binder status survives diagnostic mapping");
    std::cout<<"ZUIOPT_BINDER_ABI_FIXTURES=PASS;DEVICE_WIRE_PERMISSION_CLAIM=NO\n";return 0;
}catch(const std::exception& e){std::cerr<<"BINDER_FIXTURE_FAIL "<<e.what()<<'\n';return 1;}}
