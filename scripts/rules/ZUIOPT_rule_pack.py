"""P1.1 host rule manager/reference importer. Android engine consumes schema 2.

Only two ZIP members. Package profiles are complete override units, not partial
field inheritance. Activation is a single atomic effective.conf replacement;
its generation comment also selects the matching pack/user state.
Production authenticated Android transport/UI are a separate P2 gate.
"""
from pathlib import Path
from contextlib import contextmanager
import argparse, csv, hashlib, io, json, os, re, shlex, shutil, stat, tempfile, zipfile

MAX_RULES=65536
MAX_PACK=131072
MAX_PACKS=8
LABEL=re.compile(r'[A-Za-z0-9_.-]{1,128}\Z')
PACKAGE=re.compile(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\Z')
PACK_ID=re.compile(r'[a-z][a-z0-9_.-]{0,63}\Z')
VERSION=re.compile(r'[0-9]{1,6}(?:\.[0-9]{1,6}){0,3}(?:-[A-Za-z0-9.-]{1,32})?\Z')
GEN=re.compile(r'g[0-9a-f]{24}\Z')
FIELDS={'schema_version','pack_id','pack_version','pack_priority','source_type',
        'source_binary_sha256','source_binary_version','target_soc','target_topology',
        'package_count','profile_count','generated_by','evidence_level','rules_sha256'}

def need(ok,reason):
    if not ok: raise ValueError(reason)

def digest(data): return hashlib.sha256(data).hexdigest()

def read_bounded(path,limit):
    with Path(path).open('rb') as f: data=f.read(limit+1)
    need(len(data)<=limit,'input file size');return data

def mask(text):
    need(bool(re.fullmatch(r'[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*',text)),'CPU syntax')
    bits=0
    for part in text.split(','):
        ends=list(map(int,part.split('-')));a=ends[0];b=ends[-1]
        need(0<=a<=b<8,'SM8650 CPU bound')
        for c in range(a,b+1): bits|=1<<c
    return bits

def validate_glob(pattern):
    need(0<len(pattern)<=64 and all(32<=ord(c)<127 and c!='\\' for c in pattern),'glob characters/size')
    i=0
    while i<len(pattern):
        c=pattern[i]
        if c=='[':
            i+=1;count=0
            while i<len(pattern) and pattern[i]!=']':
                a=pattern[i];need(a not in '[\\!^','glob class')
                if i+1<len(pattern) and pattern[i+1]=='-':
                    need(i+2<len(pattern) and pattern[i+2] not in '][\\' and a<=pattern[i+2],'glob range')
                    i+=2
                count+=1;i+=1
            need(i<len(pattern) and count>0,'glob class closing')
        else: need(c!=']','unpaired glob close')
        i+=1

def parse_rules(data):
    if isinstance(data,str): data=data.encode('utf8')
    need(0<len(data)<=MAX_RULES and b'\0' not in data,'config size/NUL')
    text=data.decode('ascii');profiles={};packages=[];headers={}
    for line in text.splitlines():
        line=line.strip()
        if not line or line.startswith('#'): continue
        need(len(line)<1024,'line bound')
        t=shlex.split(line,comments=False,posix=True);k=t[0]
        if k in ('schema','enabled','debug'):
            need(len(t)==2 and k not in headers,'header syntax/duplicate')
            need(t[1] in (('2',) if k=='schema' else ('true','false')),'header value');headers[k]=t[1]
        elif k=='profile':
            need(len(t)==3 and LABEL.fullmatch(t[1]) and t[1] not in profiles,'profile syntax/duplicate')
            profiles[t[1]]={'mask':mask(t[2]),'rules':[]}
        elif k=='thread':
            need(len(t)==8 and t[1] in profiles and LABEL.fullmatch(t[2]),'thread syntax')
            _,name,group,kind,pattern,selector,priority,cpu=t
            need(kind in ('exact','prefix','contains','glob') and (pattern or kind=='contains') and len(pattern)<=64,'thread match')
            need(all(32<=ord(c)<127 for c in pattern),'thread characters')
            if kind=='glob': validate_glob(pattern)
            need(selector=='selector=all' or re.fullmatch(r'selector=rank:[0-9]+',selector),'explicit selector')
            rank=0 if selector=='selector=all' else int(selector.split(':')[1]);need(selector=='selector=all' or 1<=rank<=1024,'rank bound')
            need(re.fullmatch(r'-?[0-9]+',priority) is not None,'priority syntax');priority=int(priority);need(-2147483648<=priority<=2147483647,'priority bound')
            rule=(group,kind,pattern,rank,priority,mask(cpu))
            for old in profiles[name]['rules']:
                need(old[4]!=priority and (old[0]!=group or (old[3],old[5])==(rank,rule[5])),'thread priority/group conflict')
            profiles[name]['rules'].append(rule)
        elif k=='package':
            need(len(t)==5 and t[1] in ('exact','prefix','contains') and LABEL.fullmatch(t[2]) and t[3] in profiles,'package syntax')
            need(t[1]!='exact' or PACKAGE.fullmatch(t[2]),'package name')
            need(re.fullmatch(r'-?[0-9]+',t[4]) is not None,'package priority syntax');priority=int(t[4]);need(-2147483648<=priority<=2147483647,'priority bound')
            need(all(p[3]!=priority and p[:2]!=(t[1],t[2]) for p in packages),'package duplicate/priority conflict')
            packages.append((t[1],t[2],t[3],priority))
        else: raise ValueError('unknown directive '+k)
        need(len(profiles)<=64 and len(packages)<=512,'config bound')
    need(headers.get('schema')=='2' and 'enabled' in headers,'schema/enabled required')
    for profile in profiles.values():
        need(len(profile['rules'])<=32,'rule count');profile['rules'].sort(key=lambda r:-r[4])
    packages.sort(key=lambda p:-p[3])
    return dict(profiles=profiles,packages=packages,enabled=headers['enabled']=='true',debug=headers.get('debug')=='true')

def cpu_text(bits): return ','.join(str(n) for n in range(8) if bits&(1<<n))

def dump_rules(config):
    lines=['schema 2','enabled '+str(config['enabled']).lower(),'debug '+str(config.get('debug',False)).lower()]
    for name,p in sorted(config['profiles'].items()):
        lines.append('profile '+name+' '+cpu_text(p['mask']))
        for group,kind,pattern,rank,priority,bits in p['rules']:
            lines.append(f'thread {name} {group} {kind} {json.dumps(pattern)} selector={"rank:"+str(rank) if rank else "all"} {priority} {cpu_text(bits)}')
    for kind,package,name,priority in config['packages']: lines.append(f'package {kind} {package} {name} {priority}')
    data=('\n'.join(lines)+'\n').encode('ascii');parse_rules(data);return data

def matches(kind,pattern,value):
    return value==pattern if kind=='exact' else value.startswith(pattern) if kind=='prefix' else pattern in value

def overlaps(a,b):
    if a[0]=='exact': return matches(b[0],b[1],a[1])
    if b[0]=='exact': return matches(a[0],a[1],b[1])
    if a[0]==b[0]=='prefix': return a[1].startswith(b[1]) or b[1].startswith(a[1])
    # Contains/prefix languages may overlap for a future package: conservatively reject conflicts.
    return True

def merge(factory,packs,user):
    """Layer priority precedes mapping priority. A matched profile is a full package override."""
    configs=[(0,0,'factory',parse_rules(factory))]
    for manifest,rules in packs:
        config=parse_rules(rules);need(config['enabled'],'disabled pack header')
        configs.append((1,manifest['pack_priority'],manifest['pack_id'],config))
    if user:
        u=parse_rules(user);need(u['enabled'],'disabled user header');configs.append((2,0,'user',u))
    for i,(tier,priority,_,c) in enumerate(configs):
        if tier!=1: continue
        for tier2,priority2,_,d in configs[i+1:]:
            if tier2!=1 or priority!=priority2: continue
            for a in c['packages']:
                for b in d['packages']:
                    need(not overlaps(a,b) or c['profiles'][a[2]]==d['profiles'][b[2]],'same pack_priority overlapping conflict')
    result=dict(enabled=configs[0][3]['enabled'],debug=configs[0][3]['debug'],profiles={},packages=[])
    seen=set()
    for _,_,_,c in sorted(configs,key=lambda x:(-x[0],-x[1],x[2])):
        aliases={}
        for kind,package,profile,_ in c['packages']:
            if (kind,package) in seen: continue
            seen.add((kind,package))
            if profile not in aliases:
                alias=f'p{len(result["profiles"]):04d}';aliases[profile]=alias;result['profiles'][alias]=c['profiles'][profile]
            result['packages'].append((kind,package,aliases[profile],100000-len(result['packages'])))
    return dump_rules(result)

def strict_json(data):
    def pairs(items):
        out={}
        for key,value in items:
            need(key not in out,'duplicate JSON key');out[key]=value
        return out
    return json.loads(data,object_pairs_hook=pairs)

def validate_manifest(manifest,rules):
    need(type(manifest) is dict and set(manifest)==FIELDS,'manifest field set')
    for key in FIELDS-{'schema_version','pack_priority','package_count','profile_count'}:
        need(type(manifest[key]) is str and len(manifest[key])<=128 and all(32<=ord(c)<127 for c in manifest[key]),'manifest text '+key)
    for key in ('schema_version','pack_priority','package_count','profile_count'):
        need(type(manifest[key]) is int,'manifest integer '+key)
    need(manifest['schema_version']==1 and PACK_ID.fullmatch(manifest['pack_id']) and VERSION.fullmatch(manifest['pack_version']),'pack identity/version')
    need(-1000000<=manifest['pack_priority']<=1000000,'pack priority')
    need(manifest['source_type'] in ('asoul_binary','appopt','zuiopt_native','user_export'),'source type')
    need(manifest['source_binary_sha256']=='' or re.fullmatch('[0-9a-f]{64}',manifest['source_binary_sha256']),'binary hash')
    need(manifest['evidence_level'] in ('STATIC_RECOVERED','USER_DECLARED'),'evidence level')
    if manifest['source_type']=='asoul_binary':
        need(manifest['source_binary_sha256']!='' and manifest['evidence_level']=='STATIC_RECOVERED','asoul provenance')
    need(manifest['target_soc']=='SM8650' and manifest['target_topology']=='0-7','target topology')
    need(manifest['rules_sha256']==digest(rules),'rules digest')
    c=parse_rules(rules);need(c['enabled'],'pack must be enabled internally')
    need(manifest['package_count']==len(c['packages']) and manifest['profile_count']==len(c['profiles']),'manifest count mismatch')
    return manifest

def manifest_for(rules,pack_id,source_type='zuiopt_native',priority=0,version='1',binary_sha='',binary_version='not-applicable',evidence='USER_DECLARED'):
    c=parse_rules(rules)
    m=dict(schema_version=1,pack_id=pack_id,pack_version=version,pack_priority=priority,source_type=source_type,
           source_binary_sha256=binary_sha,source_binary_version=binary_version,target_soc='SM8650',target_topology='0-7',
           package_count=len(c['packages']),profile_count=len(c['profiles']),generated_by='ZUIopt-P1.1',evidence_level=evidence,rules_sha256=digest(rules))
    return validate_manifest(m,rules)

def pack_bytes(manifest,rules):
    validate_manifest(manifest,rules);out=io.BytesIO()
    with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_STORED) as z:
        for name,data in [('manifest.json',json.dumps(manifest,sort_keys=True,separators=(',',':')).encode('ascii')),('rules.conf',rules)]:
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.external_attr=(stat.S_IFREG|0o600)<<16;z.writestr(info,data)
    need(len(out.getvalue())<=MAX_PACK,'pack size');return out.getvalue()

