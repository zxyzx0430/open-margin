"""Regression coverage for historical subscription dates and safe partial edits."""
import sqlite3
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
from zoneinfo import ZoneInfo

import mcp_service
import wallos_api


def stamp(iso):
    return int(datetime.fromisoformat(iso).replace(
        tzinfo=ZoneInfo("Asia/Shanghai")).timestamp()*1000)


def subscription(start="2026-10-22", **kwargs):
    record = {
        "id":8,"name":"commend code go 会员","amount_cents":1000,
        "currency":"CNY","cadence":"monthly","next_due_ms":stamp("2026-11-20"),
        "start_ms":stamp(start),"payment_channel":"未指定（待确认）",
        "auto_renew":True,"notify":False,"notify_days_before":None,"active":True
    }
    record.update(kwargs)
    return record


class SubscriptionPatchTests(unittest.TestCase):
    def test_add_passes_historical_start_date_and_verifies_readback(self):
        saved = subscription(start="2026-10-20")
        with patch("wallos_api.get_all", side_effect=[[],[saved]]), \
             patch("wallos_api.add",return_value=8) as writer:
            result = mcp_service.add_subscription(saved["name"],1000,"CNY",
                "monthly",stamp("2026-11-20"),start_date="2026-10-20")
        self.assertTrue(result["created"])
        self.assertTrue(result["verified"])
        self.assertEqual(writer.call_args.kwargs["start_date"],"2026-10-20")

    def test_edit_only_start_date_does_not_touch_due_date(self):
        before=subscription(start="2026-10-22")
        after=subscription(start="2026-10-20")
        with patch("wallos_api.get_all",side_effect=[[before],[after]]),\
             patch("wallos_api.edit",return_value=8) as writer:
            result=mcp_service.edit_subscription(8,start_date="2026-10-20")
        self.assertTrue(result["updated"])
        self.assertTrue(result["verified"])
        writer.assert_called_once_with(8,start_date="2026-10-20")
        self.assertEqual(result["subscription"]["next_due_ms"],stamp("2026-11-20"))

    def test_edit_active_false(self):
        before=subscription()
        after=subscription(active=False)
        with patch("wallos_api.get_all",side_effect=[[before],[after]]),\
             patch("wallos_api.edit",return_value=8) as writer:
            result=mcp_service.edit_subscription(8,active=False)
        self.assertTrue(result["verified"])
        writer.assert_called_once_with(8,active=False)

    def test_edit_mismatch_is_reported_not_claimed_verified(self):
        before=subscription()
        with patch("wallos_api.get_all",side_effect=[[before],[before]]),\
             patch("wallos_api.edit",return_value=8):
            result=mcp_service.edit_subscription(8,start_date="2026-10-20")
        self.assertTrue(result["updated"])
        self.assertFalse(result["verified"])
        self.assertEqual(result["mismatches"],["start_date"])

    def test_dedup_conflicting_start_date_fails_closed(self):
        with patch("wallos_api.get_all",return_value=[subscription()]),\
             patch("wallos_api.add") as writer:
            result=mcp_service.add_subscription("commend code go 会员",1000,
                "CNY","monthly",stamp("2026-11-20"),start_date="2026-10-20")
        self.assertIn("start_date",result["conflicting_fields"])
        writer.assert_not_called()

    def test_unknown_start_date_on_existing_record_conflicts(self):
        with patch("wallos_api.get_all",return_value=[subscription(start_ms=None)]),\
             patch("wallos_api.add") as writer:
            result=mcp_service.add_subscription("commend code go 会员",1000,
                "CNY","monthly",stamp("2026-11-20"),start_date="2026-10-20")
        self.assertIn("start_date",result["conflicting_fields"])
        writer.assert_not_called()

    def test_dedup_exact_keeps_legacy_behavior(self):
        with patch("wallos_api.get_all",return_value=[subscription(start="2026-10-20")]),\
             patch("wallos_api.add") as writer:
            result=mcp_service.add_subscription("commend code go 会员",1000,
                "CNY","monthly",stamp("2026-11-20"),start_date="2026-10-20")
        self.assertFalse(result["created"])
        self.assertTrue(result["already_exists"])
        writer.assert_not_called()

    def test_reject_invalid_dates_and_unsupported_one_time(self):
        cases=[
            ("monthly","2026-02-30",stamp("2026-11-20")),
            ("monthly","2026-12-20",stamp("2026-11-20")),
            ("one_time","2026-10-20",stamp("2026-11-20")),
        ]
        for cadence,start,due in cases:
            with self.subTest(cadence=cadence,start=start), \
                 patch("wallos_api.get_all") as reader,\
                 patch("wallos_api.add") as writer:
                result=mcp_service.add_subscription("test",100,"CNY",cadence,due,start_date=start)
                self.assertIn("error",result)
                reader.assert_not_called()
                writer.assert_not_called()

    def test_wallos_add_payload_sends_explicit_start_date(self):
        with patch("wallos_api.valid_subscription_links",return_value={
                "payer_user_id":1,"category_id":2,"payment_method_id":3}),\
             patch("wallos_api.call",return_value={"subscriptionId":8}) as api:
            wallos_api.add("sample",1000,"CNY","monthly",stamp("2026-11-20"),
                           start_date="2026-10-20",auto_renew=False,notify=True)
        request=api.call_args.args[1]
        self.assertEqual(request["start_date"],"2026-10-20")
        self.assertEqual(request["next_payment"],"2026-11-20")
        self.assertEqual(request["auto_renew"],0)
        self.assertEqual(request["notify"],1)

    def test_wallos_edit_sends_minimal_partial_payload(self):
        with patch("wallos_api.call",return_value={"success":True}) as api:
            wallos_api.edit(8,start_date="2026-10-20")
        self.assertEqual(api.call_args.args[1],{
            "action":"edit","id":8,"start_date":"2026-10-20"
        })

    def test_wallos_edit_stop_uses_native_inactive_flag(self):
        with patch("wallos_api.call",return_value={"success":True}) as api:
            wallos_api.edit(8,active=False)
        self.assertEqual(api.call_args.args[1]["inactive"],1)

    def test_strict_payment_channel_does_not_silently_substitute(self):
        with tempfile.TemporaryDirectory() as tmp:
            db=Path(tmp)/"wallos.db"
            with sqlite3.connect(db) as con:
                con.executescript("""
                CREATE TABLE user(id INTEGER);
                CREATE TABLE household(id INTEGER,user_id INTEGER);
                CREATE TABLE categories(id INTEGER,user_id INTEGER,name TEXT);
                CREATE TABLE payment_methods(id INTEGER,user_id INTEGER,enabled INTEGER,name TEXT);
                INSERT INTO user VALUES(1);
                INSERT INTO household VALUES(1,1);
                INSERT INTO categories VALUES(1,1,'无分类');
                INSERT INTO payment_methods VALUES(3,1,1,'Cash');
                """)
            with patch.object(wallos_api,"WALLOS_DB",db):
                with self.assertRaisesRegex(wallos_api.WallosError,"Unsupported payment channel"):
                    wallos_api.valid_subscription_links("MysteryPay")
                with self.assertRaisesRegex(wallos_api.WallosError,"not configured"):
                    wallos_api.valid_subscription_links("WeChat")
                self.assertEqual(
                    wallos_api.valid_subscription_links("")["payment_method_id"],3)

if __name__=="__main__":
    unittest.main()
