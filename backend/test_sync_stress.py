"""Stress and robustness tests for v1 upload and v2 bidirectional sync.

These tests use only synthetic records and a temporary SQLite database. They
exercise concurrency, replay, pagination, tombstones and malformed payloads;
they do not touch production data or require Docker.
"""
import json
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import app as core

BASE_MS = 1_791_000_000_000
DEVICE_TOKEN = "stress-device-token"


def event(i, prefix="stress", **overrides):
    record = {
        "event_id": f"android:stress:{prefix}:{i:06d}",
        "timestamp_ms": BASE_MS + i * 1000,
        "amount_cents": 100 + (i % 9000),
        "currency": "CNY",
        "direction": "EXPENSE",
        "source": "WECHAT",
        "category": "FOOD",
        "merchant": f"merchant-{i % 37}",
    }
    record.update(overrides)
    return record


def record_of(item):
    return {key: value for key, value in item.items() if key != "event_id"}


class SyncStressBase(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="pfs-sync-stress-")
        self.old_db = core.DB
        self.old_token = core.DEVICE_TOKEN
        core.DB = Path(self.folder.name) / "finance.sqlite3"
        core.DEVICE_TOKEN = DEVICE_TOKEN
        self.headers = {"X-Device-Token": DEVICE_TOKEN}
        self.client = TestClient(core.app)

    def tearDown(self):
        self.client.close()
        core.DB = self.old_db
        core.DEVICE_TOKEN = self.old_token
        self.folder.cleanup()

    def upload(self, events):
        response = self.client.post(
            "/api/v1/transactions/batch",
            json={"events": events},
            headers=self.headers,
        )
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def push(self, operations):
        response = self.client.post(
            "/api/v2/sync/push",
            json={"operations": operations},
            headers=self.headers,
        )
        return response

    def pull_all(self, limit=500):
        cursor = 0
        items = []
        pages = 0
        while True:
            response = self.client.get(
                "/api/v2/sync/changes",
                params={"since": cursor, "limit": limit},
                headers=self.headers,
            )
            self.assertEqual(response.status_code, 200, response.text)
            page = response.json()
            items.extend(page["items"])
            pages += 1
            if not page["has_more"]:
                self.assertEqual(page["cursor"], page["head_revision"])
                return items, pages
            self.assertGreater(page["cursor"], cursor)
            cursor = page["cursor"]

    def count(self, table="transactions"):
        with core.db_connection() as db:
            return db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]

    def post_batch_in_thread(self, events, barrier):
        client = TestClient(core.app)
        try:
            barrier.wait(timeout=20)
            return client.post(
                "/api/v1/transactions/batch",
                json={"events": events},
                headers=self.headers,
            )
        finally:
            client.close()

    def push_in_thread(self, operations, barrier):
        client = TestClient(core.app)
        try:
            barrier.wait(timeout=20)
            return client.post(
                "/api/v2/sync/push",
                json={"operations": operations},
                headers=self.headers,
            )
        finally:
            client.close()


