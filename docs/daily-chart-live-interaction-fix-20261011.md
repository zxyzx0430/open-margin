# Daily spending interaction regression repair · 2026-10-11

## User-visible symptom

On both Android and desktop browsers, tapping an expense-day column did not consistently produce the expected persistent selection highlight or brief animation.

## Root causes

1. The PWA Service Worker treated `daily-spend-chart.js` as cache-first and used `ignoreSearch: true`, so the PHP `?v=filemtime` URL did **not** guarantee a fresh script. Old PWA installations could continue running stale behavior across deployments.
2. The 60-second Finance refresh would `oldView.replaceWith(newView)` whenever its revision changed. That deletes ApexCharts' SVG and all attached click listeners; scripts from `DOMParser` are never executed, so the new HTML had no chart initializer.
3. Existing selection feedback lasted only 0.28 seconds and the day band had a nearly imperceptible 10% opacity; zero-spend dates have no visible bar to enlarge.

## Repair

- A new `daily-spend-chart-v2.js` filename bypasses any previously installed SW entry for the old filename. The new SW handles this script network-first, honoring newly deployed code rather than the ignore-search stale cache.
- Revision changes now reload the full page exactly once, keeping the existing stable chart DOM when nothing changed. This rebuilds the ApexCharts instance from the real database without mutating the ledger.
- The selected day gets an unmistakable persistent blue vertical outline and band, a visibly brighter/larger column where possible, and a blue bordered/tinted amount panel. Touching an empty or tiny bar still highlights that calendar day. Selection animations last 0.46–0.48 seconds, respecting reduced-motion preferences.
- No changes to API authentication, Wallos owner permissions, Finance or Wallos database schema, or accounting calculations.
- The retired subscription-payment reconciliation view remains removed.

## Tests and operational safety

- Run `python3 deploy/test_daily_chart_browser.py` using **synthetic** fixture amounts and the vendored ApexCharts library. Covers 11 calendar cases × 4 device widths × 2 themes, including click-to-select accuracy.
- Run `python3 deploy/test_daily_chart_live_refresh.py`: with unchanged revision, the initialized chart DOM survives; a changed revision reloads the page.
- Run backend unit tests in the existing virtual environment, PHP syntax check in the vendor image, Node syntax check, and deploy script Bash syntax check.
- Verify both SQLite online backups and leave an intact stopped previous Wallos container for rollback.
- Post-deploy: compare baked asset hashes to committed files, verify the newly named script responds HTTP 200, then verify authenticated PHP worker's real daily/monthly ledger aggregation without printing any finance records.
- Live signed-in screenshots need an authorized user session; synthetic browser evidence alone must not be claimed to be a production account screenshot.

### Visual fixture

[390px dark-mode selected day, synthetic data](assets/daily-highlight-v2-390-dark.png)
