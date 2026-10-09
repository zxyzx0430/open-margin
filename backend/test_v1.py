import io
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
import app as core

class TestV1(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        core.DB=Path(self.tmp.name)/"transactions.sqlite"
        core.DEVICE_TOKEN="test-device-key-1234567890"
        core.ADMIN_TOKEN="test-admin-key-1234567890"
        self.client=TestClient(core.app)
        self.device={"X-Device-Token":core.DEVICE_TOKEN}
        self.admin={"X-Admin-Token":core.ADMIN_TOKEN}
        self.now=int(time.time()*1000)
    def tearDown(self):
        self.client.close()
        self.tmp.cleanup()

    def event(self,src,eid,amount=1990):
        return {"event_id":eid,"timestamp_ms":self.now,"amount_cents":amount,
                "currency":"CNY","direction":"EXPENSE","source":src,
                "merchant":"Cafe","category":"FOOD"}
    def test_sync_duplicates_and_review(self):
        url="/api/v1/transactions/batch"
        self.assertEqual(self.client.post(url,json={"events":[self.event("WECHAT","w:11111111")]},headers=self.device).json()["inserted"],1)
        self.assertEqual(self.client.post(url,json={"events":[self.event("WECHAT","w:11111111")]},headers=self.device).json()["already_present"],1)
        self.assertEqual(self.client.post(url,json={"events":[self.event("BANK_SMS","b:11111111")]},headers=self.device).json()["inserted"],1)
        rows=self.client.get("/api/v1/transactions",headers=self.admin).json()["items"]
        self.assertEqual(len(rows),2)
        self.assertTrue(all(r["review_status"]=="possible_duplicate" for r in rows))
        self.assertEqual(self.client.patch(f"/api/v1/transactions/{rows[0]['id']}/review",json={"status":"excluded"},headers=self.admin).status_code,200)
        s=self.client.get("/api/v1/summary",params={"start_ms":self.now-1000,"end_ms":self.now+1000},headers=self.admin).json()
        self.assertEqual(s["groups"][0]["total_cents"],1990)
        self.assertEqual(self.client.post(url,json={"events":[self.event("WECHAT","x:22222222")]},headers={"X-Device-Token":"wrong"}).status_code,401)

    def test_subscriptions_use_wallos_instead_of_duplicate_store(self):
        p={"name":"Example","amount_cents":1234,"currency":"USD","cadence":"monthly",
            "next_due_ms":self.now+86400000,"payment_channel":"Google Play"}
        self.assertEqual(self.client.post("/api/v1/subscriptions",json=p,headers=self.admin).status_code,410)
        self.assertEqual(self.client.put("/api/v1/subscriptions/1",json=p,headers=self.admin).status_code,410)
        self.assertEqual(self.client.get("/api/v1/subscriptions").status_code,401)
        item={**p,"id":1,"active":True,"source":"Wallos"}
        with patch("wallos_api.get_all",return_value=[item]):
            r=self.client.get("/api/v1/dashboard",headers=self.admin).json()
            self.assertEqual(r["subscriptions"][0]["name"],"Example")
            self.assertEqual(r["subscription_source"],"Wallos")
            output=self.client.get("/api/v1/subscriptions",headers=self.admin).json()
            self.assertEqual(output["source"],"Wallos")
            self.assertEqual(output["items"],[item])
            self.assertEqual(self.client.get("/api/v1/subscriptions",params={"active_only":False},headers=self.admin).json()["items"],[item])

    def test_standard_csv_import(self):
        csv_data=("timestamp_ms,amount_cents,currency,direction,source,merchant,category\n"
                  +str(self.now)+",2550,CNY,EXPENSE,ALIPAY,Taxi,TRANSPORT\n")
        args={"file":("expenses.csv",csv_data.encode(),"text/csv")}
        r=self.client.post("/api/v1/import/standard-csv",files=args,headers=self.admin)
        self.assertEqual(r.status_code,200,r.text)
        self.assertEqual(r.json()["inserted"],1)
        r2=self.client.post("/api/v1/import/standard-csv",files=args,headers=self.admin)
        self.assertEqual(r2.json()["inserted"],0)
        bad=self.client.post("/api/v1/import/standard-csv",files={"file":("bad.csv",b"merchant\nnope\n","text/csv")},headers=self.admin)
        self.assertEqual(bad.status_code,422)

    def test_export_csv_requires_admin(self):
        self.assertEqual(self.client.get("/api/v1/export/standard-csv").status_code,401)
        response=self.client.get("/api/v1/export/standard-csv",headers=self.admin)
        self.assertEqual(response.status_code,200)
        self.assertIn("timestamp_ms,amount_cents",response.text)

    def test_public_boundary(self):
        self.assertEqual(self.client.get("/health").status_code,200)
        landing=self.client.get("/",follow_redirects=False)
        self.assertEqual(landing.status_code,302)
        self.assertEqual(landing.headers["location"],"/finance/wallos/expenses.php")
        self.assertEqual(self.client.get("/mcp/").status_code,401)
        self.assertEqual(self.client.get("/api/v1/dashboard").status_code,401)

if __name__=="__main__":unittest.main()
