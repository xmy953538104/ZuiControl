"""Deterministic two-file upstream publication using the qualified Schema2 normalizer."""
from pathlib import Path
import argparse,datetime,hashlib,json,os,re,tempfile
from ZUIOPT_rule_pack import MAX_PACK,MAX_RULES,normalize_source,parse_rules,read_bounded,need

LATEST_LIMIT=2048
MAX_REVISION=9007199254740991  # exact in Kotlin Long and JavaScript safe integers
FIELDS={'schema','revision','source','sourceVersion','sourceDate','sourceCommit',
        'targetSoC','targetTopology','rulesSchema','rulesSha256','rulesSize'}

def encoded(value):
    return (json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True)+'\n').encode('ascii')

def validate(value):
    need(isinstance(value,dict) and set(value)==FIELDS,'latest fields')
    need(type(value['schema']) is int and value['schema']==1,'latest schema')
    need(type(value['rulesSchema']) is int and value['rulesSchema']==2,'rules schema')
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
    need(normalize_source(rules,'canonical')==rules,'rules must be normalized Schema2')
    need(all(mapping[0]=='exact' for mapping in parse_rules(rules)['packages']),'V83 upstream library requires exact package mappings')

def build(data,kind,revision,source,version,date,commit):
    rules=normalize_source(data,kind)
    manifest=validate(dict(schema=1,revision=revision,source=source,sourceVersion=version,sourceDate=date,sourceCommit=commit,
        targetSoC='SM8650',targetTopology='0-7',rulesSchema=2,rulesSha256=hashlib.sha256(rules).hexdigest(),rulesSize=len(rules)))
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
