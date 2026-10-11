"""Browser regression of calendar-accurate Wallos expense chart.
No connection to production ledger: all fixtures use synthetic daily cents.

Usage: PFS_CHART_EVIDENCE=/tmp/pfs-chart-evidence python3 deploy/test_daily_chart_browser.py
Requires python-playwright and local Chromium; reads vendor ApexCharts from
PFS_APEXCHARTS_JS (test asset copied from Wallos vendor image, not committed).
"""
from __future__ import annotations
import calendar
from datetime import date
import html
import json
import os
from pathlib import Path
import tempfile
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]
CHART=ROOT/"deploy/wallos-extension/daily-spend-chart.js"
PHP=ROOT/"deploy/wallos-extension/expenses.php"
APEX=Path(os.environ.get("PFS_APEXCHARTS_JS","/tmp/pfs-chart-test-assets/apexcharts.min.js"))
EVIDENCE=Path(os.environ.get("PFS_CHART_EVIDENCE","/tmp/pfs-chart-evidence"))
SCENARIOS={
  "no_spend":("2026-10",{}),
  "single":("2026-10",{3:1500}),
  "adjacent":("2026-10",{3:1500,4:5800,5:1886}),
  "dispersed":("2026-10",{1:120,13:5800,31:1886}),
  "outlier":("2026-10",{2:20,5:1000000,10:700,28:1800}),
  "partial_month":("2026-10",{1:1500,10:860,11:2099}),
  "30_days":("2026-09",{1:120,30:3300}),
  "february":("2025-02",{14:988}),
  "leap_february":("2024-02",{29:1234}),
  "year_boundary":("2025-12",{31:9800}),
  "refund_transfer_excluded":("2026-10",{3:1500,6:1886}),
}
VIEWPORTS=[320,390,768,1440]

def points_for(month, spend):
    y,m=map(int,month.split("-"))
    return [{"day":f"{month}-{d:02d}","cents":spend.get(d,0)}
        for d in range(1,calendar.monthrange(y,m)[1]+1)]

def fixture(month, entries, css):
    opts="".join(f'<option value="{r["day"]}">{r["day"][8:]} 日 · ¥{r["cents"]/100:.2f}</option>' for r in entries)
    rows="".join(f'<tr><td>{r["day"]}</td><td>¥{r["cents"]/100:.2f}</td><td><a href="expenses.php?month={month}&day={r["day"]}">查看流水</a></td></tr>' for r in entries)
    total=sum(p["cents"] for p in entries)
    return f'''<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>日常消费 · {month}（测试模拟数据）</title>
    <link rel="stylesheet" href="styles/theme.css"><link rel="stylesheet" href="styles/dark-theme.css" id="dark-theme" disabled>
    <style>{css}</style></head>
    <body class="light">
    <main class="contain dashboard wallos-ledger">
      <div class="ledger-heading"><h1>日常消费</h1><p>与 Wallos 订阅管理使用同一账户 · 模拟测试</p></div>
      <section class="stats-section"><h2>消费概览 · {month}</h2><div class="statistics">
      <div class="statistic"><span>¥{total/100:,.2f}</span><div class="title">本月支出（模拟）</div></div>
      <div class="statistic"><span>{len([r for r in entries if r["cents"]>0])}</span><div class="title">有消费日期数</div></div></div></section>
      <section class="stats-section" id="finance-daily-chart-section">
      <h2>每日消费趋势 · {month}</h2>
      <div class="ledger-chart-shell" tabindex="0" role="region" aria-label="每日消费柱状图，可左右滑动查看整月">
      <div id="finance-daily-chart" class="ledger-apex-chart" role="img" aria-label="每日消费图"></div></div>
      <div class="ledger-chart-controls"><label for="finance-daily-date">查看指定日期</label>
      <select id="finance-daily-date"><option value="">选择日期</option>{opts}</select>
      <span>柱子对应自然日，左右滑动可查看整月</span></div>
      <div id="finance-daily-selection" class="ledger-chart-selection" role="status" aria-live="polite" hidden>
      <span id="finance-daily-selected-date"></span><strong id="finance-daily-selected-amount"></strong>
      <a id="finance-daily-detail-link" href="expenses.php">查看当日交易</a></div>
      <details class="ledger-chart-table"><summary>查看整月每日金额与明细（含零消费日期）</summary>
      <table><thead><tr><th>日期</th><th>实际支出</th><th>操作</th></tr></thead><tbody>{rows}</tbody></table></details>
      <p class="ledger-section-message ledger-explainer">按北京时间展示已入库消费。不含退款、转账、排除的记录。</p>
      </section></main>
      <script id="finance-daily-data" type="application/json">{html.escape(json.dumps(entries,ensure_ascii=False),quote=False)}</script>
      <script src="apexcharts.min.js"></script><script src="daily-spend-chart.js"></script>
    </body></html>'''

