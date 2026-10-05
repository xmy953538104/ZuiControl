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
                *[str(base/name) for name in ('PolicyJson.java','GpuRange.java','AppPolicyStore.java','UperfConfigStore.java','SettingsBackup.java')],
                str(ROOT/'tests/gpu/AppPolicyFixture.java')],check=True)
            subprocess.run(['java','-cp',tmp,'com.zui.server.control.AppPolicyFixture'],check=True)

    def test_binder_and_visual_binding(self):
        source=(ROOT/'framework_patch/src/services/com/zui/server/control/ZuiControlService.java').read_text(encoding='utf8')
        self.assertIn('case TX_SET_GLOBAL_GPU_RANGE:\n                    enforceCommandCallerAllowed();',source)
        bar=(ROOT/'app/src/main/java/com/zui/zuicontrol/GpuRangeBar.kt').read_text(encoding='utf8')
        self.assertIn('canvas.drawRoundRect(x(231),y-5*unit,x(903),y+5*unit,5*unit,5*unit,paint)',bar)
        # Owner HTML places the readout in .sec-head, and animates OPP index positions.
        self.assertIn('canvas.drawRoundRect(pos(shownMin),y-5*unit,pos(shownMax),y+5*unit,5*unit,5*unit,paint)',bar)
        self.assertIn('paint.strokeWidth = 10 * unit',bar)
        self.assertIn('radius-4*unit',bar)
        self.assertNotIn('canvas.drawText',bar)
        main=(ROOT/'app/src/main/java/com/zui/zuicontrol/MainActivity.kt').read_text(encoding='utf8')
        self.assertIn('ownerUpdateReadout(readout,it)',main)

if __name__=='__main__':unittest.main()
