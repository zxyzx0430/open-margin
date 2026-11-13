"""Source-contract smoke tests for unified Open Margin web pages.

These tests never connect to a server or mutate user finances.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent / "wallos-extension"


def source(name):
    return (ROOT / name).read_text(encoding="utf-8")


class UnifiedWebStructureTest(unittest.TestCase):
    def test_global_assets_and_theme(self):
        self.assertIn('class="om-ui <?= $theme ?>', source("header.php"))
        self.assertIn("ui-unified.css", source("header.php"))
        self.assertIn("ui-unified.js", source("header.php"))
        self.assertIn("COPY ui-unified.js", source("Dockerfile"))

    def test_seven_page_routing(self):
        script = source("ui-unified.js")
        for page in (
            "settings.php", "profile.php", "about.php", "subscriptions.php",
            "calendar.php", "stats.php",
        ):
            self.assertIn(page, script)
        self.assertIn("expenses-preview.php", script)
        self.assertIn('heading("财务分析", root, finance);', script)

    def test_stats_no_duplicate_native_overview(self):
        stats = source("stats.php")
        self.assertIn('data-om-stat-extra hidden', stats)
        self.assertIn("om-native-duplicated", stats)
        self.assertIn("订阅详细统计", stats)
        self.assertNotIn("页面上方为 Finance 真实财务分析", stats)

    def test_calendar_has_single_month_control_layer(self):
        calendar = source("calendar.php")
        common = source("finance-unified-common.php")
        script = source("ui-unified.js")
        self.assertIn("om-calendar-main", calendar)
        self.assertIn("om-calendar-page", calendar)
        self.assertIn("财务日历", script)
        self.assertIn('finance_render_panel(\'calendar\'', calendar)
        self.assertIn("本月续费次数", script)
        self.assertIn('finance-native-heading', common)

    def test_expenses_primary_action(self):
        ledger = source("expenses.php")
        css = source("ui-unified.css")
        self.assertIn('id="ledger-live"', ledger)
        self.assertIn("交易流水", ledger)
        self.assertIn("nth-of-type(5){order:1}", css)
        self.assertNotIn("按月实时汇总已同步交易。Wallos 订阅计划", ledger)

    def test_settings_grouping_and_no_stale_payment_count(self):
        js = source("ui-unified.js")
        self.assertIn('"finance"', js)
        self.assertIn('"notifications"', js)
        self.assertIn('"appearance"', js)
        self.assertIn('"advanced"', js)
        self.assertIn("#upcomingpaymentslimit", js)
        self.assertIn("group.remove()", js)

    def test_home_carousel_not_regressed(self):
        index = source("index.php")
        self.assertIn("dashboard-carousel.js", index)
        self.assertIn("om-carousel-track", index)
        self.assertIn("['current' => [], 'future' => []]", index)
        self.assertIn("CLONES = 4", source("dashboard-carousel.js"))

    def test_about_explicit_upstream_attribution(self):
        about = source("about.php")
        self.assertIn("清衡（Open Margin）基于 Wallos 与 Seamless Bookkeeping", about)
        self.assertIn("我们是整合与修改方", about)
        self.assertIn("https://github.com/ellite/Wallos", about)
        self.assertIn("https://github.com/DykiSensei/seamless-bookkeeping", about)
        self.assertIn("GPL-3.0", about)
        self.assertIn("MIT", about)
        self.assertIn('translate("credits"', about)

    def test_upstream_page_width_restored(self):
        css = source("ui-unified.css")
        self.assertNotIn("max-width:1240px", css)
        self.assertIn("upstream .contain max-width:970px", css)

    def test_responsive_and_non_destructive(self):
        css = source("ui-unified.css")
        self.assertIn("@media(max-width:480px)", css)
        self.assertIn("prefers-reduced-motion", css)


if __name__ == "__main__":
    unittest.main()
