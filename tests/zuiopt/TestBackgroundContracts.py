"""V58 privacy coverage and exact scope guards. No image/device operation."""
from pathlib import Path
import re
import unittest
ROOT=Path(__file__).resolve().parents[2]
def text(name):return (ROOT/'native/zuiopt'/name).read_text(encoding='utf8')
def literals(source):
    for match in re.finditer(r'\b(require|std::runtime_error)\(',source):
        start=match.end();depth=1;quote=False;escape=False;arg=start
        for end in range(start,len(source)):
            c=source[end]
            if quote:
                if escape:escape=False
                elif c=='\\':escape=True
                elif c=='"':quote=False
            elif c=='"':quote=True
            elif c=='(':depth+=1
            elif c==')':
                depth-=1
                if not depth:break
            elif c==',' and depth==1 and match[1]=='require':arg=end+1
        literal=re.match(r'\s*"([^"\n]+)"',source[arg:end])
        if literal:yield literal[1]
class BackgroundContracts(unittest.TestCase):
    def test_all_production_ownership_exceptions_classified(self):
        lifecycle=text('ZUIopt_lifecycle.h')
        known=set(re.findall(r'"([^"\n]+)"',lifecycle.split('static constexpr const char* known[]={',1)[1].split('};',1)[0]))
        prefixes={'journal release failed TID=','RECOVERY_UNKNOWN_TASK_FAIL_CLOSED tid='}
        source=text('ZUIopt_owner.h')+text('ZUIopt_core.h').split('struct ProcessState {',1)[1].split('#ifndef ZUIOPT_PRODUCTION',1)[0]+text('ZUIopt_daemon.h')
        missing=set(literals(source))-known-prefixes
        self.assertEqual(missing,set())
        print('KNOWN_PRODUCTION_EXCEPTION_UNCLASSIFIED=0')
    def test_background_has_no_acquisition_or_busy_retry(self):
        lane=text('ZUIopt_owner.h').split('bool backgroundPass(',1)[1].split('    void cleanup()',1)[0].split('        if(!p.managed)',1)[0]
        for forbidden in ('probeBaseline(', 'prepare(p)', 'restore(r)', 'for(int pass=', 'usleep(', 'sleep('):self.assertNotIn(forbidden,lane)
        self.assertIn('backgroundReleaseSchedule={0,50,100,250,500}',text('ZUIopt_core.h'))
        self.assertIn('require(remaining.empty(),"background unrecoverable owned task")',lane)
        self.assertLess(lane.index('if(pending||!remaining.empty()||residue)return false;'),lane.index('journal.leases.erase(p.pid)'))
        self.assertIn('p.acquiring=false;p.acquireFinalConfirmation=false;p.baselineCandidate={}',lane)
        self.assertIn('if(!terminal&&(p.releaseParked||p.next>time))return;',lane)
    def test_park_rearm_is_shared_and_authority_bounded(self):
        core=text('ZUIopt_core.h');daemon=text('ZUIopt_daemon.h');owner=text('ZUIopt_owner.h')
        helper=core.split('void activateAuthority(',1)[1].split('enum class BaselineResult',1)[0]
        self.assertIn('p.releaseParked&&(fresh||newScene)',helper)
        self.assertIn('activateAuthority(p,wanted,authorityEvent,now(),*this)',daemon)
        self.assertIn('authorityEvent=sceneBurst.step==0',daemon)
        self.assertIn('p.releaseBlocked=p.releaseRearmed&&p.activity_foreground',owner)
        self.assertIn('if(p.releaseBlocked)blocked(RuntimeBlockerReason::BACKGROUND_RELEASE_BLOCKED)',daemon)
        for token in ('journal.', 'group(', 'affinity(', 'write(', 'snapshot(', 'sleep('):self.assertNotIn(token,helper)
        self.assertNotIn('background_release_pending',text('ZUIopt_lifecycle.h'))
    def test_substages_are_bounded_and_both_failures_persist(self):
        lifecycle=text('ZUIopt_lifecycle.h');daemon=text('ZUIopt_daemon.h')
        self.assertIn('cleanup_reason=',lifecycle);self.assertIn('data.size()<1024',lifecycle)
        self.assertIn('const auto primarySubstage=substage;',daemon)
        self.assertIn('phase,&e,primarySubstage,&x,substage)',daemon)
        self.assertNotIn('recordLifecycle(',daemon.split('while(!stop){',1)[1].split('phase=StartupStage::STOP;',1)[0])
if __name__=='__main__':unittest.main(verbosity=2)
