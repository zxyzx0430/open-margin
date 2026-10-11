"""Render the actual Wallos expenses.php with a synthetic ledger in Chromium.

No production data, passwords, or logins are accessed. Requires Docker and
an installed Chromium/Playwright. The PHP endpoint runs in a throwaway container
with mock includes and ledger requests.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import os
import shutil
import socket
import subprocess
import time
import unittest
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
HOST_IMAGE = subprocess.check_output(
    ["docker", "inspect", "finance-wallos", "--format", "{{.Config.Image}}"], text=True
).strip()

HEADER = """<?php
session_start();
$_SESSION['finance_csrf']='synthetic-fixture';
echo '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="styles/theme.css"><link rel="stylesheet" href="styles/dark-theme.css" id="dark-theme" disabled></head><body class="light">';
?>
"""
COMMON = """<?php
function ledger_require_owner(){}
function ledger_escape($v){return htmlspecialchars((string)$v,ENT_QUOTES,'UTF-8');}
function ledger_money($n,$c){return '¥'.number_format($n/100,2,'.',',');}
function ledger_request($method,$path,$payload=null){
  if($method!='GET')return ['ok'=>true];
  return [
    'month'=>'2026-10',
    'items'=>[
      ['id'=>42,'timestamp_ms'=>strtotime('2026-10-09T12:30:00+08:00')*1000,
       'amount_cents'=>1886,'currency'=>'CNY','direction'=>'EXPENSE',
       'source'=>'MANUAL','category'=>'FOOD','merchant'=>'模拟排除交易',
       'review_status'=>'excluded'],
      ['id'=>43,'timestamp_ms'=>strtotime('2026-10-05T09:30:00+08:00')*1000,
       'amount_cents'=>5800,'currency'=>'CNY','direction'=>'EXPENSE',
       'source'=>'MANUAL','category'=>'FOOD','merchant'=>'模拟已确认交易',
       'review_status'=>'confirmed'],
      ['id'=>44,'timestamp_ms'=>strtotime('2026-10-06T10:30:00+08:00')*1000,
       'amount_cents'=>1200,'currency'=>'CNY','direction'=>'INCOME',
       'source'=>'MANUAL','category'=>'TRANSFER','merchant'=>'模拟转账',
       'review_status'=>'unverified']
    ],
    'totals'=>[['currency'=>'CNY','direction'=>'EXPENSE','category'=>'FOOD',
                'total_cents'=>5800,'count'=>1]],
    'daily_totals'=>[['day'=>'2026-10-05','currency'=>'CNY',
                      'direction'=>'EXPENSE','total_cents'=>5800,'count'=>1]],
    'today_totals'=>[], 'last7_totals'=>[], 'previous_month_totals'=>[],
    'month_count'=>3, 'budget_cny_cents'=>100000, 'next_offset'=>null,
    'last_synced_at_utc'=>'synthetic time'
  ];
}
?>
"""

class RenderedExpensePage(unittest.TestCase):
    def test_page_forms_chart_mobile(self):
        with TemporaryDirectory(prefix="pfs-real-php-ui-") as folder:
            target=Path(folder)
            (target/"includes").mkdir()
            (target/"scripts/libs").mkdir(parents=True)
            (target/"includes/header.php").write_text(HEADER)
            (target/"includes/footer.php").write_text("</body></html>")
            (target/"ledger-common.php").write_text(COMMON)
            for name in ("expenses.php","expenses-action.php","daily-spend-chart-v2.js"):
                shutil.copy2(ROOT/"wallos-extension"/name,target/name)
            subprocess.run(["docker","cp","finance-wallos:/var/www/html/styles",
                            str(target/"styles")],check=True,stdout=subprocess.DEVNULL)
            subprocess.run(["docker","cp","finance-wallos:/var/www/html/scripts/libs/apexcharts.min.js",
                            str(target/"scripts/libs/apexcharts.min.js")],
                           check=True,stdout=subprocess.DEVNULL)
            with socket.socket() as s:
                s.bind(("127.0.0.1",0));port=s.getsockname()[1]
            container="pfs-real-php-fixture-"+str(os.getpid())
            subprocess.run(
                ["docker","run","-d","--rm","--network","host","--name",container,
                 "-v",str(target)+":/fixture:ro","--entrypoint","php",HOST_IMAGE,
                 "-S",f"127.0.0.1:{port}","-t","/fixture"],
                check=True,stdout=subprocess.DEVNULL)
            try:
                for _ in range(45):
                    try:
                        with socket.create_connection(("127.0.0.1",port),timeout=.2):break
                    except OSError:time.sleep(.2)
                else: self.fail("Synthetic PHP server did not start")
                with sync_playwright() as pw:
                    browser=pw.chromium.launch(executable_path="/usr/bin/chromium",
                                               headless=True,args=["--no-sandbox"])
                    for width in (320,390,768,1440):
                        for dark in (False,True):
                            page=browser.new_page(viewport={"width":width,"height":850})
                            errors=[]
                            page.on("pageerror",lambda e:errors.append(str(e)))
                            page.goto(f"http://127.0.0.1:{port}/expenses.php?month=2026-10&day=2026-10-09&category=FOOD&direction=EXPENSE&q=mock&offset=100")
                            try:
                                page.locator("[data-chart-ready=true]").wait_for(timeout=12000)
                            except Exception:
                                print("RENDER_DIAGNOSTIC",page.url,
                                    "chart host",page.locator("#finance-daily-chart").count(),
                                    "chart data",page.locator("#finance-daily-data").count(),
                                    "render text",page.locator("body").inner_text()[-800:],
                                    "JS errors",errors,flush=True)
                                raise
                            if dark:
                                page.evaluate("document.body.classList.replace('light','dark')")
                                page.locator("#dark-theme").evaluate("(el) => el.disabled = false")
                            self.assertFalse(errors,errors)
                            self.assertEqual(page.locator("#transaction-42").count(),1)
                            self.assertIn("不计入统计",page.locator("#transaction-42 .ledger-review-state").inner_text())
                            self.assertIn("转账 · 不计入",page.locator("#transaction-44 .ledger-review-state").inner_text())
                            self.assertEqual(page.locator('form[action="expenses-action.php"] input[name="return_day"]').count(),10)
                            self.assertEqual(page.locator('form[action="expenses-action.php"] input[name="return_offset"]').count(),10)
                            self.assertEqual(page.locator('form.ledger-search-form input[name^="return_"]').count(),0)
                            self.assertLessEqual(page.evaluate("document.documentElement.scrollWidth"),width)
                            page.locator("#finance-daily-date").select_option("2026-10-05")
                            self.assertEqual(page.locator("#finance-daily-selected-amount").inner_text(),"¥58.00")
                            self.assertEqual(page.locator("#finance-daily-selection.is-active").count(),1)
                            if width==390 and not dark and os.getenv("PFS_UI_SCREENSHOT"):
                                page.screenshot(path=os.environ["PFS_UI_SCREENSHOT"],full_page=True)
                            page.close()
                    browser.close()
            finally:
                subprocess.run(["docker","rm","-f",container],check=False,
                               stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        print("FULL_PHP_TEMPLATE_MOCK_CHROMIUM_PASS: 4 widths × 2 themes")

if __name__=="__main__":
    unittest.main(verbosity=2)
