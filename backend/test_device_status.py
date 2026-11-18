import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from fastapi.testclient import TestClient

import app as m
import device_status


def _event(index: int = 1) -> dict:
    return {
        "event_id": f"WECHAT:device-status-{index:08d}",
        "timestamp_ms": 1791500000000 + index,
        "amount_cents": 1850,
        "direction": "EXPENSE",
        "source": "WECHAT",
        "merchant": "food",
        "category": "FOOD",
    }


class DeviceStatusTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        m.DB = root / "db.sqlite3"
        m.DEVICE_TOKEN = "device-test-token"
        m.ADMIN_TOKEN = "admin-test-token"
        device_status.DEVICE_SEEN_FILE = root / "device-seen.json"
        self.marker = device_status.DEVICE_SEEN_FILE
        self.client = TestClient(m.app)
        self.device_headers = {
            "X-Device-Token": "device-test-token",
            "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 16; test)",
        }
        self.admin_headers = {"X-Admin-Token": "admin-test-token"}

    def tearDown(self):
        self.tmp.cleanup()

    def test_status_requires_admin_token(self):
        self.assertEqual(self.client.get("/api/v1/device-status").status_code, 401)
        self.assertEqual(
            self.client.get(
                "/api/v1/device-status",
                headers={"X-Admin-Token": "wrong-token"},
            ).status_code,
            401,
        )

    def test_unseen_until_a_device_syncs(self):
        response = self.client.get("/api/v1/device-status", headers=self.admin_headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"ok": True, "seen": False, "at": None})
        self.assertFalse(self.marker.exists())

    def test_batch_sync_marks_device_seen(self):
        ingest = self.client.post(
            "/api/v1/transactions/batch",
            json={"events": [_event()]},
            headers=self.device_headers,
        )
        self.assertEqual(ingest.status_code, 200)

        status = self.client.get("/api/v1/device-status", headers=self.admin_headers)
        self.assertEqual(status.status_code, 200)
        body = status.json()
        self.assertTrue(body["seen"])
        parsed = datetime.fromisoformat(body["at"].replace("Z", "+00:00"))
        self.assertEqual(parsed.tzinfo, timezone.utc)

        raw = self.marker.read_text(encoding="utf-8")
        self.assertNotIn("device-test-token", raw)
        self.assertNotIn("admin-test-token", raw)
        self.assertIn("Dalvik/2.1.0", raw)
        self.assertNotIn("device-test-token", status.text)

    def test_v2_sync_marks_device_seen(self):
        response = self.client.get(
            "/api/v2/sync/changes?since=0&limit=1",
            headers=self.device_headers,
        )
        self.assertEqual(response.status_code, 200)
        status = self.client.get("/api/v1/device-status", headers=self.admin_headers)
        self.assertTrue(status.json()["seen"])

    def test_local_probe_does_not_mark_device_seen(self):
        response = self.client.get(
            "/api/v2/sync/changes?since=0&limit=1",
            headers={
                "X-Device-Token": "device-test-token",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; PowerShell)",
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertFalse(self.marker.exists())

    def test_rejected_sync_does_not_mark_device_seen(self):
        response = self.client.post(
            "/api/v1/transactions/batch",
            json={"events": [_event(2)]},
            headers={"X-Device-Token": "wrong-token"},
        )
        self.assertEqual(response.status_code, 401)
        self.assertFalse(self.marker.exists())

    def test_marker_write_failure_does_not_break_sync(self):
        blocked = Path(self.tmp.name) / "blocked"
        blocked.write_text("not a directory", encoding="utf-8")
        device_status.DEVICE_SEEN_FILE = blocked / "device-seen.json"

        response = self.client.post(
            "/api/v1/transactions/batch",
            json={"events": [_event(3)]},
            headers=self.device_headers,
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["inserted"], 1)

        status = self.client.get("/api/v1/device-status", headers=self.admin_headers)
        self.assertEqual(status.status_code, 200)
        self.assertFalse(status.json()["seen"])


if __name__ == "__main__":
    unittest.main()
