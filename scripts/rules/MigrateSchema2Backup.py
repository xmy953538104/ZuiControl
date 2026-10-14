"""Explicit offline migration only; never rewrites its input or touches live storage."""
from pathlib import Path
import argparse,io,json,os,shlex,stat,struct,zipfile,zlib
from ZUIOPT_rule_pack import parse_rules,dump_rules,strict_json,digest,need
NAMES=('manifest.json','policy.json','zuiopt.canonical.conf','preferences.json')
LIMITS=(16384,98304,65536,16384)
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode('utf8')+b'\n'
def migrate_rules(raw):
    need(0<len(raw)<=65536 and b'\r' not in raw,'rules size/encoding')
    rows=raw.decode('ascii').splitlines();schema=[]
    for n,line in enumerate(rows):
        text=line.strip()
        if not text or text.startswith('#'):continue
        tokens=shlex.split(text)
        if tokens[0]=='schema':schema.append((n,tokens))
    need(len(schema)==1 and schema[0][1] in (['schema','2'],['schema','3']),'explicit schema 2/3 required')
    if schema[0][1][1]=='2':
        need(not any(line.strip().split()[:1] in (['runtime'],['runtime_defaults']) for line in rows),'legacy rules cannot declare runtime')
        rows[schema[0][0]]='schema 3'
    return dump_rules(parse_rules(('\n'.join(rows)+'\n').encode('ascii')))
def settings_zip(entries):
    # Same bounded canonical ZIP metadata as SettingsBackup.zip; verified by its Java reader.
    local=bytearray();central=bytearray()
    for name in NAMES:
        data=entries[name];label=name.encode('ascii');offset=len(local);crc=zlib.crc32(data)
        shared=struct.pack('<5H3I',20,0x800,0,0,33,crc,len(data),len(data))
        local+=struct.pack('<I',0x04034b50)+shared+struct.pack('<2H',len(label),0)+label+data
        central+=struct.pack('<IH',0x02014b50,0x314)+shared+struct.pack('<5H2I',len(label),0,0,0,0,0o100600<<16,offset)+label
    return bytes(local+central+struct.pack('<I4H2IH',0x06054b50,0,0,4,4,len(central),len(local),0))
def migrate(raw):
    if not raw.startswith(b'PK'):
        result=migrate_rules(raw);return result,dict(kind='RULES',original_sha256=digest(raw),migrated_sha256=digest(result))
    need(0<len(raw)<=196608,'settings backup size')
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        infos=z.infolist();need([i.filename for i in infos]==list(NAMES),'exact settings members required')
        entries={}
        for info,limit in zip(infos,LIMITS):
            need(not info.flag_bits&1 and info.compress_type==0 and stat.S_IFMT(info.external_attr>>16)==stat.S_IFREG and 0<info.file_size<=limit,'bounded regular settings member')
            entries[info.filename]=z.read(info);need(len(entries[info.filename])==info.file_size,'settings member size')
    need(settings_zip(entries)==raw,'canonical settings ZIP required')
    manifest=strict_json(entries['manifest.json']);old=dict(manifest);mh=manifest.pop('manifestHash',None)
    need(mh==digest(canonical(manifest)),'original manifest digest')
    need(manifest['kind']=='zui.settings.backup' and manifest['schemaVersion']==1 and manifest['zuioptSchema'] in (2,3) and manifest['targetSoC']=='SM8650','settings migration compatibility')
    need(len(manifest['entries'])==3,'settings manifest entries')
    for name,row in zip(NAMES[1:],manifest['entries']):
        need(row==dict(name=name,bytes=len(entries[name]),sha256=digest(entries[name])),'original member digest/size')
    rules=entries['zuiopt.canonical.conf'];need(parse_rules(migrate_rules(rules)) is not None,'rules validation')
    expected=str(manifest['zuioptSchema']);tokens=[shlex.split(l.strip()) for l in rules.decode('ascii').splitlines() if l.strip() and not l.strip().startswith('#')]
    need(['schema',expected] in tokens,'original manifest/rules schema binding')
    entries['zuiopt.canonical.conf']=migrate_rules(rules);manifest['zuioptSchema']=3
    manifest['entries']=[dict(name=n,bytes=len(entries[n]),sha256=digest(entries[n])) for n in NAMES[1:]]
    manifest['manifestHash']=digest(canonical(manifest));entries['manifest.json']=canonical(manifest);result=settings_zip(entries)
    need(len(result)<=196608,'migrated backup size')
    return result,dict(kind='SETTINGS_BACKUP',original_sha256=digest(raw),migrated_sha256=digest(result),original_manifest_sha256=digest(canonical(old)),rules_before=digest(rules),rules_after=digest(entries['zuiopt.canonical.conf']),policy_and_preferences_unchanged=True,generation='PROVENANCE_ONLY_LIVE_RESTORE_USES_NEW_CAS')
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('input',type=Path);parser.add_argument('output',type=Path);a=parser.parse_args()
    need(a.input.resolve()!=a.output.resolve() and not a.output.exists(),'fresh output required')
    need(a.input.stat().st_size<=196608 and not a.output.with_name(a.output.name+'.migration.json').exists(),'bounded input/fresh receipt required')
    raw=a.input.read_bytes();result,receipt=migrate(raw)
    with a.output.open('xb') as f:f.write(result);f.flush();os.fsync(f.fileno())
    with a.output.with_name(a.output.name+'.migration.json').open('x',encoding='utf8') as f:json.dump(receipt,f,indent=2);f.write('\n')
    print(json.dumps(receipt))
if __name__=='__main__':main()
