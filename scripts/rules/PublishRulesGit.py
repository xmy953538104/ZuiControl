"""Publish only output/* to the dedicated distribution branch; source HEAD/index stay unchanged."""
from pathlib import Path
import argparse,json,os,subprocess,tempfile
from PublishRules import publish,latest,MAX_REVISION
from ZUIOPT_rule_pack import need,MAX_PACK,read_bounded

BRANCH='refs/heads/rules-published'
def release(repo,input_path,kind,source,version,date,upload=False):
    repo=Path(repo).resolve();input_path=(repo/input_path).resolve()
    need(input_path.is_relative_to(repo),'source outside checkout')
    def git(*args,env=None):
        return subprocess.run(['git',*args],cwd=repo,env=env,capture_output=True,check=True).stdout.decode().strip()
    head=git('rev-parse','HEAD');base='';previous=None;revision=1
    source_ref=head+':'+input_path.relative_to(repo).as_posix()
    need(int(git('cat-file','-s',source_ref))<=MAX_PACK,'committed source bound')
    need(subprocess.run(['git','show',source_ref],cwd=repo,capture_output=True,check=True).stdout==read_bounded(input_path,MAX_PACK),'source differs from committed provenance')
    lookup=subprocess.run(['git','ls-remote','--exit-code','--heads','origin',BRANCH],cwd=repo,capture_output=True)
    need(lookup.returncode in (0,2),'cannot inspect publication branch')
    with tempfile.TemporaryDirectory(prefix='rules-git-') as tmp:
        temporary=Path(tmp);previous=temporary/'latest.json'
        if lookup.returncode==0:
            git('fetch','--no-tags','origin',BRANCH);base=git('rev-parse','FETCH_HEAD')
            previous.write_bytes(git('show',base+':output/latest.json').encode())
            revision=latest(previous.read_bytes())['revision']+1
        need(revision<=MAX_REVISION,'revision exhausted')
        manifest=publish(input_path,repo/'output',kind,revision,source,version,date,head,previous)
        index=temporary/'index';env=dict(os.environ,GIT_INDEX_FILE=str(index))
        git('read-tree','--empty',env=env);git('add','--force','--','output/latest.json','output/rules.conf',env=env)
        tree=git('write-tree',env=env)
        need(git('ls-tree','-r','--name-only',tree).splitlines()==['output/latest.json','output/rules.conf'],'distribution tree ownership')
        args=['-c','user.name=github-actions[bot]','-c','user.email=41898282+github-actions[bot]@users.noreply.github.com',
              'commit-tree',tree,'-m','Publish Schema2 rules revision '+str(revision)]
        if base:args+=['-p',base]
        commit=git(*args)
        if upload:git('push','origin',commit+':'+BRANCH) # fast-forward only; external races fail closed
    need(git('rev-parse','HEAD')==head,'source HEAD changed')
    return dict(sourceHead=head,publicationParent=base,publicationCommit=commit,revision=manifest['revision'],published=upload,branch=BRANCH)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,default=Path.cwd());parser.add_argument('--input',type=Path,required=True)
    parser.add_argument('--kind',choices=('canonical','appopt','recovered-csv'),default='canonical')
    parser.add_argument('--source',required=True);parser.add_argument('--source-version',required=True);parser.add_argument('--source-date',required=True)
    parser.add_argument('--publish',action='store_true');args=parser.parse_args()
    print(json.dumps(release(args.repo,args.input,args.kind,args.source,args.source_version,args.source_date,args.publish),sort_keys=True))

if __name__=='__main__':main()
