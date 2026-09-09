// Production raw callback/queue/burst/reconcile logic with synthetic authority.
#define main existing_binder_fixture_main
#include "ZUIoptBinderTest.cpp"
#undef main
#include "../../native/zuiopt/ZUIopt_scene.h"
#include "../../native/zuiopt/ZUIopt_owner.h"
#include <atomic>
#include <future>
#include <filesystem>
#include <poll.h>
#include <random>
#include <thread>
#include <sys/syscall.h>

struct AIBinder_Class {
    std::string descriptor;void*(*create)(void*);void(*destroy)(void*);
    binder_status_t(*callback)(AIBinder*,transaction_code_t,const AParcel*,AParcel*);
};
namespace SceneWire {
AIBinder service{nullptr};std::map<AIBinder*,AIBinder_Class*> peers;
std::vector<int> requests;int64_t seq=81,ack=-1;bool absent=false,malformed=false;
}
extern "C" {
AIBinder* AServiceManager_checkService(const char* name){require(std::string(name)=="zui_control","private service lookup");return SceneWire::absent?nullptr:&SceneWire::service;}
AIBinder_Class* AIBinder_Class_define(const char* desc,void*(*create)(void*),void(*destroy)(void*),binder_status_t(*callback)(AIBinder*,transaction_code_t,const AParcel*,AParcel*)){
    static std::vector<std::unique_ptr<AIBinder_Class>> classes;
    classes.push_back(std::make_unique<AIBinder_Class>(AIBinder_Class{desc,create,destroy,callback}));return classes.back().get();
}
bool AIBinder_associateClass(AIBinder* b,const AIBinder_Class* c){return b==&SceneWire::service&&c->descriptor=="android.zui.IZuiControl";}
AIBinder* AIBinder_new(const AIBinder_Class* c,void* data){
    require(c->descriptor=="com.zui.server.control.IZuioptSceneCallback","private callback descriptor");
    auto* b=new AIBinder{c->create(data)};SceneWire::peers[b]=const_cast<AIBinder_Class*>(c);return b;
}
void AIBinder_decStrong(AIBinder* b){
    if(b==&SceneWire::service)return;
    auto* c=SceneWire::peers.at(b);c->destroy(b->userdata);SceneWire::peers.erase(b);delete b;
}
binder_status_t AIBinder_prepareTransaction(AIBinder* b,AParcel** out){require(b==&SceneWire::service,"service transaction target");*out=new AParcel;return STATUS_OK;}
binder_status_t AParcel_writeStrongBinder(AParcel* p,AIBinder* b){p->atoms.push_back({'b',reinterpret_cast<int64_t>(b),{}});return STATUS_OK;}
binder_status_t AParcel_writeInt64(AParcel* p,int64_t seq){p->atoms.push_back({'w',seq,{}});return STATUS_OK;}
binder_status_t AIBinder_transact(AIBinder* b,transaction_code_t code,AParcel** input,AParcel** out,uint32_t flags){
    require(b==&SceneWire::service&&flags==0,"registration/ACK synchronous private requests");
    auto* p=*input;require(p->atoms.size()==(code==1003?2u:1u)&&p->atoms[0].kind=='b',"private argument layout");
    auto* cb=reinterpret_cast<AIBinder*>(p->atoms[0].number);require(SceneWire::peers.count(cb),"callback identity token");
    SceneWire::requests.push_back(code);*out=new AParcel;
    if(code==1001){AParcel replay{{{'w',SceneWire::seq,{}}},0,true};require(SceneObserver::transact(cb,1,&replay,nullptr)==STATUS_OK,"immediate registration replay");
        if(SceneWire::malformed)(*out)->atoms.push_back(integer(1));}
    else if(code==1003){require(p->atoms[1].kind=='w',"ACK int64 wire type");SceneWire::ack=p->atoms[1].number;}
    else require(code==1002,"private unregister code");
    delete p;*input=nullptr;return STATUS_OK;
}
}

// Only the synthetic saved destination exists in this fixture. No cgroup is
// created and no placement syscall runs; journal bytes/CRC/atomic I/O are real.
extern "C" int access(const char* path,int mode)
#if defined(__GLIBC__)
    noexcept
