"""Every literal product action row must resolve to an existing Owner icon."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[2]
source=(ROOT/'app/src/main/java/com/zui/zuicontrol/MainActivity.kt').read_text('utf8')
titles=set(re.findall(r'\bactionRow\("([^"\n]+)"',source))
icon_map=source.split('val icon=when(title){',1)[1].split('\n',1)[0]
icons={title:icon for title,icon in re.findall(r'"([^"\n]+)"->R\.drawable\.(\w+)',icon_map)}
assert titles and not titles-icons.keys(),'Action rows without Owner icons: '+str(sorted(titles-icons.keys()))
for title in titles:
 assert (ROOT/'app/src/main/res/drawable'/str(icons[title]+'.xml')).is_file(),title+' icon asset missing'
print('ALL_PRODUCT_ACTION_ROWS_HAVE_EXISTING_OWNER_ICONS=PASS')
