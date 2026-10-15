"""Bound the new Monitor permissions; target compile and production reads remain device gates."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[2]
text=(ROOT/'payload/patches/vendor_sepolicy_zui_scheduler.cil').read_text(encoding='utf8')
rows=[line for line in text.splitlines() if line.startswith('(allow system_server_34_0 ')]
assert set(rows)=={
    '(allow system_server_34_0 vendor_sysfs_battery_supply (file (getattr open read)))',
    '(allow system_server_34_0 vendor_sysfs_usb_supply (file (getattr open read)))',
}
source=(ROOT/'framework_patch/src/services/com/zui/server/control/MonitorPower.java').read_text(encoding='utf8')
assert not re.search(r'number\("ucsi',source),'Do not require generic sysfs file access for port selection'
for bad in ('FileWriter','FileOutputStream','Runtime.getRuntime','ProcessBuilder','setProperty'):
    assert bad not in source,bad
collector=(ROOT/'framework_patch/src/services/com/zui/server/control/MonitorCollector.java').read_text(encoding='utf8')
assert collector.count('new UEventObserver()')==1 and 'stopPowerEvents();' in collector
print('POWER_TYPED_READ_ONLY_EDGES_AND_EXISTING_PRODUCER=PASS; TARGET_COMPILE_AND_PRODUCTION_READS=REQUIRED')