#endif
{
    if(mode==F_OK&&strcmp(path,"/dev/cpuset/top-app/tasks")==0)return 0;
    return static_cast<int>(syscall(SYS_faccessat,AT_FDCWD,path,mode));
}

struct SceneFixture:RuntimeFixture {
    RawEvents queue;SceneBurst burst;int64_t ack=-1;int reconciles=0;
    int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    std::map<int,uint64_t> ownerModel;
    std::string directory;std::unique_ptr<Journal> journal;
    SceneFixture(){
        require(fd>=0,"scene fixture eventfd");char path[]="/tmp/zuiopt-scene-XXXXXX";
        require(mkdtemp(path),"exclusive journal fixture directory");directory=path;
        journal=std::make_unique<Journal>(directory);
    }
    ~SceneFixture(){close(fd);journal.reset();std::filesystem::remove_all(directory);}
    void release(ProcessState& p){
        RuntimeFixture::release(p);ownerModel.erase(p.pid);
        if(journal->entries.erase(p.pid)){journal->leases.erase(p.pid);journal->commit();}
    }
    void activate(ProcessState& p){
        if(!p.activity_foreground){if(p.managed)release(p);return;}
        if(!p.managed){
            require(ownerModel.emplace(p.pid,p.generation).second,"duplicate owner model");
            OwnerRecord record{p.pid,p.uid,p.pid,p.generation,p.generation,p.package,p.name,"/top-app",1};
            journal->entries[p.pid]=record;journal->leases[p.pid]=record;journal->commit();
            placements++;p.managed=true;
        }
    }
    ProcessState* resolve(const Snapshot& s){
        auto id=validateManagedSnapshot(s,config,*this);if(!id.start)return nullptr;
        auto it=states.find(s.pid);
        if(it!=states.end()&&(it->second.generation!=id.start||it->second.uid!=s.uid)){release(it->second);states.erase(it);}
        auto& p=states[s.pid];p.pid=s.pid;p.uid=s.uid;p.generation=id.start;p.name=s.name;p.package=s.packages[0];p.alive=true;return &p;
    }
    void edges(){for(auto& e:queue.take())processEvent(e,states,*this);}
    void primary(){queue.push(fd,1,42,10001,1);}
    void scene(int64_t seq){queue.pushScene(fd,seq);}
    void tick(int64_t time){
        uint64_t notification;while(::read(fd,&notification,sizeof(notification))>0){}
        edges();burst.accept(queue.takeScene(),time);
        if(burst.timeout(time)==0){auto seq=burst.sequence;auto snapshot=activitySnapshot();
            if(queue.latestScene(seq)){reconcileSnapshot(snapshot,states,*this);reconciles++;if(burst.complete(time)&&queue.latestScene(seq))ack=seq;}}
    }
    void finish(int64_t base=0){for(int offset:SceneBurst::schedule)tick(base+offset);}
    bool managed()const{auto p=states.find(42);return p!=states.end()&&p->second.managed&&p->second.generation==generation;}
    void verifyModel(){
        journal->entries.clear();journal->leases.clear();journal->load();
        for(auto& [pid,p]:states)if(p.managed){
            require(p.generation==generation&&ownerModel.at(pid)==p.generation,"stale PID/model");
            require(journal->entries.at(pid).processStart==p.generation&&journal->leases.at(pid).processStart==p.generation,"journal generation mismatch");
        }
        require(ownerModel.size()==static_cast<size_t>(managed()),"owner leak");
        require(journal->entries.size()==ownerModel.size()&&journal->leases.size()==ownerModel.size(),"journal owner leak");
    }
};

