"""Regression tests for online/atomic finance and Wallos SQLite backups."""
from datetime import datetime,timezone,timedelta
import importlib.util
import sqlite3
import tempfile
import unittest
from pathlib import Path

BACKUP_CODE=Path(__file__).parent.parent/"deploy/backup.py"
spec=importlib.util.spec_from_file_location("finance_deploy_backup",BACKUP_CODE)
backup=importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)

class BackupRegressionTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
    def tearDown(self):
        self.tmp.cleanup()
    def test_snapshot_integrity_and_missing_source(self):
        src=self.root/"finance.sqlite3"
        out=self.root/"archive.sqlite3"
        with sqlite3.connect(src) as c:
            c.execute("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT)")
            c.execute("INSERT INTO items(name) VALUES ('a real transaction')")
        backup.snapshot_one(src,out)
        with sqlite3.connect(out) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM items").fetchone()[0],1)
            self.assertEqual(c.execute("PRAGMA integrity_check").fetchone()[0],"ok")
        with self.assertRaises(FileNotFoundError):
            backup.snapshot_one(self.root/"missing.sqlite3",self.root/"never.sqlite3")
        self.assertFalse((self.root/"never.sqlite3").exists())
    def test_30_day_retention_keeps_daily_sets(self):
        now=datetime.now(timezone.utc)
        for days,minute in [(0,0),(0,1),(0,2),(0,3),(1,0),(3,0),(31,0)]:
            stamp=(now-timedelta(days=days)).strftime("%Y%m%d")+"-"+f"{minute:06d}"
            (self.root/f"wallos-{stamp}.sqlite3").write_bytes(b"x")
        backup.retention("wallos",now,self.root)
        dates={p.name.split("-")[1] for p in self.root.glob("wallos-*.sqlite3")}
        self.assertIn((now-timedelta(days=3)).strftime("%Y%m%d"),dates)
        self.assertNotIn((now-timedelta(days=31)).strftime("%Y%m%d"),dates)

if __name__=="__main__":
    unittest.main()