class UploadStressTests(SyncStressBase):
    def test_10k_events_in_max_batches(self):
        total = 10_000
        inserted = 0
        for start in range(0, total, 100):
            batch = [event(i) for i in range(start, min(start + 100, total))]
            result = self.upload(batch)
            self.assertEqual(result["accepted"], len(batch))
            self.assertEqual(result["inserted"], len(batch))
            self.assertEqual(result["already_present"], 0)
            inserted += result["inserted"]
        self.assertEqual(inserted, total)
        self.assertEqual(self.count(), total)
        self.assertEqual(self.count("sync_journal"), total)
        items, pages = self.pull_all(limit=500)
        self.assertEqual(len(items), total)
        self.assertEqual(pages, total // 500)
        self.assertEqual(len({item["revision"] for item in items}), total)

    def test_full_replay_and_overlapping_batches_are_idempotent(self):
        total = 2_000
        events = [event(i, prefix="replay") for i in range(total)]
        for start in range(0, total, 100):
            self.upload(events[start:start + 100])
        self.assertEqual(self.count(), total)
        for start in range(0, total, 100):
            result = self.upload(events[start:start + 100])
            self.assertEqual(result["inserted"], 0)
            self.assertEqual(result["already_present"], len(events[start:start + 100]))
        # Every event also appears in the next batch: still no duplicates.
        for start in range(50, total, 100):
            self.upload(events[start:start + 50])
        self.assertEqual(self.count(), total)
        self.assertEqual(self.count("sync_journal"), total)

    def test_concurrent_identical_batches_insert_exactly_once(self):
        workers = 16
        batch = [event(i, prefix="race") for i in range(100)]
        barrier = threading.Barrier(workers)
        with ThreadPoolExecutor(max_workers=workers) as pool:
            responses = list(pool.map(
                lambda _: self.post_batch_in_thread(batch, barrier),
                range(workers),
            ))
        for response in responses:
            self.assertEqual(response.status_code, 200, response.text)
        bodies = [response.json() for response in responses]
        self.assertEqual(sum(body["inserted"] for body in bodies), 100)
        self.assertEqual(sum(body["already_present"] for body in bodies), 100 * (workers - 1))
        self.assertEqual(self.count(), 100)
        self.assertEqual(self.count("sync_journal"), 100)

    def test_concurrent_distinct_batches_do_not_lose_writes(self):
        workers = 20
        per_worker = 5
        barrier = threading.Barrier(workers)
        batches = [
            [event(worker * per_worker + index, prefix="distinct")
             for index in range(per_worker)]
            for worker in range(workers)
        ]
        with ThreadPoolExecutor(max_workers=workers) as pool:
            responses = list(pool.map(
                lambda batch: self.post_batch_in_thread(batch, barrier),
                batches,
            ))
        for response in responses:
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(response.json()["inserted"], per_worker)
        self.assertEqual(self.count(), workers * per_worker)

    def test_malformed_and_boundary_batches_never_return_500(self):
        too_many = [event(i, prefix="too-many") for i in range(101)]
        cases = [
            ("empty", {"events": []}),
            ("too_many", {"events": too_many}),
            ("missing_events", {}),
            ("null_events", {"events": None}),
            ("missing_direction", {"events": [{k: v for k, v in event(1, prefix="bad").items()
                                                if k != "direction"}]}),
            ("negative_amount", {"events": [{**event(2, prefix="bad"), "amount_cents": -1}]}),
            ("overflow_amount", {"events": [{**event(3, prefix="bad"),
                                              "amount_cents": 100_000_000_001}]}),
            ("old_timestamp", {"events": [{**event(4, prefix="bad"), "timestamp_ms": 0}]}),
            ("future_timestamp", {"events": [{**event(5, prefix="bad"),
                                               "timestamp_ms": 4_200_000_000_000}]}),
            ("bad_currency", {"events": [{**event(6, prefix="bad"), "currency": "cny"}]}),
            ("bad_direction", {"events": [{**event(7, prefix="bad"), "direction": "SPEND"}]}),
            ("bad_source", {"events": [{**event(8, prefix="bad"), "source": "CASH"}]}),
            ("short_event_id", {"events": [{**event(9, prefix="bad"), "event_id": "short"}]}),
            ("bad_event_id", {"events": [{**event(10, prefix="bad"),
                                           "event_id": "android:bad id:1"}]}),
            ("long_merchant", {"events": [{**event(11, prefix="bad"), "merchant": "x" * 141}]}),
            ("long_category", {"events": [{**event(12, prefix="bad"), "category": "x" * 46}]}),
            ("wrong_type", {"events": [{**event(13, prefix="bad"), "amount_cents": "abc"}]}),
        ]
        for name, payload in cases:
            response = self.client.post(
                "/api/v1/transactions/batch", json=payload, headers=self.headers
            )
            self.assertIn(response.status_code, (400, 401, 403, 413, 422),
                          f"{name}: {response.status_code} {response.text[:200]}")
            self.assertNotEqual(response.status_code, 500, name)
        # Invalid JSON and oversized/blank bodies are rejected without crashing.
        for name, body in [
            ("invalid_json", b'{"events":['),
            ("blank", b""),
            ("wrong_root", json.dumps([event(14, prefix="bad")]).encode()),
        ]:
            response = self.client.post(
                "/api/v1/transactions/batch", content=body,
                headers={**self.headers, "Content-Type": "application/json"},
            )
            self.assertIn(response.status_code, (400, 413, 422), name)
            self.assertNotEqual(response.status_code, 500, name)
        self.assertEqual(self.count(), 0)

    def test_max_length_unicode_payloads(self):
        merchant = "商户" * 70          # 140 Unicode characters
        category = "分类" * 22          # 44 Unicode characters
        batch = []
        for i in range(100):
            event_id = ("android:stress:unicode:" + f"{i:06d}" + "x" * 200)[:130]
            self.assertEqual(len(event_id), 130)
            batch.append(event(i, prefix="unicode", event_id=event_id,
                               merchant=merchant, category=category))
        result = self.upload(batch)
        self.assertEqual(result["inserted"], 100)
        items, _ = self.pull_all(limit=500)
        sample = next(item for item in items if item["event_id"].startswith(
            "android:stress:unicode:"))
        self.assertEqual(len(sample["event_id"]), 130)
        self.assertEqual(sample["record"]["merchant"], merchant)
        self.assertEqual(sample["record"]["category"], category)
        self.assertEqual(len(sample["record"]["merchant"]), 140)
        self.assertEqual(len(sample["record"]["category"]), 44)

        # A ~1 MB body with overlong merchant values is rejected cleanly.
        large = {"events": [{**event(i, prefix="large"), "merchant": "x" * 10_000}
                            for i in range(100)]}
        response = self.client.post(
            "/api/v1/transactions/batch", json=large, headers=self.headers
        )
        self.assertEqual(response.status_code, 422, response.text)
        self.assertEqual(self.count(), 100)

    def test_duplicate_event_ids_inside_one_batch(self):
        duplicate = event(1, prefix="dup")
        response = self.upload([duplicate, duplicate])
        self.assertEqual(response["accepted"], 2)
        self.assertEqual(response["inserted"], 1)
        self.assertEqual(response["already_present"], 1)
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.count("sync_journal"), 1)

    def test_mid_batch_exception_does_not_leave_partial_rows(self):
        class Boom(RuntimeError):
            pass

        calls = {"count": 0}

        def failing_resolve(db, merchant, category):
            calls["count"] += 1
            if calls["count"] == 2:
                raise Boom("synthetic classification failure")
            return category

        client = TestClient(core.app, raise_server_exceptions=False)
        try:
            with patch("integration.resolve_category", side_effect=failing_resolve):
                response = client.post(
                    "/api/v1/transactions/batch",
                    json={"events": [event(1, prefix="atomic"), event(2, prefix="atomic")]},
                    headers=self.headers,
                )
            self.assertEqual(response.status_code, 500, response.text)
            self.assertEqual(self.count("transactions"), 0)
            self.assertEqual(self.count("sync_journal"), 0)
        finally:
            client.close()