void sceneCases(){
    for(int mode=0;mode<4;mode++){
        SceneFixture r;
        if(mode==3){r.scene(1);r.tick(0);r.primary();}
        else{if(mode!=1)r.primary();if(mode!=2)r.scene(1);}
        r.finish();require(r.managed()&&r.placements==1&&r.ownerModel.size()==1,"A-D dual/order/drop acquisition");r.verifyModel();
    }
    {SceneFixture r;r.scene(1);r.finish();int calls=r.reconciles,placed=r.placements;
        for(int i=0;i<100;i++)r.scene(1);r.tick(1000);
        require(r.reconciles==calls&&r.placements==placed&&r.burst.timeout(1000)==-1,"E duplicate no extra work");}
    {SceneFixture r;r.scene(10);r.scene(11);r.scene(12);r.finish();
        require(r.ack==12&&r.reconciles==4&&r.placements==1,"F latest coalesces");}
    {SceneFixture r;r.scene(10);r.tick(0);r.scene(11);r.tick(50);r.scene(12);r.tick(60);
        r.tick(150);require(r.reconciles==3,"old burst cancelled");r.tick(160);r.tick(310);r.tick(560);
        require(r.ack==12&&r.reconciles==6&&r.placements==1,"only latest burst completes");}
    for(int visible:{100,250,500}){
        SceneFixture r;r.absent=true;r.scene(1);r.tick(0);require(r.ack==-1&&!r.managed(),"no ACK on receipt/missing snapshot");
        for(int t:{100,250,500}){if(t>=visible)r.absent=false;r.tick(t);if(t>=visible)require(r.managed(),"G delayed snapshot acquisition");}
        require(r.ack==1&&r.reconciles==4,"G bounded finish ACK");
    }
    {SceneFixture r;r.scene(1);r.finish();r.app.state=19;r.app.flags=0;r.scene(2);r.finish(1000);
        require(!r.managed()&&r.ownerModel.empty(),"H background release");r.verifyModel();}
    {SceneFixture r;r.scene(1);r.tick(0);r.generation=0;r.absent=true;r.tick(100);r.tick(250);r.tick(500);
        require(r.states.empty()&&r.ownerModel.empty(),"I death during burst");}
    {SceneFixture r;r.scene(1);r.tick(0);r.generation=20;r.tick(100);r.tick(250);r.tick(500);
        require(r.managed()&&r.released==std::vector<uint64_t>{10}&&r.ownerModel.at(42)==20,"J PID reused during burst");r.verifyModel();}
    {SceneFixture r;r.scene(1);r.tick(0);r.generations={10,20};r.tick(100);
        require(r.states.empty()&&r.ownerModel.empty(),"J in-snapshot PID reuse cannot retain owner");r.generation=20;r.tick(250);r.tick(500);require(r.managed(),"J acquire new stable generation");}
    puts("SCENE_CASES_A_TO_J=PASS;OWNER_MODEL_LEAK=0;STALE_PID=0");
}

void callbackLifecycle(){
    SceneFixture r;
    Observer::Holder holder=std::make_shared<CallbackState>(std::function<void(int64_t)>([&](int64_t seq){r.scene(seq);}));
    AIBinder binder{&holder};
    auto send=[&](int64_t seq,int code=1,bool extra=false){AParcel p{{{'w',seq,{}}},0,true};if(extra)p.atoms.push_back(integer(0));return SceneObserver::transact(&binder,code,&p,nullptr);};
    require(send(0)==STATUS_OK&&send(12)==STATUS_OK&&send(11)==STATUS_OK,"scene callback accepts numeric generation");
    require(r.procReads==0&&r.snapshotQueries==0&&r.packageQueries==0&&r.placements==0&&r.ack==-1,"callback zero observation/ACK");
    caller=2000;require(send(13)==STATUS_PERMISSION_DENIED,"non-system sender rejected");caller=1000;
    require(send(-1)==STATUS_BAD_VALUE&&send(13,1,true)==STATUS_BAD_VALUE&&send(13,2)==STATUS_UNKNOWN_TRANSACTION,"scene parcel bounds");
    r.finish();require(r.ack==12&&r.reconciles==4,"callback latest applies in reactor");
    std::promise<void> entered,leave;auto leaveFuture=leave.get_future();
    holder->sceneHandler=[&](int64_t){entered.set_value();leaveFuture.wait();};
    std::thread callback([&]{require(send(13)==STATUS_OK,"inflight callback finishes");});entered.get_future().wait();
    holder->stopAccepting();std::atomic<bool> drained{false};
    std::thread shutdown([&]{holder->drain();drained=true;});
    require(send(14)==STATUS_OK&&!drained,"shutdown ignores new callback while inflight retained");
    leave.set_value();callback.join();shutdown.join();require(drained,"drain joined without UAF");
    require(send(15)==STATUS_OK,"late binder reference harmless after drain");
    puts("SCENE_CALLBACK_ZERO_PROC_SNAPSHOT_PM_PLACEMENT_ACK=PASS;UNREGISTER_DRAIN_JOIN=PASS");
}

