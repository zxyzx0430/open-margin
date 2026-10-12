"""Compatibility tests for production v1.5 APIs merged into single OSS source."""
import csv
import io
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
import app as core

class UnifiedV15Routes(unittest.TestCase):
    def setUp(self):
        self.folder=tempfile.TemporaryDirectory(prefix="pfs-unified-compat-")
        self.client=TestClient(core.app)
        self.db_original=core.DB
        self.admin_original=core.ADMIN_TOKEN
        core.DB=Path(self.folder.name)/"test.sqlite3"
        core.ADMIN_TOKEN="synthetic-admin-test-token"
        self.headers={"X-Admin-Token":"synthetic-admin-test-token"}

    def tearDown(self):
        self.client.close()
        core.DB=self.db_original
        core.ADMIN_TOKEN=self.admin_original
        self.folder.cleanup()

    def test_production_compatibility_and_auth(self):
        self.assertEqual(self.client.get("/health").json()["version"],"1.5.0")
        paths={(r.path,tuple(r.methods)) for r in core.app.routes
               if getattr(r,"methods",None)}
        expected=["/api/v1/reconciliation/status","/api/v1/reconciliation/import-csv",
                  "/api/v1/reconciliation/review","/api/v1/merchant-alias",
                  "/api/v1/transactions/{tx_id}/split"]
        for path in expected:self.assertTrue(any(p==path for p,_ in paths),path)
        self.assertEqual(self.client.get(expected[0]).status_code,401)
        self.assertEqual(self.client.get(expected[0],headers=self.headers).status_code,200)
        c=self.client.post("/api/v1/merchant-alias",
                           json={"alias":"Example Shop","canonical":"Local Grocery"},
                           headers=self.headers)
        self.assertEqual(c.status_code,200,c.text)
        with core.db_connection() as db:
            db.execute("""INSERT INTO transactions(event_id,timestamp_ms,amount_cents,currency,direction,
                   source,category,merchant) VALUES ('unit:txn01',1791791400000,4500,'CNY','EXPENSE',
                   'MANUAL','FOOD','Example Shop')""")
            tx_id=db.execute("SELECT id FROM transactions").fetchone()[0]
        response=self.client.post(f"/api/v1/transactions/{tx_id}/split",
                 json={"splits":[{"category":"FOOD","amount_cents":2500},
                                  {"category":"OTHER","amount_cents":2000}]},
                 headers=self.headers)
        self.assertEqual(response.status_code,200,response.text)
        bad=self.client.post(f"/api/v1/transactions/{tx_id}/split",
                 json={"splits":[{"category":"FOOD","amount_cents":2500}]},headers=self.headers)
        self.assertEqual(bad.status_code,422)
        csvdata=("external_id,timestamp_ms,amount_cents,currency,direction,merchant\n"
                 "ext1,1791791400000,4500,CNY,EXPENSE,Example Shop\n")
        resp=self.client.post("/api/v1/reconciliation/import-csv",
             files={"file":("synthetic.csv",csvdata.encode(),"text/csv")},headers=self.headers)
        self.assertEqual(resp.status_code,200,resp.text)
        self.assertEqual(resp.json()["imported_rows"],1)
        self.assertIn("items",self.client.get("/api/v1/reconciliation/review",headers=self.headers).json())
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM transaction_splits").fetchone()[0],2)

if __name__=="__main__":
    unittest.main()
