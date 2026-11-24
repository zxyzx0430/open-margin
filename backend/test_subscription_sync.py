"""Unit tests for subscription bidirectional sync and Wallos channel fallback."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import app as core
import wallos_api
from testutil import connect as sqlite_connect


class FakeWallos:
    """In-memory Wallos API stand-in backed by wallos_api.normalize."""

    def __init__(self, initial=None):
        self._next_id = 100
        self.rows = {}
        for row in initial or []:
            self.rows[int(row["id"])] = dict(row)
            self._next_id = max(self._next_id, int(row["id"]) + 1)
        self.add_calls = []
        self.edit_calls = []
        self.delete_calls = []

    def get_all(self):
        return sorted(
            [wallos_api.normalize(r) for r in self.rows.values()],
            key=lambda item: item["next_due_ms"] or 0,
        )

    def add(
        self,
        name,
        amount_cents,
        currency,
        cadence,
        next_due_ms,
        payment_channel="",
        start_date=None,
        auto_renew=True,
        notify=False,
        notify_days_before=None,
        note="",
    ):
        self.add_calls.append(
            {
                "name": name,
                "amount_cents": amount_cents,
                "currency": currency,
                "cadence": cadence,
                "next_due_ms": next_due_ms,
                "payment_channel": payment_channel,
                "start_date": start_date,
                "auto_renew": auto_renew,
                "notify": notify,
                "notify_days_before": notify_days_before,
                "note": note,
            }
        )
        wid = self._next_id
        self._next_id += 1
        freq, cycle = wallos_api.CYCLES[cadence]
        from datetime import datetime

        next_payment = datetime.fromtimestamp(
            next_due_ms / 1000, wallos_api.LOCAL_TZ
        ).strftime("%Y-%m-%d")
        self.rows[wid] = {
            "id": wid,
            "name": name,
            "price": f"{amount_cents / 100:.2f}",
            "currency_id": wallos_api.CURRENCIES[currency],
            "frequency": freq,
            "cycle": cycle,
            "next_payment": next_payment,
            "start_date": start_date,
            "payment_method_name": payment_channel or "未指定（待确认）",
            "auto_renew": int(auto_renew),
            "notify": int(notify),
            "notify_days_before": notify_days_before,
            "inactive": 0,
            "notes": wallos_api.format_wallos_notes(payment_channel, note),
        }
        return wid

    def edit(self, subscription_id, **changes):
        self.edit_calls.append((subscription_id, dict(changes)))
        row = self.rows[subscription_id]
        if "name" in changes:
            row["name"] = changes["name"]
        if "amount_cents" in changes:
            row["price"] = f"{changes['amount_cents'] / 100:.2f}"
        if "currency" in changes:
            row["currency_id"] = wallos_api.CURRENCIES[changes["currency"]]
        if "cadence" in changes:
            freq, cycle = wallos_api.CYCLES[changes["cadence"]]
            row["frequency"], row["cycle"] = freq, cycle
        if "next_due_ms" in changes:
            from datetime import datetime

            row["next_payment"] = datetime.fromtimestamp(
                changes["next_due_ms"] / 1000, wallos_api.LOCAL_TZ
            ).strftime("%Y-%m-%d")
        if "start_date" in changes:
            row["start_date"] = changes["start_date"]
        if "payment_channel" in changes:
            row["payment_method_name"] = changes["payment_channel"]
        if "auto_renew" in changes:
            row["auto_renew"] = int(changes["auto_renew"])
        if "notify" in changes:
            row["notify"] = int(changes["notify"])
        if "notify_days_before" in changes:
            row["notify_days_before"] = changes["notify_days_before"]
        if "active" in changes:
            row["inactive"] = int(not changes["active"])
        if "note" in changes:
            row["notes"] = changes["note"]
        return subscription_id

    def delete(self, subscription_id):
        self.delete_calls.append(subscription_id)
        self.rows.pop(subscription_id, None)
        return subscription_id


class SubscriptionSyncTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="pfs-sub-sync-")
        self.old_db = core.DB
        self.old_token = core.DEVICE_TOKEN
        core.DB = Path(self.folder.name) / "finance.sqlite3"
        core.DEVICE_TOKEN = "synthetic-device-token"
        self.client = TestClient(core.app)
        self.headers = {"X-Device-Token": "synthetic-device-token"}

    def tearDown(self):
        self.client.close()
        core.DB = self.old_db
        core.DEVICE_TOKEN = self.old_token
        self.folder.cleanup()

    def test_auth_required(self):
        self.assertEqual(self.client.get("/api/v2/sync/subscriptions").status_code, 401)
        self.assertEqual(
            self.client.post("/api/v2/sync/subscriptions/push", json={"items": []}).status_code,
            401,
        )

    def test_pull_reconciles_new_edit_and_delete_from_wallos(self):
        fake = FakeWallos(
            initial=[
                {
                    "id": 10,
                    "name": "ChatGPT Plus",
                    "price": "19.99",
                    "currency_id": 2,
                    "frequency": 1,
                    "cycle": 3,
                    "next_payment": "2026-12-15",
                    "payment_method_name": "AliPay",
                    "auto_renew": 1,
                    "notify": 1,
                    "notify_days_before": 3,
                    "inactive": 0,
                    "notes": "",
                }
            ]
        )
        with (
            patch("wallos_api.get_all", side_effect=fake.get_all),
            patch("wallos_api.add", side_effect=fake.add),
            patch("wallos_api.edit", side_effect=fake.edit),
            patch("wallos_api.delete", side_effect=fake.delete),
        ):
            first = self.client.get("/api/v2/sync/subscriptions", headers=self.headers)
            self.assertEqual(first.status_code, 200, first.text)
            body = first.json()
            self.assertEqual(len(body["server_id"]), 32)
            self.assertIn("USD", body["fx_rates"])
            self.assertEqual(len(body["items"]), 1)
            item = body["items"][0]
            self.assertEqual(item["wallos_id"], 10)
            self.assertEqual(item["amount_cents"], 1999)
            self.assertEqual(item["currency"], "USD")
            sync_uuid = item["sync_uuid"]
            first_updated = item["updated_at_ms"]

            # Web UI edits the subscription amount -> hash mismatch updates updated_at_ms.
            fake.rows[10]["price"] = "22.00"
            with patch("time.time", return_value=(first_updated + 5000) / 1000):
                second = self.client.get("/api/v2/sync/subscriptions", headers=self.headers)
            self.assertEqual(second.status_code, 200)
            edited = second.json()["items"][0]
            self.assertEqual(edited["sync_uuid"], sync_uuid)
            self.assertEqual(edited["amount_cents"], 2200)
            self.assertGreater(edited["updated_at_ms"], first_updated)

            # Web UI deletes the subscription -> tombstone emitted.
            fake.rows.pop(10)
            third = self.client.get("/api/v2/sync/subscriptions", headers=self.headers)
            self.assertEqual(third.status_code, 200)
            third_body = third.json()
            self.assertEqual(third_body["items"], [])
            self.assertEqual(len(third_body["tombstones"]), 1)
            self.assertEqual(third_body["tombstones"][0]["sync_uuid"], sync_uuid)

    def test_push_create_edit_delete_and_preserves_chinese_channel(self):
        fake = FakeWallos()
        due_ms = 1797264000000  # 2026-12-15
        with (
            patch("wallos_api.get_all", side_effect=fake.get_all),
            patch("wallos_api.add", side_effect=fake.add),
            patch("wallos_api.edit", side_effect=fake.edit),
            patch("wallos_api.delete", side_effect=fake.delete),
        ):
            # Create with custom/Chinese payment channel "花呗" + pending_cancel + note.
            create_resp = self.client.post(
                "/api/v2/sync/subscriptions/push",
                headers=self.headers,
                json={
                    "items": [
                        {
                            "sync_uuid": "sub-uuid-0001",
                            "name": "腾讯视频 VIP",
                            "amount_cents": 2500,
                            "currency": "CNY",
                            "cadence": "monthly",
                            "next_due_ms": due_ms,
                            "payment_channel": "花呗",
                            "auto_renew": True,
                            "pending_cancel": True,
                            "notify_enabled": True,
                            "notify_days_before": 3,
                            "active": True,
                            "note": "年度活动价",
                            "updated_at_ms": 1763950000000,
                        }
                    ]
                },
            )
            self.assertEqual(create_resp.status_code, 200, create_resp.text)
            cbody = create_resp.json()
            wid = cbody["id_map"]["sub-uuid-0001"]
            self.assertEqual(cbody["results"][0]["status"], "created")
            self.assertEqual(cbody["items"][0]["payment_channel"], "花呗")
            self.assertTrue(cbody["items"][0]["pending_cancel"])
            self.assertEqual(cbody["items"][0]["note"], "年度活动价")

            # Simulate Wallos returning fallback "AliPay" after web amount edit.
            fake.rows[wid]["payment_method_name"] = "AliPay"
            fake.rows[wid]["price"] = "30.00"
            with patch("time.time", return_value=1763950005.0):
                pulled = self.client.get("/api/v2/sync/subscriptions", headers=self.headers).json()
            self.assertEqual(pulled["items"][0]["payment_channel"], "花呗")
            self.assertEqual(pulled["items"][0]["amount_cents"], 3000)

            # Edit from Android with newer timestamp.
            edit_resp = self.client.post(
                "/api/v2/sync/subscriptions/push",
                headers=self.headers,
                json={
                    "items": [
                        {
                            "sync_uuid": "sub-uuid-0001",
                            "wallos_id": wid,
                            "name": "腾讯视频 SVIP",
                            "amount_cents": 3500,
                            "currency": "CNY",
                            "cadence": "monthly",
                            "next_due_ms": due_ms,
                            "payment_channel": "招行储蓄卡",
                            "auto_renew": False,
                            "pending_cancel": False,
                            "notify_enabled": True,
                            "notify_days_before": 5,
                            "active": True,
                            "note": "已升级",
                            "updated_at_ms": 1763950010000,
                        }
                    ]
                },
            )
            self.assertEqual(edit_resp.status_code, 200, edit_resp.text)
            ebody = edit_resp.json()
            self.assertEqual(ebody["results"][0]["status"], "updated")
            self.assertEqual(ebody["items"][0]["name"], "腾讯视频 SVIP")
            self.assertEqual(ebody["items"][0]["payment_channel"], "招行储蓄卡")
            self.assertFalse(ebody["items"][0]["pending_cancel"])

            # Delete from Android.
            del_resp = self.client.post(
                "/api/v2/sync/subscriptions/push",
                headers=self.headers,
                json={
                    "items": [
                        {
                            "sync_uuid": "sub-uuid-0001",
                            "wallos_id": wid,
                            "name": "腾讯视频 SVIP",
                            "amount_cents": 3500,
                            "currency": "CNY",
                            "cadence": "monthly",
                            "next_due_ms": due_ms,
                            "updated_at_ms": 1763950020000,
                            "deleted_at_ms": 1763950020000,
                        }
                    ]
                },
            )
            self.assertEqual(del_resp.status_code, 200, del_resp.text)
            dbody = del_resp.json()
            self.assertEqual(dbody["results"][0]["status"], "deleted")
            self.assertEqual(fake.delete_calls, [wid])
            self.assertEqual(dbody["items"], [])
            self.assertEqual(len(dbody["tombstones"]), 1)

    def test_lww_conflict_resolution(self):
        fake = FakeWallos(
            initial=[
                {
                    "id": 21,
                    "name": "iCloud+",
                    "price": "6.00",
                    "currency_id": 20,
                    "frequency": 1,
                    "cycle": 3,
                    "next_payment": "2026-12-01",
                    "payment_method_name": "Apple Pay",
                    "auto_renew": 1,
                    "notify": 1,
                    "notify_days_before": 3,
                    "inactive": 0,
                    "notes": "",
                }
            ]
        )
        with (
            patch("wallos_api.get_all", side_effect=fake.get_all),
            patch("wallos_api.add", side_effect=fake.add),
            patch("wallos_api.edit", side_effect=fake.edit),
            patch("wallos_api.delete", side_effect=fake.delete),
        ):
            with patch("time.time", return_value=2000.0):
                pulled = self.client.get("/api/v2/sync/subscriptions", headers=self.headers).json()
            sync_uuid = pulled["items"][0]["sync_uuid"]
            server_ts = pulled["items"][0]["updated_at_ms"]
            self.assertEqual(server_ts, 2_000_000)

            # Stale client edit (updated_at_ms < server_ts) -> server wins!
            stale_push = self.client.post(
                "/api/v2/sync/subscriptions/push",
                headers=self.headers,
                json={
                    "items": [
                        {
                            "sync_uuid": sync_uuid,
                            "wallos_id": 21,
                            "name": "Old Client Title",
                            "amount_cents": 2100,
                            "currency": "CNY",
                            "cadence": "monthly",
                            "next_due_ms": 1796054400000,
                            "updated_at_ms": server_ts - 1000,
                        }
                    ]
                },
            ).json()
            self.assertEqual(stale_push["results"][0]["status"], "server_wins")
            self.assertEqual(stale_push["items"][0]["name"], "iCloud+")
            self.assertEqual(fake.edit_calls, [])

            # Newer client edit (updated_at_ms > server_ts) -> client wins!
            newer_push = self.client.post(
                "/api/v2/sync/subscriptions/push",
                headers=self.headers,
                json={
                    "items": [
                        {
                            "sync_uuid": sync_uuid,
                            "wallos_id": 21,
                            "name": "iCloud+ 200GB",
                            "amount_cents": 2100,
                            "currency": "CNY",
                            "cadence": "monthly",
                            "next_due_ms": 1796054400000,
                            "payment_channel": "Apple Pay",
                            "updated_at_ms": server_ts + 5000,
                        }
                    ]
                },
            ).json()
            self.assertEqual(newer_push["results"][0]["status"], "updated")
            self.assertEqual(newer_push["items"][0]["name"], "iCloud+ 200GB")
            self.assertEqual(len(fake.edit_calls), 1)

    def test_chinese_and_custom_payment_channel_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "wallos.db"
            with sqlite_connect(db) as con:
                con.executescript(
                    """
                    CREATE TABLE user(id INTEGER);
                    CREATE TABLE household(id INTEGER, user_id INTEGER);
                    CREATE TABLE categories(id INTEGER, user_id INTEGER, name TEXT);
                    CREATE TABLE payment_methods(id INTEGER, user_id INTEGER, enabled INTEGER, name TEXT);
                    INSERT INTO user VALUES(1);
                    INSERT INTO household VALUES(1, 1);
                    INSERT INTO categories VALUES(1, 1, '无分类');
                    INSERT INTO payment_methods VALUES(10, 1, 1, 'Money');
                    INSERT INTO payment_methods VALUES(11, 1, 1, 'AliPay');
                    INSERT INTO payment_methods VALUES(12, 1, 1, 'WeChat');
                    INSERT INTO payment_methods VALUES(13, 1, 1, 'Credit Card');
                    """
                )
            with patch.object(wallos_api, "WALLOS_DB", db):
                self.assertEqual(wallos_api.valid_subscription_links("微信")["payment_method_id"], 12)
                self.assertEqual(wallos_api.valid_subscription_links("支付宝")["payment_method_id"], 11)
                self.assertEqual(wallos_api.valid_subscription_links("花呗")["payment_method_id"], 11)
                self.assertEqual(wallos_api.valid_subscription_links("银行卡")["payment_method_id"], 13)
                self.assertEqual(wallos_api.valid_subscription_links("储蓄卡")["payment_method_id"], 13)
                self.assertEqual(wallos_api.valid_subscription_links("信用卡")["payment_method_id"], 13)
                self.assertEqual(wallos_api.valid_subscription_links("云闪付")["payment_method_id"], 13)
                self.assertEqual(wallos_api.valid_subscription_links("京东白条")["payment_method_id"], 13)
                self.assertEqual(wallos_api.valid_subscription_links("Apple Pay")["payment_method_id"], 13)
                self.assertEqual(wallos_api.valid_subscription_links("抖音支付")["payment_method_id"], 11)
                self.assertEqual(wallos_api.valid_subscription_links("未指定（待确认）")["payment_method_id"], 10)
                self.assertEqual(wallos_api.valid_subscription_links("招行储蓄卡")["payment_method_id"], 13)
                self.assertEqual(wallos_api.valid_subscription_links("未知自定义渠道")["payment_method_id"], 10)

            with (
                patch.object(wallos_api, "WALLOS_DB", db),
                patch("wallos_api.call", return_value={"subscriptionId": 77}) as api_call,
            ):
                sid = wallos_api.add(
                    "88VIP",
                    8800,
                    "CNY",
                    "yearly",
                    1797264000000,
                    payment_channel="招行储蓄卡",
                    note="淘宝会员",
                )
                self.assertEqual(sid, 77)
                payload = api_call.call_args[0][1]
                self.assertEqual(payload["payment_method_id"], 13)
                self.assertIn("Payment channel: 招行储蓄卡", payload["notes"])
                self.assertIn("淘宝会员", payload["notes"])


if __name__ == "__main__":
    unittest.main()
