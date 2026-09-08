"""Cross-check the production importer against the accepted schema-2 Python reference."""
from pathlib import Path
import io,json,subprocess,sys,tempfile,zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts/rules'))
from ZUIOPT_rule_pack import parse_rules,dump_rules,manifest_for,pack_bytes,unpack,appopt,merge

ROOT=Path(__file__).resolve().parents[2]
BASE=b'schema 2\nenabled true\nprofile G 2-6\npackage exact org.example.game G 100\n'
OPEN=b'schema 2\nenabled true\nprofile G 2-7\nthread G ALL glob "Job.worker[AB]*" selector=all 30 2-4\nthread G R1 prefix Top selector=rank:1 20 7\nthread G R2 contains Runner selector=rank:2 10 5-6\npackage exact org.example.game G 100\n'

def main(binary):
    checked=0
    with tempfile.TemporaryDirectory(prefix='zuiopt-parity-') as tmp:
        directory=Path(tmp)
        def call(command,*inputs,extra=(),ok=True):
            nonlocal checked
            paths=[]
            for index,data in enumerate(inputs):
                path=directory/str(index);path.write_bytes(data);paths.append(str(path))
            result=subprocess.run([binary,command,*paths,*extra],capture_output=True,timeout=15)
            assert (result.returncode==0)==ok,(command,result.stderr.decode(errors='replace'))
            checked+=1;return result.stdout
        factory=(ROOT/'payload/system/etc/zuiopt/factory_rules.conf').read_bytes()
        for rules in (BASE,OPEN,factory,OPEN.replace(b'Job.worker[AB]*',b'a?[0-9]*'),OPEN.replace(b'prefix Top',b'exact Top')):
            assert parse_rules(call('rules',rules))==parse_rules(rules)
        for bad in (b'',BASE.replace(b'2-6',b'0-8'),BASE.replace(b'schema 2',b'schema 1'),BASE+b'profile G 1\n',BASE+b'unknown true\n',OPEN.replace(b'selector=all',b'selector=rank:0'),OPEN.replace(b'selector=all',b'selector=rank:1025'),OPEN.replace(b' 20 7',b' 30 7'),OPEN.replace(b' R2 ',b' R1 ')):
            call('rules',bad,ok=False)
        for source in ('recovered_binary','appopt','zuiopt_native','user_export'):
            manifest=manifest_for(OPEN,'parity',source,binary_sha='a'*64,evidence='STATIC_RECOVERED')
            assert json.loads(call('pack',pack_bytes(manifest,OPEN)))==manifest
        for compression in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED):
            # Valid compressed and >64-KiB archives must share the same rules bound.
            rules=OPEN+b'# padding\n'*6400
            manifest=manifest_for(rules,'large')
            out=io.BytesIO()
            with zipfile.ZipFile(out,'w',compression=compression) as archive:
                archive.writestr('manifest.json',json.dumps(manifest));archive.writestr('rules.conf',rules)
            assert json.loads(call('pack',out.getvalue()))==manifest
        manifest=manifest_for(OPEN,'invalid')
        for key,value in (('schema_version',True),('pack_id','../bad'),('pack_priority',1000001),('target_soc','other'),('package_count',99),('rules_sha256','0'*64)):
            bad=dict(manifest);bad[key]=value;out=io.BytesIO()
            with zipfile.ZipFile(out,'w') as archive:
                archive.writestr('manifest.json',json.dumps(bad));archive.writestr('rules.conf',OPEN)
            call('pack',out.getvalue(),ok=False)
        for text in ('org.example.game=0-6\norg.example.game{Job.worker*}=7', 'org.b.game=2-6\norg.a.game=0-7\norg.a.game{Render?[a-z]}=4-6'):
            expected_m,expected_r=unpack(appopt(text,'converted',-5))
            actual_m,actual_r=unpack(call('appopt',text.encode(),extra=('converted','-5')))
            assert actual_r==expected_r
            # Only producer provenance changes from P1.1 to the V21 release adapter.
            expected_m['generated_by']='ZUIopt-V21'
            assert actual_m==expected_m
        for bad in ('org.example.game{X}=7','org.example.game=0-8','org.example.game=0-7\norg.example.game=2-6'):
            call('appopt',bad.encode(),extra=('converted','0'),ok=False)
        a=(manifest_for(OPEN,'a',priority=1),OPEN);b=(manifest_for(BASE,'b',priority=2),BASE)
        for factory,user,packs in ((BASE,b'',[]),(BASE,b'',[a]),(BASE,OPEN,[a,b]),(factory,BASE,[b,a])):
            actual=call('merge',factory,user,*[pack_bytes(*pack) for pack in packs])
            assert actual==merge(factory,packs,user)
        b[0]['pack_priority']=1
        call('merge',BASE,b'',pack_bytes(*a),pack_bytes(*b),ok=False)
    print(json.dumps(dict(status='PASS',native_reference_checks=checked,factory_profiles=27,factory_mappings=316)))

if __name__=='__main__': main(str(Path(sys.argv[1]).resolve()))
