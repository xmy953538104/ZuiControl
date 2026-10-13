"""Deterministic Schema2/3 publication; manifest last, named capabilities fail closed."""
from pathlib import Path
import argparse,datetime,hashlib,json,os,re,tempfile
from ZUIOPT_rule_pack import MAX_PACK,MAX_RULES,normalize_source,parse_rules,read_bounded,need

LATEST_LIMIT=2048
MAX_REVISION=9007199254740991  # exact in Kotlin Long and JavaScript safe integers
FIELDS={'schema','revision','source','sourceVersion','sourceDate','sourceCommit',
        'targetSoC','targetTopology','rulesSchema','rulesSha256','rulesSize'}
BASE_CAPABILITIES={'schema3','category-ranking','runtime-inheritance','owned-rt-recovery'}
CAPABILITIES=BASE_CAPABILITIES|{'mode0','mode1','mode2','marker-dummy','marker-double','walt-boost-restorable','walt-reduce-restorable'}

def required_capabilities(config):
    if config.get('schema',2)==2:return set()
    required=set(BASE_CAPABILITIES)
    for p in config['profiles'].values():
        mode,rt,placement,marker=p['runtime'];mode=config['mode'] if mode<0 else mode;rt=config['rt'] if rt<0 else rt
        required.add('mode'+str(mode))
        if mode==2 and placement=='preset':required.add('walt-reduce-restorable' if rt else 'walt-boost-restorable')
        if marker!='none':required.add('marker-'+marker)
    if not config['profiles']:required.add('mode'+str(config['mode']))
    return required

def encoded(value):
    return (json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True)+'\n').encode('ascii')

def validate(value):
    need(isinstance(value,dict),'latest object')
    runtime=value.get('rulesSchema')==3
    need(set(value)==(FIELDS|{'requiredCapabilities'} if runtime else FIELDS),'latest fields')
    need(type(value['schema']) is int and value['schema']==(2 if runtime else 1),'latest schema')
    need(type(value['rulesSchema']) is int and value['rulesSchema'] in (2,3),'rules schema')
    if runtime:
        caps=value['requiredCapabilities'];need(isinstance(caps,list) and all(isinstance(c,str) for c in caps),'capability types')
        need(caps==sorted(set(caps)) and BASE_CAPABILITIES<=set(caps)<=CAPABILITIES,'required capabilities')
    need(type(value['revision']) is int and 1<=value['revision']<=MAX_REVISION,'revision bound')
    for key,pattern in (('source',r'[A-Za-z0-9_.:/-]{1,128}'),('sourceVersion',r'[A-Za-z0-9_.+-]{1,64}'),
                        ('sourceDate',r'[0-9]{4}-[0-9]{2}-[0-9]{2}'),('sourceCommit',r'[0-9a-f]{40}'),('rulesSha256',r'[0-9a-f]{64}')):
        need(isinstance(value[key],str) and re.fullmatch(pattern,value[key]),key+' bound')
    datetime.date.fromisoformat(value['sourceDate'])
    need(value['targetSoC']=='SM8650' and value['targetTopology']=='0-7','target platform')
    need(type(value['rulesSize']) is int and 1<=value['rulesSize']<=MAX_RULES,'rules size bound')
    need(len(encoded(value))<=LATEST_LIMIT,'latest size bound')
    return value

def latest(data):
    need(0<len(data)<=LATEST_LIMIT,'latest byte bound')
    def unique(pairs):
        result={}
        for key,value in pairs:
            need(key not in result,'latest duplicate key');result[key]=value
        return result
    return validate(json.loads(data.decode('utf8'),object_pairs_hook=unique))

def compare(candidate,current):
    validate(candidate);validate(current)
    if candidate['revision']<current['revision']:return 'STALE'
    if candidate['revision']>current['revision']:return 'NEWER'
    need(candidate==current,'same revision conflict')
    return 'SAME'

def verify(manifest,rules):
    validate(manifest)
    need(len(rules)==manifest['rulesSize'] and hashlib.sha256(rules).hexdigest()==manifest['rulesSha256'],'rules integrity')
    config=parse_rules(rules)
    need(not config['debug'],'published debug must remain disabled')
    need(normalize_source(rules,'canonical')==rules and config.get('schema',2)==manifest['rulesSchema'],'rules must be normalized declared schema')
    need(all(mapping[0]=='exact' for mapping in config['packages']),'upstream library requires exact package mappings')
    need(required_capabilities(config)==set(manifest.get('requiredCapabilities',[])),'capabilities/content mismatch')

def build(data,kind,revision,source,version,date,commit):
    rules=normalize_source(data,kind)
    config=parse_rules(rules);schema=config.get('schema',2)
    manifest=dict(schema=2 if schema==3 else 1,revision=revision,source=source,sourceVersion=version,sourceDate=date,sourceCommit=commit,
        targetSoC='SM8650',targetTopology='0-7',rulesSchema=schema,rulesSha256=hashlib.sha256(rules).hexdigest(),rulesSize=len(rules))
    if schema==3:manifest['requiredCapabilities']=sorted(required_capabilities(config))
    validate(manifest)
    verify(manifest,rules)
    return encoded(manifest),rules

def replace(path,data):
    with tempfile.NamedTemporaryFile(dir=path.parent,prefix='.'+path.name+'-',delete=False) as stream:
        temporary=Path(stream.name)
        try:stream.write(data);stream.flush();os.fsync(stream.fileno())
        except BaseException:temporary.unlink(missing_ok=True);raise
    try:os.replace(temporary,path)
    finally:temporary.unlink(missing_ok=True)

def publish(source_path,output,kind,revision,source,version,date,commit,previous=None):
    source_path=Path(source_path).resolve();output=Path(output).resolve()
    need(source_path not in (output/'latest.json',output/'rules.conf'),'source/output overlap')
    metadata,rules=build(read_bounded(source_path,MAX_PACK),kind,revision,source,version,date,commit)
    previous=Path(previous) if previous else output/'latest.json'
    if previous.exists():need(compare(latest(metadata),latest(read_bounded(previous,LATEST_LIMIT)))!='STALE','stale publication')
    output.mkdir(parents=True,exist_ok=True)
    # Manifest last. Readers must verify hash/size and retry an interrupted two-file fetch.
    replace(output/'rules.conf',rules);replace(output/'latest.json',metadata)
    return latest(metadata)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--kind',choices=('canonical','appopt','recovered-csv'),default='canonical')
    parser.add_argument('--revision',type=int,required=True);parser.add_argument('--source',required=True)
    parser.add_argument('--source-version',required=True);parser.add_argument('--source-date',required=True)
    parser.add_argument('--source-commit',required=True);parser.add_argument('--previous',type=Path)
    args=parser.parse_args()
    value=publish(args.input,args.output,args.kind,args.revision,args.source,args.source_version,args.source_date,args.source_commit,args.previous)
    print(encoded(value).decode().strip())

if __name__=='__main__':main()
