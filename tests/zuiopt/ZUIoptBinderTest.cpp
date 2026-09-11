// Runs the actual production parser against typed Parcel-call fixtures.
// Token cursors verify field order/bounds, not device wire bytes or SELinux.
#include "../../native/zuiopt/ZUIopt_binder.h"
#include "../../native/zuiopt/ZUIopt_events.h"
#include <iostream>
#include <sys/eventfd.h>
struct Atom {char kind;int64_t number;std::string text;};
struct AParcel {std::vector<Atom> atoms;mutable size_t at=0;bool ok=true;};
struct AStatus {bool ok;};
struct AIBinder {void* userdata;};
uid_t caller=1000;
extern "C" {
uid_t AIBinder_getCallingUid(){return caller;}
void* AIBinder_getUserData(AIBinder* b){return b->userdata;}
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
struct RuntimeFixture {
    bool sceneForeground(const ProcessState&,bool am)const{return am;}
    Config config=parseConfig("schema 2\nenabled true\nprofile G 2-6\npackage exact org.example.game G 100\n",255);
    std::map<int,ProcessState> states;
    Snapshot app{"org.example.game",42,10001,4,100,2,true,{"org.example.game"}};
    std::vector<std::string> authority={"org.example.game"};
    uint64_t generation=10;int user=10001,error=0;
    std::deque<uint64_t> generations;std::deque<int> users;
    bool absent=false;int procReads=0,packageQueries=0,snapshotQueries=0,placements=0,blockers=0;
    std::vector<uint64_t> released;
    Identity procIdentity(int){procReads++;if(error)throw ProcError(error,"proc identity open failed");auto value=generation;if(!generations.empty()){value=generations.front();generations.pop_front();}return {value,0,'S'};}
    int procUid(int){procReads++;if(users.empty())return user;auto value=users.front();users.pop_front();return value;}
    std::string procName(int){procReads++;return "org.example.game";}
    std::vector<std::string> packageAuthority(int){packageQueries++;return authority;}
    std::vector<Snapshot> activitySnapshot(){snapshotQueries++;return absent?std::vector<Snapshot>{}:std::vector<Snapshot>{app};}
    void release(ProcessState& p){released.push_back(p.generation);p.ownership=Ownership::ANDROID_OWNED;}
    void activate(ProcessState& p){if(!p.activity_foreground){if(p.managed())release(p);}else if(!p.managed()){p.ownership=Ownership::ZUIOPT_OWNED;placements++;}}
    ProcessState* resolve(const Snapshot& s){
        Identity id;try{id=validateManagedSnapshot(s,config,*this);}
        catch(const ProcError& e){if(!e.permission())throw;blockers++;return nullptr;}
        if(!id.start)return nullptr;
        auto it=states.find(s.pid);if(it!=states.end()&&(it->second.generation!=id.start||it->second.uid!=s.uid)){release(it->second);states.erase(it);}
        auto& p=states[s.pid];p.pid=s.pid;p.uid=s.uid;p.generation=id.start;p.name=s.name;p.package=s.packages[0];return &p;
    }
    void edge(int code=1,int value=1,int eventUid=10001){processEvent({code,42,eventUid,value,1},states,*this);}
};
void eventTests(){
    RuntimeFixture runtime;runtime.error=EACCES;
    RawEvents events;int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);require(fd>=0,"fixture eventfd");
    Observer::Holder holder=std::make_shared<CallbackState>([&](int c,int p,int u,int v){events.push(fd,c,p,u,v);});
    AIBinder binder{&holder};
    auto send=[&](int code,int value){AParcel args{{integer(42),integer(10001)},0,true};if(code!=3)args.atoms.push_back(integer(value));return Observer::transact(&binder,code,&args,nullptr);};
    for(int value:{1,0,1})require(send(1,value)==STATUS_OK,"proc unavailable cannot fail raw callback");
    require(runtime.procReads==0&&runtime.packageQueries==0&&runtime.snapshotQueries==0&&runtime.placements==0,"callback observation boundary");
    auto pending=events.take();require(pending.size()==3&&pending[0].sequence==1&&pending[1].sequence==2&&pending[2].sequence==3,"FIFO event sequences");
    for(const auto& e:pending)processEvent(e,runtime.states,runtime);
    require(runtime.blockers==3&&runtime.states.empty()&&runtime.packageQueries==0,"permission observed in reactor, not STATUS_NO_MEMORY");
    puts("BINDER_CALLBACK_PROC_READS=0;BINDER_CALLBACK_PACKAGE_QUERY=0;BINDER_CALLBACK_PLACEMENT=0");
    caller=2000;require(send(1,1)==STATUS_PERMISSION_DENIED,"callback authority");caller=1000;
    require(send(1,2)==STATUS_BAD_VALUE&&send(4,0)==STATUS_UNKNOWN_TRANSACTION,"callback parameter ABI");
    holder->handler=[](int,int,int,int){throw std::bad_alloc();};require(send(1,1)==STATUS_NO_MEMORY,"real allocation error");
    holder->handler=[](int,int,int,int){throw ProcError(EACCES,"proc identity open failed");};require(send(1,1)==STATUS_FAILED_TRANSACTION,"nonallocation never masquerades as memory error");
    holder->handler=[&](int c,int p,int u,int v){events.push(-1,c,p,u,v);};require(send(1,1)==STATUS_FAILED_TRANSACTION,"eventfd infrastructure error");
    holder->stopAccepting();holder->drain();require(send(1,1)==STATUS_OK,"shutdown callback safely ignored");close(fd);

    for(int user:{0,1000,2000,9999,20000,110001}){RuntimeFixture r;r.edge(1,1,user);require(r.procReads==0&&r.snapshotQueries==0,"unknown ordinary UID cheap filter");}
    for(auto packages:std::vector<std::vector<std::string>>{{},{"org.example.game","org.other.app"},{"org.other.app"},{"invalid"}}){
        RuntimeFixture r;r.app.packages=packages;r.edge();require(r.procReads==0&&r.packageQueries==0&&r.states.empty(),"unmanaged snapshot zero proc/package query");
    }
    {RuntimeFixture r;r.absent=true;r.edge();require(r.states.empty()&&r.procReads==0,"missing snapshot drop");}
    {RuntimeFixture r;r.authority.clear();r.edge();require(r.states.empty(),"package authority failure drop");}
    {RuntimeFixture r;r.generation=0;r.edge();require(r.states.empty()&&r.packageQueries==0,"disappeared process drop");}
    {RuntimeFixture r;r.generations={10,11};r.edge();require(r.states.empty(),"PID reused during acquisition");}
    {RuntimeFixture r;r.users={10001,10002};r.edge();require(r.states.empty(),"UID changed during acquisition");}
    {RuntimeFixture r;r.app.state=19;r.app.flags=0;r.edge();require(!r.states.at(42).activity_foreground&&r.placements==0,"new state uses current snapshot not stale true");}
    {RuntimeFixture r;r.edge();require(r.states.at(42).generation==10&&r.placements==1,"managed acquisition");
        r.edge(1,0);require(r.states.at(42).activity_foreground,"known state rejects stale false through current snapshot");
        r.app.state=19;r.app.flags=0;r.edge(1,1);require(!r.states.at(42).managed(),"known state stale true does not retain foreground");}
    {RuntimeFixture r;r.edge();r.generation=20;r.edge();require(r.released==std::vector<uint64_t>{10}&&r.states.at(42).generation==20,"release old generation before new acquisition");}
    {RuntimeFixture r;r.edge();r.user=10002;r.app.uid=10002;r.edge();require(r.states.empty()&&r.released==std::vector<uint64_t>{10},"UID mismatch releases stale state, old callback cannot acquire");}
    {RuntimeFixture r;r.edge(3);require(r.procReads==0&&r.snapshotQueries==0,"unknown death zero observation");}
    {RuntimeFixture r;r.edge();r.edge(3);require(r.states.size()==1&&r.released.empty(),"stale death cannot remove live generation");
        r.generation=20;r.edge(3);require(r.states.empty()&&r.released==std::vector<uint64_t>{10},"death releases only stale state");}
    {RuntimeFixture r;r.edge();r.generation=0;r.edge(3);require(r.states.empty(),"actual death releases state");}
    {RuntimeFixture r;r.edge();r.absent=true;r.edge();require(r.states.empty(),"missing current authority releases known state");}
    for(int error:{EACCES,EPERM}){RuntimeFixture r;r.error=error;r.edge();require(r.blockers==1&&r.states.empty(),"stable managed permission is blocker, not race");}
    require(read("/proc/2147483647/stat",true).empty(),"real absent proc is nonfatal");
    puts("PID_REUSE_STALE_CALLBACK_PROCESS_DEATH_MANAGED_ACQUISITION=PASS");
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
    eventTests();
    std::cout<<"ZUIOPT_BINDER_ABI_FIXTURES=PASS;DEVICE_WIRE_PERMISSION_CLAIM=NO\n";return 0;
}catch(const std::exception& e){std::cerr<<"BINDER_FIXTURE_FAIL "<<e.what()<<'\n';return 1;}}
