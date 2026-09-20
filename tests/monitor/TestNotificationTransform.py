"""Narrow notification exception contracts; exact DEX/VM proof remains a ROM host gate."""
from pathlib import Path
import sys,unittest
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts/build'))
from NotificationProofTransforms import GUARD,patch_builder
from BuildNotificationFramework import canonical_method

class NotificationTransform(unittest.TestCase):
    def test_three_conjunctive_guards_without_sdk_mutation(self):
        self.assertEqual(GUARD.count('if-eqz v1, :zui_standard_decoration'),3)
        self.assertEqual(GUARD.count('return v0'),1)
        for required in ('"com.zui.zuicontrol"','"zui_control_monitor_v1"',
                         'ApplicationInfo;->flags:I','and-int/lit8 v1, v1, 0x1'):
            self.assertIn(required,GUARD)
        self.assertNotIn('targetSdkVersion',GUARD)
        self.assertNotIn('iput',GUARD)
        self.assertNotIn('systemui',GUARD.lower())
        self.assertNotIn('setSmallIcon',GUARD)

    def test_unqualified_input_rejected(self):
        for invalid in (b'',b'.class public Landroid/app/Notification$Builder;\n',GUARD.encode()):
            with self.assertRaises(ValueError):patch_builder(invalid)

    def test_label_normalization_preserves_reference_identity(self):
        a='if-eqz v0, :cond_14\n:cond_14\nif-eqz v1, :zui_standard_decoration\n:zui_standard_decoration'
        b='if-eqz v0, :cond_27\n:cond_27\nif-eqz v1, :cond_a5\n:cond_a5'
        self.assertEqual(canonical_method(a),canonical_method(b))
        self.assertNotEqual(canonical_method(a),canonical_method(b.replace('if-eqz v1, :cond_a5','if-eqz v1, :cond_27')))
        self.assertEqual(canonical_method('    const-string/jumbo v2, "literal"'),
                         canonical_method('    const-string v2, "literal"'))
        self.assertNotEqual(canonical_method('    const-string/jumbo v1, "other"'),
                            canonical_method('    const-string v2, "literal"'))

if __name__=='__main__':unittest.main(verbosity=2)