def unpack(data):
    need(0<len(data)<=MAX_PACK,'archive size')
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        infos=z.infolist();need(len(infos)==2 and sorted(i.filename for i in infos)==['manifest.json','rules.conf'],'exact members (no traversal, extra or duplicate)')
        files={}
        for i in infos:
            need(not i.flag_bits&1 and i.compress_type in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED),'encryption/compression')
            need(stat.S_IFMT(i.external_attr>>16) in (0,stat.S_IFREG),'non-regular member')
            limit=8192 if i.filename=='manifest.json' else MAX_RULES;need(0<i.file_size<=limit,'member size')
            with z.open(i) as f: content=f.read(limit+1)
            need(len(content)==i.file_size<=limit,'decompressed size');files[i.filename]=content
    rules=files['rules.conf'];manifest=validate_manifest(strict_json(files['manifest.json']),rules);return manifest,rules

def appopt(text,pack_id,priority=0):
    need(0<len(text.encode('utf8'))<=MAX_RULES and '\0' not in text,'AppOpt size/NUL')
    packages={}
    for line in text.splitlines():
        line=line.strip()
        if not line or line.startswith('#'): continue
        m=re.fullmatch(r'([^{}=\s]+)(?:\{([^{}\r\n]+)\})?=([0-9,-]+)',line)
        need(m is not None,'unsupported AppOpt line: '+line[:100]);package,pattern,cpu=m.groups()
        need(PACKAGE.fullmatch(package) is not None and len(package)<=128,'AppOpt package')
        p=packages.setdefault(package,dict(mask=None,rules=[]));bits=mask(cpu)
        if pattern is None: need(p['mask'] is None,'duplicate package default');p['mask']=bits
        else:
            validate_glob(pattern);need(not any(r[2]==pattern for r in p['rules']),'duplicate AppOpt thread')
            p['rules'].append((f'r{len(p["rules"])}','glob',pattern,0,1000-len(p['rules']),bits))
        need(len(packages)<=64 and len(p['rules'])<=32,'AppOpt bound')
    need(bool(packages) and all(p['mask'] is not None for p in packages.values()),'explicit package default required')
    c=dict(enabled=True,debug=False,profiles={},packages=[])
    for n,(package,p) in enumerate(sorted(packages.items())):
        name=f'A{n:03d}';c['profiles'][name]=p;c['packages'].append(('exact',package,name,10000-n))
    rules=dump_rules(c);return pack_bytes(manifest_for(rules,pack_id,'appopt',priority),rules)

