"""Execute production owner/payload validation and real durable policy recovery.

V74_POLICY_FIXTURE optionally supplies a read-only device policy directory; device
bytes stay outside Git. CI independently constructs the same interrupted phase.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'framework_patch/src/services/com/zui/server/control'


class ProjectionModeTests(unittest.TestCase):
    def test_finite_startup_observer(self):
        source = (BASE / 'UperfImportCommand.java').read_text(encoding='utf8')
        method = source[source.index('    private static boolean awaitStartup('):source.index('    private static boolean restartReady(')]
        # Advance only the clock/wait boundary; execute the production decision loop.
        method = method.replace('Thread.sleep(100);', 'SystemClock.now += 100;')
        harness = '''public class StartupObserverFixture {
          static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
          static int scenario;
          static class SystemProperties {static String get(String key,String fallback){
            if(key.equals("zui_control.scheduler"))return SystemClock.now<200?"prepared":"restarted";
            if(key.equals("sys.zui_control.uperf_fail_safe"))return scenario==1||SystemClock.now<200?"1":"0";
            if(key.equals("init.svc.zui_uperf"))return scenario==2?"stopped":"running";
            throw new AssertionError(key);
          }}
          static class Fileset {Fileset root=this;byte[] read(String n){return scenario==3?new byte[0]:new byte[]{1};}}
          METHOD
          public static void main(String[] args)throws Exception{
            for(scenario=0;scenario<4;scenario++){
              SystemClock.now=0;boolean ready=awaitStartup(new Fileset());
              if(ready!=(scenario==0)||SystemClock.now>35000)throw new AssertionError("startup "+scenario);
              if(scenario==0&&SystemClock.now!=200)throw new AssertionError("stale failsafe before init start");
            }
            System.out.println("FINITE_STARTUP_OBSERVER_PASS");
          }
        }'''.replace('METHOD', method)
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / 'StartupObserverFixture.java'
            fixture.write_text(harness)
            subprocess.run(['javac', '-d', tmp, str(fixture)], check=True)
            subprocess.run(['java', '-cp', tmp, 'StartupObserverFixture'], check=True)
        self.assertLess(source.index('boolean startupReady = observing && awaitStartup(files)'), source.index('FileLock lock = channel.tryLock()'))
        self.assertIn('integer(selection.get("generation")) == observedGeneration', source)
        self.assertIn('store.complete(observedGeneration, startupReady)', source)
        self.assertIn('if (!startupReady) files.root.write("uperf.json", store.startup())', source)

    def test_owner_projection_and_recovery(self):
        source = (BASE / 'PolicyCommand.java').read_text(encoding='utf8')
        owner = source[source.index('    static AppPolicyStore.Owner owner('):source.index('    static Map<String,Object> snapshot(')]
        validation = source[source.index('    static String projectionMode('):source.index('    private static final class Projection')]
        harness = r'''
package com.zui.server.control;
import java.util.*;
import java.nio.file.*;
import static com.zui.server.control.PolicyJson.*;
public final class ProjectionModeFixture {
    static int checks, writes; static String mode="balance";
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
    interface Work {void run()throws Exception;}
    static void rejects(Work w)throws Exception{
        int before=writes;
        try{w.run();}catch(IllegalArgumentException|IllegalStateException e){check(writes==before,"invalid payload wrote state");return;}
        throw new AssertionError("invalid payload accepted");
    }
    static class IBinder { Map<String,byte[]> stages=new TreeMap<>();byte[] active;Map<String,Object> applied; }
    static String callback(IBinder remote,String action,String text)throws Exception{
        Map<String,Object> r=object(parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String tx=string(r.get("transaction"));
        if(action.equals("prepare")){remote.stages.put(tx,bytes(r.get("policy")));return hash(remote.stages.get(tx));}
        byte[] prepared=remote.stages.getOrDefault(tx,new byte[0]);
        String received=projectionMode(r,prepared);
        check(received.equals(mode),"system owner mode transmitted");writes++;
        remote.active=prepared.clone();remote.applied=r;return hash(prepared);
    }
    OWNER
    VALIDATION
    static final class Disk implements AppPolicyStore.Storage {
        final Map<String,byte[]> files=new TreeMap<>();
        public byte[] read(String n){return files.getOrDefault(n,new byte[0]).clone();}
        public void write(String n,byte[] b){files.put(n,b.clone());}
    }
    public static void main(String[] args)throws Exception{
        Map<Integer,Long> users=new TreeMap<>();users.put(0,0L);
        byte[] profiles="version=1\ngpuGlobal|0|performance|834|903\n".getBytes(),saved="balance\n".getBytes(),perapp="org.example.game fast\n".getBytes();
        AppPolicyStore.Migration migration=AppPolicyStore.migrate(profiles,saved,perapp,"balance","org.example.game|fast",users,Collections.emptySet());
        Disk fresh=new Disk();IBinder remote=new IBinder();AppPolicyStore store=new AppPolicyStore(fresh);
        AppPolicyStore.Owner trusted=owner(remote,s->mode);
        store.migrate(migration,profiles,saved,perapp,trusted);
        check(store.current.generation==1&&!store.recoveryRequired,"fresh migration");
        byte[] initial=store.current.bytes();String tx="12345678-1234-1234-1234-123456789012";
        for(String valid:AppPolicyStore.MODES){
            mode=valid;trusted.prepare(store.current,tx);trusted.apply(store.current,tx);
            check(remote.applied.get("desiredMode").equals(valid),"four modes");
            check(Arrays.equals(initial,store.current.bytes())&&store.current.global(0).uperfMode.equals("balance"),"effective mode never edits saved global");
        }
        mode="balance";
        for(String key:new String[]{"transaction","hash","generation","desiredMode"}){
            Map<String,Object> r=new TreeMap<>(remote.applied);
            r.put(key,key.equals("generation")?99L:key.equals("transaction")?"87654321-4321-4321-4321-210987654321":"invalid");
            rejects(()->callback(remote,"apply",encode(r)));
            Map<String,Object> missing=new TreeMap<>(remote.applied);missing.remove(key);
            rejects(()->callback(remote,"apply",encode(missing)));
        }
        for(Object invalid:new Object[]{"", "BALANCE", "balance\n", 0L, null}){
            Map<String,Object> r=new TreeMap<>(remote.applied);r.put("desiredMode",invalid);
            rejects(()->callback(remote,"apply",encode(r)));
        }
        // Real first migration interruption: durable ACTIVE + COMMIT_INTENT, no applied ACK.
        Disk interrupted=new Disk();AppPolicyStore attempt=new AppPolicyStore(interrupted);
        try{attempt.migrate(migration,profiles,saved,perapp,owner(new IBinder(),s->{throw new IllegalArgumentException("injected V74 owner failure");}));throw new AssertionError("missing interruption");}
        catch(IllegalArgumentException expected){}
        check(object(parse(interrupted.read(AppPolicyStore.JOURNAL))).get("phase").equals("COMMIT_INTENT"),"exact interrupted phase");
        recover(interrupted);
        if(args.length!=0){
            Disk captured=new Disk();try(java.util.stream.Stream<Path> entries=Files.list(Path.of(args[0]))){
                for(Path p:(Iterable<Path>)entries.filter(Files::isRegularFile)::iterator)captured.files.put(p.getFileName().toString(),Files.readAllBytes(p));
            }
            check(object(parse(captured.read(AppPolicyStore.JOURNAL))).get("phase").equals("COMMIT_INTENT"),"captured V74 phase");
            recover(captured);System.out.println("EXACT_DEVICE_COMMIT_INTENT_RECOVERY=PASS");
        }
        System.out.println("PROJECTION_MODE_FRESH_RECOVERY_PASS checks="+checks);
    }
    static void recover(Disk disk)throws Exception{
        byte[] before=disk.read(AppPolicyStore.ACTIVE);AppPolicyStore store=new AppPolicyStore(disk);
        Map<String,String> retained=new TreeMap<>();for(Map.Entry<String,byte[]> e:disk.files.entrySet())retained.put(e.getKey(),hash(e.getValue()));
        IBinder remote=new IBinder();store.recover(owner(remote,s->mode));
        check(!store.recoveryRequired&&store.current.generation==1,"recover same generation");
        check(Arrays.equals(before,store.current.bytes())&&Arrays.equals(before,remote.active),"policy/projection exact recovery");
        check(object(parse(disk.read(AppPolicyStore.JOURNAL))).get("phase").equals("APPLIED"),"terminal applied");
        for(Map.Entry<String,String> e:retained.entrySet())if(!e.getKey().equals(AppPolicyStore.JOURNAL))check(e.getValue().equals(hash(disk.read(e.getKey()))),"retained bytes "+e.getKey());
        int count=writes;store.recover(owner(remote,s->mode));check(writes==count,"terminal recovery idempotent");
    }
}
'''.replace('OWNER', owner).replace('VALIDATION', validation)
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / 'ProjectionModeFixture.java'
            fixture.write_text(harness, encoding='utf8')
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', tmp,
                *[str(BASE / n) for n in ('PolicyJson.java', 'GpuRange.java', 'AppPolicyStore.java')], str(fixture)], check=True)
            command = ['java', '-cp', tmp, 'com.zui.server.control.ProjectionModeFixture']
            if os.environ.get('V74_POLICY_FIXTURE'):
                command.append(os.environ['V74_POLICY_FIXTURE'])
            subprocess.run(command, check=True)

    def test_domain_and_write_order(self):
        policy = (ROOT / 'payload/patches/plat_sepolicy_zui_control.cil').read_text()
        self.assertNotIn('(allow shell zui_control_uperf_mode_prop', policy)
        source = (BASE / 'PolicyCommand.java').read_text()
        self.assertNotIn('SystemProperties', source)
        projection = source[source.index('private static final class Projection'):]
        self.assertLess(projection.index('projectionMode(request, prepared)'), projection.index('Os.open(mode.getPath()'))
        self.assertLess(projection.index('Os.fsync(fd)'), projection.index('"Uperf runtime file ACK"'))
        self.assertLess(projection.index('"Uperf runtime file ACK"'), projection.index('disk.write("active.json"'))
        self.assertIn('"projection generation CAS"', projection)
        service = (BASE / 'ZuiControlService.java').read_text()
        runtime = service[service.index('private String applyUnifiedPolicy('):service.index('private void publishPolicySettings(')]
        self.assertLess(runtime.index('"Uperf runtime property ACK"'), runtime.index('return mUperfScenePolicy.mDesiredMode'))
        native = (ROOT / 'native/zui_uperf_supervisor.c').read_text()
        self.assertNotIn('app_process', native)
        self.assertNotIn('PolicyCommand', native)


if __name__ == '__main__':
    unittest.main()
