"""Execute production profile methods, including the authenticated global setter."""
from pathlib import Path
import subprocess,tempfile,unittest

ROOT=Path(__file__).resolve().parents[2]

class GpuProfiles(unittest.TestCase):
    def test_real_parser_serializer_global_transaction(self):
        # Execute the actual unified store/migration, not a parallel Python model.
        base=ROOT/'framework_patch/src/services/com/zui/server/control'
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(['javac','-encoding','UTF-8','-d',tmp,
                *[str(base/name) for name in ('PolicyJson.java','GpuRange.java','AppPolicyStore.java')],
                str(ROOT/'tests/gpu/AppPolicyFixture.java')],check=True)
            subprocess.run(['java','-cp',tmp,'com.zui.server.control.AppPolicyFixture'],check=True)

    def test_binder_and_visual_binding(self):
        source=(ROOT/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java').read_text(encoding='utf8')
        self.assertIn('case TX_SET_GLOBAL_GPU_RANGE:\n                    enforceCommandCallerAllowed();',source)
        bar=(ROOT/'app/src/main/java/com/zui/zuicontrol/GpuRangeBar.kt').read_text(encoding='utf8')
        self.assertIn('canvas.drawLine(x(231), y, x(903), y, paint)',bar)
        self.assertIn('canvas.drawLine(x(range.min), y, x(range.max), y, paint)',bar)
        self.assertIn('canvas.drawText(minLabel, x(range.min), baseline, labelPaint)',bar)
        self.assertIn('canvas.drawText(maxLabel, x(range.max), baseline + stagger, labelPaint)',bar)
        self.assertIn('y + 16f * unit - labelPaint.fontMetrics.ascent',bar)

if __name__=='__main__':unittest.main()
