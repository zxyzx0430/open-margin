"""Live-stage native Finance chart interactions; no production writes.

The Wallos DB is a temp copy. Finance API access is read-only, and all
finance-unified-action.php POST requests are intercepted in Chromium.
"""
from playwright.sync_api import sync_playwright
from pathlib import Path
import json
BASE="http://127.0.0.1:18282/"
RESULTS=Path("/tmp/pfs-trend-fixed-evidence")
RESULTS.mkdir(exist_ok=True)
def run():
    count=0
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path="/usr/bin/chromium",
                                   args=["--no-sandbox"])
        for width in (320,390,768,1440):
            for dark in (False,True):
                ctx=browser.new_context(viewport={"width":width,"height":870},
                                        device_scale_factor=2,is_mobile=width<500,
                                        has_touch=width<500,color_scheme="dark" if dark else "light")
                ctx.add_cookies([{"name":"PHPSESSID","value":"pfsnativeuidemo","url":BASE}])
                page=ctx.new_page()
                errors=[]
                calls=[]
                page.on("pageerror",lambda exc:errors.append(str(exc)))
                page.route("**/finance-unified-action.php",
                           lambda route:(calls.append(route.request.post_data),
                              route.fulfill(status=200,content_type="application/json",body='{"ok":true}')))
                response=page.goto(BASE+"stats.php?finance_month=2026-10",
                                   wait_until="domcontentloaded",timeout=20000)
                assert response.status==200,(width,response.status)
                area=page.locator(".finance-native[data-finance-view=stats] .finance-chart")
                section=page.locator(".finance-native[data-finance-view=stats] .finance-chart-section")
                page.locator("[data-finance-chart-ready=true]").wait_for(timeout=20000)
                assert section.locator(".finance-trend-controls").count()==1
                assert area.evaluate("""el=>[...el.querySelectorAll('.apexcharts-tooltip,.apexcharts-xaxistooltip')].every(x=>
                    getComputedStyle(x).display==='none'||getComputedStyle(x).opacity==='0')""")
                entries=area.evaluate("(e)=>e.financeTrend.getEntries()")
                assert len(entries)==31,(width,len(entries))
                # Use a native SVG path center, as the user taps the blue bar.
                for day,index in [(11,10),(12,11)]:
                    bar=area.locator(".apexcharts-bar-area").nth(index)
                    assert bar.count()==1
                    bar.click(force=True,timeout=12000)
                    page.wait_for_function("(date)=>document.querySelector('.finance-native[data-finance-view=stats] .finance-chart')?.dataset.financeSelectedDay===date",arg=f"2026-10-{day:02d}",timeout=3500)
                    assert area.locator(".finance-trend-selected-bar").count()==1
                    assert area.locator(".finance-trend-focus:not([hidden])").count()==1
                    assert section.locator(".finance-trend-selected").is_visible()
                    page.wait_for_timeout(590)
                    metrics=area.evaluate("""(host)=>{
                      let bar=host.querySelector(".apexcharts-bar-area.finance-trend-selected-bar");
                      let outline=host.querySelector(".finance-trend-focus");
                      let bb=bar.getBoundingClientRect(),ob=outline.getBoundingClientRect();
                      return {bar:bb.height,outline:ob.height,top:bb.top,outlineTop:ob.top,
                        nativeTooltipVisible:[...host.querySelectorAll('.apexcharts-tooltip')]
                        .some(e=>getComputedStyle(e).display!=='none'&&getComputedStyle(e).opacity!=='0')}
                    }""")
                    assert abs(metrics["bar"]-metrics["outline"])<4,(width,day,metrics)
                    assert abs(metrics["top"]-metrics["outlineTop"])<4,(width,day,metrics)
                    assert not metrics["nativeTooltipVisible"],(width,day,metrics)
                    assert f"{day:02d}" in section.locator(".finance-trend-selected-date").inner_text()
                if width==390 and not dark:
                    page.screenshot(path=str(RESULTS/"month-12-selected-390.png"))
                section.locator(".finance-trend-detail").click()
                page.locator("dialog.finance-day-dialog[open]").wait_for(timeout=12000)
                assert "2026-10-12" in page.locator(".finance-modal-title").inner_text()
                page.locator("[data-finance-close]").click()
                area.locator("[data-finance-range='7']").count() # range controls live one level higher
                page.locator(".finance-native[data-finance-view=stats] [data-finance-range='7']").click()
                page.locator("[data-finance-chart-ready=true]").wait_for(timeout=20000)
                assert section.locator(".finance-trend-controls").count()==1
                series=area.evaluate("(e)=>e.financeTrend.getEntries()")
                assert [r["date"] for r in series]==[
                    f"2026-10-{i:02d}" for i in range(6,13)],(width,series)
                assert area.locator(".apexcharts-bar-area").count()==7
                assert section.locator(".finance-trend-date option").count()==8
                assert area.get_attribute("data-finance-selected-day") is None
                area.locator(".apexcharts-bar-area").nth(5).click(force=True,timeout=12000)
                page.wait_for_function("()=>document.querySelector('.finance-native[data-finance-view=stats] .finance-chart')?.dataset.financeSelectedDay==='2026-10-11'",timeout=3500)
                assert "¥91.36" in section.locator(".finance-trend-selected-amount").inner_text()
                section.locator(".finance-trend-date").select_option("2026-10-12")
                page.wait_for_function("()=>document.querySelector('.finance-native[data-finance-view=stats] .finance-chart')?.dataset.financeSelectedDay==='2026-10-12'",timeout=3500)
                assert "¥12.30" in section.locator(".finance-trend-selected-amount").inner_text()
                page.wait_for_timeout(630)
                metrics=area.evaluate("""host=>{
                  const bar=host.querySelector(".finance-trend-selected-bar");
                  const outline=host.querySelector(".finance-trend-focus");
                  const b=bar.getBoundingClientRect(),o=outline.getBoundingClientRect();
                  return {height:b.height,outline:o.height,top:b.top,outlineTop:o.top,
                    card:host.parentElement.querySelector(".finance-trend-selected").hidden}
                }""")
                assert abs(metrics["height"]-metrics["outline"])<4,(width,dark,metrics)
                assert abs(metrics["top"]-metrics["outlineTop"])<4,(width,dark,metrics)
                assert not metrics["card"],(width,dark,metrics)
                if width==390 and not dark:
                    page.screenshot(path=str(RESULTS/"last-seven-12-selected-390.png"))
                assert not errors,(width,dark,errors)
                assert not calls,(width,dark,"unexpected mutation")
                count+=1
                ctx.close()
        browser.close()
    print("NATIVE_TREND_SELECTION_PASS",count,"screens; click date, glow, drill-down, 7-day window")

if __name__=="__main__":run()
