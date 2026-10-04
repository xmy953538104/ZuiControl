"""Publisher determinism, bounds, monotonic mirror comparison and native Schema2 parity."""
from pathlib import Path
import json,subprocess,sys,tempfile
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts/rules'))
from PublishRules import build,publish,latest,compare,verify,MAX_REVISION
from ZUIOPT_rule_pack import normalize_source,parse_rules
from PublishRulesGit import release

def rejects(call):
    try:call()
    except (ValueError,UnicodeError):return
    raise AssertionError('invalid publisher input accepted')

def main(native=None):
    factory=(ROOT/'payload/system/etc/zuiopt/factory_rules.conf').read_bytes();original=factory
    args=('canonical',8,'zuiopt-upstream','0.84','2026-10-04','a'*40)
    metadata,rules=build(factory,*args);manifest=latest(metadata)
    assert rules==normalize_source(factory,'canonical') and len(metadata)<=2048
    assert build(factory,*args)==(metadata,rules)
    assert compare(manifest,manifest)=='SAME'
    old=dict(manifest,revision=7,rulesSha256='0'*64)
    assert compare(old,manifest)=='STALE' and compare(manifest,old)=='NEWER'
    rejects(lambda:compare(dict(manifest,rulesSha256='0'*64),manifest))
    for key,value in (('schema',True),('revision',True),('revision',0),('revision',MAX_REVISION+1),('rulesSize',65537),
                      ('rulesSchema',1),('targetSoC','OTHER'),('targetTopology','0-8'),('source','x'*129),
                      ('sourceVersion','bad version'),('sourceDate','2026-02-30'),('sourceCommit','a'*41)):
        rejects(lambda:latest(json.dumps(dict(manifest,**{key:value})).encode()))
    rejects(lambda:latest(metadata.replace(b'"schema":1',b'"schema":1,"schema":1')))
    rejects(lambda:latest(metadata+b'x'));rejects(lambda:verify(manifest,rules+b'x'))
    rejects(lambda:build(factory.replace(b'schema 2',b'schema 1'),*args))
    rejects(lambda:build(factory.replace(b'package exact',b'package prefix',1),*args))
    with tempfile.TemporaryDirectory(prefix='rules-publisher-') as tmp:
        root=Path(tmp);source=root/'input.conf';source.write_bytes(factory)
        first=root/'a/output';second=root/'b/output'
        for output in (first,second):publish(source,output,*args)
        assert [(first/n).read_bytes() for n in ('latest.json','rules.conf')]==[(second/n).read_bytes() for n in ('latest.json','rules.conf')]
        publish(source,first,*args);before=[(first/n).read_bytes() for n in ('latest.json','rules.conf')]
        rejects(lambda:publish(source,first,'canonical',7,*args[2:]));assert before==[(first/n).read_bytes() for n in ('latest.json','rules.conf')]
        rejects(lambda:publish(first/'rules.conf',first,*args))
        assert source.read_bytes()==original
        if native:
            result=subprocess.run([native,'rules',str(first/'rules.conf')],capture_output=True,check=True,timeout=15)
            assert parse_rules(result.stdout)==parse_rules(rules)
    assert (ROOT/'payload/system/etc/zuiopt/factory_rules.conf').read_bytes()==original
    with tempfile.TemporaryDirectory(prefix='rules-git-fixture-') as tmp:
        folder=Path(tmp);repo=folder/'source';remote=folder/'remote.git';repo.mkdir()
        def git(*args):return subprocess.run(['git',*args],cwd=repo,capture_output=True,check=True).stdout.decode().strip()
        subprocess.run(['git','init','--bare',str(remote)],capture_output=True,check=True)
        git('init');git('config','user.name','Fixture');git('config','user.email','fixture@example.invalid')
        (repo/'input.conf').write_bytes(factory);git('add','input.conf');git('commit','-m','Fixture source')
        git('remote','add','origin',str(remote));head=git('rev-parse','HEAD')
        for revision in (1,2):
            receipt=release(repo,Path('input.conf'),'canonical','zuiopt-upstream','0.84','2026-10-04',True)
            assert receipt['revision']==revision and receipt['sourceHead']==head and receipt['published']
            assert git('ls-tree','-r','--name-only',receipt['publicationCommit']).splitlines()==['output/latest.json','output/rules.conf']
        accepted=git('ls-remote','origin','refs/heads/rules-published')
        draft=release(repo,Path('input.conf'),'canonical','zuiopt-upstream','0.84','2026-10-04',False)
        assert draft['revision']==3 and not draft['published'] and git('ls-remote','origin','refs/heads/rules-published')==accepted
        assert git('rev-parse','HEAD')==head and not git('diff','--cached') and (repo/'input.conf').read_bytes()==factory
        (repo/'input.conf').write_bytes(factory+b'# modified\n')
        rejects(lambda:release(repo,Path('input.conf'),'canonical','zuiopt-upstream','0.84','2026-10-04',True))
    print('RULE_PUBLISHER=PASS deterministic/strict-bounds/hash-size-platform/monotonic/stale-mirror/no-input-write native='+('PASS' if native else 'NOT_RUN'))

if __name__=='__main__':main(str(Path(sys.argv[1]).resolve()) if len(sys.argv)>1 else None)