def run():
    assert APEX.is_file(),f"Vendor ApexCharts missing: {APEX}"
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    css=PHP.read_text().split("<style>",1)[1].split("</style>",1)[0]
    print("Testing vendor ApexCharts with production chart code; mocked transactions only",flush=True)
    failures=[]
    count=0
    with tempfile.TemporaryDirectory(prefix="pfs-daily-chart-") as td:
        folder=Path(td)
        (folder/"daily-spend-chart.js").write_bytes(CHART.read_bytes())
        (folder/"apexcharts.min.js").write_bytes(APEX.read_bytes())
        for src in Path("/tmp/pfs-chart-test-assets/styles").rglob("*.css"):
            target=folder/"styles"/src.relative_to("/tmp/pfs-chart-test-assets/styles")
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(src.read_bytes())
        with sync_playwright() as p:
            browser=p.chromium.launch(executable_path="/usr/bin/chromium",headless=True,args=["--no-sandbox"])
            for key,(month,spent) in SCENARIOS.items():
                entries=points_for(month,spent)
                assert sum(e["cents"] for e in entries)==sum(spent.values())
                assert len(entries)==calendar.monthrange(*map(int,month.split("-")))[1]
                (folder/"fixture.html").write_text(fixture(month,entries,css))
                for width in VIEWPORTS:
                    for dark in [False,True]:
                        count+=1
                        page=browser.new_page(viewport={"width":width,"height":820}, device_scale_factor=1)
                        errors=[]
                        page.on("pageerror",lambda e: errors.append(str(e)))
                        try:
                            page.goto((folder/"fixture.html").as_uri(),wait_until="load")
                            page.locator("[data-chart-ready=true]").wait_for(timeout=12000)
                            if dark:
                                page.evaluate("document.body.classList.replace('light','dark')")
                                page.locator("#dark-theme").evaluate("(el) => el.disabled = false")
                            assert page.locator(".apexcharts-bar-area").count()==len(entries),(key,width,"bar count",page.locator(".apexcharts-bar-area").count(),len(entries))
                            assert page.locator("#finance-daily-date option").count()==len(entries)+1
                            # Adjacent 30/31 tick labels overlap around tablet widths.
                            axis_labels=page.locator(".apexcharts-xaxis-label").all_text_contents()
                            if len(entries)==31:
                                assert "30日" not in axis_labels,(key,width,axis_labels)
                            assert not errors,(key,width,errors)
                            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"),(key,width,"page horizontal overflow")
                            # Validate category alignment and amount by selecting a zero/non-zero
                            for index in set([0,len(entries)-1,*[d-1 for d in spent]]):
                                row=entries[index]
                                page.locator("#finance-daily-date").select_option(row["day"])
                                assert page.locator("#finance-daily-selected-date").inner_text()==row["day"]
                                assert page.locator("#finance-daily-selected-amount").inner_text()==f'¥{row["cents"]/100:,.2f}'
                                assert "day="+row["day"] in page.locator("#finance-daily-detail-link").get_attribute("href")
                            # Any day column must work, even beside a much taller expense bar.
                            if spent:
                                target=next(iter(spent))-1
                                if width<768 and target>13: target=0
                                bounds=page.locator(".apexcharts-grid").bounding_box()
                                assert bounds is not None
                                x=bounds["x"]+bounds["width"]*(target+0.5)/len(entries)
                                y=bounds["y"]+bounds["height"]*0.45
                                if x<width:
                                    page.mouse.click(x,y)
                                    assert page.locator("#finance-daily-selected-date").inner_text()==entries[target]["day"],(key,width,"click mismatch")
                            if width in VIEWPORTS and key in ["no_spend","dispersed","outlier","30_days"]:
                                if width in (320,390,768,1440) and ((key=="dispersed" and dark) or (key=="no_spend" and not dark) or (key=="outlier" and width==390 and not dark) or (key=="30_days" and width==1440)):
                                    filename=f"{key}-{width}-{'dark' if dark else 'light'}.png"
                                    page.screenshot(path=str(EVIDENCE/filename),full_page=True)
                        except Exception as e:
                            failures.append((key,width,"dark" if dark else "light",str(e)[:240]))
                        finally:
                            page.close()
            browser.close()
    print(f"SCENARIO_CASES={count} FAILURES={len(failures)}")
    print("SCREENSHOTS="+str(len(list(EVIDENCE.glob("*.png")))))
    for failed in failures[:35]:print("FAIL:",*failed,flush=True)
    if failures: raise SystemExit(1)
    print("CHART_BROWSER_PASS")

if __name__=="__main__":run()
