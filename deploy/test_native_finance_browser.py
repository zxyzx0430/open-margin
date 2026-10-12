"""Read-only integration checks against the isolated Wallos staging container.

The production Finance ledger may be queried read-only through the existing PHP
bridge. Every POST to the custom action endpoint is intercepted in Chromium;
NEVER allow this suite to write to live Finance or Wallos databases.
"""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE="http://127.0.0.1:18282/"
SESSION="pfsnativeuidemo"
def run():
    tested=0
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path="/usr/bin/chromium",
                                  headless=True,args=["--no-sandbox"])
        for width in (320,390,768,1440):
            for dark in (False,True):
                context=browser.new_context(viewport={"width":width,"height":880})
                context.add_cookies([{"name":"PHPSESSID","value":SESSION,
                                      "url":BASE}])
                pages=[
                    ("index.php",".finance-native[data-finance-view=dashboard]"),
                    ("calendar.php",".finance-native[data-finance-view=calendar]"),
                    ("stats.php",".finance-native[data-finance-view=stats]"),
                ]
                for url,selector in pages:
                    page=context.new_page()
                    errors=[]
                    page.on("pageerror",lambda err:errors.append(str(err)))
                    if dark:
                        page.emulate_media(color_scheme="dark")
                    # Never write to live Finance ledger from an end-to-end test.
                    intercepted=[]
                    def mock_action(route):
                        intercepted.append(json.loads(route.request.post_data))
                        route.fulfill(status=200,content_type="application/json",body='{"ok":true}')
                    page.route("**/finance-unified-action.php",mock_action)
                    response=page.goto(BASE+url,wait_until="domcontentloaded",timeout=20000)
                    assert response.status==200,(width,dark,url,response.status)
                    page.locator(selector).wait_for(timeout=16000)
                    root=page.locator(selector)
                    assert root.locator("[data-finance-csrf]").count()==0 # token is adjacent
                    assert page.locator("[data-finance-csrf]").count()>=1
                    assert page.locator(".finance-unavailable").count()==0,(url,page.locator("body").inner_text()[-300:])
                    assert root.locator("[data-finance-value]").count()>=2,(url,"missing metrics")
                    if url=="index.php":
                        page.locator(".finance-chart .apexcharts-series").first.wait_for(timeout=14000)
                        page.locator("[data-finance-range='7']").click()
                        assert page.locator("[data-finance-range='7']").get_attribute("class").find("is-current")>=0
                        page.locator("[data-finance-detail='expense']").click()
                        page.locator("dialog.finance-day-dialog[open]").wait_for(timeout=12000)
                        assert page.locator("dialog .finance-modal-title").inner_text()=="本月实际支出"
                        page.locator("[data-finance-close]").click()
                        # Budget form sends an isolated, mocked mutation payload.
                        input=page.locator("form[data-finance-budget-form] input[name=budget]")
                        input.fill("2026.10")
                        page.locator("form[data-finance-budget-form] button[type=submit]").click()
                        page.wait_for_timeout(300)
                        assert any(x.get("action")=="budget" and x.get("amount_cents")==202610
                                   for x in intercepted),(url,intercepted)
                    elif url=="calendar.php":
                        assert page.locator(".finance-calendar-day").count()>=28
                        first=page.locator(".finance-calendar-day").first
                        first.locator(".finance-calendar-amount").wait_for(timeout=7000)
                        first.locator(".day").click()
                        page.locator("dialog.finance-day-dialog[open]").wait_for(timeout=16000)
                        page.locator(".finance-day-amounts strong").first.wait_for(timeout=12000)
                        assert page.locator(".finance-day-amounts strong").count()==3
                        page.locator("[data-finance-close]").click()
                    elif url=="stats.php":
                        assert page.locator("[data-finance-tab]").count()==3
                        page.locator("[data-finance-tab=categories]").click()
                        assert page.locator("[data-finance-panel=categories]").is_visible()
                        page.locator("[data-finance-tab=subscriptions]").click()
                        assert page.locator("[data-finance-panel=subscriptions]").is_visible()
                        page.locator("[data-finance-tab=overview]").click()
                        page.locator(".finance-chart .apexcharts-series").first.wait_for(timeout=14000)
                    # Different upstream Wallos sections can require scrolling; check
                    # the NEW widget independently for accidental overflow.
                    root_width=root.evaluate("(el)=>el.getBoundingClientRect().width")
                    assert root_width <= width+3,(width,url,root_width)
                    assert not errors,(width,dark,url,errors[:5])
                    tested+=1
                    page.close()
                context.close()
        # Existing vendor calendar rejected historical months. Financial calendar may not.
        context=browser.new_context()
        context.add_cookies([{"name":"PHPSESSID","value":SESSION,"url":BASE}])
        page=context.new_page()
        page.goto(BASE+"calendar.php?month=9&year=2026",wait_until="domcontentloaded")
        assert page.locator(".finance-native[data-finance-month='2026-09']").count()==1
        assert page.locator(".finance-calendar-day").count()==30
        context.close()
        browser.close()
    print("NATIVE_FINANCE_BROWSER_PASS",tested,"page combinations; history navigation pass; HTTP mutations intercepted")

if __name__=="__main__":run()
