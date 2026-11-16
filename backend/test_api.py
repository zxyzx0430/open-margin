import os
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

with tempfile.TemporaryDirectory() as temp_unused:
    pass
from app import app
import app as m

class FinanceApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        m.DB = Path(self.tmp.name) / "db.sqlite3"
        m.DEVICE_TOKEN = "device-test-token"
        m.ADMIN_TOKEN = "admin-test-token"
        self.client = TestClient(app)

    def tearDown(self):
        self.tmp.cleanup()

    def test_auth_idempotence_and_summary(self):
        url = "/api/v1/transactions/batch"
        event = {"event_id":"WECHAT:12345678", "timestamp_ms":1791500000000,
                 "amount_cents":1850, "direction":"EXPENSE", "source":"WECHAT",
                 "merchant":"food", "category":"FOOD"}
        self.assertEqual(self.client.post(url,json={"events":[event]}).status_code,401)
        h={"X-Device-Token":"device-test-token"}
        first=self.client.post(url,json={"events":[event]},headers=h)
        self.assertEqual(first.status_code,200)
        self.assertEqual(first.json()["inserted"],1)
        second=self.client.post(url,json={"events":[event]},headers=h)
        self.assertEqual(second.json()["already_present"],1)
        self.assertEqual(self.client.get("/api/v1/transactions").status_code,401)
        s=self.client.get("/api/v1/summary?start_ms=1791400000000&end_ms=1791600000000",
              headers={"X-Admin-Token":"admin-test-token"}).json()
        self.assertEqual(s["groups"][0]["total_cents"],1850)
        self.assertEqual(s["groups"][0]["currency"],"CNY")

    def test_income_transfer_inflow_included_outgoing_transfer_excluded(self):
        events=[]
        for i,(direction,category,cents) in enumerate([
            ("INCOME","TRANSFER",1200),("EXPENSE","TRANSFER",2500),
            ("EXPENSE","FOOD",700)
        ]):
            events.append({
                "event_id":f"test-category-transfer-{i}",
                "timestamp_ms":1791800000000+i*60000,
                "amount_cents":cents,"currency":"CNY",
                "direction":direction,"source":"MANUAL",
                "merchant":"Synthetic","category":category
            })
        result=self.client.post("/api/v1/transactions/batch",
                headers={"X-Device-Token":"device-test-token"},json={"events":events})
        self.assertEqual(result.status_code,200,result.text)
        response=self.client.get("/api/v1/summary",
            params={"start_ms":1791700000000,"end_ms":1792000000000},
            headers={"X-Admin-Token":"admin-test-token"})
        self.assertEqual(response.status_code,200,response.text)
        self.assertEqual([(r["direction"],r["category"],r["total_cents"])
                          for r in response.json()["groups"]],
                         [("EXPENSE","FOOD",700),("INCOME","TRANSFER",1200)])

    def test_ledger_start_does_not_block_historical_imports(self):
        # 账本起始日只是订阅预测的边界，不再是写入闸门：早于它的历史账单
        # 必须被接收、入库，回执也如实报告忽略条数为 0。
        from datetime import datetime, timezone
        from zoneinfo import ZoneInfo
        boundary = int(datetime(2026, 10, 10, tzinfo=ZoneInfo("Asia/Shanghai")).timestamp() * 1000)
        h = {"X-Device-Token": "device-test-token"}
        with m.db_connection() as db:
            db.execute("INSERT INTO ledger_settings(key,value) VALUES('start_timestamp_ms',?)", (str(boundary),))
        base = {"event_id":"android:test-new-install:10", "timestamp_ms":boundary-60000,
                "amount_cents":483, "currency":"CNY", "direction":"EXPENSE",
                "source":"WECHAT", "merchant":"Old test", "category":"OTHER"}
        imported=self.client.post("/api/v1/transactions/batch",headers=h,json={"events":[base]})
        self.assertEqual(imported.status_code,200,imported.text)
        self.assertEqual(imported.json()["accepted"],1)
        self.assertEqual(imported.json()["inserted"],1)
        # 字段仅为兼容旧客户端保留；服务端不再丢弃任何记录。
        self.assertEqual(imported.json()["ignored_before_start"],0)
        with m.db_connection() as db:
            stored=db.execute("SELECT timestamp_ms,merchant FROM transactions WHERE event_id=?",
                              (base["event_id"],)).fetchone()
            self.assertIsNotNone(stored)
            self.assertEqual(stored["timestamp_ms"],boundary-60000)
            # 绕过 API 的导入通道同样不能被旧的 start_date_guard 触发器静默丢弃。
            cur=db.execute("""INSERT OR IGNORE INTO transactions
                (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant)
                VALUES (?,?,?,?,?,?,?,?)""",
                ("android:test-new-install:11",boundary-1,120,"CNY","EXPENSE","MANUAL","OTHER","old"))
            self.assertEqual(cur.rowcount,1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],2)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions WHERE timestamp_ms<?",
                                        (boundary,)).fetchone()[0],2)
        accepted=self.client.post("/api/v1/transactions/batch",headers=h,
                   json={"events":[{**base,"event_id":"android:test-new-install:12",
                                    "timestamp_ms":boundary}]})
        self.assertEqual(accepted.status_code,200,accepted.text)
        self.assertEqual(accepted.json()["inserted"],1)
        with m.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],3)

    def test_legacy_start_date_guard_trigger_is_removed_on_startup(self):
        # 生产库当初由旧版本建过 transactions_start_date_guard（RAISE(IGNORE)）。
        # 升级后初始化必须拆掉它，否则早于起始日的写入仍会被静默吞掉。
        with m.db_connection() as db:
            db.execute("""CREATE TRIGGER IF NOT EXISTS transactions_start_date_guard
                BEFORE INSERT ON transactions
                WHEN EXISTS (SELECT 1 FROM ledger_settings
                             WHERE key='start_timestamp_ms'
                               AND NEW.timestamp_ms < CAST(value AS INTEGER))
                BEGIN SELECT RAISE(IGNORE); END""")
            db.execute("INSERT OR REPLACE INTO ledger_settings(key,value) VALUES('start_timestamp_ms',?)",
                       (str(1791561600000),))
        with m.db_connection() as db:
            m._initialize_database(db)
            triggers=[row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='trigger'")]
            self.assertNotIn("transactions_start_date_guard",triggers)
            cur=db.execute("""INSERT OR IGNORE INTO transactions
                (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant)
                VALUES (?,?,?,?,?,?,?,?)""",
                ("android:test-new-install:20",1791561600000-3600000,500,"CNY","EXPENSE","MANUAL","OTHER","older"))
            self.assertEqual(cur.rowcount,1)

    def test_guarded_android_rounding_correction(self):
        batch="/api/v1/transactions/batch"
        correction="/api/v1/transactions/correct-rounding"
        headers={"X-Device-Token":"device-test-token"}
        event={"event_id":"android:test-install:2","timestamp_ms":1791500000000,
               "amount_cents":1880,"direction":"EXPENSE","source":"WECHAT",
               "merchant":"paid","category":"OTHER"}
        self.assertEqual(self.client.post(batch,json={"events":[event]},headers=headers).status_code,200)
        repair={"event_id":event["event_id"],"old_cents":1880,"corrected_cents":1881}
        self.assertEqual(self.client.post(correction,json=repair).status_code,401)
        self.assertEqual(self.client.post(correction,json={**repair,"corrected_cents":9999},headers=headers).status_code,422)
        good=self.client.post(correction,json=repair,headers=headers)
        self.assertEqual(good.status_code,200,good.text)
        self.assertTrue(good.json()["corrected"])
        again=self.client.post(correction,json=repair,headers=headers)
        self.assertTrue(again.json()["already_correct"])
        with m.db_connection() as db:
            amount=db.execute("SELECT amount_cents FROM transactions WHERE event_id=?",(event["event_id"],)).fetchone()[0]
        self.assertEqual(amount,1881)
        missing=self.client.post(correction,json={**repair,"event_id":"android:test-install:100"},headers=headers)
        self.assertTrue(missing.json()["not_found"])
        # User-reviewed transactions cannot be silently overwritten.
        with m.db_connection() as db:
            db.execute("UPDATE transactions SET review_status='confirmed' WHERE event_id=?",(event["event_id"],))
        newer=self.client.post(correction,json=repair,headers=headers)
        self.assertEqual(newer.status_code,409)

if __name__=="__main__": unittest.main()
