"""Concurrency regression for the one-time sync journal trigger upgrade."""
import json
import sqlite3
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from sync_v2 import schema as sync_schema


LEGACY_RECORD_JSON = """json_object(
 'event_id',NEW.event_id,'timestamp_ms',NEW.timestamp_ms,
 'amount_cents',NEW.amount_cents,'currency',NEW.currency,
 'direction',NEW.direction,'source',NEW.source,
 'category',NEW.category,'merchant',NEW.merchant,
 'review_status',NEW.review_status)"""


class _PauseAfterInsertTriggerDrop(sqlite3.Connection):
    """Hold an upgrade after DROP returns so a competing write can run."""

    def execute(self, sql, parameters=()):
        cursor = super().execute(sql, parameters)
        if sql.strip().lower() == "drop trigger if exists sync_journal_insert":
            self.drop_finished.set()
            self.writer_attempting.wait(timeout=5)
            # On the old implementation the competing transaction completes
            # during this autocommit gap. An atomic migration keeps it blocked.
            self.writer_finished.wait(timeout=0.25)
        return cursor


class SyncJournalSchemaRaceTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory(prefix="sync-journal-race-")
        self.db_path = Path(self.tempdir.name) / "ledger.sqlite3"

    def tearDown(self):
        self.tempdir.cleanup()

    def connect(self, factory=sqlite3.Connection):
        db = sqlite3.connect(self.db_path, timeout=10, factory=factory)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=10000")
        return db

    def create_legacy_database(self):
        db = self.connect()
        db.executescript("""CREATE TABLE transactions(
          id INTEGER PRIMARY KEY,
          event_id TEXT NOT NULL UNIQUE,
          timestamp_ms INTEGER NOT NULL,
          amount_cents INTEGER NOT NULL,
          currency TEXT NOT NULL,
          direction TEXT NOT NULL,
          source TEXT NOT NULL,
          category TEXT NOT NULL,
          merchant TEXT NOT NULL,
          event_time_needs_confirmation INTEGER NOT NULL DEFAULT 0,
          review_status TEXT NOT NULL DEFAULT 'unverified'
        );
        CREATE TABLE ledger_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);""")
        sync_schema(db)
        db.commit()

        # Recreate the definitions stored by databases from before the flag was
        # included in journal payloads. The next schema call must upgrade them.
        db.execute("DROP TRIGGER sync_journal_insert")
        db.execute("DROP TRIGGER sync_journal_update")
        db.execute(f"""CREATE TRIGGER sync_journal_insert
          AFTER INSERT ON transactions BEGIN
           INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
           VALUES(NEW.event_id,'upsert',{LEGACY_RECORD_JSON},0);
          END""")
        db.execute(f"""CREATE TRIGGER sync_journal_update
          AFTER UPDATE OF timestamp_ms,amount_cents,currency,direction,source,
            category,merchant,review_status ON transactions
          WHEN OLD.timestamp_ms<>NEW.timestamp_ms
            OR OLD.amount_cents<>NEW.amount_cents OR OLD.currency<>NEW.currency
            OR OLD.direction<>NEW.direction OR OLD.source<>NEW.source
            OR OLD.category<>NEW.category OR OLD.merchant<>NEW.merchant
            OR OLD.review_status<>NEW.review_status
          BEGIN
           INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
           VALUES(NEW.event_id,'upsert',{LEGACY_RECORD_JSON},0);
          END""")
        db.commit()
        db.close()

    def write_insert_and_update(self, event_id, inserted_flag, final_flag):
        db = self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            db.execute("""INSERT INTO transactions(
              event_id,timestamp_ms,amount_cents,currency,direction,source,
              category,merchant,event_time_needs_confirmation)
              VALUES(?,?,?,?,?,?,?,?,?)""",
              (event_id,1791791400000,1230,"CNY","EXPENSE","WECHAT",
               "FOOD","Concurrency test",int(inserted_flag)))
            db.execute("UPDATE transactions SET event_time_needs_confirmation=? WHERE event_id=?",
                       (int(final_flag),event_id))
            db.commit()
        finally:
            db.close()

    def test_repeated_schema_initialization_never_drops_journaled_writes(self):
        self.create_legacy_database()

        drop_finished = threading.Event()
        writer_attempting = threading.Event()
        writer_finished = threading.Event()
        errors = []

        race_event = "race:trigger-upgrade:0001"

        def competing_writer():
            try:
                if not drop_finished.wait(timeout=5):
                    raise TimeoutError("trigger upgrade did not reach insert trigger drop")
                writer_attempting.set()
                self.write_insert_and_update(race_event, False, True)
            except BaseException as exc:  # surfaced in the test thread below
                errors.append(exc)
            finally:
                writer_finished.set()

        def first_migration():
            migration_db = self.connect(factory=_PauseAfterInsertTriggerDrop)
            migration_db.drop_finished = drop_finished
            migration_db.writer_attempting = writer_attempting
            migration_db.writer_finished = writer_finished
            try:
                sync_schema(migration_db)
                migration_db.commit()
            except BaseException as exc:  # surfaced in the test thread below
                errors.append(exc)
            finally:
                migration_db.close()

        writer = threading.Thread(target=competing_writer, name="journal-race-writer")
        migration = threading.Thread(target=first_migration, name="journal-trigger-upgrade")
        writer.start()
        migration.start()
        writer.join(timeout=15)
        migration.join(timeout=15)
        self.assertFalse(writer.is_alive(), "competing writer did not finish")
        self.assertFalse(migration.is_alive(), "trigger migration did not finish")
        self.assertEqual(errors, [])

        # Keep exercising fresh schema connections while transactions insert
        # rows and update their flag. This also guards against reintroducing
        # trigger churn after the one-time migration has completed.
        schema_workers = 4
        writer_workers = 4
        writes_per_worker = 30
        barrier = threading.Barrier(schema_workers + writer_workers + 1)
        expected_latest = {race_event: True}
        writer_batches = []
        for worker_id in range(writer_workers):
            batch = []
            for index in range(writes_per_worker):
                event_id = f"race:stress:{worker_id}:{index}"
                final_flag = (worker_id + index) % 2 == 0
                batch.append((event_id, not final_flag, final_flag))
                expected_latest[event_id] = final_flag
            writer_batches.append(batch)

        def initialize_repeatedly():
            barrier.wait()
            for _ in range(30):
                db = self.connect()
                try:
                    sync_schema(db)
                    db.commit()
                finally:
                    db.close()

        def write_batch(batch):
            barrier.wait()
            for event_id, inserted_flag, final_flag in batch:
                self.write_insert_and_update(event_id, inserted_flag, final_flag)

        with ThreadPoolExecutor(max_workers=schema_workers + writer_workers) as pool:
            futures = [pool.submit(initialize_repeatedly) for _ in range(schema_workers)]
            futures.extend(pool.submit(write_batch, batch) for batch in writer_batches)
            barrier.wait()
            for future in futures:
                future.result(timeout=60)

        db = self.connect()
        try:
            rows = db.execute("""SELECT event_id,action,payload FROM sync_journal
              ORDER BY revision""").fetchall()
        finally:
            db.close()

        journal_by_event = {}
        for row in rows:
            journal_by_event.setdefault(row["event_id"], []).append(row)
        self.assertEqual(set(journal_by_event), set(expected_latest))
        latest_flags = set()
        for event_id, expected_flag in expected_latest.items():
            entries = journal_by_event[event_id]
            self.assertEqual(len(entries), 2, f"missing insert/update journal entry for {event_id}")
            self.assertEqual([entry["action"] for entry in entries], ["upsert", "upsert"])
            for entry in entries:
                payload = json.loads(entry["payload"])
                self.assertIn("event_time_needs_confirmation", payload, event_id)
                self.assertIs(type(payload["event_time_needs_confirmation"]), bool, event_id)
            latest_flag = json.loads(entries[-1]["payload"])["event_time_needs_confirmation"]
            self.assertIs(latest_flag, expected_flag, event_id)
            latest_flags.add(latest_flag)

        self.assertEqual(latest_flags, {False, True})

    def test_upgrade_keeps_caller_transaction_open(self):
        self.create_legacy_database()
        db = self.connect()
        db.execute("BEGIN")
        sync_schema(db)
        self.assertTrue(db.in_transaction)
        self.assertIn("event_time_needs_confirmation", db.execute(
            """SELECT sql FROM sqlite_master
              WHERE type='trigger' AND name='sync_journal_insert'"""
        ).fetchone()[0])

        # The upgrade is still part of the caller's transaction until it
        # commits; schema() must not commit work owned by its caller.
        observer = self.connect()
        try:
            self.assertNotIn("event_time_needs_confirmation", observer.execute(
                """SELECT sql FROM sqlite_master
                  WHERE type='trigger' AND name='sync_journal_insert'"""
            ).fetchone()[0])
        finally:
            observer.close()
        db.commit()
        db.close()


if __name__ == "__main__":
    unittest.main()
