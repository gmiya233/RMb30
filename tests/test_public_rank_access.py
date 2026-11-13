import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server/qqbot'))
from public_rank_access import ensure_public_access


class PublicRankAccessTests(unittest.TestCase):
    @unittest.skipIf(os.name=='nt','POSIX directory permissions')
    def test_repair_only_public_directories(self):
        # Keep artifacts beside this test, including on the E: workspace.
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as tmp:
            root=Path(tmp)/'public';revision='a'*64
            charts=root/revision/'charts';charts.mkdir(parents=True)
            private=Path(tmp)/'private';private.mkdir();private.chmod(0o700)
            record=charts/'1.json';record.write_text('{}');record.chmod(0o640)
            for directory in (root,root/revision,charts):directory.chmod(0o770)
            ensure_public_access(root,revision)
            for directory in (root,root/revision,charts):
                self.assertEqual(stat.S_IMODE(directory.stat().st_mode),0o775)
            self.assertEqual(stat.S_IMODE(private.stat().st_mode),0o700)
            self.assertEqual(stat.S_IMODE(record.stat().st_mode),0o640)
            ensure_public_access(root,revision)  # idempotent

    def test_reject_invalid_revision(self):
        with self.assertRaises(ValueError):ensure_public_access('.', '../private')


if __name__=='__main__':unittest.main()
