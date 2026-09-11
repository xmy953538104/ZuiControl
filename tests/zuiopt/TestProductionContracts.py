"""Source fences for the reviewed V21 integration; no device or image writes."""
from pathlib import Path
import hashlib
import re
import unittest
from TestCanonicalDocs import verify_repository_docs

ROOT=Path(__file__).resolve().parents[2]
def read(path): return (ROOT/path).read_text(encoding='utf-8')

class ProductionContracts(unittest.TestCase):
    def test_authority_epoch_scan_and_write_fences(self):
        daemon=read('native/zuiopt/ZUIopt_daemon.h');owner=read('native/zuiopt/ZUIopt_owner.h')
        scan=daemon.split('void scan(ProcessState& p)',1)[1].split('    void stats()',1)[0]
        self.assertIn('const auto token=acceptedAuthority',scan)
        self.assertNotIn('token=events.authorityEpoch()',scan)
        self.assertIn('for(int tid:tids){\n            authority.check();',scan)
        for call in ('verifyCoherence(p,start,&authority)','prepare(p,&authority)','r?r->cls:"default",&authority)'):
            self.assertIn(call,scan)
        self.assertIn('catch(const StaleAuthorityScan&)',scan)
        self.assertIn('if(authority.background){p.activity_foreground=false;release(p);}',scan)
        self.assertEqual(scan.count('activitySnapshot()'),1)
        self.assertIn('validateManagedSnapshot(s,config,*this).start==p.generation',scan)
        self.assertIn('if(events.authorityCurrent(epoch))acceptedAuthority=epoch',daemon)
        self.assertLess(owner.index('if(authority)authority->drift()'),owner.index('bool exhausted=p.coherenceEpisodes'))
        self.assertIn('fence(authority);placed=setAffinity(tid,m)',owner)
        self.assertIn('write(path+"/tasks",std::to_string(tid),authority)',owner)
        self.assertIn('if(placed)t.appliedMask=m;',owner)
        self.assertIn('catch(const StaleAuthorityScan&){if(changed)journal.commit();throw;}',owner)

    def test_fixture_io_isolation_and_ci_bound(self):
        fixture=read('tests/zuiopt/ZUIoptAcquisitionTest.cpp')
        self.assertIn('journal=std::make_unique<Journal>(Kernel::root+"/state")',fixture)
        self.assertIn('storage.f_type!=0x01021994,"journal must remain disk-backed"',fixture)
        self.assertIn('storage.f_type==0x01021994,"virtual kernel must be tmpfs"',fixture)
        self.assertIn('target=virtualRoot+"/read/"',fixture)
        workflow=read('.github/workflows/build.yml')
        self.assertIn('timeout --kill-after=10s 5m',workflow)
        self.assertIn('timeout-minutes: 10',workflow)
        self.assertNotIn('--wrap=fsync',workflow)
        for name in ('matrix','recovery','stress512','coherence1024','horizon','liveness1024'):
            self.assertIn('timed("'+name+'",',fixture)

    def test_versioned_apk_staging_matches_app_and_payload_consumer(self):
        version=re.search(r'versionCode\s*=\s*(\d+)',read('app/build.gradle.kts'))[1]
        relative=f'system/priv-app/ZuiControlV{version}/ZuiControl.apk'
        self.assertIn(f'APP_APK_PATH = "{relative}"',read('scripts/build/ApplyZuiControlPayload.py'))
        stage=read('.github/workflows/build.yml').split('- name: Stage payload APK',1)[1].split('- name:',1)[0]
        self.assertEqual(re.findall(r'mkdir -p (\S+)',stage),['payload/'+relative.rsplit('/',1)[0]])
        self.assertEqual(re.findall(r'payload/system/priv-app/ZuiControlV\d+/ZuiControl.apk',stage),['payload/'+relative])
        self.assertIn(f'rm -rf payload/system/priv-app/ZuiControlV{int(version)-1}',stage)
        self.assertIn(f'"ZuiControlV{version}"',read('scripts/build/BuildZuiControl.ps1'))
        self.assertIn(f"priv-app\\ZuiControlV{version}\\ZuiControl.apk",read('scripts/build/VerifyZuiControlFlashPackage.ps1'))
        from VerifyZUIoptPayload import REQUIRED
        self.assertEqual({p for p in REQUIRED if p.endswith('/ZuiControl.apk')},{'/'+relative})

    def test_temporal_and_coherence_production_wiring(self):
        owner=read('native/zuiopt/ZUIopt_owner.h');core=read('native/zuiopt/ZUIopt_core.h');daemon=read('native/zuiopt/ZUIopt_daemon.h')
        for field in ('BaselineCandidate','firstStableAt','lastStableAt','acquisitionEpoch','coherenceEpisodes'):
            self.assertIn(field,core)
        self.assertLess(owner.index('time-candidate.firstStableAt<250'),owner.index('const auto floor=proc.floor()'))
        self.assertIn('if(result!=BaselineResult::STABLE){p.baselineCandidate={};return result;}',owner)
        self.assertIn('!forceCoherence(p)&&t.appliedMask==m',owner)
        self.assertIn('p.coherenceEpisodes>=2',owner)
        self.assertIn('coherenceSchedule={100,250,500,1000,1500,2000,2500,3000,3500,4000,4500,5000,5500,6000}',core)
        self.assertIn('repairSchedule={100,250,500,1000}',core)
        self.assertIn('finishScan(p,now(),coherence==CoherenceResult::REPAIR)',daemon)
        self.assertNotIn('authorityEvent=true;',daemon)
        self.assertLess(daemon.index('verifyCoherence(p,start,&authority)'),daemon.index('placement->prepare(p,&authority)'))
        self.assertIn('authorityEvent=sceneBurst.step==0;',daemon)
        self.assertIn('activateAuthority(p,wanted,authorityEvent,now(),*this);',daemon)
        self.assertIn('if(newScene)armCoherence(p,time);',core)
        self.assertIn('authorityEvent=outerAuthority;',daemon)
        self.assertIn('if(p.coherenceReleasing){relinquishCoherence(p);',owner)
        self.assertIn('ownership_contested',read('native/zuiopt/ZUIopt_lifecycle.h'))

    def test_acquisition_zero_write_and_local_bounded_deadlines(self):
        core=read('native/zuiopt/ZUIopt_core.h')
        self.assertIn('schedule={0,100,250,500,750,1000,1250,1500,2000,2500,3000}',core)
        self.assertIn('auto result=runtime.acquire(p);',core)
        self.assertNotIn('?BaselineResult::DEFER:runtime.acquire(p)',core)
        self.assertIn('confirmation<=p.acquireStarted+3500',core)
        self.assertIn('!p.acquireFinalConfirmation&&valid',core)
        self.assertIn('if(p.backgroundReleasing||p.managed||p.acquiring||p.acquireBlocked)return;',core)
        self.assertIn('if(!p.acquiring||p.next>time)return;',core)
        owner=read('native/zuiopt/ZUIopt_owner.h')
        probe=owner.split('BaselineResult probeBaseline(',1)[1].split('class Journal {',1)[0]
        for token in ('journal.','setAffinity(', 'write(', 'p.managed=', 'p.ownershipFloor='):
            self.assertNotIn(token,probe)
        acquire=owner.split('BaselineResult acquire(ProcessState& p,Proc& proc)',1)[1].split('void prepare(',1)[0]
        self.assertLess(acquire.index('probeBaseline('),acquire.index('proc.floor()'))
        self.assertLess(acquire.index('proc.floor()'),acquire.index('journal.leases['))
        self.assertLess(acquire.index('journal.commit()'),acquire.index('p.managed=true'))
        release=owner.split('void release(ProcessState& p,ReleaseCause',1)[1].split('void cleanup()',1)[0]
        self.assertLess(release.index('if(!p.managed)'),release.index('prepare(p)'))
        pending=release.split('if(!p.managed)',1)[1].split('return;}',1)[0]
        for token in ('prepare(', 'journal.commit(', 'restore(', 'write(', 'identity('):self.assertNotIn(token,pending)
        daemon=read('native/zuiopt/ZUIopt_daemon.h')
        activate=daemon.split('void activate(',1)[1].split('ProcessState* resolve(',1)[0]
        for token in ('group(', 'affinity(', 'ownershipFloor', 'managed=true'):self.assertNotIn(token,activate)
        self.assertIn('advanceAcquisition(p,now(),*this)',daemon)
        self.assertIn('if((p.managed||p.acquiring)&&!p.releaseParked)',daemon)
        lifecycle=read('native/zuiopt/ZUIopt_lifecycle.h')
        self.assertIn('inheritance_baseline_unstable',lifecycle)

    def test_resolve_filters_before_all_proc_reads(self):
        core=read('native/zuiopt/ZUIopt_core.h')
        guard=core.split('inline bool eligibleSnapshot(',1)[1].split('inline Config parseConfig(',1)[0]
        for token in ('s.uid>=10000','s.uid<20000','s.packages.size()==1','packageLabel(s.packages[0])','config.find(s.packages[0])'):
            self.assertLess(guard.index(token),guard.index('return probe(s.pid,0)'))
        self.assertIn('if(!eligibleSnapshot(s,config))return {};',guard)
        daemon=read('native/zuiopt/ZUIopt_daemon.h')
        resolve=daemon.split('ProcessState* resolve(',1)[1].split('void reconcile(',1)[0]
        self.assertIn('validateManagedSnapshot(s,config,*this)',resolve)
        events=read('native/zuiopt/ZUIopt_events.h')
        validate=events.split('Identity validateManagedSnapshot(',1)[1].split('void processEvent(',1)[0]
        self.assertLess(validate.index('eligibleSnapshot(s,config)'),validate.index('runtime.procIdentity(s.pid)'))
        self.assertIn('authoritativeIdentity(s,runtime.packageAuthority(s.uid))',validate)
        self.assertIn('runtime.procIdentity(s.pid).start!=first.start||runtime.procUid(s.pid)!=s.uid',validate)

    def test_callback_has_only_raw_queue_and_notify(self):
        daemon=read('native/zuiopt/ZUIopt_daemon.h')
        enqueue=daemon.split('void enqueue(',1)[1].split('Identity procIdentity(',1)[0]
        self.assertIn('events.push(eventFd,code,pid,user,value);',enqueue)
        events=read('native/zuiopt/ZUIopt_events.h')
        queue=events.split('class RawEvents {',1)[1].split('inline bool sameProcess(',1)[0]
        for token in ('identity(', 'processName(', 'packagesForUid(', 'snapshot(', 'placement', '/proc', 'affinity('):
            self.assertNotIn(token,enqueue+queue)
        self.assertIn('processEvent(e,states,*this)',daemon)
        self.assertIn('processName(pid,true)',daemon)
        self.assertIn('catch(const ProcError& error){if(!error.permission())throw;procBlocked(error);',daemon)
        self.assertIn('p->activity_foreground=s.state==2&&(s.flags&4)!=0;',events)
        self.assertNotIn('activity_foreground=e.value',events)
        self.assertIn('id.start==p.generation&&user==p.uid',events)
        binder=read('native/zuiopt/ZUIopt_binder.h')
        self.assertIn('catch(const std::bad_alloc&) {return STATUS_NO_MEMORY;}',binder)
        self.assertIn('catch(...) {return STATUS_FAILED_TRANSACTION;}',binder)

    def test_durable_fatal_before_cleanup_and_no_steady_state_writes(self):
        daemon=read('native/zuiopt/ZUIopt_daemon.h')
        reactor=daemon.split('int run()',1)[1]
        loop=reactor.split('while(!stop){',1)[1].split('phase=StartupStage::STOP;',1)[0]
        self.assertNotIn('recordLifecycle(',loop)
        fatal=reactor.split('const auto primarySubstage=substage;',1)[1]
        self.assertLess(fatal.index('phase,&e,primarySubstage)'),fatal.index('observer.reset()'))
        self.assertLess(fatal.index('phase,&e,primarySubstage)'),fatal.index('releaseAll()'))
        self.assertIn('phase,&e,primarySubstage,&x,substage)',fatal)
        self.assertIn('return 3;',fatal)
        self.assertIn('return 2;',fatal)
        lifecycle=read('native/zuiopt/ZUIopt_lifecycle.h')
        for token in ('noexcept','catch(...){return false;}','data.size()<1024','reason.size()<=96','PrivateDir directory(root)','directory.put(fatal?"fatal.v1":"startup.v1",data)'):
            self.assertIn(token,lifecycle)
        for stage in ('CORE_CONSTRUCTED','OBSERVER_OK','SNAPSHOT_OK','PACKAGE_ABI_OK','PLACEMENT_OK','RECONCILE_OK','READY'):
            self.assertEqual(reactor.count('recordLifecycle(stateRoot,journal->currentBootId(),StartupStage::'+stage+')'),0 if stage=='CORE_CONSTRUCTED' else 1)
        self.assertIn('recordLifecycle(stateRoot,journal->currentBootId(),phase);',reactor)
        self.assertNotIn('recordLifecycle(stateRoot,journal->boot',reactor)
        binder=read('native/zuiopt/ZUIopt_binder.h')
        self.assertLess(binder.index('*phase=StartupStage::REGISTER_PROCESS_OBSERVER'),binder.index('registered=true;registration(120)'))

    def test_canonical_docs(self):
        verify_repository_docs(ROOT)

    def test_init_scaffold_and_keep_root(self):
        rc=read('payload/system/etc/init/zui_scheduler.rc')
        scaffold='    mkdir /dev/cpuset/ZUIopt 0755 root root'
        self.assertEqual(rc.count(scaffold),1)
        action=rc[:rc.index(scaffold)].strip().splitlines()[0]
        self.assertEqual(action,'on post-fs-data')
        self.assertLess(rc.index(scaffold),rc.index('zuiopt_boot_state.sh'))
        self.assertLess(rc.index(scaffold),rc.index('start zui_zuiopt'))
        owner=read('native/zuiopt/ZUIopt_owner.h')
        self.assertNotIn('mkdir(root.c_str()',owner)
        self.assertNotIn('rmdir(root.c_str()',owner)
        self.assertIn('requireScaffold();journal.load();',owner)
        self.assertIn('RECOVERY_UNKNOWN_TASK_FAIL_CLOSED',owner)

    def test_single_owner_start_is_failsafe_guarded(self):
        rc=read('payload/system/etc/init/zui_scheduler.rc')
        action='';starts=[]
        for line in rc.splitlines():
            if line.startswith('on '): action=line
            if line.strip() == 'start zui_zuiopt':
                self.assertIn('property:sys.zui_control.scheduler_active=1',action)
                self.assertIn('property:sys.zui_control.zuiopt_failed=0',action)
                starts.append((action,line))
        self.assertEqual(len(starts),1)
        self.assertIn('    trigger zuiopt-start',rc)
        self.assertIn('    restart_period 5',rc)
        self.assertNotRegex(rc,r'^\s+critical(?:\s|$)')
        crash=read('payload/system/etc/zuiopt/crash_gate.sh')
        self.assertNotIn('while ',crash)
        boot=read('payload/system/etc/zuiopt/zuiopt_boot_state.sh')
        self.assertIn('|| state=FAILSAFE',boot)
        self.assertIn('READY_TO_START) setprop sys.zui_control.zuiopt_failed 0',boot)
        self.assertNotIn('zuiopt_boot_state.sh',rc[rc.index('on property:zui_control.scheduler=restart'):])

    def test_bound_transport_and_native_paths(self):
        daemon=read('payload/system/bin/zui_controld')
        for command in ('state','reset','read','begin','chunk','commit','abort','enable','disable','rollback'):
            self.assertIn('zo_'+command,daemon)
        self.assertIn('[ "${#3}" -le 10924 ]',daemon)
        self.assertIn('bounded_transport=1',daemon)
        binary=read('native/zuiopt/ZUIopt.cpp')
        for forbidden in ('--selftest','--check-config','/data/local/tmp','sched_setscheduler','SCHED_FIFO','SCHED_RR'):
            self.assertNotIn(forbidden,binary)
        self.assertIn('pidfd_send_signal',binary)
        self.assertIn('root identity required',binary)
        store=read('native/zuiopt/ZUIopt_store.h')
        for token in ('O_NOFOLLOW','st_nlink==1','flock(lockFd,LOCK_EX)','600000','PACK_COUNT','sha256(data)==f[3]','root.put("effective.conf",effective)'):
            self.assertIn(token,store)

    def test_factory_and_retained_binary_identity(self):
        factory=ROOT/'payload/system/etc/zuiopt/factory_rules.conf'
        self.assertEqual(hashlib.sha256(factory.read_bytes()).hexdigest(),'1ac23f379482649608b44860eac0be165ba6b71022eee140735edbc362970f65')
        self.assertEqual(len(re.findall(r'^profile ',factory.read_text(),re.M)),27)
        self.assertEqual(len(re.findall(r'^package ',factory.read_text(),re.M)),316)

    def test_new_policy_has_no_forbidden_owner_scope(self):
        policy=read('payload/patches/plat_sepolicy_zui_control.cil')
        zuiopt=policy[policy.index(';; ZUIopt direct owner.'):]
        self.assertNotIn('(typepermissive ',zuiopt)
        self.assertNotIn('unconfined', '\n'.join(x for x in zuiopt.splitlines() if not x.startswith(';;')))
        for target in ('proc_type','fs_type','sysfs_type','property_type','domain','surfaceflinger_exec','vendor_sysfs_kgsl','zui_scheduler_proc'):
            self.assertNotIn('(allow zuiopt '+target+' ',zuiopt)
        self.assertNotIn('(allow priv_app zuiopt_data_file',zuiopt)
        self.assertIn('(type zuiopt)',zuiopt)
        self.assertEqual(zuiopt.count('(typeattributeset mlstrustedsubject (zuiopt))'),1)
        self.assertNotIn('(typeattributeset mlsvendorcompat (zuiopt))',zuiopt)
        self.assertIn('(allow shell zuiopt_config_file (file (getattr open read)))',zuiopt)
        self.assertIn('(typetransition shell zuiopt_exec process zuiopt)',zuiopt)
        self.assertNotIn('(allow shell zuiopt_data_file',zuiopt)
        self.assertNotIn('(allow shell zuiopt (process (signal)))',zuiopt)
        self.assertNotIn('dac_override',zuiopt)
        self.assertNotIn('dac_read_search',zuiopt)
        self.assertIn('(allow zuiopt self (capability (sys_nice)))',zuiopt)

if __name__=='__main__': unittest.main(verbosity=2)