void stressAndIdle(){
    std::mt19937 random(0x21c054);int dropPrimary=0,dropScene=0;
    for(int mode=0;mode<2;mode++){
        SceneFixture r;
        for(int i=1;i<=128;i++){
            bool foreground=i%2==1;r.app.flags=foreground?4:0;r.app.state=foreground?2:19;
            if(foreground)r.generation+=10;
            bool drop=random()%5==0;
            if(!(mode==0&&drop))r.primary();else dropPrimary++;
            if(!(mode==1&&drop))r.scene(i);else dropScene++;
            r.finish(i*1000);require(r.managed()==foreground,"stress managed scene miss");r.verifyModel();
        }
        require(r.ownerModel.empty(),"stress final owner released");
        auto reads=r.procReads,snapshots=r.snapshotQueries,queries=r.packageQueries;auto commits=r.journal->commits;
        r.tick(1000000);require(r.burst.timeout(1000000)==-1,"idle infinite block");
        pollfd p{r.fd,POLLIN,0};require(poll(&p,1,25)==0,"idle no residual/lost eventfd wake");
        for(int i=0;i<1000;i++)r.tick(1000000+i);
        require(r.procReads==reads&&r.snapshotQueries==snapshots&&r.packageQueries==queries,"no idle polling work");
        require(r.journal->commits==commits,"no steady journal writes");
    }
    require(dropPrimary>12&&dropPrimary<39&&dropScene>12&&dropScene<39,"deterministic drop rate 10-30 percent");
    std::cout<<"TRANSITIONS=256;DROP_PRIMARY="<<dropPrimary<<";DROP_SCENE="<<dropScene
             <<";MANAGED_SCENE_MISS=0;STALE_PID=0;OWNER_LEAK=0;JOURNAL_CORRUPTION=0;IDLE_POLLING=0\n";
}

void registrationWireAndWake(){
    SceneFixture r;SceneWire::requests.clear();SceneWire::ack=-1;
    {
        SceneObserver observer([&](int64_t seq){r.scene(seq);});
        require(SceneWire::requests==std::vector<int>{1001}&&r.snapshotQueries==0&&SceneWire::ack==-1,"registration only queues latest, no early ACK");
        r.finish();observer.ack(r.ack);require(SceneWire::ack==81,"reactor completed ACK wire");
    }
    require(SceneWire::requests==std::vector<int>({1001,1003,1002})&&SceneWire::peers.empty(),"unregister and release callback strong reference");
    SceneWire::seq=103;
    {SceneFixture fresh;SceneObserver observer([&](int64_t seq){fresh.scene(seq);});fresh.finish();require(fresh.ack==103&&fresh.managed(),"daemon restart replay latest unregistered scene");}
    SceneWire::malformed=true;SceneWire::requests.clear();bool rejected=false;
    try{SceneObserver broken([](int64_t){});}catch(const std::exception&){rejected=true;}
    require(rejected&&SceneWire::requests==std::vector<int>({1001,1002})&&SceneWire::peers.empty(),"malformed registration reply cleans accepted registration");SceneWire::malformed=false;
    RawEvents queue;int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);require(fd>=0,"wake race descriptor");
    std::thread producer([&]{for(int i=0;i<=10000;i++)queue.pushScene(fd,i);});
    int64_t latest=-1;
    while(latest<10000){pollfd p{fd,POLLIN,0};require(poll(&p,1,1000)>0,"eventfd lost wake");uint64_t n;while(::read(fd,&n,sizeof(n))>0){};latest=std::max(latest,queue.takeScene());}
    producer.join();close(fd);
    puts("SCENE_PRIVATE_REGISTER_ACK_UNREGISTER_WIRE=PASS;CONCURRENT_EVENTFD_NO_LOST_WAKE=PASS");
}

int main(){try{sceneCases();callbackLifecycle();stressAndIdle();registrationWireAndWake();puts("ZUIOPT_SCENE_NATIVE=PASS");return 0;}
catch(const std::exception& e){std::cerr<<"SCENE_FAIL "<<e.what()<<'\n';return 1;}}
