"""Audited schema-2 product facts; native acceptance also runs in parity suite."""
from pathlib import Path
import re,sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/rules'))
from ZUIOPT_rule_pack import parse_rules,dump_rules,appopt,unpack
CANONICAL=b'schema 2\nenabled true\nprofile Shared 2-6\nthread Shared Pool exact "Worker" selector=rank:2 40 7\nthread Shared Pool prefix "Worker-" selector=rank:2 30 7\nthread Shared All contains "Render" selector=all 20 2-4\nthread Shared Glob glob "Job*" selector=all 10 5-6\npackage exact org.example.game Shared 100\npackage prefix org.other Shared 90\n'
INVALID=[CANONICAL.replace(b'selector=rank:2',b'selector='+scope) for scope in (b'thread',b'task',b'process')]
INVALID += [CANONICAL+b'enabled org.example.game false\n',CANONICAL+b'ruleName Rule_Game_Main\n']
INVALID += [CANONICAL+b'sched FIFO\n',CANONICAL+b'sched RR\n',CANONICAL+b'nice -10\n',CANONICAL+b'uclamp 100\n']
def check():
 c=parse_rules(CANONICAL)
 assert c['profiles']['Shared']['mask']==124
 assert c['profiles']['Shared']['rules'][0]==('Pool','exact','Worker',2,40,128)
 assert c['profiles']['Shared']['rules'][1][0]=='Pool'
 assert {r[1] for r in c['profiles']['Shared']['rules']}=={'exact','prefix','contains','glob'}
 assert c['packages'][0]==('exact','org.example.game','Shared',100)
 assert parse_rules(dump_rules(c))==c
 for data in INVALID:
  try:parse_rules(data)
  except ValueError:pass
  else:raise AssertionError('unsupported schema accepted: '+repr(data))
 _,converted=unpack(appopt('org.example.game=2-6\norg.example.game{Worker*}=7','compat'))
 compat=parse_rules(converted);rule=next(iter(compat['profiles'].values()))['rules'][0]
 assert rule[0]=='r0' and rule[3]==0 # Generated class; AppOpt has no rank preservation.
 engine='\n'.join(p.read_text(encoding='utf8') for p in (ROOT/'native/zuiopt').glob('*.h'))
 engine+='\n'+(ROOT/'native/zuiopt/ZUIopt.cpp').read_text(encoding='utf8')
 engine=re.sub(r'/\*.*?\*/|//[^\n]*','',engine,flags=re.S)
 assert 'sched_setaffinity(' in engine and ':cpuset:' in engine
 for forbidden in ('sched_setscheduler','sched_setparam','setpriority','UCLAMP_MIN','UCLAMP_MAX'):
  assert not re.search(r'\b'+forbidden+r'\b',engine),forbidden
 assert not re.search(r'\bnice\s*\(',engine)
 # Owner ADCB explicitly adds sched_attr ownership. Schema2 still accepts no RT directives.
 assert 'SYS_sched_getattr' in engine and 'SYS_sched_setattr' in engine
 print('SCHEMA_CONTRACT=PASS schema2 grammar; explicit ADCB sched_attr ownership separately tested')
if __name__=='__main__':check()
