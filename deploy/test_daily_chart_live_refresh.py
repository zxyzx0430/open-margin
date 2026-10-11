"""Regression: monthly auto-refresh does not replace an initialized chart's DOM.
Run from an isolated local HTML fixture; no production credentials or transactions.
"""
from pathlib import Path
import re
import tempfile
from playwright.sync_api import sync_playwright

PHP=Path(__file__).parent/"wallos-extension/expenses.php"
source=PHP.read_text()
assert "oldView.replaceWith(newView)" not in source
assert "location.reload();" in source
script="// Refresh current view every 60 seconds."+source.split("// Refresh current view every 60 seconds.",1)[1].split("</script>",1)[0]
assert "async function ledgerRefreshCurrentView()" in script
assert "setInterval(ledgerRefreshCurrentView,60000)" in script
html="""<!doctype html><html><body><div id="ledger-dialog"></div>
<div id="ledger-live" data-revision="baseline"><div id="chart-listeners-preserved">keep</div></div>
<span id="ledger-check-time"></span></body></html>"""
with tempfile.TemporaryDirectory(prefix="finance-refresh-test-") as temp:
    file=Path(temp)/"expenses.php"/"index.html"
    file.parent.mkdir()
    file.write_text(html)
    with sync_playwright() as browser_api:
        browser=browser_api.chromium.launch(executable_path="/usr/bin/chromium",
                                            headless=True,args=["--no-sandbox"])
        page=browser.new_page()
        page.goto(file.as_uri())
        page.add_script_tag(content=script)
        page.evaluate("""() => {
            window.fetch = async () => ({
                ok:true,url:location.href,
                text: async () => '<div id="ledger-live" data-revision="baseline"><div>different content</div></div>'
                });
        }""")
        page.evaluate("ledgerRefreshCurrentView()")
        assert page.locator("#chart-listeners-preserved").count()==1,"Old chart DOM replaced despite unchanged data"
        page.evaluate("""() => {
            window.fetch = async () => ({
                ok:true,url:location.href,
                text: async () => '<div id="ledger-live" data-revision="changed">new</div>'
            });
        }""")
        with page.expect_navigation(timeout=6000):
            page.evaluate("ledgerRefreshCurrentView()")
        assert page.locator("#chart-listeners-preserved").count()==1,"Full page not rebuilt"
        browser.close()
print("LIVE_REFRESH_BROWSER_PASS: stable revision keeps chart DOM, changed revision reloads scripts")
