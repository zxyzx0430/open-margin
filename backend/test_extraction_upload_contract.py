"""Cross-layer contract: extraction fields -> v1 upload -> stored ledger.

The fixtures mirror TransactionParser/SmsParser outputs and UploadPayloadTest.
Every server-supported field must survive unchanged; local-only note/account
text must never appear in the stored record.
"""
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

import app as core

BASE_MS = 1_793_000_000_000
DEVICE_TOKEN = "contract-device-token"
ADMIN_TOKEN = "contract-admin-token"


class ExtractionUploadContractTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="pfs-extraction-contract-")
        self.old_db = core.DB
        self.old_device = core.DEVICE_TOKEN
        self.old_admin = core.ADMIN_TOKEN
        core.DB = Path(self.folder.name) / "finance.sqlite3"
        core.DEVICE_TOKEN = DEVICE_TOKEN
        core.ADMIN_TOKEN = ADMIN_TOKEN
        self.client = TestClient(core.app)
        self.device_headers = {"X-Device-Token": DEVICE_TOKEN}
        self.admin_headers = {"X-Admin-Token": ADMIN_TOKEN}

    def tearDown(self):
        self.client.close()
        core.DB = self.old_db
        core.DEVICE_TOKEN = self.old_device
        core.ADMIN_TOKEN = self.old_admin
        self.folder.cleanup()

    def test_every_uploaded_field_survives_extraction_to_storage(self):
        fixtures = [
            {
                "event_id": "android:contract:wechat-expense",
                "timestamp_ms": BASE_MS + 1_000,
                "amount_cents": 1230,
                "currency": "CNY",
                "direction": "EXPENSE",
                "source": "WECHAT",
                "category": "FOOD",
                "merchant": "麦当劳",
            },
            {
                "event_id": "android:contract:wechat-transfer",
                "timestamp_ms": BASE_MS + 2_000,
                "amount_cents": 10_000,
                "currency": "CNY",
                "direction": "INCOME",
                "source": "WECHAT",
                "category": "TRANSFER",
                "merchant": "张三",
            },
            {
                "event_id": "android:contract:alipay-refund",
                "timestamp_ms": BASE_MS + 3_000,
                "amount_cents": 1234,
                "currency": "CNY",
                "direction": "INCOME",
                "source": "ALIPAY",
                "category": "OTHER",
                "merchant": "",
            },
            {
                "event_id": "android:contract:bank-salary",
                "timestamp_ms": BASE_MS + 4_000,
                "amount_cents": 800_000,
                "currency": "CNY",
                "direction": "INCOME",
                "source": "BANK_SMS",
                "category": "SALARY",
                "merchant": "",
            },
            {
                "event_id": "android:contract:usd-shopping",
                "timestamp_ms": BASE_MS + 5_000,
                "amount_cents": 999,
                "currency": "USD",
                "direction": "EXPENSE",
                "source": "ALIPAY",
                "category": "SHOPPING",
                "merchant": "Apple Store",
            },
            {
                "event_id": "android:contract:accessibility-transfer",
                "timestamp_ms": BASE_MS + 6_000,
                "amount_cents": 8800,
                "currency": "CNY",
                "direction": "INCOME",
                "source": "WECHAT",
                "category": "TRANSFER",
                "merchant": "",
            },
            {
                "event_id": "android:contract:max-merchant",
                "timestamp_ms": BASE_MS + 7_000,
                "amount_cents": 4321,
                "currency": "CNY",
                "direction": "EXPENSE",
                "source": "MANUAL",
                "category": "SHOPPING",
                "merchant": "店" * 140,
            },
        ]
        response = self.client.post(
            "/api/v1/transactions/batch",
            json={"events": fixtures},
            headers=self.device_headers,
        )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["inserted"], len(fixtures))

        stored = self.client.get(
            "/api/v1/transactions", params={"limit": 50}, headers=self.admin_headers
        )
        self.assertEqual(stored.status_code, 200, stored.text)
        by_event = {item["event_id"]: item for item in stored.json()["items"]}
        self.assertEqual(set(by_event), {item["event_id"] for item in fixtures})
        for fixture in fixtures:
            row = by_event[fixture["event_id"]]
            for key in (
                "event_id", "timestamp_ms", "amount_cents", "currency",
                "direction", "source", "category", "merchant",
            ):
                self.assertEqual(row[key], fixture[key], f"{fixture['event_id']}:{key}")
            self.assertIn(row["review_status"], ("unverified", "possible_duplicate"))
            # Local-only evidence never enters the server schema.
            self.assertNotIn("note", row)
            self.assertNotIn("account", row)

    def test_extraction_upload_is_idempotent_under_replay(self):
        event = {
            "event_id": "android:contract:replay",
            "timestamp_ms": BASE_MS + 10_000,
            "amount_cents": 1881,
            "currency": "CNY",
            "direction": "EXPENSE",
            "source": "WECHAT",
            "category": "FOOD",
            "merchant": "美宜佳",
        }
        first = self.client.post(
            "/api/v1/transactions/batch", json={"events": [event]},
            headers=self.device_headers,
        )
        second = self.client.post(
            "/api/v1/transactions/batch", json={"events": [event]},
            headers=self.device_headers,
        )
        self.assertEqual(first.json()["inserted"], 1)
        self.assertEqual(second.json()["inserted"], 0)
        self.assertEqual(second.json()["already_present"], 1)
        stored = self.client.get(
            "/api/v1/transactions", params={"limit": 50}, headers=self.admin_headers
        ).json()["items"]
        self.assertEqual(len(stored), 1)
        self.assertEqual(stored[0]["amount_cents"], 1881)
        self.assertEqual(stored[0]["merchant"], "美宜佳")


if __name__ == "__main__":
    unittest.main()
