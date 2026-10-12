"""Transactional tests for v2 replication and legacy-client replay safety."""
import tempfile
import unittest
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
from pathlib import Path
from fastapi.testclient import TestClient
import app as core


class SyncV2Tests(unittest.TestCase):
    def setUp(self):
        self.folder=tempfile.TemporaryDirectory(prefix="pfs-bidirectional-")
        self.old_db=core.DB
        self.old_token=core.DEVICE_TOKEN
        core.DB=Path(self.folder.name)/"finance.sqlite3"
        core.DEVICE_TOKEN="synthetic-device-token"
        self.client=TestClient(core.app)
        self.headers={"X-Device-Token":"synthetic-device-token"}
        self.path="/api/v2/sync"
        self.event={
            "event_id":"android:fake-device:101",
            "timestamp_ms":1791791400000,"amount_cents":1230,
            "currency":"CNY","direction":"EXPENSE","source":"WECHAT",
            "category":"FOOD","merchant":"Demo cafe"
        }

    def tearDown(self):
        self.client.close()
        core.DB=self.old_db
        core.DEVICE_TOKEN=self.old_token
        self.folder.cleanup()

    def pull(self,since=0,limit=100):
        response=self.client.get(f"{self.path}/changes",
                                 params={"since":since,"limit":limit},headers=self.headers)
        self.assertEqual(response.status_code,200,response.text)
        return response.json()

    def push(self,mutation_id,action="upsert",revision=0,event=None):
        record={k:v for k,v in (event or self.event).items() if k!="event_id"}
        op={"mutation_id":mutation_id,"event_id":(event or self.event)["event_id"],
            "base_revision":revision,"action":action}
        if action=="upsert":op["record"]=record
        response=self.client.post(f"{self.path}/push",json={"operations":[op]},
                                  headers=self.headers)
        self.assertEqual(response.status_code,200,response.text)
        return response.json()["results"][0]

    def test_stable_server_identity_and_rollback_head(self):
        """Different DB volumes need distinct identities even at the same URL."""
        first=self.pull()
        server_id=first["server_id"]
        self.assertEqual(len(server_id),32)
        self.assertEqual(first["head_revision"],0)
        created=self.push("server-id-test-create-001")
        self.assertEqual(created["status"],"applied")
        same=self.pull()
        self.assertEqual(same["server_id"],server_id)
        self.assertGreaterEqual(same["head_revision"],created["revision"])
        old_db=core.DB
        try:
            core.DB=Path(self.folder.name)/"another-clean-volume.sqlite3"
            different=self.pull()
            self.assertNotEqual(different["server_id"],server_id)
            self.assertEqual(different["head_revision"],0)
        finally:
            core.DB=old_db
        again=self.pull()
        self.assertEqual(again["server_id"],server_id)
        self.assertEqual(again["head_revision"],same["head_revision"])

    def test_security_bootstrap_and_paginated_pull(self):
        self.assertEqual(self.client.get(f"{self.path}/changes").status_code,401)
        with core.db_connection() as db:
            db.execute("""INSERT INTO transactions(event_id,timestamp_ms,amount_cents,currency,
                direction,source,category,merchant)
                VALUES(?,?,?,?,?,?,?,?)""",tuple(self.event[k] for k in
                  ["event_id","timestamp_ms","amount_cents","currency","direction","source","category","merchant"]))
        first=self.pull()
        self.assertEqual(len(first["items"]),1)
        self.assertEqual(first["items"][0]["record"]["amount_cents"],1230)
        revision=first["cursor"]
        # Both server UI reviews and explicit database edits must generate deltas.
        with core.db_connection() as db:
            db.execute("UPDATE transactions SET amount_cents=?,review_status='confirmed' WHERE event_id=?",
                       (1886,self.event["event_id"]))
        second=self.pull(revision)
        self.assertEqual(second["items"][0]["record"]["review_status"],"confirmed")
        self.assertEqual(second["items"][0]["record"]["amount_cents"],1886)
        self.assertEqual(self.pull(second["cursor"])["items"],[])

    def test_push_idempotent_conflict_and_server_wins_when_stale(self):
        first=self.push("op-create-0001")
        self.assertEqual(first["status"],"applied")
        revision=first["revision"]
        self.assertEqual(self.push("op-create-0001")["revision"],revision)
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],1)
            db.execute("UPDATE transactions SET merchant='Server edit' WHERE event_id=?",
                       (self.event["event_id"],))
        current=self.pull(revision)["items"][-1]
        self.assertEqual(current["record"]["merchant"],"Server edit")
        conflict=self.push("op-stale-0002",revision=revision,
                           event={**self.event,"amount_cents":9999})
        self.assertEqual(conflict["status"],"conflict")
        self.assertEqual(conflict["current"]["record"]["merchant"],"Server edit")
        self.assertEqual(conflict["current"]["revision"],current["revision"])
        accepted=self.push("op-current-0003",revision=current["revision"],
                           event={**self.event,"amount_cents":9999})
        self.assertEqual(accepted["status"],"applied")
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT amount_cents FROM transactions").fetchone()[0],9999)
            # Server review status must not be reset by phone updates.
            db.execute("UPDATE transactions SET review_status='confirmed'")
        last=self.pull(accepted["revision"])["items"][-1]
        self.assertEqual(last["record"]["review_status"],"confirmed")

    def test_phone_review_exclusion_is_bidirectional(self):
        record={**self.event,"review_status":"excluded"}
        first=self.push("review-excluded-001",event=record)
        self.assertEqual(first["status"],"applied")
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT review_status FROM transactions").fetchone()[0],
                             "excluded")
        pulled=self.pull()
        self.assertEqual(pulled["items"][-1]["record"]["review_status"],"excluded")
        restored={**self.event,"review_status":"unverified"}
        second=self.push("review-restored-002",revision=first["revision"],event=restored)
        self.assertEqual(second["status"],"applied")
        self.assertEqual(self.pull(first["revision"])["items"][-1]["record"]["review_status"],
                         "unverified")
        # A free-floating refund flag without a verified server refund link is invalid.
        invalid=self.client.post(self.path+"/push",headers=self.headers,json={"operations":[{
            "mutation_id":"review-invalid-refund-003","event_id":self.event["event_id"],
            "base_revision":second["revision"],"action":"upsert",
            "record":{**self.event,"review_status":"refund"}
        }]})
        self.assertEqual(invalid.status_code,422)

    def test_mutation_id_cannot_be_reused_with_changed_payload(self):
        first=self.push("op-hash-stable-001")
        self.assertEqual(first["status"],"applied")
        self.assertEqual(self.push("op-hash-stable-001"),{
            "mutation_id":"op-hash-stable-001",
            "status":"applied","revision":first["revision"],"replayed":True
        })
        updated={**self.event,"merchant":"A different edit"}
        record={k:v for k,v in updated.items() if k!="event_id"}
        response=self.client.post(f"{self.path}/push",json={"operations":[{
            "mutation_id":"op-hash-stable-001","event_id":updated["event_id"],
            "base_revision":0,"action":"upsert","record":record
        }]},headers=self.headers)
        self.assertEqual(response.status_code,409)
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT merchant FROM transactions").fetchone()[0],
                             self.event["merchant"])

    def test_phone_delete_tombstone_and_legacy_replay(self):
        first=self.push("op-create-0004")
        deletion=self.push("op-delete-0005",action="delete",revision=first["revision"])
        self.assertEqual(deletion["status"],"applied")
        self.assertEqual(self.pull(first["revision"])["items"][-1]["action"],"delete")
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],0)
        old=self.client.post("/api/v1/transactions/batch",
            json={"events":[self.event]},headers=self.headers)
        self.assertEqual(old.status_code,200)
        self.assertEqual(old.json()["inserted"],0)
        self.assertEqual(old.json()["already_present"],1)
        again=self.push("op-delete-0005",action="delete",revision=first["revision"])
        self.assertEqual(again["status"],"applied")
        self.assertEqual(again["revision"],deletion["revision"])
        # Tombstoned records cannot be re-added without an explicit resolution.
        self.assertEqual(self.push("op-old-resurrection-0006")["status"],"conflict")

    def test_concurrent_writes_on_same_revision_return_one_conflict(self):
        """Two offline clients cannot both overwrite the same base revision."""
        first=self.push("seed-concurrent-001")
        revision=first["revision"]
        barrier=threading.Barrier(2)
        original_latest=__import__("sync_v2").latest
        seen=threading.local()

        def slow_read(db,event_id):
            row=original_latest(db,event_id)
            if event_id==self.event["event_id"] and not getattr(seen,"waited",False):
                seen.waited=True
                # Widen read-to-write gap to deterministically exercise the
                # race previously accepted as two successful mutations.
                time.sleep(0.13)
            return row

        def concurrent_edit(mid,merchant):
            barrier.wait(timeout=10)
            operation={
                "mutation_id":mid,
                "event_id":self.event["event_id"],
                "base_revision":revision,
                "action":"upsert",
                "record":{k:v for k,v in {**self.event,"merchant":merchant}.items()
                          if k!="event_id"}
            }
            client=TestClient(core.app)
            try:
                response=client.post(f"{self.path}/push",
                    json={"operations":[operation]},headers=self.headers)
                self.assertEqual(response.status_code,200,response.text)
                return response.json()["results"][0]
            finally:
                client.close()

        with patch("sync_v2.latest",side_effect=slow_read):
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures=[
                    pool.submit(concurrent_edit,"concurrent-phone-002","phone"),
                    pool.submit(concurrent_edit,"concurrent-web-003","web")
                ]
                results=[future.result(timeout=15) for future in futures]
        self.assertEqual(sorted(r["status"] for r in results),["applied","conflict"])
        winner=next(r for r in results if r["status"]=="applied")
        loser=next(r for r in results if r["status"]=="conflict")
        self.assertEqual(loser["current"]["revision"],winner["revision"])
        with core.db_connection() as db:
            count=db.execute("SELECT COUNT(*) FROM sync_journal WHERE event_id=?",
                (self.event["event_id"],)).fetchone()[0]
            self.assertEqual(count,2)  # initial revision + exactly one accepted edit

    def test_delete_before_upload_and_genuine_server_insert(self):
        tomb=self.push("op-delete-first-7",action="delete",revision=0)
        self.assertEqual(tomb["status"],"applied")
        self.assertEqual(self.pull()["items"][0]["action"],"delete")
        with core.db_connection() as db:
            db.execute("""INSERT INTO transactions(event_id,timestamp_ms,amount_cents,currency,
                direction,source,category,merchant)
                VALUES('wallos:manual:integration-test',1791791400000,2500,'CNY',
                       'EXPENSE','MANUAL','OTHER','Server manual')""")
        all_changes=self.pull(limit=1)
        self.assertTrue(all_changes["has_more"])
        more=self.pull(all_changes["cursor"],1)
        self.assertFalse(more["has_more"])
        self.assertEqual(more["items"][0]["event_id"],"wallos:manual:integration-test")


if __name__=="__main__":
    unittest.main()
