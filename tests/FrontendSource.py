"""Read logical frontend methods across physical controller files; no runtime substitution."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app/src/main/java/com/zui/zuicontrol'
def controller_method(row):
 source=(APP/row['file']).read_text(encoding='utf8');start=source.index('internal fun MainActivity.'+row['extension']+'()')
 method=source[start:].rstrip().replace('internal fun MainActivity.'+row['extension']+'()','private fun '+row['method']+'()',1).replace('this@'+row['extension'],'this@MainActivity')
 return '\n'.join('    '+line if line else '' for line in method.splitlines())
def read_frontend(path):
 path=Path(path);text=path.read_text(encoding='utf8')
 if path.name!='MainActivity.kt':return text
 plan=json.loads((ROOT/'tests/page_controllers_move.json').read_text(encoding='utf8'))
 for row in plan['methods']:
  assert text.count(row['wrapper'])==1
  text=text.replace(row['wrapper'],controller_method(row),1)
 for row in plan['visibility']:text=text.replace(row['after'],row['before'],1)
 return text
