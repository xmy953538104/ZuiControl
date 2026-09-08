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
    def call(self,args):
        self.sequence+=1
        result=subprocess.run([self.adb,'-s',SERIAL,*args],capture_output=True,timeout=60)
        persist(self.transaction/('%05d-command.json'%self.sequence),dict(argv=args,returncode=result.returncode,stdout_b64=base64.b64encode(result.stdout).decode(),stderr_b64=base64.b64encode(result.stderr).decode()))
        need(result.returncode==0,'ADB command failed')
        return result.stdout
    def root(self,command):
        # su may return0 for a failed child: require an unpredictable child-RC trailer.
        marker='ZRET_'+uuid.uuid4().hex
        wrapped='( set -eu; '+command+' ); zret_rc=$?; printf "\\n'+marker+'=%s\\n" "$zret_rc"'
        raw=self.call(['exec-out','su','-c',shlex.join(['/system/bin/sh','-c',wrapped])]).decode('utf8').replace('\r\n','\n')
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
    def released(self):
        need(not self.service_running(),'predecessor process still present')
        output=self.root("for f in /proc/[0-9]*/task/[0-9]*/cpuset; do [ -f \"$f\" ] || continue; value=$(cat \"$f\" 2>/dev/null) || continue; case \"$value\" in /asopt|/asopt/*) printf '%s %s\\n' \"$f\" \"$value\" ;; esac; done")
        need(not output.strip(),'owned tasks not released; rollback required')
    def product_toggle(self,enable):
        # User opens the real Threads page. Only a fresh observed signed-App button is tapped.
        wanted='启用 AsoulOpt' if enable else '停止 AsoulOpt'
        print('Open ZuiControl Threads page with '+wanted+' visible. No private command bypass is supported.',flush=True)
        remote='/data/local/tmp/zuiopt-retirement-ui-'+uuid.uuid4().hex+'.xml'
        try:
            self.root('uiautomator dump '+q(remote))
            xml=self.root('cat '+q(remote))
            root=ET.fromstring(xml);parents={child:parent for parent in root.iter() for child in parent}
            nodes=[n for n in root.iter('node') if n.get('text')==wanted and n.get('package')=='com.zui.zuicontrol']
            need(len(nodes)==1,'exact authenticated App button not visible')
            node=nodes[0]
            while node.get('clickable')!='true' and node in parents:node=parents[node]
            need(node.get('clickable')=='true' and node.get('enabled')=='true','button not enabled')
            m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''));need(m is not None,'button bounds')
            x1,y1,x2,y2=map(int,m.groups());need(x2>x1 and y2>y1,'positive bounds')
            self.call(['shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)])
            for _ in range(30):
                if self.service_running()==enable:return
                time.sleep(1)
            raise RuntimeError('product action did not reach requested state')
        finally:self.root('test ! -L '+q(remote)+' && rm -f '+q(remote))
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
        if running:device.product_toggle(False)
        device.released();need(device.rules()[1]==rules,'RuleStore changed before retirement')
        device.delete(rows)
        need(all(v is None for v in device.inventory().values()),'retired paths remain')
        need(device.rules()[1]==rules,'RuleStore changed during retirement')
        device.released()
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
    device.sequence=max([int(f.name.split('-')[0]) for f in a.transaction.glob('*-command.json')]+[0])
    if a.stage=='inventory':
        identity=device.identity();rows=device.inventory();archive,rules=device.rules()
        persist(a.transaction/'inventory.json',dict(identity=identity,paths=rows,rules=rules,was_running=device.service_running()))
        print('INVENTORY_SHA256='+digest((a.transaction/'inventory.json').read_bytes()))
    elif a.stage=='retire':retire(device,a.transaction,a.approved_inventory_sha256)
    else:rollback(device,a.transaction)

if __name__=='__main__':main()
