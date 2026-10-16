from pathlib import Path
import hashlib,json,sys
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tests'))
from FrontendSource import controller_method,read_frontend
plan=json.loads((ROOT/'tests/page_controllers_move.json').read_text(encoding='utf8'))
for row in plan['methods']:
 assert hashlib.sha256(controller_method(row).encode()).hexdigest()==row['beforeSha256'],row['file']
 assert 'class MainActivity' not in (ROOT/'app/src/main/java/com/zui/zuicontrol'/row['file']).read_text(encoding='utf8')
assert len(plan['methods'])==4
print('FOUR_STATELESS_PAGE_CONTROLLERS_EXACT_METHOD_MOVE_PASS')
