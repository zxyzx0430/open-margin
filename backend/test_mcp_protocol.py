"""MCP protocol and OAuth scope tests using temporary synthetic databases only."""
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import app as core
import mcp_access
import oauth_support as oauth


class MCPProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # StreamableHTTP MCP session manager only supports one lifespan per process.
        cls.client_context = TestClient(core.app, base_url="http://127.0.0.1:8767",
                                        follow_redirects=False)
        cls.client = cls.client_context.__enter__()

    @classmethod
    def tearDownClass(cls):
        cls.client_context.__exit__(None, None, None)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="openmargin-mcp-test-")
        self.original_oauth = oauth.OAUTH_DB
        self.original_db = core.DB
        oauth.OAUTH_DB = Path(self.temp.name) / "oauth.sqlite3"
        core.DB = Path(self.temp.name) / "transactions.sqlite3"
        with oauth.store() as db:
            self.read = oauth.new_tokens(db, "readonly-client", "finance:read", oauth.RESOURCE)["access_token"]
            self.write = oauth.new_tokens(db, "writeonly-client", "finance:write", oauth.RESOURCE)["access_token"]
            self.both = oauth.new_tokens(db, "full-client", "finance:read finance:write", oauth.RESOURCE)["access_token"]
        self.admin_patch = patch.object(core, "ADMIN_TOKEN", "isolated-test-admin-key")
        self.admin_patch.start()

    def tearDown(self):
        self.admin_patch.stop()
        oauth.OAUTH_DB, core.DB = self.original_oauth, self.original_db
        self.temp.cleanup()

    def headers(self, token):
        return {
            "Authorization": "Bearer " + token,
            "Accept": "application/json, text/event-stream",
            "Content-Type": "application/json",
            "MCP-Protocol-Version": "2025-06-18",
        }

    @staticmethod
    def call(tool_name, **arguments):
        return {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": {"name": tool_name, "arguments": arguments}}

    def post(self, token, payload):
        return self.client.post("/mcp/", headers=self.headers(token), json=payload)

    def test_list_is_stable_and_every_tool_is_scoped(self):
        r = self.post(self.read, {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
        self.assertEqual(r.status_code, 200, r.text)
        tool_names = {tool["name"] for tool in r.json()["result"]["tools"]}
        self.assertEqual(tool_names, set(mcp_access.TOOL_SCOPES))
        self.assertEqual(len(tool_names), 12)

    def test_tool_annotations_match_read_write_scopes(self):
        """ChatGPT must not classify pure queries as write actions."""
        from mcp_access import READ_TOOLS, WRITE_TOOLS
        response = self.post(self.read, {
            "jsonrpc": "2.0", "id": 81, "method": "tools/list", "params": {}
        })
        self.assertEqual(response.status_code, 200, response.text)
        tools = {tool["name"]: tool for tool in response.json()["result"]["tools"]}
        self.assertEqual(set(tools), set(READ_TOOLS) | set(WRITE_TOOLS))
        for name in READ_TOOLS:
            with self.subTest(name=name, mode="read"):
                annotations = tools[name]["annotations"]
                self.assertIs(annotations["readOnlyHint"], True)
                self.assertIs(annotations["destructiveHint"], False)
                self.assertIs(annotations["idempotentHint"], True)
                self.assertIs(annotations["openWorldHint"], False)
        for name in WRITE_TOOLS:
            with self.subTest(name=name, mode="write"):
                annotations = tools[name]["annotations"]
                self.assertIs(annotations["readOnlyHint"], False)
                self.assertIs(annotations["destructiveHint"], True)
                self.assertIs(annotations["idempotentHint"], False)
                self.assertIs(annotations["openWorldHint"], False)

    def test_edit_subscription_is_write_scoped(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        tz=ZoneInfo("Asia/Shanghai")
        def ms(day):
            return int(datetime.strptime(day,"%Y-%m-%d").replace(tzinfo=tz).timestamp()*1000)
        before={"id":8,"name":"sample","amount_cents":1000,"currency":"CNY",
                "cadence":"monthly","start_ms":ms("2026-10-22"),"next_due_ms":ms("2026-11-20")}
        after={**before,"start_ms":ms("2026-10-20")}
        payload=self.call("edit_subscription",subscription_id=8,start_date="2026-10-20")
        self.assertEqual(self.post(self.read,payload).status_code,403)
        with patch("wallos_api.get_all",side_effect=[[before],[after]]), \
             patch("wallos_api.edit",return_value=8) as updater:
            response=self.post(self.write,payload)
        self.assertEqual(response.status_code,200,response.text)
        self.assertTrue(json.loads(response.json()["result"]["content"][0]["text"])["verified"])
        updater.assert_called_once_with(8,start_date="2026-10-20")

    def test_no_anonymous_or_expired_access(self):
        payload = self.call("list_recent_transactions", limit=1)
        r = self.client.post("/mcp/", json=payload, headers={
            "Accept": "application/json, text/event-stream",
        })
        self.assertEqual(r.status_code, 401)
        self.assertIn("WWW-Authenticate", r.headers)
        self.assertEqual(self.post("wrong-token", payload).status_code, 401)
        self.assertEqual(self.client.get("/mcp/").status_code, 401)

    def test_only_explicit_read_scope_can_query_and_only_write_scope_can_mutate(self):
        read = self.call("list_recent_transactions", limit=2)
        write = self.call("register_merchant_rule", needle="safe", category="FOOD")
        # Check access gate before any database read or write.
        self.assertEqual(self.post(self.read, write).status_code, 403)
        self.assertEqual(self.post(self.write, read).status_code, 403)
        self.assertEqual(self.post(self.read, read).status_code, 200)
        self.assertEqual(self.post(self.both, read).status_code, 200)

    def test_unregistered_and_malformed_tool_calls_fail_closed(self):
        cases = [
            [self.call("register_merchant_rule", needle="evil", category="FOOD")],
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
             "params": {"name": "write_future_tool", "arguments": {}}},
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": []},
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": None},
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {}},
            {"jsonrpc": "2.0", "id": 1, "params": {}},
        ]
        for case in cases:
            with self.subTest(case=case):
                r = self.post(self.read, case)
                self.assertEqual(r.status_code, 400, r.text)
        r = self.client.post("/mcp/", content="bad json", headers=self.headers(self.read))
        self.assertEqual(r.status_code, 400)

    def test_synthetic_real_tool_call_and_no_real_finance_data(self):
        # This creates only a temporary SQLite file under the test directory.
        with core.db_connection() as db:
            db.execute("""INSERT INTO transactions
                (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant,review_status)
                VALUES(?,?,?,?,?,?,?,?,?)""", (
                    "test:mcp:expense1", 1791756000000, 2345,
                    "CNY", "EXPENSE", "MANUAL", "FOOD", "synthetic shop", "confirmed"))
        response = self.post(self.read, self.call("list_recent_transactions", limit=1))
        self.assertEqual(response.status_code, 200, response.text)
        text = json.dumps(response.json(), ensure_ascii=False)
        self.assertIn("synthetic shop", text)
        summary = self.post(self.read, self.call("spending_summary",
            start_ms=1791750000000, end_ms=1791760000000))
        self.assertEqual(summary.status_code, 200, summary.text)
        self.assertIn("2345", json.dumps(summary.json()))

    def test_every_read_tool_executes_with_a_read_grant(self):
        # Wallos data stays mocked; SQL readers operate on the temp ledger.
        calls = [
            self.call("list_recent_transactions", limit=1),
            self.call("spending_summary", start_ms=1791750000000, end_ms=1791760000000),
            self.call("get_subscription_list"),
            self.call("get_recurring_expense_candidates"),
            self.call("personal_finance_long_term_trends", months=2),
            self.call("finance_reconciliation_queue"),
            self.call("finance_duplicate_review", limit=1),
        ]
        with patch("wallos_api.get_all", return_value=[]):
            for call in calls:
                with self.subTest(tool=call["params"]["name"]):
                    response = self.post(self.read, call)
                    self.assertEqual(response.status_code, 200, response.text)
                    self.assertFalse(response.json().get("result", {}).get("isError"),
                                     response.text)

    def test_all_write_tools_require_scope_and_work_on_synthetic_data(self):
        with core.db_connection() as db:
            expense = db.execute("""INSERT INTO transactions
                (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant,review_status)
                VALUES(?,?,?,?,?,?,?,?,?)""", (
                    "test:mcp:expense", 1791756000000, 2345, "CNY", "EXPENSE",
                    "MANUAL", "OTHER", "synthetic expense", "confirmed")).lastrowid
            refund = db.execute("""INSERT INTO transactions
                (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant,review_status)
                VALUES(?,?,?,?,?,?,?,?,?)""", (
                    "test:mcp:refund", 1791757000000, 345, "CNY", "INCOME",
                    "MANUAL", "OTHER", "synthetic refund", "unverified")).lastrowid
        operations = [
            self.call("edit_record", tx_id=expense, merchant="edited synthetic expense"),
            self.call("register_merchant_rule", needle="synthetic merchant", category="FOOD"),
            self.call("link_refund", original_tx_id=expense, refund_tx_id=refund, amount_cents=345),
            self.call("add_subscription", name="test only", amount_cents=300,
                      currency="CNY", cadence="monthly", next_due_ms=1795000000000),

        ]
        with patch("wallos_api.get_all", return_value=[]), patch("wallos_api.add", return_value=7):
            for operation in operations:
                with self.subTest(tool=operation["params"]["name"]):
                    self.assertEqual(self.post(self.read, operation).status_code, 403)
                    response = self.post(self.both, operation)
                    self.assertEqual(response.status_code, 200, response.text)
                    self.assertFalse(response.json().get("result", {}).get("isError"),
                                     response.text)
        with core.db_connection() as db:
            self.assertEqual(
                db.execute("SELECT merchant FROM transactions WHERE id=?", (expense,)).fetchone()[0],
                "edited synthetic expense",
            )
            self.assertEqual(db.execute("SELECT count(*) FROM merchant_rules").fetchone()[0], 1)
            self.assertEqual(db.execute("SELECT count(*) FROM refund_links").fetchone()[0], 1)

    def test_admin_token_retains_compatibility(self):
        r = self.post("isolated-test-admin-key", {
            "jsonrpc": "2.0", "id": 7, "method": "tools/list", "params": {},
        })
        self.assertEqual(r.status_code, 200, r.text)


if __name__ == "__main__":
    unittest.main()