class SyncStressTests(SyncStressBase):
    def make_operations(self, count, prefix="push"):
        operations = []
        for i in range(count):
            operations.append({
                "mutation_id": f"mutation:{prefix}:{i:06d}",
                "event_id": f"android:stress:{prefix}:{i:06d}",
                "base_revision": 0,
                "action": "upsert",
                "record": record_of(event(i, prefix=prefix)),
            })
        return operations

    def test_50_operation_push_replay_and_paginated_pull(self):
        operations = self.make_operations(50, prefix="batch50")
        response = self.push(operations)
        self.assertEqual(response.status_code, 200, response.text)
        results = response.json()["results"]
        self.assertEqual(len(results), 50)
        self.assertTrue(all(item["status"] == "applied" for item in results))
        revisions = [item["revision"] for item in results]
        self.assertEqual(revisions, sorted(revisions))
        self.assertEqual(self.count(), 50)
        journal = self.count("sync_journal")
        self.assertEqual(journal, 50)

        replay = self.push(operations)
        self.assertEqual(replay.status_code, 200, replay.text)
        replayed = replay.json()["results"]
        self.assertTrue(all(item.get("replayed") is True for item in replayed))
        self.assertEqual(self.count(), 50)
        self.assertEqual(self.count("sync_journal"), journal)

        items, pages = self.pull_all(limit=17)
        self.assertEqual(len(items), 50)
        self.assertGreaterEqual(pages, 3)
        self.assertEqual([item["revision"] for item in items],
                         sorted(item["revision"] for item in items))
        self.assertEqual(len({item["revision"] for item in items}), 50)

    def test_concurrent_distinct_pushes_do_not_lose_writes(self):
        workers = 20
        per_worker = 5
        barrier = threading.Barrier(workers)
        batches = [
            self.make_operations(per_worker, prefix=f"worker{worker}")
            for worker in range(workers)
        ]
        # Each worker's event IDs and mutation IDs are distinct.
        for worker, operations in enumerate(batches):
            for index, operation in enumerate(operations):
                operation["event_id"] = f"android:stress:concurrent:{worker}:{index}"
                operation["mutation_id"] = f"mutation:concurrent:{worker}:{index}"

        with ThreadPoolExecutor(max_workers=workers) as pool:
            responses = list(pool.map(
                lambda operations: self.push_in_thread(operations, barrier),
                batches,
            ))
        for response in responses:
            self.assertEqual(response.status_code, 200, response.text)
            self.assertTrue(all(item["status"] == "applied"
                                for item in response.json()["results"]))
        self.assertEqual(self.count(), workers * per_worker)

    def test_concurrent_same_revision_yields_one_winner(self):
        seed = self.push(self.make_operations(1, prefix="sameevent"))
        self.assertEqual(seed.status_code, 200, seed.text)
        revision = seed.json()["results"][0]["revision"]
        workers = 8
        barrier = threading.Barrier(workers)
        operations = []
        for worker in range(workers):
            operations.append([{
                "mutation_id": f"mutation:same:{worker}",
                "event_id": "android:stress:sameevent:000000",
                "base_revision": revision,
                "action": "upsert",
                "record": record_of(event(0, prefix="sameevent", merchant=f"worker-{worker}")),
            }])
        with ThreadPoolExecutor(max_workers=workers) as pool:
            responses = list(pool.map(
                lambda ops: self.push_in_thread(ops, barrier), operations
            ))
        statuses = []
        for response in responses:
            self.assertEqual(response.status_code, 200, response.text)
            statuses.append(response.json()["results"][0]["status"])
        self.assertEqual(statuses.count("applied"), 1)
        self.assertEqual(statuses.count("conflict"), workers - 1)
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.count("sync_journal"), 2)  # initial + one accepted edit

    def test_concurrent_same_mutation_id_is_idempotent(self):
        operation = self.make_operations(1, prefix="samemutation")[0]
        workers = 8
        barrier = threading.Barrier(workers)
        with ThreadPoolExecutor(max_workers=workers) as pool:
            responses = list(pool.map(
                lambda _: self.push_in_thread([operation], barrier), range(workers)
            ))
        revisions = []
        for response in responses:
            self.assertEqual(response.status_code, 200, response.text)
            result = response.json()["results"][0]
            self.assertEqual(result["status"], "applied")
            revisions.append(result["revision"])
        self.assertEqual(len(set(revisions)), 1)
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.count("sync_journal"), 1)

    def test_tombstone_storm_and_stale_v1_replay(self):
        total = 1_000
        events = [event(i, prefix="tombstone") for i in range(total)]
        for start in range(0, total, 100):
            self.upload(events[start:start + 100])
        items, _ = self.pull_all(limit=73)
        latest = {item["event_id"]: item["revision"]
                  for item in items if item["action"] == "upsert"}
        self.assertEqual(len(latest), total)

        deleted = events[:200]
        delete_operations = [{
            "mutation_id": f"mutation:delete:{index:06d}",
            "event_id": item["event_id"],
            "base_revision": latest[item["event_id"]],
            "action": "delete",
        } for index, item in enumerate(deleted)]
        for start in range(0, len(delete_operations), 50):
            response = self.push(delete_operations[start:start + 50])
            self.assertEqual(response.status_code, 200, response.text)
            self.assertTrue(all(result["status"] == "applied"
                                for result in response.json()["results"]))
        self.assertEqual(self.count(), total - 200)

        replay_present = 0
        for start in range(0, len(deleted), 100):
            replay = self.upload(deleted[start:start + 100])
            self.assertEqual(replay["inserted"], 0)
            replay_present += replay["already_present"]
        self.assertEqual(replay_present, 200)
        self.assertEqual(self.count(), total - 200)

        all_items, _ = self.pull_all(limit=89)
        deleted_actions = [item["action"] for item in all_items
                           if item["event_id"] in {e["event_id"] for e in deleted}]
        self.assertEqual(deleted_actions.count("delete"), 200)
        self.assertEqual(self.count("transactions"), total - 200)

    def test_push_max_length_unicode_payload(self):
        merchant = "商户" * 70
        category = "分类" * 22
        operations = []
        for i in range(50):
            record = record_of(event(i, prefix="pushmax"))
            record["merchant"] = merchant
            record["category"] = category
            operations.append({
                "mutation_id": f"mutation:pushmax:{i:06d}",
                "event_id": f"android:stress:pushmax:{i:06d}",
                "base_revision": 0,
                "action": "upsert",
                "record": record,
            })
        response = self.push(operations)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertTrue(all(item["status"] == "applied"
                            for item in response.json()["results"]))
        items, _ = self.pull_all(limit=100)
        sample = next(item for item in items if item["event_id"].startswith(
            "android:stress:pushmax:"))
        self.assertEqual(sample["record"]["merchant"], merchant)
        self.assertEqual(sample["record"]["category"], category)
        self.assertEqual(self.count(), 50)

    def test_push_validation_never_returns_500(self):
        base = self.make_operations(1, prefix="invalid")[0]
        cases = [
            ("empty", {"operations": []}),
            ("too_many", {"operations": [base] * 51}),
            ("missing_mutation_id", {"operations": [{k: v for k, v in base.items()
                                                      if k != "mutation_id"}]}),
            ("short_mutation_id", {"operations": [{**base, "mutation_id": "short"}]}),
            ("bad_mutation_id", {"operations": [{**base, "mutation_id": "bad id!"}]}),
            ("bad_event_id", {"operations": [{**base, "event_id": "bad id!"}]}),
            ("negative_revision", {"operations": [{**base, "base_revision": -1}]}),
            ("bad_action", {"operations": [{**base, "action": "rename"}]}),
            ("upsert_without_record", {"operations": [{k: v for k, v in base.items()
                                                         if k != "record"}]}),
            ("delete_with_record", {"operations": [{**base, "action": "delete"}]}),
            ("record_missing_direction", {"operations": [{**base, "record": {
                k: v for k, v in base["record"].items() if k != "direction"}}]}),
            ("record_bad_amount", {"operations": [{**base, "record": {
                **base["record"], "amount_cents": 0}}]}),
            ("record_bad_currency", {"operations": [{**base, "record": {
                **base["record"], "currency": "RMB"}}]}),
        ]
        for name, payload in cases:
            response = self.push(payload)
            self.assertIn(response.status_code, (400, 401, 403, 409, 413, 422),
                          f"{name}: {response.status_code} {response.text[:200]}")
            self.assertNotEqual(response.status_code, 500, name)
        self.assertEqual(self.count(), 0)


if __name__ == "__main__":
    unittest.main()
