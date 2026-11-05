"""Live HTTP stress probe for upload and v2 sync.

Requires an already-running *staging* server with a throwaway database.
Refuses any non-loopback target so it cannot touch production by accident.

Example:
    $env:FINANCE_STRESS_TOKEN = '<device token>'
    python scripts/stress_http_sync.py
    $env:FINANCE_STRESS_EVENTS = '20000'
    python scripts/stress_http_sync.py
"""
import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urlsplit

import requests

BASE = os.getenv("FINANCE_STRESS_URL", "http://127.0.0.1:18867").rstrip("/")
TOKEN = os.getenv("FINANCE_STRESS_TOKEN", "")
EVENTS = int(os.getenv("FINANCE_STRESS_EVENTS", "5000"))
WORKERS = int(os.getenv("FINANCE_STRESS_WORKERS", "8"))
HEADERS = {"X-Device-Token": TOKEN}
BASE_MS = 1_792_000_000_000

parsed = urlsplit(BASE)
if parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
    raise SystemExit("FINANCE_STRESS_URL must point at a loopback staging server")
if not TOKEN:
    raise SystemExit("FINANCE_STRESS_TOKEN is required")
if EVENTS < 100 or EVENTS % 100:
    raise SystemExit("FINANCE_STRESS_EVENTS must be a positive multiple of 100")
if WORKERS < 1 or WORKERS > 32:
    raise SystemExit("FINANCE_STRESS_WORKERS must be between 1 and 32")


def event(i, prefix):
    return {
        "event_id": f"android:http:{prefix}:{i:06d}",
        "timestamp_ms": BASE_MS + i,
        "amount_cents": 100 + (i % 9000),
        "currency": "CNY",
        "direction": "EXPENSE",
        "source": "WECHAT",
        "category": "FOOD",
        "merchant": f"merchant-{i % 41}",
    }


def record_of(item):
    return {key: value for key, value in item.items() if key != "event_id"}


def upload(events, session=None):
    client = session or requests.Session()
    try:
        response = client.post(BASE + "/api/v1/transactions/batch",
                               json={"events": events}, headers=HEADERS, timeout=120)
        response.raise_for_status()
        return response.json()
    finally:
        if session is None:
            client.close()


def push(operations, session=None):
    client = session or requests.Session()
    try:
        response = client.post(BASE + "/api/v2/sync/push",
                               json={"operations": operations}, headers=HEADERS, timeout=120)
        response.raise_for_status()
        return response.json()
    finally:
        if session is None:
            client.close()


def main():
    result = {"events": EVENTS, "workers": WORKERS}
    session = requests.Session()

    batches = [[event(i, "upload") for i in range(start, start + 100)]
               for start in range(0, EVENTS, 100)]
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = [pool.submit(upload, batch) for batch in batches]
        inserted = sum(future.result()["inserted"] for future in as_completed(futures))
    result["upload_sec"] = round(time.perf_counter() - started, 3)
    result["upload_inserted"] = inserted
    assert inserted == EVENTS, inserted

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = [pool.submit(upload, batch) for batch in batches]
        replayed = sum(future.result()["already_present"] for future in as_completed(futures))
    result["replay_sec"] = round(time.perf_counter() - started, 3)
    result["replay_already_present"] = replayed
    assert replayed == EVENTS, replayed

    race_batch = [event(i, "race") for i in range(100)]
    barrier = threading.Barrier(16)

    def race_upload(_):
        local = requests.Session()
        try:
            barrier.wait(timeout=30)
            return upload(race_batch, local)
        finally:
            local.close()

    with ThreadPoolExecutor(max_workers=16) as pool:
        race_results = list(pool.map(race_upload, range(16)))
    result["race_inserted"] = sum(item["inserted"] for item in race_results)
    result["race_already_present"] = sum(item["already_present"] for item in race_results)
    assert result["race_inserted"] == 100, result["race_inserted"]
    assert result["race_already_present"] == 1500, result["race_already_present"]

    push_count = max(50, (EVENTS // 10) // 50 * 50)
    push_batches = []
    for start in range(0, push_count, 50):
        operations = []
        for i in range(start, start + 50):
            operations.append({
                "mutation_id": f"mutation:http:{i:06d}",
                "event_id": f"android:http:push:{i:06d}",
                "base_revision": 0,
                "action": "upsert",
                "record": record_of(event(i, "push")),
            })
        push_batches.append(operations)
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = [pool.submit(push, batch) for batch in push_batches]
        applied = sum(1 for future in as_completed(futures)
                      for item in future.result()["results"]
                      if item["status"] == "applied")
    result["push_sec"] = round(time.perf_counter() - started, 3)
    result["push_applied"] = applied
    assert applied == push_count, applied

    cursor = 0
    pulled = 0
    pages = 0
    revisions = set()
    started = time.perf_counter()
    while True:
        response = session.get(BASE + "/api/v2/sync/changes",
                               params={"since": cursor, "limit": 500},
                               headers=HEADERS, timeout=120)
        response.raise_for_status()
        page = response.json()
        pulled += len(page["items"])
        pages += 1
        revisions.update(item["revision"] for item in page["items"])
        if not page["has_more"]:
            assert page["cursor"] == page["head_revision"]
            break
        assert page["cursor"] > cursor
        cursor = page["cursor"]
    result["pull_sec"] = round(time.perf_counter() - started, 3)
    result["pull_items"] = pulled
    result["pull_pages"] = pages
    result["pull_unique_revisions"] = len(revisions)
    expected = EVENTS + 100 + push_count
    assert pulled == expected, (pulled, expected)
    assert len(revisions) == expected, (len(revisions), expected)

    seed = push([{
        "mutation_id": "mutation:http:seed",
        "event_id": "android:http:same:000000",
        "base_revision": 0,
        "action": "upsert",
        "record": record_of(event(0, "same")),
    }])
    revision = seed["results"][0]["revision"]
    barrier = threading.Barrier(8)

    def race_push(worker):
        operation = {
            "mutation_id": f"mutation:http:same:{worker}",
            "event_id": "android:http:same:000000",
            "base_revision": revision,
            "action": "upsert",
            "record": {**record_of(event(0, "same")),
                       "merchant": f"worker-{worker}"},
        }
        barrier.wait(timeout=30)
        return push([operation])["results"][0]["status"]

    with ThreadPoolExecutor(max_workers=8) as pool:
        statuses = list(pool.map(race_push, range(8)))
    result["same_revision_applied"] = statuses.count("applied")
    result["same_revision_conflict"] = statuses.count("conflict")
    assert result["same_revision_applied"] == 1, statuses
    assert result["same_revision_conflict"] == 7, statuses

    def malformed(_):
        local = requests.Session()
        try:
            bad = event(0, "bad")
            bad["amount_cents"] = -1
            return local.post(BASE + "/api/v1/transactions/batch",
                              json={"events": [bad]}, headers=HEADERS,
                              timeout=30).status_code
        finally:
            local.close()

    with ThreadPoolExecutor(max_workers=20) as pool:
        codes = list(pool.map(malformed, range(20)))
    result["malformed_codes"] = sorted(set(codes))
    assert all(400 <= code < 500 for code in codes), codes

    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
