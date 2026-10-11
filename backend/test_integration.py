"""Decommissioned Wallos subscription/payment matching endpoints.
All tests use temporary databases; transaction, refund and rule APIs remain.
"""
import os
import tempfile
import unittest
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
from fastapi.testclient import TestClient
import app as core

def stamp(y,m,d):
    return int(datetime(y,m,d,tzinfo=ZoneInfo("Asia/Shanghai")).timestamp()*1000)

class RetiredSubscriptionReconciliationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.previous=(core.DB,os.environ.get("FINANCE_WALLOS_LEDGER_TOKEN"))
        core.DB=Path(self.tmp.name)/"finance-test.sqlite3"
        os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]="retired-subscription-reconciliation-fixture-token"
        self.client=TestClient(core.app)
        self.h={"X-Wallos-Ledger-Token":os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]}
        self.path="/api/v1/wallos-ledger/"
    def tearDown(self):
        self.client.close()
        core.DB,secret=self.previous
        if secret is None:os.environ.pop("FINANCE_WALLOS_LEDGER_TOKEN",None)
        else:os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]=secret
        self.tmp.cleanup()
    def tx(self, y=2026,m=10,d=8,cents=1500,merchant="Training expense",direction="EXPENSE"):
        with core.db_connection() as conn:
            key=f"test:{y}-{m}-{d}:{cents}:{direction}:{merchant}"
            cur=conn.execute("""INSERT INTO transactions(event_id,timestamp_ms,amount_cents,currency,
                direction,source,category,merchant) VALUES(?,?,?,'CNY',?,'MANUAL','OTHER',?)""",
                (key,stamp(y,m,d),cents,direction,merchant))
            return cur.lastrowid
    def test_subscription_reconciliation_dashboard_removed(self):
        r=self.client.get(self.path+"integration",params={"month":"2026-10"},headers=self.h)
        self.assertEqual(r.status_code,404)
    def test_subscription_payment_link_removed(self):
        r=self.client.post(self.path+"integration/link",headers=self.h,json={
            "tx_id":1,"subscription_id":42,"cycle_key":"2026-10-15"})
        self.assertEqual(r.status_code,404)
    def test_subscription_payment_unlink_removed(self):
        self.assertEqual(self.client.delete(self.path+"integration/link/1",headers=self.h).status_code,404)
    def test_recurring_discovery_removed(self):
        self.assertEqual(self.client.get(self.path+"integration/discover",headers=self.h).status_code,404)
    def test_integrated_trends_removed(self):
        self.assertEqual(self.client.get(self.path+"integration/trends",headers=self.h).status_code,404)
    def test_reconciliation_subscription_creation_removed(self):
        self.assertEqual(self.client.post(self.path+"integration/subscribe",headers=self.h,json={
            "name":"Synthetic","amount_cents":1000,"currency":"CNY",
            "cadence":"monthly","next_due_ms":1800000000000}).status_code,404)
    def test_retired_action_cannot_reclassify_transaction(self):
        tx=self.tx()
        self.client.post(self.path+"integration/link",headers=self.h,json={
            "tx_id":tx,"subscription_id":42,"cycle_key":"2026-10-15"})
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM tx_subscription_links").fetchone()[0],0)
            self.assertEqual(db.execute("SELECT category FROM transactions WHERE id=?",(tx,)).fetchone()[0],"OTHER")
    def test_manual_expense_still_appears_today_or_month(self):
        self.tx(cents=1886)
        r=self.client.get(self.path+"month",params={"month":"2026-10"},headers=self.h)
        self.assertEqual(r.status_code,200)
        self.assertEqual(sum(x["total_cents"] for x in r.json()["daily_totals"] if x["direction"]=="EXPENSE"),1886)
    def test_transaction_editor_still_available(self):
        tx=self.tx()
        r=self.client.patch(self.path+f"integration/transaction/{tx}",headers=self.h,
            json={"amount_cents":1886,"merchant":"Edited transaction"})
        self.assertEqual(r.status_code,200,r.text)
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT amount_cents FROM transactions WHERE id=?",(tx,)).fetchone()[0],1886)
            self.assertEqual(db.execute("SELECT count(*) FROM transaction_edits WHERE tx_id=?",(tx,)).fetchone()[0],2)
    def test_general_merchant_rules_still_available(self):
        r=self.client.post(self.path+"integration/rules",headers=self.h,
            json={"needle":"Example synthetic","category":"FOOD"})
        self.assertEqual(r.status_code,200,r.text)
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM merchant_rules").fetchone()[0],1)
    def test_refund_linking_retained_without_double_expense(self):
        original=self.tx(cents=2500,merchant="Item purchase")
        refund=self.tx(d=9,cents=500,merchant="Item refund",direction="INCOME")
        response=self.client.post(self.path+"integration/refund",headers=self.h,
            json={"original_tx_id":original,"refund_tx_id":refund,"amount_cents":500})
        self.assertEqual(response.status_code,200,response.text)
        r=self.client.get(self.path+"month",params={"month":"2026-10"},headers=self.h).json()
        self.assertEqual(sum(x["total_cents"] for x in r["daily_totals"] if x["direction"]=="EXPENSE"),2500)
    def test_api_auth_protected_for_active_ledger(self):
        self.assertEqual(self.client.get(self.path+"month",params={"month":"2026-10"}).status_code,401)

if __name__=="__main__":
    unittest.main()
