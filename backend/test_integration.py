"""Finance x Wallos integration regression tests with an isolated finance SQLite DB."""
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from datetime import datetime
from zoneinfo import ZoneInfo

import app as core
from fastapi.testclient import TestClient

TZ=ZoneInfo("Asia/Shanghai")
def stamp(y,m,d):
    return int(datetime(y,m,d,tzinfo=TZ).timestamp()*1000)

SUBS=[
    {"id":42,"name":"Example Streaming","amount_cents":2000,"currency":"CNY",
     "cadence":"monthly","next_due_ms":stamp(2026,11,15),"payment_channel":"WeChat","active":True},
    {"id":43,"name":"USD VPS","amount_cents":300,"currency":"USD",
     "cadence":"monthly","next_due_ms":stamp(2026,11,22),"payment_channel":"Card","active":True},
]

class IntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.prior=(core.DB,os.environ.get("FINANCE_WALLOS_LEDGER_TOKEN"))
        core.DB=Path(self.tmp.name)/"test.sqlite3"
        os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]="integration-test-ledger-auth-token"
        self.client=TestClient(core.app)
        self.base="/api/v1/wallos-ledger/integration"
        self.h={"X-Wallos-Ledger-Token":os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]}
        self.patch=patch("integration.get_all",return_value=SUBS)
        self.patch.start()
    def tearDown(self):
        self.patch.stop()
        self.client.close()
        core.DB,self_token=self.prior
        if self_token is None:os.environ.pop("FINANCE_WALLOS_LEDGER_TOKEN",None)
        else:os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]=self_token
        self.tmp.cleanup()

    def insert(self,ts=stamp(2026,10,15),amount=2000,currency="CNY",direction="EXPENSE",merchant="Example Streaming",category="OTHER"):
        with core.db_connection() as db:
            cur=db.execute("""INSERT INTO transactions(event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant)
                 VALUES(?,?,?,?,?,'WECHAT',?,?)""",(f"test:{ts}:{amount}:{direction}:{merchant}",ts,amount,currency,direction,category,merchant))
            return cur.lastrowid

    def test_recurrence_discovery_supports_weekly_and_skips_existing_wallos(self):
        for day in (1,8,15):
            self.insert(ts=stamp(2026,9,day),amount=850,merchant="Coffee Pass")
        for day,month in ((15,8),(15,9)):
            self.insert(ts=stamp(2026,month,day),amount=2000,merchant="Example Streaming")
        result=self.client.get(self.base+"/discover",params={"months":12},headers=self.h)
        self.assertEqual(result.status_code,200,result.text)
        names={r["merchant"]:r for r in result.json()["items"]}
        self.assertEqual(names["Coffee Pass"]["cadence"],"weekly")
        self.assertNotIn("Example Streaming",names,"Known active Wallos subscriptions must not be proposed again")

    def test_money_decimal_rounding_from_wallos(self):
        from wallos_api import normalize
        row={"id":99,"name":"Rounding Example","price":"1.005",
             "currency_id":20,"cycle":3,"frequency":1,"next_payment":"2026-11-12",
             "start_date":"2026-10-12","inactive":0}
        self.assertEqual(normalize(row)["amount_cents"],101)

    def test_month_end_billing_does_not_drift(self):
        from integration import occurrences
        jan31={**SUBS[0],"next_due_ms":stamp(2026,1,31)}
        start=stamp(2026,2,1)
        end=stamp(2026,6,1)
        self.assertEqual([r["cycle_key"] for r in occurrences(jan31,start,end)],
                         ["2026-02-28","2026-03-31","2026-04-30","2026-05-31"])
        leap={**SUBS[0],"next_due_ms":stamp(2024,2,29),"cadence":"yearly"}
        self.assertEqual([r["cycle_key"] for r in occurrences(leap,stamp(2025,1,1),stamp(2029,1,1))],
                         ["2025-02-28","2026-02-28","2027-02-28","2028-02-29"])

    def test_forecast_link_and_no_double_count(self):
        tx=self.insert()
        res=self.client.get(self.base,params={"month":"2026-10"},headers=self.h)
        self.assertEqual(res.status_code,200,res.text)
        j=res.json()
        self.assertEqual(j["net_actual_spend_cny_cents"],2000)
        self.assertEqual(j["expected_unpaid_cny_cents"],2000)
        self.assertEqual(j["forecast_total_cny_cents"],4000)
        self.assertEqual(j["pending_count"],2)
        self.assertTrue(any(s["tx_id"]==tx for s in j["suggestions"]))
        r=self.client.post(self.base+"/link",headers=self.h,json={"tx_id":tx,"subscription_id":42,"cycle_key":"2026-10-15"})
        self.assertEqual(r.status_code,200,r.text)
        j=self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()
        self.assertEqual(j["net_actual_spend_cny_cents"],2000)
        self.assertEqual(j["expected_unpaid_cny_cents"],0)
        self.assertEqual(j["forecast_total_cny_cents"],2000)
        self.assertEqual(j["linked_count"],1)
        self.assertEqual(self.client.post(self.base+"/link",headers=self.h,json={"tx_id":tx,"subscription_id":42,"cycle_key":"2026-10-15"}).status_code,409)
        self.assertEqual(self.client.delete(self.base+"/link/"+str(tx),headers=self.h).status_code,200)
        self.assertEqual(self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()["expected_unpaid_cny_cents"],2000)

    def test_rules_edits_and_audit(self):
        x=self.client.post(self.base+"/rules",headers=self.h,json={"needle":"Example Streaming","category":"SUBSCRIPTION"})
        self.assertEqual(x.status_code,200,x.text)
        y=self.client.get(self.base+"/rules",headers=self.h)
        self.assertEqual(len(y.json()["items"]),1)
        import integration
        with core.db_connection() as db:
            self.assertEqual(integration.resolve_category(db,"Example Streaming","OTHER"),"SUBSCRIPTION")
        tx=self.insert(merchant="TestOther",amount=2150)
        e=self.client.patch(self.base+"/transaction/"+str(tx),headers=self.h,json={"amount_cents":2250,"merchant":"Edited streaming","category":"SUBSCRIPTION"})
        self.assertEqual(e.status_code,200,e.text)
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transaction_edits WHERE tx_id=?",(tx,)).fetchone()[0],3)
        self.assertEqual(self.client.delete(self.base+"/rules/1",headers=self.h).status_code,200)

    def test_cross_month_refund_tracks_received_month_cashflow(self):
        original=self.insert(ts=stamp(2026,9,25),amount=2500,merchant="A September expense")
        income=self.insert(ts=stamp(2026,10,16),amount=500,direction="INCOME",merchant="Refund received")
        res=self.client.post(self.base+"/refund",headers=self.h,
            json={"original_tx_id":original,"refund_tx_id":income,"amount_cents":500})
        self.assertEqual(res.status_code,200,res.text)
        september=self.client.get(self.base,params={"month":"2026-09"},headers=self.h).json()
        october=self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()
        self.assertEqual(september["net_actual_spend_cny_cents"],2500)
        self.assertEqual(october["net_actual_spend_cny_cents"],-500)
        self.assertEqual(october["refunds_cents_by_currency"]["CNY"],500)
        self.assertEqual(len(october["refund_links"]),1)

    def test_partial_allocation_and_manual_refund_without_link_rejected(self):
        original=self.insert(ts=stamp(2026,10,12),amount=2500)
        income=self.insert(ts=stamp(2026,10,14),amount=500,direction="INCOME",merchant="Refund received")
        result=self.client.post(self.base+"/refund",headers=self.h,
            json={"original_tx_id":original,"refund_tx_id":income,"amount_cents":400})
        self.assertEqual(result.status_code,422,result.text)
        result=self.client.patch("/api/v1/wallos-ledger/review/"+str(income),
            headers=self.h,json={"status":"refund"})
        self.assertEqual(result.status_code,422,result.text)
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT review_status FROM transactions WHERE id=?",(income,)).fetchone()[0],
                             "unverified")

    def test_refund_and_duplicate(self):
        tx=self.insert(amount=2500)
        income=self.insert(ts=stamp(2026,10,16),amount=500,direction="INCOME",merchant="refund money")
        r=self.client.post(self.base+"/refund",headers=self.h,json={"original_tx_id":tx,"refund_tx_id":income,"amount_cents":500})
        self.assertEqual(r.status_code,200,r.text)
        j=self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()
        self.assertEqual(j["net_actual_spend_cny_cents"],2000)
        self.assertEqual(j["refunds_cents_by_currency"]["CNY"],500)
        self.assertEqual(self.client.post(self.base+"/refund",headers=self.h,json={"original_tx_id":tx,"refund_tx_id":income,"amount_cents":500}).status_code,409)
        self.assertEqual(self.client.post(self.base+"/duplicate/"+str(tx),headers=self.h).status_code,409)
        self.assertEqual(self.client.delete(self.base+"/refund/"+str(income),headers=self.h).status_code,200)
        self.assertEqual(self.client.post(self.base+"/duplicate/"+str(tx),headers=self.h).status_code,200)
        self.assertEqual(self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()["net_actual_spend_cny_cents"],0)

    def test_duplicate_subscription_cycle_charge_is_flagged(self):
        original=self.insert()
        self.insert(ts=stamp(2026,10,17),amount=2000,merchant="Example Streaming")
        ok=self.client.post(self.base+"/link",headers=self.h,
            json={"tx_id":original,"subscription_id":42,"cycle_key":"2026-10-15"})
        self.assertEqual(ok.status_code,200,ok.text)
        j=self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()
        self.assertTrue(any(alert["kind"]=="duplicate_subscription_charge" for alert in j["alerts"]))

    def test_payment_channel_mismatch_warning(self):
        with patch("integration.get_all",return_value=[
             {**SUBS[0],"payment_channel":"AliPay"}]):
            tx=self.insert()
            self.assertEqual(self.client.post(self.base+"/link",headers=self.h,
                json={"tx_id":tx,"subscription_id":42,"cycle_key":"2026-10-15"}).status_code,200)
            j=self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()
            self.assertTrue(any(a["kind"]=="payment_method_mismatch" for a in j["alerts"]))

    def test_reset_cutoff_excludes_before_start_subscription_billing(self):
        old={**SUBS[0],"next_due_ms":stamp(2026,11,9)}
        with patch("integration.get_all",return_value=[old]):
            with core.db_connection() as db:
                db.execute("INSERT INTO ledger_settings(key,value) VALUES(?,?)",
                           ("start_timestamp_ms",str(stamp(2026,10,10))))
            j=self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()
            self.assertEqual(j["subscriptions"],[])
            self.assertEqual(j["pending_count"],0)
            self.assertEqual(j["alerts"],[])

    def test_no_unauthorized_access_or_automatic_subscription_creation(self):
        r=self.client.get(self.base,params={"month":"2026-10"})
        self.assertEqual(r.status_code,401)
        self.assertEqual(self.client.get(self.base+"/integration/rules").status_code,404)
        self.assertEqual(self.client.post(self.base+"/link",json={"tx_id":1,"subscription_id":42,"cycle_key":"2026-10-15"}).status_code,401)
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM tx_subscription_links").fetchone()[0],0)

    def test_usd_remains_separate(self):
        self.insert(amount=300,currency="USD",ts=stamp(2026,10,22))
        j=self.client.get(self.base,params={"month":"2026-10"},headers=self.h).json()
        self.assertEqual(j["net_actual_spend_cny_cents"],0)
        self.assertEqual(j["expected_unpaid_cny_cents"],2000)
        self.assertEqual(j["forecast_total_cny_cents"],2000)
        self.assertTrue(any(x["currency"]=="USD" for x in j["actual_totals"]))

if __name__=="__main__":unittest.main()
