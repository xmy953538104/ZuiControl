"""Host mtimes must not affect JAR bytes; payload and existing attributes survive."""
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts/build'))
from PatchZuiControlFramework import rewrite_zip

class DeterministicZip(unittest.TestCase):
    def test_mtime_independent_payload_and_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            src, replacement, added = (root / n for n in ('source.jar', 'dex', 'new'))
            replacement.write_bytes(b'dex\n035\0same manager')
            added.write_bytes(b'same added resource')
            with zipfile.ZipFile(src, 'w') as z:
                for name, data in [('keep.txt', b'unchanged'), ('classes6.dex', b'old')]:
                    info = zipfile.ZipInfo(name, (2026, 9, 18, 10, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.create_system = 3
                    info.external_attr = 0o100640 << 16
                    z.writestr(info, data)
            outputs = []
            for i, stamp in enumerate((946684800, 1790000000)):
                os.utime(src, (stamp, stamp))
                os.utime(replacement, (stamp, stamp))
                os.utime(added, (stamp, stamp))
                dst = root / f'{i}.jar'
                rewrite_zip(src, dst, {'z.txt': added, 'classes6.dex': replacement})
                outputs.append(dst.read_bytes())
                with zipfile.ZipFile(dst) as z:
                    self.assertIsNone(z.testzip())
                    self.assertEqual(z.namelist(), ['keep.txt', 'classes6.dex', 'z.txt'])
                    self.assertEqual([z.read(n) for n in z.namelist()], [b'unchanged', replacement.read_bytes(), added.read_bytes()])
                    self.assertTrue(all(x.date_time == (1980, 1, 1, 0, 0, 0) for x in z.infolist()))
                    self.assertEqual(z.getinfo('classes6.dex').compress_type, zipfile.ZIP_DEFLATED)
                    self.assertEqual(z.getinfo('classes6.dex').external_attr, 0o100640 << 16)
            self.assertEqual(hashlib.sha256(outputs[0]).digest(), hashlib.sha256(outputs[1]).digest())
            rewrite_zip(root/'0.jar', root/'0.jar', {'classes6.dex': replacement, 'z.txt': added})
            self.assertEqual((root/'0.jar').read_bytes(), outputs[0])

if __name__ == '__main__':
    unittest.main()
