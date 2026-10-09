"""Wallos is the only live subscription source for MCP and API."""
import unittest
from unittest.mock import patch
from datetime import datetime
from zoneinfo import ZoneInfo

from mcp_service import get_subscription_list,add_subscription
from wallos_api import normalize

class WallosCanonicalTests(unittest.TestCase):
    def test_normalize_wall_date(self):
        row={"id":1,"name":"ChatGPT Plus","price":19.99,"currency_id":2,
             "cycle":3,"frequency":1,"next_payment":"2026-10-15","inactive":0}
        subscription=normalize(row)
        self.assertEqual(subscription["amount_cents"],1999)
        self.assertEqual(subscription["currency"],"USD")
        self.assertEqual(subscription["cadence"],"monthly")
        self.assertEqual(subscription["next_due_ms"],int(datetime(2026,10,15,tzinfo=ZoneInfo("Asia/Shanghai")).timestamp()*1000))

    def test_direct_api_supplies_required_wallos_relationships(self):
        from wallos_api import add
        with patch("wallos_api.valid_subscription_links",return_value={
             "payer_user_id":1,"category_id":1,"payment_method_id":32}),\
             patch("wallos_api.call",return_value={"subscriptionId":88}) as api_call:
            created=add("Test service",1999,"USD","monthly",1792036800000)
            self.assertEqual(created,88)
            payload=api_call.call_args[0][1]
            self.assertEqual(payload["payer_user_id"],1)
            self.assertEqual(payload["category_id"],1)
            self.assertEqual(payload["payment_method_id"],32)

    def test_mcp_list_reads_wallos(self):
        with patch("wallos_api.get_all",return_value=[{"id":7,"name":"Wallos-owned"}]):
            r=get_subscription_list()
            self.assertEqual(r["source"],"Wallos")
            self.assertEqual(r["items"][0]["name"],"Wallos-owned")

    def test_mcp_add_deduplicates_and_writes_wallos(self):
        due=1792036800000
        existing={"id":1,"name":"ChatGPT Plus","amount_cents":1999,"currency":"USD",
            "cadence":"monthly","next_due_ms":1791993600000}
        with patch("wallos_api.get_all",return_value=[existing]),patch("wallos_api.add") as writer:
            result=add_subscription("ChatGPT Plus",1999,"USD","monthly",due)
            self.assertFalse(result["created"])
            writer.assert_not_called()
        with patch("wallos_api.get_all",return_value=[]),patch("wallos_api.add",return_value=9) as writer:
            result=add_subscription("Example",999,"USD","monthly",due)
            self.assertEqual(result["subscription_id"],9)
            writer.assert_called_once()

if __name__=="__main__":unittest.main()
