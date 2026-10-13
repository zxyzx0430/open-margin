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
    def test_optional_oauth_snapshot_is_included_after_initialization(self):
        original_oauth=backup.OAUTH_DB
        original_sources=backup.SOURCES
        original_backups=backup.BACKUPS
        try:
            finance=self.root/"finance.sqlite3"
            wallos=self.root/"wallos.db"
            oauth=self.root/"oauth.sqlite3"
            for path in (finance,wallos):
                with sqlite3.connect(path) as db:
                    db.execute("CREATE TABLE fixture(value TEXT)")
            backup.OAUTH_DB=oauth
            backup.SOURCES={"transactions":finance,"wallos":wallos}
            backup.BACKUPS=self.root/"backups"
            self.assertNotIn("oauth",backup.sources_for_backup())
            backup.run()
            self.assertEqual(len(list(backup.BACKUPS.glob("*.sqlite3"))),2)
            with sqlite3.connect(oauth) as db:
                db.execute("CREATE TABLE grants(token TEXT)")
                db.execute("INSERT INTO grants VALUES ('synthetic-grant')")
            self.assertIn("oauth",backup.sources_for_backup())
            backup.run()
            snapshots=list(backup.BACKUPS.glob("oauth-*.sqlite3"))
            self.assertEqual(len(snapshots),1)
            with sqlite3.connect(snapshots[0]) as db:
                self.assertEqual(db.execute("SELECT token FROM grants").fetchone()[0],"synthetic-grant")
        finally:
            backup.OAUTH_DB=original_oauth
            backup.SOURCES=original_sources
            backup.BACKUPS=original_backups

    def test_wal_mode_backup_is_standalone_without_orphan_sidecars(self):
        # Real finance.sqlite3 uses WAL; never touch any live data in tests.
        src=self.root/"synthetic-live-wal.sqlite3"
        dest=self.root/"wal-archive.sqlite3"
        with sqlite3.connect(src) as live:
            self.assertEqual(live.execute("PRAGMA journal_mode=WAL").fetchone()[0],"wal")
            live.execute("CREATE TABLE expense(id INTEGER PRIMARY KEY, cents INTEGER)")
            live.execute("INSERT INTO expense(cents) VALUES(1886)")
        backup.snapshot_one(src,dest)
        self.assertEqual({p.name for p in self.root.glob("wal-archive.sqlite3*")},
                         {"wal-archive.sqlite3"})
        with sqlite3.connect(f"file:{dest}?mode=ro&immutable=1",uri=True) as restored:
            self.assertEqual(restored.execute("PRAGMA journal_mode").fetchone()[0],"delete")
            self.assertEqual(restored.execute("SELECT cents FROM expense").fetchone()[0],1886)
            self.assertEqual(restored.execute("PRAGMA integrity_check").fetchone()[0],"ok")
        with sqlite3.connect(src) as original:
            self.assertEqual(original.execute("PRAGMA journal_mode").fetchone()[0],"wal")

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
