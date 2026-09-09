"""Separate host upgrade utility. Default inventory only; no EDL/flash implementation.

No private RuleStore deletion, direct product command invocation or forced kill.
Any exception after product stop attempts same-boot rollback from the host backup.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

SERIAL='HA25HSZM'
LINK='/data/vendor/asopt.conf'
DIRECTORY='/data/vendor/zui_control/asoul'
SELECTOR='/data/vendor/zui_control/zuiopt/next_owner.v1'
CONFIG_SHA='69a73f9bedb3a5f3e07d8f74d3ab9d18f8ab97ff48e02c74a378333fa3b1b75e'
TARGETS=[LINK,DIRECTORY,DIRECTORY+'/asopt.conf',DIRECTORY+'/asopt.conf.tmp',SELECTOR]
CPUSET='/dev/cpuset/asopt'
RELEASE_TIMEOUT=15
# One find of the retired hierarchy, shell-builtin reads; no process/task scan.
CPUSET_READ='''
test ! -L /dev/cpuset && test -d /dev/cpuset
test ! -L /dev/cpuset/asopt
if [ ! -e /dev/cpuset/asopt ]; then
 printf 'HIERARCHY=ABSENT\\n'
else
 test -d /dev/cpuset/asopt
 dirs=$(find /dev/cpuset/asopt -type d) || exit 21
 test -n "$dirs"
 for dir in $dirs; do
  test ! -L "$dir" && test ! -L "$dir/tasks" && test -f "$dir/tasks" && test -r "$dir/tasks"
  printf 'TASK_FILE=%s/tasks\\n' "$dir"
  while IFS= read -r tid || [ -n "$tid" ]; do
   case "$tid" in ''|*[!0-9]*) exit 22 ;; esac
   printf 'TID=%s\\n' "$tid"
  done < "$dir/tasks"
 done
fi
'''
ANCESTORS={'/data':'1000:1000:771','/data/vendor':'0:0:771','/data/vendor/zui_control':'0:0:755','/data/vendor/zui_control/zuiopt':'0:0:700'}
PAYLOAD={
 '/system/bin/ZUIopt':'eef525863ff51a55575457f0e2913898156e8ce9dc6717842bc9875c58e24d8c',
 '/system/bin/zui_controld':'5dcef0ca38a438aaf1e8e431be3ef2a93902bc3e551245a5aeb0b20715538e2d',
 '/system/framework/services.jar':'245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000',
 '/system/framework/framework.jar':'b5f57d62546569b9bd9ba34d8757678da000c33f876358dd93a4dc747b8f1b32',
 '/system/priv-app/ZuiControlV51/ZuiControl.apk':'b3bda94422fbba785c77bbdd9b9fd2a9bfd742c1cb3a70fc28dd27b98d3a287b'}

def need(condition,reason):
    if not condition:raise RuntimeError(reason)
def digest(data):return hashlib.sha256(data).hexdigest()
def q(value):return shlex.quote(str(value))
def persist(path,value):
    data=(json.dumps(value,ensure_ascii=False,sort_keys=True,indent=2)+'\n').encode()
    with path.open('xb') as f:f.write(data);f.flush();os.fsync(f.fileno())

def parse_tasks(text):
    files=[];tids=[];absent=False
    for line in text.splitlines():
        if line=='HIERARCHY=ABSENT':absent=True
        elif line.startswith('TASK_FILE='):
            path=line.split('=',1)[1]
            need(re.fullmatch(r'/dev/cpuset/asopt(?:/[A-Za-z0-9_.-]+)*/tasks',path) is not None and '/..' not in path,'unexpected task file')
            files.append(path)
        elif line.startswith('TID='):
            need(files and re.fullmatch(r'[1-9][0-9]*',line[4:]) is not None,'invalid task ID')
            tids.append(int(line[4:]))
        elif line.startswith(('SURVIVOR=','EXITED=')):continue
        else:need(False,'unexpected cgroup output')
    need((absent and not files and not tids) or (not absent and CPUSET+'/tasks' in files),'incomplete hierarchy proof')
    need(len(files)==len(set(files)),'duplicate task file')
    return sorted(set(tids))

def ui_bounds(node):
    m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
    need(m is not None,'button bounds')
    x1,y1,x2,y2=map(int,m.groups());need(x2>x1 and y2>y1,'positive bounds')
    return x1,y1,x2,y2

def assert_ui_package(root):
    need(any(n.get('package')=='com.zui.zuicontrol' for n in root.iter('node')),'UI_NAVIGATION_FAIL: wrong package')

def ui_button(root,label,optional=False):
    nodes=[n for n in root.iter('node') if n.get('text')==label]
    if not nodes and optional:return None
    need(len(nodes)==1,'exact unique UI label required')
    node=nodes[0];parents={c:p for p in root.iter() for c in p}
    need(node.get('package')=='com.zui.zuicontrol','UI target wrong package')
    while node.get('clickable')!='true' and node in parents:node=parents[node]
    need(node.get('package')=='com.zui.zuicontrol' and node.get('clickable')=='true' and node.get('enabled')=='true','button not enabled')
    x1,y1,x2,y2=ui_bounds(node)
    return (x1+x2)//2,(y1+y2)//2

def validate(rows):
    need(set(rows)==set(TARGETS),'exact inventory target set')
    for name,row in rows.items():
        if row is None:continue
        if name==LINK:
            need(row['type']=='symbolic link' and row['target']==DIRECTORY+'/asopt.conf' and (row['uid'],row['gid'],row['mode'])==(0,0,'777'),'unexpected link identity')
        elif name==DIRECTORY:
            need(row['type']=='directory' and (row['uid'],row['gid'],row['mode'])==(0,2000,'775'),'unexpected directory identity')
            need(set(row['children']) <= {'asopt.conf','asopt.conf.tmp'},'unknown directory child')
        else:
            need(row['type'] in ('regular file','regular empty file') and row['nlink']==1,'unsafe file type/link count')
            if name==SELECTOR:
                need((row['uid'],row['gid'],row['mode'])==(0,0,'600') and row['size']<=128,'selector identity')
                data=base64.b64decode(row['data'],validate=True)
                import zlib
                m=re.fullmatch(rb'ZUIOPT_NEXT_OWNER_V1 ([0-9]+)\n(ASOULOPT|ZUIOPT)\n',data)
                need(m is not None and int(m[1])==zlib.crc32(m[2]+b'\n'),'selector CRC/type')
            else:
                need((row['uid'],row['gid'],row['mode'],row['size'],row['sha256'])==(0,2000,'644',103,CONFIG_SHA),'unexpected config identity/bytes')
            need(digest(base64.b64decode(row['data'],validate=True))==row['sha256'],'backup data SHA')
    if rows[DIRECTORY] is None:
        need(rows[DIRECTORY+'/asopt.conf'] is None and rows[DIRECTORY+'/asopt.conf.tmp'] is None,'orphan child')
    return rows

class Device:
    def __init__(self,adb,transaction):self.adb=str(adb);self.transaction=transaction;self.sequence=0
    def call(self,args,timeout=60):
        self.sequence+=1
        argv=[self.adb,'-s',SERIAL,*args];started=time.monotonic()
        stem=self.transaction/('%05d-command'%self.sequence)
        info=dict(sequence=self.sequence,argv=argv,timeout=timeout,host_monotonic_start=started)
        persist(Path(str(stem)+'-start.json'),info)
        try:result=subprocess.run(argv,capture_output=True,timeout=timeout)
        except subprocess.TimeoutExpired as error:
            persist(Path(str(stem)+'-timeout.json'),dict(info,elapsed=time.monotonic()-started,
                partial_stdout_b64=base64.b64encode(error.stdout or b'').decode(),
                partial_stderr_b64=base64.b64encode(error.stderr or b'').decode()))
            raise
        except OSError as error:
            persist(Path(str(stem)+'-error.json'),dict(info,elapsed=time.monotonic()-started,error=str(error)))
            raise
        persist(Path(str(stem)+'-result.json'),dict(info,returncode=result.returncode,elapsed=time.monotonic()-started,
            stdout_b64=base64.b64encode(result.stdout).decode(),stderr_b64=base64.b64encode(result.stderr).decode()))
        need(result.returncode==0,'ADB command failed')
        return result.stdout
    def root(self,command,timeout=60):
        # su may return0 for a failed child: require an unpredictable child-RC trailer.
        marker='ZRET_'+uuid.uuid4().hex
        wrapped='( set -eu; '+command+'\n); zret_rc=$?; printf "\\n'+marker+'=%s\\n" "$zret_rc"'
        raw=self.call(['exec-out','su','-c',shlex.join(['/system/bin/sh','-c',wrapped])],timeout=timeout).decode('utf8').replace('\r\n','\n')
        tail='\n'+marker+'=0\n'
        need(raw.endswith(tail),'root child failed or incomplete output')
        return raw[:-len(tail)]
    def identity(self):
        values=self.root('id -u; getprop ro.serialno; getprop ro.product.model; getprop ro.build.display.id; cat /proc/sys/kernel/random/boot_id').splitlines()
        need(len(values)==5 and values[:3]==['0',SERIAL,'TB321FU'] and '16.1.11.072' in values[3],'device identity')
        for name,h in PAYLOAD.items():need(self.root('sha256sum '+q(name)).split()[0]==h,'approved interim payload')
        return dict(serial=SERIAL,model=values[2],build=values[3],boot_id=values[4])
    def ancestors(self):
        for name,expected in ANCESTORS.items():
            need(self.root('test -d '+q(name)+' && test ! -L '+q(name)+' && stat -c %u:%g:%a '+q(name)).strip()==expected,'ancestor identity')
    def inventory(self):
        self.ancestors();rows={}
        for name in TARGETS:
            s=self.root('if [ -e '+q(name)+' ] || [ -L '+q(name)+' ]; then stat -c "%F|%u|%g|%a|%s|%h" '+q(name)+'; else echo ABSENT; fi').strip()
            if s=='ABSENT':rows[name]=None;continue
            kind,uid,gid,mode,size,nlink=s.split('|')
            row=dict(type=kind,uid=int(uid),gid=int(gid),mode=mode,size=int(size),nlink=int(nlink))
            if kind=='symbolic link':row['target']=self.root('readlink '+q(name)).strip()
            elif kind=='directory':row['children']=sorted(self.root('find '+q(name)+' -mindepth 1 -maxdepth 1 -exec basename {} \\;').splitlines())
            elif kind in ('regular file','regular empty file'):
                need(int(size)<=128,'unexpected file size before read')
                data=base64.b64decode(''.join(self.root('base64 '+q(name)).split()),validate=True)
                row.update(data=base64.b64encode(data).decode(),sha256=digest(data))
                need(len(data)==int(size) and self.root('sha256sum '+q(name)).split()[0]==row['sha256'],'host backup byte proof')
            rows[name]=row
        return validate(rows)
    def rules(self):
        import io,tarfile,zipfile
        data=base64.b64decode(''.join(self.root('tar -cf - -C /data/vendor/zui_control zuiopt | base64').split()),validate=True)
        need(len(data)%512==0,'truncated archive')
        expected=set(self.root('find /data/vendor/zui_control/zuiopt').splitlines())
        state={}
        with tarfile.open(fileobj=io.BytesIO(data),mode='r:') as archive:
            members=archive.getmembers()
            need({'/data/vendor/zui_control/'+i.name.rstrip('/') for i in members}==expected,'independent RuleStore path coverage')
            for item in members:
                need(not item.name.startswith('/') and '..' not in Path(item.name).parts,'archive path')
                if not item.isfile():continue
                b=archive.extractfile(item).read()
                if item.name=='zuiopt/next_owner.v1':continue
                state[item.name]=digest(b)
                chunks=[b]
                if item.name.endswith('.zip'):
                    with zipfile.ZipFile(io.BytesIO(b)) as pack:
                        need(pack.testzip() is None,'pack CRC');chunks += [pack.read(n) for n in pack.namelist() if not n.endswith('/')]
                need(not any(re.search(rb'asoul|a-soul|asopt',c,re.I) for c in chunks),'HOLD_RULE_PROVENANCE_REQUIRES_AUTHENTICATED_NEUTRALIZATION')
        need('zuiopt/effective.conf' in state,'effective rule missing')
        return data,state
    def service_running(self):return bool(self.root('pidof AsoulOpt || true').strip())
    def retired_tasks(self):
        return parse_tasks(self.root(CPUSET_READ,timeout=RELEASE_TIMEOUT))
    def released(self,pre_stop=()):
        started=time.monotonic()
        need(len(pre_stop)<=65536 and all(type(t) is int and t>0 for t in pre_stop),'invalid bounded TID set')
        # A single command deadline covers process/init checks, hierarchy and survivors.
        command='test -z "$(pidof AsoulOpt || true)"; test "$(getprop init.svc.zui_asoulopt)" = stopped\n'+CPUSET_READ
        if pre_stop:
            command+='for tid in '+ ' '.join(str(t) for t in sorted(set(pre_stop)))+'''; do
 if [ ! -d "/proc/$tid" ]; then printf 'EXITED=%s\\n' "$tid"; continue; fi
 group=''
 if ! IFS= read -r group < "/proc/$tid/cpuset"; then
  test ! -d "/proc/$tid" || exit 23
  printf 'EXITED=%s\\n' "$tid"; continue
 fi
 case "$group" in /asopt*) exit 24 ;; /*) ;; *) exit 25 ;; esac
 printf 'SURVIVOR=%s|%s\\n' "$tid" "$group"
done
'''
        receipt=dict(architecture='DIRECT_RETIRED_CPUSET',pre_stop_tids=sorted(set(pre_stop)),status='FAIL')
        try:
            output=self.root(command,timeout=RELEASE_TIMEOUT)
            need(not parse_tasks(output),'ASOPT_TASK_FOUND; rollback required')
            receipt.update(status='PASS',retired_cpuset_tasks=0,output=output)
        finally:
            receipt['duration_ms']=(time.monotonic()-started)*1000
            persist(self.transaction/('release-proof-'+uuid.uuid4().hex+'.json'),receipt)
        print('RELEASE_PROOF_DURATION_MS='+str(round(receipt['duration_ms'],3)),flush=True)
        return receipt
    def ui_xml(self):
        raw=self.call(['exec-out','uiautomator','dump','/dev/tty'],timeout=15).decode('utf8')
        begin=raw.find('<?xml');end=raw.rfind('</hierarchy>')
        need(begin>=0 and end>=begin,'UI XML incomplete')
        return ET.fromstring(raw[begin:end+12])
    def product_button(self,wanted,max_scrolls=8):
        self.call(['shell','input','keyevent','KEYCODE_WAKEUP'])
        self.call(['shell','am','start','-n','com.zui.zuicontrol/.MainActivity'])
        on_threads_page=False;scrolls=0
        while True:
            root=self.ui_xml();assert_ui_package(root)
            point=ui_button(root,wanted,optional=True)
            if point is not None:
                self.call(['shell','input','tap',*map(str,point)])
                return
            if any(n.get('text')=='Task Scheduler' for n in root.iter('node')):
                on_threads_page=True
            if not on_threads_page:
                self.call(['shell','input','tap',*map(str,ui_button(root,'线程'))])
                on_threads_page=True
                continue
            need(scrolls<max_scrolls,'UI_NAVIGATION_FAIL: bounded scroll exhausted')
            containers=[n for n in root.iter('node') if n.get('package')=='com.zui.zuicontrol' and n.get('scrollable')=='true']
            need(len(containers)==1,'UI_NAVIGATION_FAIL: unique scroll container required')
            x1,y1,x2,y2=ui_bounds(containers[0])
            x=(x1+x2)//2
            self.call(['shell','input','swipe',str(x),str(y1+3*(y2-y1)//4),str(x),str(y1+(y2-y1)//4),'400'])
            scrolls+=1
    def product_toggle(self,enable):
        wanted='启用 AsoulOpt' if enable else '停止 AsoulOpt'
        self.product_button(wanted)
        for _ in range(30):
            if self.service_running()==enable:return
            time.sleep(1)
        raise RuntimeError('product action did not reach requested state')
    def delete(self,rows):
        # Existing shell-writable directory is frozen after durable host backup.
        self.ancestors()
        if rows[DIRECTORY]:self.root('chmod 0700 '+q(DIRECTORY))
        checks=[]
        for name,row in rows.items():
            if row is None:
                checks.append('test ! -e '+q(name)+' && test ! -L '+q(name));continue
            if name==DIRECTORY:
                checks.append('test "$(stat -c %u:%g:%a '+q(name)+')" = 0:2000:700')
                checks.append('test "$(find '+q(name)+' -mindepth 1 -maxdepth 1 | wc -l)" -eq '+str(len(row['children'])))
            elif name==LINK:checks.append('test -L '+q(name)+' && test "$(readlink '+q(name)+')" = '+q(row['target']))
            else:checks.append('test ! -L '+q(name)+' && test "$(stat -c %u:%g:%a:%h:%s '+q(name)+')" = '+q(f"{row['uid']}:{row['gid']}:{row['mode']}:1:{row['size']}")+' && test "$(sha256sum '+q(name)+' | cut -d\" \" -f1)" = '+q(row['sha256']))
        commands=['set -eu',*checks]
        commands += ['rm '+q(n) for n in (LINK,DIRECTORY+'/asopt.conf',DIRECTORY+'/asopt.conf.tmp',SELECTOR) if rows[n]]
        if rows[DIRECTORY]:commands.append('rmdir '+q(DIRECTORY))
        self.root('; '.join(commands))
    def restore(self,rows):
        self.ancestors()
        # Refuse to overwrite anything except absent or exact backup objects.
        for name,row in rows.items():
            if row is None:
                need(self.root('if [ -e '+q(name)+' ] || [ -L '+q(name)+' ]; then echo PRESENT; fi').strip()=='','unexpected new rollback object')
                continue
            if name==DIRECTORY:
                self.root('if [ ! -e '+q(name)+' ]; then mkdir -m 0700 '+q(name)+'; fi; test -d '+q(name)+' && test ! -L '+q(name))
                self.root('test "$(stat -c %u '+q(name)+')" = 0 && chmod 0700 '+q(name))
                names=self.root('find '+q(name)+' -mindepth 1 -maxdepth 1 -exec basename {} \\;').splitlines()
                need(set(names)<=set(row['children']),'rollback directory gained unknown content')
        for name,row in rows.items():
            if row is None or name in (LINK,DIRECTORY):continue
            self.root('if [ -e '+q(name)+' ] || [ -L '+q(name)+' ]; then test -f '+q(name)+' && test ! -L '+q(name)+' && test "$(stat -c %h '+q(name)+')" = 1 && test "$(sha256sum '+q(name)+' | cut -d\" \" -f1)" = '+q(row['sha256'])+'; else printf %s '+q(row['data'])+' | base64 -d > '+q(name)+'; fi; chown '+str(row['uid'])+':'+str(row['gid'])+' '+q(name)+'; chmod '+row['mode']+' '+q(name))
        if rows[LINK]:self.root('if [ -e '+q(LINK)+' ] || [ -L '+q(LINK)+' ]; then test -L '+q(LINK)+' && test "$(readlink '+q(LINK)+')" = '+q(rows[LINK]['target'])+'; else ln -s '+q(rows[LINK]['target'])+' '+q(LINK)+'; fi; chown -h 0:0 '+q(LINK))
        if rows[DIRECTORY]:self.root('chown 0:2000 '+q(DIRECTORY)+' && chmod 0775 '+q(DIRECTORY))
        restored=self.inventory()
        # Directory allocation size/link count are filesystem bookkeeping, not bytes
        # we can restore. All file/link identity and directory children remain exact.
        def comparable(value):
            return {name:({k:v for k,v in row.items() if k not in ('size','nlink')} if row and row['type']=='directory' else row) for name,row in value.items()}
        need(comparable(restored)==comparable(rows),'restored exact path/bytes/owner/mode differs')

def retire(device,transaction,authorization):
    identity=device.identity();rows=device.inventory();archive,rules=device.rules();running=device.service_running()
    backup=dict(identity=identity,paths=rows,rules=rules,was_running=running)
    persist(transaction/'backup.json',backup)
    with (transaction/'rulestore.tar').open('xb') as f:f.write(archive);f.flush();os.fsync(f.fileno())
    need(authorization==digest((transaction/'inventory.json').read_bytes()),'explicit reviewed inventory SHA required')
    old=json.loads((transaction/'inventory.json').read_text());need(old==backup,'inventory changed since review')
    persist(transaction/'mutation_started.json',dict(backup_sha256=digest((transaction/'backup.json').read_bytes()),stage='BEFORE_PRODUCT_STOP'))
    try:
        pre_stop=device.retired_tasks()
        persist(transaction/'pre_stop_tasks.json',dict(tids=pre_stop))
        if running:device.product_toggle(False)
        device.released(pre_stop);need(device.rules()[1]==rules,'RuleStore changed before retirement')
        device.delete(rows)
        need(all(v is None for v in device.inventory().values()),'retired paths remain')
        need(device.rules()[1]==rules,'RuleStore changed during retirement')
        device.released(pre_stop)
        persist(transaction/'ready_for_flash.json',dict(status='READY_FOR_FLASH',identity=identity,rule_semantic_diff=0,backup_sha256=digest((transaction/'backup.json').read_bytes())))
    except BaseException:
        rollback(device,transaction)
        raise

def rollback(device,transaction):
    marker=json.loads((transaction/'mutation_started.json').read_text())
    data=(transaction/'backup.json').read_bytes();need(digest(data)==marker['backup_sha256'],'backup tampering')
    backup=json.loads(data)
    need(device.identity()==backup['identity'],'rollback only on same approved interim boot before EDL/program')
    device.restore(backup['paths']);need(device.rules()[1]==backup['rules'],'rules must remain byte-exact')
    if backup['was_running'] and not device.service_running():device.product_toggle(True)
    need(device.service_running()==backup['was_running'],'interim service recovery not proven')
    persist(transaction/('rollback_'+uuid.uuid4().hex+'.json'),dict(status='RESTORED_EXACT_INTERIM',rule_semantic_diff=0))

def main():
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['inventory','retire','rollback']);p.add_argument('--adb',type=Path,required=True);p.add_argument('--transaction',type=Path,required=True);p.add_argument('--execute',action='store_true');p.add_argument('--approved-inventory-sha256');a=p.parse_args()
    need(a.adb.is_file(),'explicit ADB file');need(not a.transaction.is_symlink(),'transaction must not be a symlink')
    if a.stage=='inventory':a.transaction.mkdir(parents=True,exist_ok=False)
    else:need(a.execute and a.transaction.is_dir(),'mutations require --execute and existing transaction')
    device=Device(a.adb,a.transaction)
    # Continue unique append-only command receipt numbering on a later invocation.
    device.sequence=max([int(f.name.split('-')[0]) for f in a.transaction.glob('*-command*.json')]+[0])
    if a.stage=='inventory':
        identity=device.identity();rows=device.inventory();archive,rules=device.rules()
        persist(a.transaction/'inventory.json',dict(identity=identity,paths=rows,rules=rules,was_running=device.service_running()))
        print('INVENTORY_SHA256='+digest((a.transaction/'inventory.json').read_bytes()))
    elif a.stage=='retire':retire(device,a.transaction,a.approved_inventory_sha256)
    else:rollback(device,a.transaction)

if __name__=='__main__':main()
