"""MCP protocol and OAuth scope tests using temporary synthetic databases only."""
import json
import sqlite3
import tempfile
import unittest
import uuid
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
        self.assertEqual(len(tool_names), 18)
        self.assertEqual(len(mcp_access.READ_TOOLS), 8)
        self.assertEqual(len(mcp_access.WRITE_TOOLS), 10)

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
                # add_record/exclude_record/set_monthly_budget are explicitly
                # idempotent; permanent deletes are not advertised as retry-safe.
                self.assertIs(annotations["idempotentHint"],
                              name in {"add_record", "exclude_record", "set_monthly_budget"})
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
            self.call("get_monthly_budget", month="2026-11"),
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

    def result(self, response):
        self.assertEqual(response.status_code, 200, response.text)
        result = response.json()["result"]
        self.assertFalse(result.get("isError"), response.text)
        return json.loads(result["content"][0]["text"])

    def insert_tx(self, event_id, amount_cents=1000, direction="EXPENSE"):
        with core.db_connection() as db:
            return db.execute("""INSERT INTO transactions
                (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant,review_status)
                VALUES(?,?,?,?,?,?,?,?,?)""",
                (event_id, 1791756000000, amount_cents, "CNY", direction,
                 "MANUAL", "OTHER", event_id, "unverified")).lastrowid

    def test_record_crud_flow_and_duplicate_retry(self):
        request_id = str(uuid.uuid4())
        payload = self.call("add_record", request_id=request_id,
                            timestamp_ms=1791756000000, amount_cents=2345,
                            direction="EXPENSE", currency="CNY",
                            category="FOOD", merchant="crud shop")
        self.assertEqual(self.post(self.read, payload).status_code, 403)
        created = self.result(self.post(self.write, payload))
        self.assertTrue(created["created"])
        tx_id = created["id"]
        listed = self.result(self.post(self.read, self.call("list_recent_transactions", limit=5)))
        self.assertIn(tx_id, {item["id"] for item in listed["items"]})
        replay = self.result(self.post(self.write, payload))
        self.assertFalse(replay["created"])
        self.assertEqual(replay["id"], tx_id)
        self.assertTrue(replay["idempotent_replay"])
        self.assertFalse(replay["current_record_differs"])
        reused = self.result(self.post(self.write, self.call(
            "add_record", request_id=request_id, timestamp_ms=1791756000000,
            amount_cents=3001, category="FOOD", merchant="crud shop")))
        self.assertFalse(reused["created"])
        self.assertEqual(reused["id"], tx_id)
        self.assertTrue(reused["current_record_differs"])
        edited = self.result(self.post(self.write, self.call(
            "edit_record", tx_id=tx_id, amount_cents=3000, merchant="crud shop edited")))
        self.assertTrue(edited["ok"])
        summary = self.result(self.post(self.read, self.call(
            "spending_summary", start_ms=1791750000000, end_ms=1791760000000)))
        self.assertEqual(summary["groups"][0]["amount_cents"], 3000)
        deleted = self.result(self.post(self.write, self.call(
            "delete_record", tx_id=tx_id, confirm=True)))
        self.assertTrue(deleted["deleted"])
        self.assertTrue(deleted["verified"])
        resurrect = self.result(self.post(self.write, payload))
        self.assertIn("deleted record", resurrect["error"])
        after = self.result(self.post(self.read, self.call("list_recent_transactions", limit=10)))
        self.assertNotIn(tx_id, {item["id"] for item in after["items"]})

    def test_add_record_validation_and_failed_retry(self):
        request_id = str(uuid.uuid4())
        bad = self.result(self.post(self.write, self.call(
            "add_record", request_id=request_id, timestamp_ms=1791756000000,
            amount_cents=100, category="INJECT_SQL")))
        self.assertIn("Invalid currency", bad["error"])
        with core.db_connection() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 0)
        zero = self.result(self.post(self.write, self.call(
            "add_record", request_id=str(uuid.uuid4()),
            timestamp_ms=1791756000000, amount_cents=0)))
        self.assertIn("amount_cents", zero["error"])
        invalid_key = self.result(self.post(self.write, self.call(
            "add_record", request_id="not-a-uuid",
            timestamp_ms=1791756000000, amount_cents=100)))
        self.assertIn("idempotency_key", invalid_key["error"])
        retried = self.result(self.post(self.write, self.call(
            "add_record", request_id=request_id, timestamp_ms=1791756000000,
            amount_cents=100, category="FOOD")))
        self.assertTrue(retried["created"])
        income = self.result(self.post(self.write, self.call(
            "add_record", request_id=str(uuid.uuid4()), timestamp_ms=1791756000000,
            amount_cents=500, direction="INCOME", category="SALARY")))
        self.assertTrue(income["created"])
        summary = self.result(self.post(self.read, self.call(
            "spending_summary", start_ms=1791750000000, end_ms=1791760000000)))
        groups = {(g["direction"], g["category"], g["amount_cents"])
                  for g in summary["groups"]}
        self.assertIn(("EXPENSE", "FOOD", 100), groups)
        self.assertIn(("INCOME", "SALARY", 500), groups)

    def test_exclude_record_keeps_row_but_removes_from_totals(self):
        created = self.result(self.post(self.write, self.call(
            "add_record", request_id=str(uuid.uuid4()), timestamp_ms=1791756000000,
            amount_cents=4321, category="FOOD", merchant="exclude shop")))
        tx_id = created["id"]
        self.assertEqual(self.post(self.read, self.call(
            "exclude_record", tx_id=tx_id)).status_code, 403)
        excluded = self.result(self.post(self.write, self.call(
            "exclude_record", tx_id=tx_id)))
        self.assertTrue(excluded["ok"])
        with core.db_connection() as db:
            row = db.execute("SELECT review_status FROM transactions WHERE id=?", (tx_id,)).fetchone()
        self.assertEqual(row["review_status"], "excluded")
        summary = self.result(self.post(self.read, self.call(
            "spending_summary", start_ms=1791750000000, end_ms=1791760000000)))
        self.assertEqual(summary["groups"], [])
        again = self.result(self.post(self.write, self.call("exclude_record", tx_id=tx_id)))
        self.assertTrue(again["ok"])
        missing = self.result(self.post(self.write, self.call("exclude_record", tx_id=999999)))
        self.assertIn("not found", missing["error"].lower())

    def test_delete_record_guards_links_and_publishes_tombstone(self):
        expense_id = self.insert_tx("test:mcp:guarded-expense")
        refund_id = self.insert_tx("test:mcp:guarded-refund", direction="INCOME")
        matched_id = self.insert_tx("test:mcp:matched-expense")
        normal_id = self.insert_tx("test:mcp:normal-delete")
        with core.db_connection() as db:
            db.execute("INSERT INTO refund_links VALUES(?,?,?)", (refund_id, expense_id, 345))
            db.execute("""INSERT INTO statement_rows(statement_id,external_id,timestamp_ms,
                amount_cents,currency,direction,merchant,matched_tx_id)
                VALUES(?,?,?,?,?,?,?,?)""",
                (1, "test-external", 1791756000000, 1000, "CNY", "EXPENSE", "bank", matched_id))
            db.execute("INSERT INTO transaction_splits(transaction_id,category,amount_cents) VALUES(?,?,?)",
                       (normal_id, "FOOD", 400))
            db.execute("INSERT INTO tx_subscription_links(tx_id,sub_id,cycle_key) VALUES(?,?,?)",
                       (normal_id, 77, "2026-10-01"))
        denied = self.result(self.post(self.write, self.call("delete_record", tx_id=normal_id)))
        self.assertIn("confirm=true", denied["error"])
        refund_guard = self.result(self.post(self.write, self.call(
            "delete_record", tx_id=expense_id, confirm=True)))
        self.assertIn("Unlink refunds", refund_guard["error"])
        exclude_guard = self.result(self.post(self.write, self.call(
            "exclude_record", tx_id=expense_id)))
        self.assertIn("Unlink related refunds", exclude_guard["error"])
        statement_guard = self.result(self.post(self.write, self.call(
            "delete_record", tx_id=matched_id, confirm=True)))
        self.assertIn("bank statement", statement_guard["error"])
        deleted = self.result(self.post(self.write, self.call(
            "delete_record", tx_id=normal_id, confirm=True)))
        self.assertTrue(deleted["deleted"])
        self.assertTrue(deleted["verified"])
        self.assertEqual(deleted["splits_removed"], 1)
        self.assertEqual(deleted["subscription_links_removed"], 1)
        with core.db_connection() as db:
            self.assertIsNone(db.execute(
                "SELECT 1 FROM transactions WHERE id=?", (normal_id,)).fetchone())
            self.assertEqual(db.execute(
                "SELECT COUNT(*) FROM transaction_splits WHERE transaction_id=?",
                (normal_id,)).fetchone()[0], 0)
            self.assertEqual(db.execute(
                "SELECT COUNT(*) FROM tx_subscription_links WHERE tx_id=?",
                (normal_id,)).fetchone()[0], 0)
            latest = db.execute("""SELECT action FROM sync_journal WHERE event_id=?
                ORDER BY revision DESC LIMIT 1""", ("test:mcp:normal-delete",)).fetchone()
        self.assertEqual(latest["action"], "delete")
        retry = self.result(self.post(self.write, self.call(
            "delete_record", tx_id=normal_id, confirm=True)))
        self.assertIn("not found", retry["error"].lower())

    def test_deleted_record_reaches_device_sync_as_tombstone(self):
        tx_id = self.insert_tx("test:mcp:device-delete")
        deleted = self.result(self.post(self.write, self.call(
            "delete_record", tx_id=tx_id, confirm=True)))
        self.assertTrue(deleted["deleted"])
        with patch.object(core, "DEVICE_TOKEN", "isolated-device-token"):
            response = self.client.get("/api/v2/sync/changes", params={"since": 0},
                                       headers={"X-Device-Token": "isolated-device-token"})
        self.assertEqual(response.status_code, 200, response.text)
        items = [item for item in response.json()["items"]
                 if item["event_id"] == "test:mcp:device-delete"]
        self.assertTrue(items)
        self.assertEqual(items[-1]["action"], "delete")
        self.assertIsNone(items[-1]["record"])

    def test_delete_subscription_requires_confirm_and_is_verified(self):
        from wallos_api import WallosError
        before = {"id": 8, "name": "sample", "amount_cents": 1000, "currency": "CNY",
                  "cadence": "monthly", "next_due_ms": 1795000000000}
        payload = self.call("delete_subscription", subscription_id=8, confirm=True)
        self.assertEqual(self.post(self.read, payload).status_code, 403)
        with patch("wallos_api.get_all", return_value=[before]), \
             patch("wallos_api.delete") as remover:
            denied = self.result(self.post(self.write, self.call(
                "delete_subscription", subscription_id=8)))
        self.assertIn("confirm=true", denied["error"])
        remover.assert_not_called()
        tx_id = self.insert_tx("test:mcp:subscription-link")
        with core.db_connection() as db:
            db.execute("INSERT INTO tx_subscription_links(tx_id,sub_id,cycle_key) VALUES(?,?,?)",
                       (tx_id, 8, "2026-10-01"))
        with patch("wallos_api.get_all", side_effect=[[before], []]), \
             patch("wallos_api.delete", return_value=8) as remover:
            deleted = self.result(self.post(self.write, payload))
        self.assertTrue(deleted["deleted"])
        self.assertTrue(deleted["verified"])
        self.assertEqual(deleted["finance_links_removed"], 1)
        self.assertEqual(deleted["deleted_subscription"]["id"], 8)
        remover.assert_called_once_with(8)
        with patch("wallos_api.get_all", return_value=[before]), \
             patch("wallos_api.delete", side_effect=WallosError("Wallos API request failed")):
            failed = self.result(self.post(self.write, payload))
        self.assertIn("failed", failed["error"])
        with patch("wallos_api.get_all", side_effect=[[before], [before]]), \
             patch("wallos_api.delete", return_value=8):
            unverified = self.result(self.post(self.write, payload))
        self.assertFalse(unverified["deleted"])
        self.assertFalse(unverified["verified"])
        self.assertIn("readback", unverified["error"])

    def test_monthly_budget_scope_set_get_clear(self):
        month = "2026-11"
        self.assertEqual(self.post(self.read, self.call(
            "set_monthly_budget", month=month, amount_cents=150000)).status_code, 403)
        self.assertEqual(self.post(self.write, self.call(
            "get_monthly_budget", month=month)).status_code, 403)
        unset = self.result(self.post(self.read, self.call("get_monthly_budget", month=month)))
        self.assertIsNone(unset["budget_cny_cents"])
        set_result = self.result(self.post(self.write, self.call(
            "set_monthly_budget", month=month, amount_cents=150000)))
        self.assertEqual(set_result["budget_cny_cents"], 150000)
        self.assertEqual(self.result(self.post(self.read, self.call(
            "get_monthly_budget", month=month)))["budget_cny_cents"], 150000)
        cleared = self.result(self.post(self.write, self.call(
            "set_monthly_budget", month=month, amount_cents=0)))
        self.assertIsNone(cleared["budget_cny_cents"])
        self.assertIsNone(self.result(self.post(self.read, self.call(
            "get_monthly_budget", month=month)))["budget_cny_cents"])
        invalid_month = self.result(self.post(self.write, self.call(
            "set_monthly_budget", month="2026-13", amount_cents=1)))
        self.assertIn("YYYY-MM", invalid_month["error"])
        negative = self.result(self.post(self.write, self.call(
            "set_monthly_budget", month=month, amount_cents=-1)))
        self.assertIn("amount_cents", negative["error"])
        loose_month = self.result(self.post(self.read, self.call(
            "get_monthly_budget", month="2026-1")))
        self.assertIn("YYYY-MM", loose_month["error"])

    def test_admin_token_retains_compatibility(self):
        r = self.post("isolated-test-admin-key", {
            "jsonrpc": "2.0", "id": 7, "method": "tools/list", "params": {},
        })
        self.assertEqual(r.status_code, 200, r.text)


if __name__ == "__main__":
    unittest.main()
