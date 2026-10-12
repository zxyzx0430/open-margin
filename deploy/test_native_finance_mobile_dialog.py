"""Regression: native Wallos transaction drawer never clips on mobile.

Read-only production Finance access via isolated Wallos staging container.
All transaction mutation POSTs are intercepted by Playwright.
"""
import json
from playwright.sync_api import sync_playwright

BASE="http://127.0.0.1:18282/"
SESSION="pfs-modal-test"

def run():
    results=0
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path="/usr/bin/chromium",
                                  headless=True,args=["--no-sandbox"])
        for width in (320,360,390,412,768,1024):
            for dark in (False,True):
                ctx=browser.new_context(viewport={"width":width,"height":780},
                                        device_scale_factor=2,is_mobile=width<850,
                                        has_touch=width<850,color_scheme="dark" if dark else "light")
                ctx.add_cookies([{"name":"PHPSESSID","value":SESSION,"url":BASE}])
                page=ctx.new_page()
                errors=[]
                requests=[]
                page.on("pageerror",lambda exc:errors.append(str(exc)))
                def fake_action(route):
                    requests.append(json.loads(route.request.post_data))
                    route.fulfill(status=200,content_type="application/json",
                                  body='{"ok":true}')
                page.route("**/finance-unified-action.php",fake_action)
                page.goto(BASE+"calendar.php?month=10&year=2026",
                          wait_until="domcontentloaded",timeout=18000)
                page.locator(".finance-calendar-day[data-finance-day='2026-10-11']").click()
                drawer=page.locator("dialog.finance-day-dialog[open]")
                drawer.wait_for(timeout=16000)
                entries=drawer.locator(".finance-day-entry")
                entries.first.wait_for(timeout=16000)
                details=drawer.locator(".finance-entry-edit")
                assert details.count()>=1
                assert not details.first.evaluate("(el)=>el.open")
                assert drawer.locator(".finance-day-amounts strong").count()==3
                # Exact bounding boxes must remain inside the viewport, even
                # when list content is taller than the screen.
                def assert_fit():
                    result=drawer.evaluate("""el=>{
                        const viewport=document.documentElement.clientWidth;
                        const rect=el.getBoundingClientRect();
                        const close=el.querySelector("[data-finance-close]").getBoundingClientRect();
                        const tooWide=[...el.querySelectorAll("input,select,button,summary,strong")]
                            .filter(x=>x.getClientRects().length)
                            .map(x=>({tag:x.tagName,r:x.getBoundingClientRect()}))
                            .filter(x=>x.r.right>viewport+1 || x.r.left < -1);
                        return {viewport,left:rect.left,right:rect.right,width:rect.width,
                            closeLeft:close.left,closeRight:close.right,
                            scrollWidth:el.scrollWidth,clientWidth:el.clientWidth,
                            overflow:tooWide.map(x=>({tag:x.tag,left:x.r.left,right:x.r.right}))}
                        }""")
                    assert abs(result["left"])<=1 or width>850,result
                    assert result["right"]<=width+1,result
                    assert result["closeRight"]<=width+1,result
                    assert result["scrollWidth"]<=result["clientWidth"]+1,result
                    assert not result["overflow"],result
                assert_fit()
                details.first.locator("summary").click()
                assert details.first.evaluate("(el)=>el.open")
                assert details.first.locator("form").is_visible()
                assert_fit()
                # Saving must still work after the compact-edit refactor. The
                # POST is intercepted; no real account data is modified.
                form=details.first.locator("form.finance-tx-edit")
                form.locator("input[name=amount]").fill("4.50")
                form.locator("button[type=submit]").click()
                page.wait_for_function("() => document.querySelector('.finance-feedback')?.textContent.includes('已保存')",
                                       timeout=16000)
                assert requests and requests[-1]["action"]=="edit",requests
                assert not errors,(width,dark,errors)
                if width==390 and not dark:
                    page.screenshot(path="/tmp/pfs-mobile-modal-fixed-390.png",full_page=False)
                results+=1
                ctx.close()
        browser.close()
    print("MOBILE_DRAWER_REGRESSION_PASS",results,
          "6 widths x 2 themes; compact forms, overflow, close, mocked edit")

if __name__=="__main__":run()
