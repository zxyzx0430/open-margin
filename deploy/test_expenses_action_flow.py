"""Isolated HTTP regression tests for Wallos expense action redirects.

Starts an ephemeral PHP server from a disposable directory with stubbed ledger_request.
Never accesses production databases, sessions, authentication keys or transactions.
Requires Docker and the existing Wallos PHP image.
"""
from __future__ import annotations

import os
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import build_opener, Request, HTTPRedirectHandler
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "wallos-extension" / "expenses-action.php"


class KeepRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class ExpenseReturnFlowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="pfs-expense-actions-")
        cls.webroot = Path(cls.tmp.name)
        (cls.webroot / "includes").mkdir()
        shutil.copy2(SOURCE, cls.webroot / "expenses-action.php")
        (cls.webroot / "includes/header.php").write_text(
            "<?php session_start(); $_SESSION['finance_csrf']='synthetic-fixture'; ?>")
        (cls.webroot / "ledger-common.php").write_text(
            "<?php function ledger_require_owner() {} "
            "function ledger_request($method,$endpoint,$payload=null) {return ['ok'=>true];} ?>")
        image = subprocess.check_output(
            ["docker", "inspect", "finance-wallos", "--format", "{{.Config.Image}}"],
            text=True).strip()
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            cls.port = s.getsockname()[1]
        cls.name = "pfs-expense-flow-fixture-" + str(os.getpid())
        subprocess.run([
            "docker", "run", "-d", "--rm", "--network", "host",
            "--name", cls.name, "-v", f"{cls.webroot}:/fixture:ro",
            "--entrypoint", "php", image,
            "-S", f"127.0.0.1:{cls.port}", "-t", "/fixture"
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        for _ in range(40):
            try:
                with socket.create_connection(("127.0.0.1", cls.port), timeout=.2):
                    break
            except OSError:
                time.sleep(.15)
        else:
            raise AssertionError("Ephemeral PHP server did not start")
        cls.opener = build_opener(KeepRedirect())

    @classmethod
    def tearDownClass(cls):
        try:
            subprocess.run(["docker", "rm", "-f", cls.name], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finally:
            cls.tmp.cleanup()

    def submit(self, **form):
        post = {"csrf": "synthetic-fixture", "month": "2026-10", **form}
        req = Request(
            f"http://127.0.0.1:{self.port}/expenses-action.php",
            data=urlencode(post).encode(), method="POST")
        try:
            self.opener.open(req, timeout=4)
            raise AssertionError("Handler did not redirect")
        except HTTPError as exc:
            self.assertEqual(exc.code, 303)
            location = exc.headers["Location"]
            self.assertIsNotNone(location)
            parts = urlparse(location)
            self.assertEqual(parts.path, "expenses.php")
            return parts, {k: v[0] for k, v in parse_qs(parts.query).items()}

    def test_review_preserves_filters_page_and_transaction(self):
        parts, query = self.submit(
            action="review", entry_id="42", status="excluded",
            return_day="2026-10-09", return_category="FOOD",
            return_direction="EXPENSE", return_q="便利店", return_offset="100")
        self.assertEqual(parts.fragment, "transaction-42")
        self.assertEqual(query, dict(month="2026-10", notice="reviewed", day="2026-10-09",
                                     category="FOOD", direction="EXPENSE", q="便利店", offset="100"))

    def test_edit_does_not_confuse_new_category_with_filter(self):
        parts, query = self.submit(
            action="edit", entry_id="24", amount="18.86", merchant="New merchant",
            category="SHOPPING", return_category="FOOD", return_q="食堂")
        self.assertEqual(parts.fragment, "transaction-24")
        self.assertEqual(query["category"], "FOOD")
        self.assertEqual(query["q"], "食堂")
        self.assertEqual(query["notice"], "saved")

    def test_duplicate_return_with_day_filter(self):
        parts, query = self.submit(
            action="duplicate", entry_id="17", return_day="2026-10-02")
        self.assertEqual(parts.fragment, "transaction-17")
        self.assertEqual(query["day"], "2026-10-02")
        self.assertEqual(query["notice"], "saved")

    def test_budget_return_keeps_context(self):
        parts, query = self.submit(
            action="budget", budget_amount="1500.00", return_direction="EXPENSE",
            return_offset="300")
        self.assertEqual(parts.fragment, "")
        self.assertEqual(query["direction"], "EXPENSE")
        self.assertEqual(query["offset"], "300")
        self.assertEqual(query["notice"], "budget")

    def test_manual_backdated_entry_routes_to_saved_month(self):
        parts, query = self.submit(
            action="add", request_id="synthetic-uuid", amount="9.80",
            currency="CNY", direction="EXPENSE", category="FOOD",
            spent_at="2026-09-15T12:30", merchant="Mock entry",
            return_day="2026-10-09", return_offset="100")
        self.assertEqual(parts.fragment, "")
        self.assertEqual(query, {"month": "2026-09", "notice": "added"})

    def test_bad_return_filters_are_not_reused(self):
        parts, query = self.submit(
            action="review", entry_id="1", status="confirmed",
            return_day="2026-09-30", return_category="../etc",
            return_direction="UNKNOWN", return_offset="-500")
        self.assertEqual(parts.fragment, "transaction-1")
        self.assertEqual(query, {"month": "2026-10", "notice": "reviewed"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
