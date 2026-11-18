"""Owner-only VPS pairing payload and secrecy regression tests."""
import os
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

import app as core
from config import PUBLIC_BASE


class PairingPayloadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.prev_db = core.DB
        self.prev_token = core.DEVICE_TOKEN
        self.prev_wallos_token = os.environ.get("FINANCE_WALLOS_LEDGER_TOKEN")
        core.DB = Path(self.tmp.name) / "finance.sqlite3"
        core.DEVICE_TOKEN = "test-device-key-abcdef123456"
        os.environ["FINANCE_WALLOS_LEDGER_TOKEN"] = "test-wall-owner-private-token-long-enough"
        self.client = TestClient(core.app)
        self.headers = {"X-Wallos-Ledger-Token": os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]}

    def tearDown(self):
        core.DB = self.prev_db
        core.DEVICE_TOKEN = self.prev_token
        if self.prev_wallos_token is None:
            os.environ.pop("FINANCE_WALLOS_LEDGER_TOKEN", None)
        else:
            os.environ["FINANCE_WALLOS_LEDGER_TOKEN"] = self.prev_wallos_token
        self.tmp.cleanup()

    def test_requires_server_side_wallos_credential_and_post(self):
        path = "/api/v1/wallos-ledger/pairing"
        self.assertEqual(self.client.post(path).status_code, 401)
        self.assertEqual(
            self.client.post(path, headers={"X-Wallos-Ledger-Token": "wrong"}).status_code, 401
        )
        self.assertEqual(self.client.get(path, headers=self.headers).status_code, 405)
        # The browser's regular admin session must not bypass server-to-server auth.
        self.assertEqual(self.client.post(path, headers={"X-Device-Token": core.DEVICE_TOKEN}).status_code, 401)

    def test_payload_matches_windows_v1_contract_and_server_identity(self):
        response = self.client.post("/api/v1/wallos-ledger/pairing", headers=self.headers)
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(body["v"], 1)
        self.assertEqual(body["kind"], "open-margin-pair")
        self.assertEqual(body["url"], PUBLIC_BASE)
        self.assertEqual(body["token"], core.DEVICE_TOKEN)
        self.assertGreaterEqual(len(body["server_id"]), 16)
        self.assertEqual(response.headers.get("cache-control"), "no-store, max-age=0")
        self.assertEqual(response.headers.get("referrer-policy"), "no-referrer")

        sync = self.client.get("/api/v2/sync/changes?since=0&limit=1",
                               headers={"X-Device-Token": core.DEVICE_TOKEN})
        self.assertEqual(sync.status_code, 200)
        self.assertEqual(body["server_id"], sync.json()["server_id"])

    def test_never_issues_empty_device_credential(self):
        core.DEVICE_TOKEN = ""
        response = self.client.post("/api/v1/wallos-ledger/pairing", headers=self.headers)
        self.assertEqual(response.status_code, 503)
        self.assertNotIn("token", str(response.json()))


if __name__ == "__main__":
    unittest.main()
