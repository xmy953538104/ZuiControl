"""Execute exact production runtime status seams; placement/storage are mocks, not kernel proof."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]

def method(text,signature):
    start=text.index(signature);brace=text.index('{',start);depth=0;quote=None;escape=False
    for at in range(brace,len(text)):
        char=text[at]
        if quote:
            if escape:escape=False
            elif char=='\\':escape=True
            elif char==quote:quote=None
        elif char in ('"',"'"):quote=char
        elif char=='{':depth+=1
        elif char=='}':
            depth-=1
            if depth==0:return text[start:at+1]
    raise AssertionError(signature)

def fixture():
    paths=['native/zuiopt/'+name for name in ('ZUIopt_daemon.h','ZUIopt_runtime.h','ZUIopt_store.h','ZUIopt.cpp','ZUIopt_rules.h')]+['tests/zuiopt/ZUIoptRuntimeTest.cpp']
    sources={path:(ROOT/path).read_text('utf8') for path in paths}
    daemon=sources[paths[0]];runtime=sources[paths[1]];store=sources[paths[2]];cli=sources[paths[3]];rules=sources[paths[4]]
    startup=daemon[daemon.index('phase=StartupStage::PLACEMENT;'):daemon.index('phase=StartupStage::RECONCILE;')]
    assert startup.index('placement=std::make_unique<Placement>')<startup.index('invalidateRuntime();')<startup.index('sceneObserver=std::make_unique<SceneObserver>')
    assert 'try{' not in startup[startup.index('invalidateRuntime();'):startup.index('sceneObserver=')]
    reload=next(line for line in daemon.splitlines() if 'if(valid){phase=StartupStage::RELOAD;' in line)
    assert reload.index('invalidateRuntime();')<reload.index('for(auto& [_,p]:states)release(p,ReleaseCause::RELOAD_OR_CONTROLLED_STOP)')<reload.index('config=std::move(next)')
    assert reload.index('loadedConfig=std::move(nextBytes)')<reload.index('loadedIdentity=loadedGenerationIdentity(')<reload.index('publishRuntime();')<reload.index('reconcile(observer->snapshot())')<reload.index('recordLoadedIdentity(')
    assert daemon.count('placement->release(')==1
    assert 'for(auto& [_,p]:states)release(p,cause)' in daemon
    assert daemon.count('loadedIdentity=loadedGenerationIdentity(')==2
    assert daemon.count('recordLoadedIdentity(stateRoot,loadedIdentity)')==2 and 'recordLoadedGeneration(stateRoot,' not in daemon
    publish=method(daemon,'    void publishRuntime()')
    assert 'sha256' not in publish and 'loadedGenerationIdentity' not in publish
    scan=daemon[daemon.index('void scan('):daemon.index('    void stats()')]
    assert 'runtimeProcesses[p.pid]=status.str();runtimePending=false;publishRuntime();' in scan
    for key in ('"uid"','"C1Start"','"C2Start"'):assert key.replace('"','\\"') in scan
    assert 'loadedGenerationIdentity(bytes,' in method(store,'inline void recordLoadedGeneration(')
    definitions='\n'.join(method(runtime,signature) for signature in ('inline std::string runtimeOwnerHeader(','inline std::string runtimeEnvelope(','inline bool runtimeOwnerMatches('))
    definitions+='\n'+'\n'.join(method(source,signature) for source,signature in ((rules,'inline bool hex('),(store,'inline bool generationId('),(store,'inline std::string generationOf('),(store,'inline std::string loadedGenerationIdentity(')))
    seams='\n'.join(method(daemon,signature) for signature in ('    void release(ProcessState& p,ReleaseCause cause)','    void release(ProcessState& p)','    void invalidateRuntime()','    void publishRuntime()'))
    receipt=method(store,'inline void recordLoadedIdentity(')
    envelope_cases=method(sources[paths[5]],'void runtimeEnvelopeCases()')
    branch=method(cli,'        else if(command=="runtime")')
    branch=branch[branch.index('{')+1:-1]
    fixture=r'''#include <map>
#include <string>
#include <sstream>
#include <iomanip>
#include <stdexcept>
#include <iostream>
#include <vector>
#include <functional>
#include <cstdint>
#include <utility>
namespace ZUIopt {
void require(bool ok,const char* reason){if(!ok)throw std::runtime_error(reason);}
const std::string BOOT="00000000-0000-0000-0000-000000000000";
std::string trim(std::string text){while(!text.empty()&&(text.back()=='\n'||text.back()=='\r'))text.pop_back();return text;}
std::string read(const std::string& path){require(path=="/proc/sys/kernel/random/boot_id","unexpected source read");return BOOT+"\n";}
int hashes=0;std::string sha256(const std::string&){hashes++;return std::string(64,'b');}
constexpr size_t RUNTIME_STATUS_LIMIT=32768;
@@DEFINITIONS@@
@@ENVELOPE_CASES@@
const std::string OWNER="g"+std::string(24,'a')+":"+BOOT+":17:123:"+std::string(64,'b');
enum class ReleaseCause {AUTHORITY_BACKGROUND,PROCESS_DEATH,RELOAD_OR_CONTROLLED_STOP,CRASH_RECOVERY};
enum class EventSubstage {NONE,BACKGROUND_RELEASE,RELOAD_RELEASE,STOP_RELEASE,CATCH_RELEASE_ALL};
enum class RuntimeBlockerReason {BACKGROUND_RELEASE_BLOCKED};
struct ProcessState {int pid=42;bool alive=true,releaseBlocked=false,owned=true,writeable=true,acquiringFlag=false;
 bool managed()const{return owned;}bool writable()const{return owned&&writeable;}bool acquiring()const{return acquiringFlag;}};
struct Placement {bool contention=false,denied=false;int calls=0;ReleaseCause last=ReleaseCause::AUTHORITY_BACKGROUND;
 void release(ProcessState& p,ReleaseCause cause){calls++;last=cause;if(denied)throw std::runtime_error("physical release denied");if(contention)p.writeable=false;else{p.owned=false;p.acquiringFlag=false;}}};
struct Journal {int entries=2,globals=1,commits=0;std::string currentBootId(){return BOOT;}};
struct Store {std::map<std::string,std::string> files;bool fail=false;int writes=0;std::function<void(const std::string&)> onRead=[](const std::string&){};};
std::map<std::string,Store> stores;
struct PrivateDir {std::string root;explicit PrivateDir(std::string r):root(std::move(r)){}
 void put(const std::string& path,const std::string& text){auto& s=stores[root];if(s.fail)throw std::runtime_error("atomic write failed");s.files[path]=text;s.writes++;}
 std::string get(const std::string& path,size_t limit,bool){auto& s=stores[root];s.onRead(path);auto text=s.files[path];require(text.size()<=limit,"mock bound");return text;}};
@@RECEIPT@@
struct StatusCore {std::string stateRoot,runtimeStatus,loadedIdentity=OWNER;bool runtimePending=true;std::map<int,std::string> runtimeProcesses;std::map<int,ProcessState> states;
 Journal j;Placement pl;Journal* journal=&j;Placement* placement=&pl;EventSubstage substage=EventSubstage::NONE;int blockers=0;
 explicit StatusCore(std::string root):stateRoot(std::move(root)){}
 void blocked(RuntimeBlockerReason){blockers++;}
 void seed(){runtimePending=false;states[42]=ProcessState{};runtimeProcesses[42]="{\"pid\":42,\"requestedRt\":1,\"effectiveRt\":1}";publishRuntime();}
 std::string file(){return stores[stateRoot].files["runtime-status.v1"];}
@@SEAMS@@
};
constexpr const char* ROOT="cli";bool selectedFlag=true;int selectedCalls=0;std::function<void(int)> selectedHook=[](int){};
bool selected(){selectedHook(++selectedCalls);return selectedFlag;}
struct RuleStore {bool live=true;int calls=0;std::function<void(int)> hook=[](int){};bool loaded(){hook(++calls);return live;}};
std::string runtimeRead(RuleStore& store){std::string key,value,result;
@@CLI@@
return result;}
void cliSetup(){stores[ROOT]=Store{};stores[ROOT].files["loaded-generation.v2"]=OWNER+"\n";stores[ROOT].files["runtime-status.v1"]=runtimeEnvelope(BOOT,OWNER,false,{{42,"{\"pid\":42}"}});selectedFlag=true;selectedCalls=0;selectedHook=[](int){};}
bool noAck(const std::string& text){return text=="{\"schema\":2,\"processes\":[],\"status\":\"NO_RUNTIME_ACK\"}";}
int cases=0;void test(const char* name,const std::function<void()>& fn){fn();cases++;std::cout<<name<<"=PASS\n";}
}
using namespace ZUIopt;
int main(){try{
 test("BOUNDED_EXACT_PRODUCTION_ENVELOPE_IDENTITY_CASES",runtimeEnvelopeCases);
 test("STARTUP_RETIRES_SAME_BOOT_PREVIOUS_OWNER",[]{StatusCore old("startup");old.seed();StatusCore fresh("startup");fresh.loadedIdentity="g"+std::string(24,'a')+":"+BOOT+":29:456:"+std::string(64,'b');int admitted=0;fresh.invalidateRuntime();admitted++;require(admitted==1&&fresh.file().find("\"processes\":[]")!=std::string::npos&&fresh.file().find("PENDING_OWNER_ACK")!=std::string::npos&&runtimeOwnerMatches(fresh.file(),BOOT,fresh.loadedIdentity)&&!runtimeOwnerMatches(fresh.file(),BOOT,OWNER),"new owner invalidation");require(fresh.j.entries==2&&fresh.j.globals==1&&fresh.j.commits==0&&fresh.pl.calls==0,"invalidation touched kernel/journal");});
 test("STARTUP_WRITE_FAILURE_PREVENTS_CALLBACK",[]{StatusCore c("writefail");stores[c.stateRoot].fail=true;int callbacks=0;try{c.invalidateRuntime();callbacks++;}catch(const std::runtime_error&){}require(callbacks==0&&c.runtimeStatus.empty(),"admitted after failed publication");});
 test("TERMINAL_CAUSE_AWARE_ERASE_PRESERVES_SUBSTAGE",[]{StatusCore c("release");c.seed();c.substage=EventSubstage::RELOAD_RELEASE;c.release(c.states.at(42),ReleaseCause::RELOAD_OR_CONTROLLED_STOP);require(!c.states.at(42).managed()&&c.runtimeProcesses.empty()&&c.file().find("NO_RUNTIME_OBSERVATIONS")!=std::string::npos,"terminal bookkeeping");require(c.substage==EventSubstage::RELOAD_RELEASE&&c.pl.last==ReleaseCause::RELOAD_OR_CONTROLLED_STOP,"cause/substage changed");});
 test("BACKGROUND_DEATH_CAUSE_AND_BLOCKER_UNCHANGED",[]{for(bool alive:{true,false}){StatusCore c(alive?"bg":"death");c.seed();auto& p=c.states.at(42);p.alive=alive;p.releaseBlocked=true;c.release(p);require(c.substage==EventSubstage::BACKGROUND_RELEASE&&c.blockers==1&&c.pl.last==(alive?ReleaseCause::AUTHORITY_BACKGROUND:ReleaseCause::PROCESS_DEATH),"default release changed");}});
 test("CONTENDED_RELEASE_HAS_PENDING_STATUS_AND_RETAINS_JOURNAL",[]{StatusCore c("pending");c.seed();c.pl.contention=true;c.release(c.states.at(42),ReleaseCause::RELOAD_OR_CONTROLLED_STOP);require(c.states.at(42).managed()&&c.runtimeProcesses.count(42)&&c.file().find("PENDING_OWNER_ACK")!=std::string::npos&&c.j.entries==2&&c.j.globals==1&&c.j.commits==0,"pending release hidden or journal deleted");});
 test("RELOAD_INVALIDATION_NEVER_RELABELS_OLD_ROWS",[]{StatusCore c("reload");c.seed();c.pl.contention=true;c.invalidateRuntime();c.release(c.states.at(42),ReleaseCause::RELOAD_OR_CONTROLLED_STOP);c.loadedIdentity="g"+std::string(24,'c')+OWNER.substr(25);c.publishRuntime();require(c.states.at(42).managed()&&c.runtimeProcesses.empty()&&c.file().find("PENDING_OWNER_ACK")!=std::string::npos&&runtimeOwnerMatches(c.file(),BOOT,c.loadedIdentity)&&!runtimeOwnerMatches(c.file(),BOOT,OWNER)&&c.j.entries==2,"reload relabelled old observation");});
 test("PHYSICAL_RELEASE_EXCEPTION_RETAINS_OBSERVATION",[]{StatusCore c("physicalfail");c.seed();auto old=c.file();c.pl.denied=true;try{c.release(c.states.at(42),ReleaseCause::CRASH_RECOVERY);}catch(const std::runtime_error&){}require(c.states.at(42).managed()&&c.runtimeProcesses.count(42)&&c.file()==old,"failed release erased facts");});
 test("PUBLISH_NEVER_REHASHES_CONFIG_OR_REWRITES_IDENTICAL_IDLE",[]{StatusCore c("idle");c.invalidateRuntime();auto count=stores[c.stateRoot].writes;int before=hashes;for(int i=0;i<100;i++)c.publishRuntime();require(stores[c.stateRoot].writes==count&&hashes==before,"new steady hash/write work");});
 test("SHARED_LOADED_RECEIPT_IDENTITY_FORMAT",[]{auto text="# ZUIOPT_GENERATION g"+std::string(24,'a')+"\nschema 2\nenabled true\n";require(loadedGenerationIdentity(text,BOOT,17,123)==OWNER,"loaded identity grammar drift");});
 test("LOADED_RECEIPT_REUSES_CACHED_IDENTITY_AND_EXACT_NEWLINE",[]{int count=hashes;recordLoadedIdentity("receipt",OWNER);require(stores["receipt"].files["loaded-generation.v2"]==OWNER+"\n"&&hashes==count,"receipt grammar or duplicate hash work");stores["receipt"].fail=true;recordLoadedIdentity("receipt",OWNER+"x");require(stores["receipt"].files["loaded-generation.v2"]==OWNER+"\n","failed receipt changed previous ACK");});
 test("CLI_ACCEPTS_CURRENT_BOUND_CAPTURED_SNAPSHOT",[]{cliSetup();RuleStore s;auto expected=stores[ROOT].files["runtime-status.v1"];require(runtimeRead(s)==expected&&s.calls==2&&selectedCalls==2,"current capture not acknowledged");});
 test("CLI_REJECTS_OLD_OWNER_BOOT_GENERATION_HASH_SCHEMA",[]{for(auto owner:std::vector<std::string>{OWNER+"x","g"+std::string(24,'c')+OWNER.substr(25),OWNER.substr(0,OWNER.size()-1)+"c"}){cliSetup();stores[ROOT].files["runtime-status.v1"]=runtimeEnvelope(BOOT,owner,false,{});RuleStore s;require(noAck(runtimeRead(s)),"wrong owner accepted");}cliSetup();stores[ROOT].files["runtime-status.v1"]=runtimeEnvelope("10000000-0000-0000-0000-000000000000",OWNER,false,{});RuleStore s;require(noAck(runtimeRead(s)),"wrong boot accepted");cliSetup();stores[ROOT].files["runtime-status.v1"]="{\"schema\":1,\"processes\":[]}\n";RuleStore legacy;require(noAck(runtimeRead(legacy)),"legacy unbound file accepted");});
 test("CLI_REJECTS_UNSELECTED_UNLOADED_OR_ABSENT_OWNER",[]{cliSetup();RuleStore stopped;selectedFlag=false;require(noAck(runtimeRead(stopped))&&stopped.calls==0,"unselected owner read");cliSetup();RuleStore dead;dead.live=false;require(noAck(runtimeRead(dead)),"dead owner read");cliSetup();stores[ROOT].files.erase("runtime-status.v1");RuleStore absent;require(noAck(runtimeRead(absent)),"missing status accepted");});
 test("CLI_REJECTS_INFLIGHT_OWNER_RECEIPT_CHANGE",[]{cliSetup();stores[ROOT].onRead=[](const std::string& name){if(name=="runtime-status.v1")stores[ROOT].files["loaded-generation.v2"]=OWNER+"x\n";};RuleStore s;require(noAck(runtimeRead(s)),"receipt change accepted");});
 test("CLI_RECHECKS_LIVE_LOADED_AND_SELECTED",[]{cliSetup();RuleStore s;s.hook=[&](int call){if(call==2)s.live=false;};require(noAck(runtimeRead(s)),"dead during read accepted");cliSetup();selectedHook=[](int call){if(call==2)selectedFlag=false;};RuleStore later;require(noAck(runtimeRead(later)),"unselected during read accepted");});
 test("CLI_EXPLICIT_PENDING_IS_OBSERVATION_NOT_RECOVERY",[]{cliSetup();stores[ROOT].files["runtime-status.v1"]=runtimeEnvelope(BOOT,OWNER,true,{});RuleStore s;require(runtimeRead(s).find("PENDING_OWNER_ACK")!=std::string::npos,"pending silently promoted or dropped");});
 std::cout<<"STATUS_EXACT_PRODUCTION_SEAMS_CASES="<<cases<<";MOCK_PLACEMENT_STORAGE_AND_LIVENESS;NOT_PHYSICAL_RECOVERY_PROOF\n";return 0;
}catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}}
'''.replace('@@DEFINITIONS@@',definitions).replace('@@ENVELOPE_CASES@@',envelope_cases).replace('@@RECEIPT@@',receipt).replace('@@SEAMS@@',seams).replace('@@CLI@@',branch)
    hashes={path:hashlib.sha256(text.encode()).hexdigest() for path,text in sources.items()}
    return fixture,hashes

def run(output,compiler):
    source,hashes=fixture();print('STATUS_EXACT_SOURCE_STARTUP_RELOAD_RELEASE_SCAN_WIRING=PASS',flush=True)
    output.mkdir(parents=True,exist_ok=True)
    cpp=output/'RuntimeStatus.cpp';cpp.write_text(source,encoding='utf8',newline='\n')
    executable=output/('RuntimeStatus.exe' if __import__('os').name=='nt' else 'RuntimeStatus')
    command=[compiler,'-std=c++17','-O1','-Wall','-Wextra','-Werror',str(cpp),'-o',str(executable)]
    compiled=subprocess.run(command,capture_output=True,text=True,timeout=60)
    (output/'compile.txt').write_text(compiled.stdout+compiled.stderr,encoding='utf8')
    assert compiled.returncode==0,compiled.stderr
    executed=subprocess.run([str(executable)],capture_output=True,text=True,timeout=20)
    (output/'cases.txt').write_text(executed.stdout+executed.stderr,encoding='utf8')
    print(executed.stdout,end='',flush=True)
    assert executed.returncode==0,executed.stderr
    assert hashes=={path:hashlib.sha256((ROOT/path).read_text('utf8').encode()).hexdigest() for path in hashes},'tested source changed during run'
    (output/'RESULT.json').write_text(json.dumps(dict(result='PASS_EXACT_PRODUCTION_RUNTIME_STATUS_SEAMS',cases=17,sourceSha256=hashes,placementStorageLivenessMocked=True,physicalRecoveryProved=False,deviceTouched=False,transportAdded=False),indent=2),encoding='utf8')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--compiler',default=shutil.which('clang++') or 'clang++');parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    if args.output:run(args.output,args.compiler)
    else:
        with tempfile.TemporaryDirectory(prefix='zuiopt-runtime-status-') as temporary:run(Path(temporary),args.compiler)