def durable(path,data):
    with open(path,'xb') as f: f.write(data);f.flush();os.fsync(f.fileno())

def sync_dir(path):
    if os.name!='nt':
        fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)

class Store:
    """P1.1 host reference transaction store, never installs production payloads."""
    def __init__(self,root,factory):
        self.root=Path(root);self.factory=bytes(factory);parse_rules(self.factory)
        self.root.mkdir(parents=True,exist_ok=True);need(not self.root.is_symlink(),'store symlink')
        (self.root/'generations').mkdir(exist_ok=True)

    @contextmanager
    def locked(self):
        with (self.root/'manager.lock').open('a+b') as f:
            if os.name=='nt':
                import msvcrt
                f.seek(0);f.write(b'0');f.flush();f.seek(0);msvcrt.locking(f.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
            try: yield
            finally:
                if os.name=='nt': f.seek(0);msvcrt.locking(f.fileno(),msvcrt.LK_UNLCK,1)
                else: fcntl.flock(f,fcntl.LOCK_UN)

    def current(self):
        path=self.root/'effective.conf'
        if not path.exists(): return dict(packs={},enabled=[],user=b'schema 2\nenabled true\n'),b''
        data=read_bounded(path,MAX_RULES);parse_rules(data);generation=data.splitlines()[0].decode().removeprefix('# ZUIOPT_GENERATION ')
        need(GEN.fullmatch(generation) is not None,'generation reference')
        d=self.root/'generations'/generation;need(read_bounded(d/'effective.conf',MAX_RULES)==data,'generation content mismatch')
        s=strict_json(read_bounded(d/'pack_state.conf',8192));need(s['factory_sha256']==digest(self.factory),'factory changed: explicit migration required')
        need(type(s['packs']) is list and type(s['enabled']) is list and len(s['packs'])<=MAX_PACKS and len(set(s['packs']))==len(s['packs']) and set(s['enabled'])<=set(s['packs']),'stored pack list')
        packs={}
        for pack_id in s['packs']:
            need(PACK_ID.fullmatch(pack_id) is not None,'stored pack ID');payload=read_bounded(d/'imported_packs'/f'{pack_id}.zip',MAX_PACK);manifest,_=unpack(payload)
            need(manifest['pack_id']==pack_id,'stored pack identity');packs[pack_id]=payload
        return dict(packs=packs,enabled=s['enabled'],user=read_bounded(d/'user_rules.conf',MAX_RULES)),data

    def apply(self,action,payload=None,pack_id=None):
        with self.locked():
            state,previous=self.current()
            if action=='import':
                m,_=unpack(payload);state['packs'][m['pack_id']]=payload;need(len(state['packs'])<=MAX_PACKS,'pack count')
            elif action in ('enable','disable'):
                need(pack_id in state['packs'],'unknown pack')
                state['enabled']=sorted((set(state['enabled'])|{pack_id}) if action=='enable' else (set(state['enabled'])-{pack_id}))
            elif action=='user': parse_rules(payload);state['user']=payload
            elif action=='rollback':
                need(bool(previous),'no previous generation');g=previous.splitlines()[0].decode().split()[-1]
                old=read_bounded(self.root/'generations'/g/'last_good.conf',MAX_RULES);need(bool(old),'no last good');parse_rules(old)
                oldg=old.splitlines()[0].decode().split()[-1];need(GEN.fullmatch(oldg) and read_bounded(self.root/'generations'/oldg/'effective.conf',MAX_RULES)==old,'last good missing')
                self.activate(old);return old
            else: need(action=='init','unknown store action')
            selected=[unpack(state['packs'][i]) for i in sorted(state['enabled'])]
            effective=merge(self.factory,selected,state['user'])
            # Build an immutable generation completely before publishing one commit record.
            generation='g'+os.urandom(12).hex();d=self.root/'generations'/generation;d.mkdir(mode=0o700)
            try:
                (d/'imported_packs').mkdir(mode=0o700)
                for i,data in sorted(state['packs'].items()): durable(d/'imported_packs'/f'{i}.zip',data)
                sync_dir(d/'imported_packs')
                durable(d/'user_rules.conf',state['user']);durable(d/'last_good.conf',previous)
                durable(d/'pack_state.conf',json.dumps(dict(packs=sorted(state['packs']),enabled=sorted(state['enabled']),factory_sha256=digest(self.factory)),sort_keys=True).encode())
                effective=f'# ZUIOPT_GENERATION {generation}\n'.encode()+effective;parse_rules(effective);durable(d/'effective.conf',effective);sync_dir(d);sync_dir(d.parent)
                self.activate(effective)
            except BaseException:
                # This directory was created exclusively here; never touches accepted evidence.
                if not (self.root/'effective.conf').exists() or (self.root/'effective.conf').read_bytes()!=effective: shutil.rmtree(d)
                raise
            self.prune(generation,previous);return effective

    def activate(self,data):
        fd,path=tempfile.mkstemp(prefix='effective-',suffix='.tmp',dir=self.root)
        try:
            with os.fdopen(fd,'wb') as f: f.write(data);f.flush();os.fsync(f.fileno())
            os.replace(path,self.root/'effective.conf');sync_dir(self.root)
        finally:
            if os.path.exists(path): os.unlink(path)

    def prune(self,current,previous):
        keep={current}
        if previous: keep.add(previous.splitlines()[0].decode().split()[-1])
        root=(self.root/'generations').resolve()
        for p in root.iterdir():
            if GEN.fullmatch(p.name) and p.name not in keep:
                need(not p.is_symlink() and p.resolve().parent==root,'unsafe generation cleanup');shutil.rmtree(p)

def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    a=sub.add_parser('appopt');a.add_argument('input',type=Path);a.add_argument('output',type=Path);a.add_argument('--pack-id',required=True);a.add_argument('--priority',type=int,default=0)
    a=sub.add_parser('check');a.add_argument('input',type=Path)
    a=sub.add_parser('store');a.add_argument('root',type=Path);a.add_argument('factory',type=Path);a.add_argument('action',choices=['init','import','enable','disable','user','rollback']);a.add_argument('--input',type=Path);a.add_argument('--pack-id')
    args=p.parse_args()
    if args.command=='appopt': args.output.write_bytes(appopt(read_bounded(args.input,MAX_RULES).decode('ascii'),args.pack_id,args.priority))
    elif args.command=='check': print(json.dumps(unpack(read_bounded(args.input,MAX_PACK))[0],sort_keys=True))
    else:
        data=Store(args.root,read_bounded(args.factory,MAX_RULES)).apply(args.action,read_bounded(args.input,MAX_PACK if args.action=='import' else MAX_RULES) if args.input else None,args.pack_id)
        print('EFFECTIVE_SHA256='+digest(data))

if __name__=='__main__': main()
