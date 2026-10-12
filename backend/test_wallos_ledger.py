"""Isolated Wallos extension tests: authentication and manual ledger integration."""
import os
import tempfile
import time
import uuid
import unittest
from pathlib import Path

import app as core
from fastapi.testclient import TestClient

class WallosLedgerTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.previous=(core.DB,os.environ.get("FINANCE_WALLOS_LEDGER_TOKEN"))
        core.DB=Path(self.temp.name)/"test.sqlite3"
        os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]="test-Wallos-only-token-with-length"
        self.client=TestClient(core.app)
        self.url="/api/v1/wallos-ledger/"
        self.headers={"X-Wallos-Ledger-Token":os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]}
        self.timestamp=int(time.time()*1000)
    def tearDown(self):
        self.client.close()
        core.DB,token=self.previous
        if token is None:os.environ.pop("FINANCE_WALLOS_LEDGER_TOKEN",None)
        else:os.environ["FINANCE_WALLOS_LEDGER_TOKEN"]=token
        self.temp.cleanup()
    def payload(self):
        return {"idempotency_key":str(uuid.uuid4()),"timestamp_ms":self.timestamp,
                "amount_cents":100,"currency":"CNY","direction":"EXPENSE",
                "category":"FOOD","merchant":"手工测试"}
    def test_manual_permissions_idempotency_and_month_summary(self):
        record=self.payload()
        self.assertEqual(self.client.post(self.url+"manual",json=record).status_code,401)
        first=self.client.post(self.url+"manual",headers=self.headers,json=record)
        self.assertEqual(first.status_code,200,first.text)
        self.assertTrue(first.json()["created"])
        same=self.client.post(self.url+"manual",headers=self.headers,json=record)
        self.assertEqual(same.status_code,200)
        self.assertFalse(same.json()["created"])
        from datetime import datetime
        from zoneinfo import ZoneInfo
        month=datetime.fromtimestamp(self.timestamp/1000,ZoneInfo("Asia/Shanghai")).strftime("%Y-%m")
        r=self.client.get(self.url+"month",params={"month":month},headers=self.headers)
        self.assertEqual(r.status_code,200,r.text)
        self.assertEqual(r.json()["count_in_page"],1)
        self.assertEqual(r.json()["totals"][0]["total_cents"],100)
        review=self.client.patch(self.url+"review/"+str(first.json()["id"]),json={"status":"excluded"},headers=self.headers)
        self.assertEqual(review.status_code,200)
        updated=self.client.get(self.url+"month",params={"month":month},headers=self.headers).json()
        self.assertEqual(updated["totals"],[])
        self.assertEqual(updated["daily_totals"],[])
        self.assertEqual(sum(x["total_cents"] for x in updated["daily_excluded_totals"]
                             if x["currency"]=="CNY" and x["direction"]=="EXPENSE"),100)
        self.assertEqual(updated["items"][0]["review_status"],"excluded")
        self.assertEqual(self.client.get("/api/v1/transactions",headers=self.headers).status_code,401)
    def test_daily_spending_reconciles_included_and_excluded_rows(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        month=datetime.fromtimestamp(self.timestamp/1000,ZoneInfo("Asia/Shanghai")).strftime("%Y-%m")
        for cents,category in ((5800,"FOOD"),(2000,"SHOPPING"),(1400,"SHOPPING")):
            event=self.payload()
            event.update(amount_cents=cents,category=category,merchant="Synthetic")
            result=self.client.post(self.url+"manual",headers=self.headers,json=event)
            self.assertEqual(result.status_code,200,result.text)
            if cents==1400:
                tx_id=result.json()["id"]
                self.assertEqual(self.client.patch(
                    self.url+f"review/{tx_id}",headers=self.headers,
                    json={"status":"excluded"}).status_code,200)
        day=datetime.fromtimestamp(self.timestamp/1000,ZoneInfo("Asia/Shanghai")).strftime("%Y-%m-%d")
        output=self.client.get(self.url+"month",headers=self.headers,
                               params={"month":month,"day":day}).json()
        actual=sum(r["total_cents"] for r in output["daily_totals"]
                   if r["currency"]=="CNY" and r["direction"]=="EXPENSE" and r["day"]==day)
        excluded=sum(r["total_cents"] for r in output["daily_excluded_totals"]
                     if r["currency"]=="CNY" and r["direction"]=="EXPENSE" and r["day"]==day)
        self.assertEqual(actual,7800)
        self.assertEqual(excluded,1400)
        self.assertEqual(actual+excluded,9200)
        self.assertEqual(sum(x["total_cents"] for x in output["totals"]),7800)
        self.assertEqual(len(output["items"]),3)

    def test_incoming_transfer_counts_as_inflow_but_outgoing_transfer_does_not_count(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        month=datetime.fromtimestamp(self.timestamp/1000,ZoneInfo("Asia/Shanghai")).strftime("%Y-%m")
        for category,direction,amount in [
            ("TRANSFER","INCOME",1200), ("TRANSFER","EXPENSE",2500),
            ("FOOD","EXPENSE",700)
        ]:
            payload=self.payload()
            payload.update(category=category,direction=direction,
                           amount_cents=amount,merchant="Synthetic")
            response=self.client.post(self.url+"manual",headers=self.headers,json=payload)
            self.assertEqual(response.status_code,200,response.text)
        result=self.client.get(self.url+"month",params={"month":month},
                               headers=self.headers).json()
        self.assertEqual(result["month_count"],3)
        self.assertEqual(len(result["items"]),3)
        self.assertEqual([(x["direction"],x["category"],x["total_cents"]) for x in result["totals"]],
                         [("EXPENSE","FOOD",700),("INCOME","TRANSFER",1200)])
        self.assertEqual(sum(x["total_cents"] for x in result["daily_totals"]
                             if x["direction"]=="EXPENSE"),700)
        self.assertEqual(sum(x["total_cents"] for x in result["daily_totals"]
                             if x["direction"]=="INCOME"),1200)
        transfer_only=self.client.get(self.url+"month",
            params={"month":month,"category":"TRANSFER"},headers=self.headers).json()
        self.assertEqual(len(transfer_only["items"]),2)
        self.assertEqual(transfer_only["totals"],result["totals"])

    def test_previous_month_same_period_vs_complete_month(self):
        from datetime import datetime,timedelta
        from zoneinfo import ZoneInfo
        tz=ZoneInfo("Asia/Shanghai")
        today=datetime.now(tz)
        first=datetime(today.year,today.month,1,tzinfo=tz)
        prev_end=first
        prev_last=prev_end-timedelta(days=1)
        prev_first=prev_last.replace(day=1)
        earlier=prev_first+timedelta(days=1,hours=12)
        later=prev_first+timedelta(days=24,hours=12)
        for timestamp,cents in [(earlier,900),(later,1500)]:
            p=self.payload()
            p.update(timestamp_ms=int(timestamp.timestamp()*1000),amount_cents=cents)
            response=self.client.post(self.url+"manual",headers=self.headers,json=p)
            self.assertEqual(response.status_code,200,response.text)
        month=today.strftime("%Y-%m")
        stats=self.client.get(self.url+"month",params={"month":month},headers=self.headers).json()
        complete=sum(x["total_cents"] for x in stats["previous_month_totals"])
        same=sum(x["total_cents"] for x in stats["previous_same_period_totals"])
        self.assertEqual(complete,2400)
        expected=2400 if today.day>=25 else (900 if today.day>=2 else 0)
        self.assertEqual(same,expected)

    def test_daily_monthly_filters_and_literal_search(self):
        from datetime import datetime,timedelta
        from zoneinfo import ZoneInfo
        tz=ZoneInfo("Asia/Shanghai")
        today=datetime.now(tz).replace(hour=12,minute=0,second=0,microsecond=0)
        yesterday=today-timedelta(days=1)
        month=today.strftime("%Y-%m")
        for i, (date,amount,category,merchant,currency) in enumerate([
             (today,350,"FOOD","食堂 100% 满意","CNY"),
             (yesterday,700,"SHOPPING","购物","CNY"),
             (today,1999,"OTHER","software","USD"),
        ]):
            p=self.payload()
            p.update(timestamp_ms=int(date.timestamp()*1000),
                amount_cents=amount,category=category,merchant=merchant,currency=currency)
            self.assertEqual(self.client.post(self.url+"manual",headers=self.headers,json=p).status_code,200)
        base={"month":month}
        response=self.client.get(self.url+"month",params=base,headers=self.headers)
        self.assertEqual(response.status_code,200,response.text)
        j=response.json()
        self.assertEqual(j["month_count"],3)
        self.assertIn("previous_month_totals",j)
        self.assertEqual(len(j["items"]),3)
        self.assertEqual(sum(x["total_cents"] for x in j["totals"] if x["currency"]=="CNY"),1050)
        self.assertEqual(sum(x["total_cents"] for x in j["today_totals"] if x["currency"]=="CNY"),350)
        self.assertEqual(sum(x["total_cents"] for x in j["last7_totals"] if x["currency"]=="CNY"),1050)
        daily={x["day"]:x["total_cents"] for x in j["daily_totals"] if x["currency"]=="CNY"}
        self.assertEqual(daily[today.strftime("%Y-%m-%d")],350)
        self.assertEqual(daily[yesterday.strftime("%Y-%m-%d")],700)
        filtered=self.client.get(self.url+"month",params={**base,"day":today.strftime("%Y-%m-%d"),"category":"FOOD","q":"100%"},headers=self.headers)
        self.assertEqual(filtered.status_code,200,filtered.text)
        self.assertEqual(len(filtered.json()["items"]),1)
        self.assertEqual(filtered.json()["totals"],j["totals"],"search cannot alter monthly budget totals")
        notwild=self.client.get(self.url+"month",params={**base,"q":"%_not a wildcard"},headers=self.headers)
        self.assertEqual(notwild.status_code,200,notwild.text)
        self.assertEqual(notwild.json()["items"],[])
        self.assertEqual(self.client.get(self.url+"month",params={**base,"day":"1999-01-01"},headers=self.headers).status_code,422)
        self.assertEqual(self.client.get(self.url+"month",params={**base,"offset":-1},headers=self.headers).status_code,422)
        export=self.client.get(self.url+"export",params={"month":month},headers=self.headers)
        self.assertEqual(export.status_code,200)
        self.assertIn("text/csv",export.headers["content-type"])
        self.assertEqual(export.content.decode("utf-8-sig").count("食堂 100% 满意"),1)
        self.assertEqual(self.client.get(self.url+"export",params={"month":month}).status_code,401)
        self.assertIsNone(self.client.get(self.url+"month",params=base,headers=self.headers).json()["budget_cny_cents"])
        self.assertEqual(self.client.put(self.url+"budget/"+month,json={"amount_cents":150000}).status_code,401)
        set_budget=self.client.put(self.url+"budget/"+month,json={"amount_cents":150000},headers=self.headers)
        self.assertEqual(set_budget.status_code,200,set_budget.text)
        self.assertEqual(self.client.get(self.url+"month",params=base,headers=self.headers).json()["budget_cny_cents"],150000)
        self.assertEqual(self.client.put(self.url+"budget/"+month,json={"amount_cents":0},headers=self.headers).status_code,200)
        self.assertIsNone(self.client.get(self.url+"month",params=base,headers=self.headers).json()["budget_cny_cents"])
        self.assertEqual(self.client.put(self.url+"budget/2026-99",json={"amount_cents":100},headers=self.headers).status_code,422)



    def test_manual_entry_rejects_prestart_date(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        cutoff=int(datetime(2026,10,10,tzinfo=ZoneInfo("Asia/Shanghai")).timestamp()*1000)
        with core.db_connection() as db:
            db.execute("INSERT INTO ledger_settings(key,value) VALUES('start_timestamp_ms',?)",(str(cutoff),))
        item=self.payload()
        item["timestamp_ms"]=cutoff-1
        response=self.client.post(self.url+"manual",headers=self.headers,json=item)
        self.assertEqual(response.status_code,422)
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],0)

    def test_validation(self):
        data=self.payload()
        data["amount_cents"]=-1
        self.assertEqual(self.client.post(self.url+"manual",headers=self.headers,json=data).status_code,422)
        data=self.payload()
        data["category"]="INJECT_SQL"
        self.assertEqual(self.client.post(self.url+"manual",headers=self.headers,json=data).status_code,422)
        self.assertEqual(self.client.get(self.url+"month",params={"month":"2026-17"},headers=self.headers).status_code,422)

if __name__=="__main__":unittest.main()
