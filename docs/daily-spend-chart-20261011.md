# Daily spend chart | 2026-10-11

## Scope and decision

Replace only the PHP/CSS calendar bars in `deploy/wallos-extension/expenses.php`.
Reuse the existing **local** Wallos `scripts/libs/apexcharts.min.js` (verified v5.15.2, 600820 bytes); do not fetch CDNs, change SQLite schema, redefine finance events, or integrate two ledgers.

Evaluated:
- [ApexCharts](https://github.com/apexcharts/apexcharts.js) — shipped with Wallos and supports column charts, automatic Y scale, point selection, theming.
- [Chart.js](https://github.com/chartjs/Chart.js) — compact Canvas alternative but new dependency and separate accessible date controls still required.
- [Apache ECharts](https://github.com/apache/echarts) — mature responsive tool but extra bundle unnecessary for one chart.
- Original CSS Flex/manual percentages — no true Y axis, narrow hit targets, text labels lack reliable date/value feedback.

**Decision:** existing ApexCharts SVG plot + stable non-overlapping selected-date panel. Local on-device date selection and table are provided to users of touch, keyboard, screen readers, and clients without script.

## Source of truth / date alignment

The existing Finance API endpoint `wallos-ledger/month?month=YYYY-MM` is the only data source. No secondary DB, subscription schedules, external exchange rates, or speculative purchases are counted.

- `daily_totals` is from Finance SQLite transaction timestamps grouped by Beijing-local calendar day. Backend excludes review statuses `excluded`, `transfer`, and `refund`, and chart only plots CNY `EXPENSE`.
- PHP generates a continuous calendar array `YYYY-MM-01` to month's actual last date using `date('t')`, in `Asia/Shanghai`; all missing days receive **integer 0 cents**. Never drop empty dates or shift data points.
- JS converts cents to yuan only at presentation; values, tooltip/selection and table are formatted to 2 decimals, while auto-scaled Y labels can use fewer decimal places.
- Chart is the **unfiltered monthly total**, matching monthly overview and category panels; selecting a day filters only the transaction list, not the overview. The daily table links to `expenses.php?month=...&day=...`.
- Months in progress show recorded transactions immediately. The future days of the displayed month remain zero placeholders.
- The Finance API remains authoritative; no real transactions are modified by these UI changes.

## Mobile and accessibility

The chart has a minimum 620px plotting width, horizontally scrollable **inside** the card on 320px/390px screens. This retains consistent 31-day columns and legible labels (1, 5, 10, etc.). It does not create page-level horizontal overflow. The plot scales with the container on tablet/desktop. Date selection works by SVG click and plot-column hit position for subpixel bars, or by labelled native `select` to choose all dates including zero days.

The date and exact yuan amount appear beneath the chart in an `aria-live` status region; this avoids tooltip escaping/clipping the viewport. Navigation is an ordinary `a` link. A full accessible daily table is always in the server-rendered HTML. CSS variables and body theme class control dark/light display; the chart responds to class changes.

## How to test

```sh
# Chart library from the existing running, pinned vendor image; test only.
mkdir -p /tmp/pfs-chart-test-assets
docker cp finance-wallos:/var/www/html/scripts/libs/apexcharts.min.js /tmp/pfs-chart-test-assets/apexcharts.min.js
docker cp finance-wallos:/var/www/html/styles /tmp/pfs-chart-test-assets/styles
PFS_CHART_EVIDENCE=/tmp/pfs-chart-evidence python3 deploy/test_daily_chart_browser.py
node --check deploy/wallos-extension/daily-spend-chart.js
# PHP requires the deployed image's PHP runtime: lint via a read-only mounted copy.
```

Playwright tests 11 synthetic calendar patterns × 4 viewport widths × 2 themes, validates every date/value option, the 30/31/leap lengths, text/link value, SVG columns and plot hit areas, page overflow and runtime exceptions; screenshots are **synthetic visual fixtures**, not screenshots of private live transactions. Backend unittest remains separate.

## Deployment / rollback

Back up Finance and Wallos SQLite using the online SQLite backup API, check `PRAGMA quick_check`; keep backups outside Git at mode 600. Branch, lint, unit test, Playwright visual inspect, then deploy through existing `deploy/install-wallos-extension.sh` (content-addressed Docker image, existing volumes retained). Script keeps previous container as `finance-wallos-rollback-YYYYMMDDHHMMSS`, automatically restores it on failure. Monitor finance-api and authenticated read-only worker-to-Finance API response. Reinstall previous image/container only if regression confirmed, without replacing either DB.

No APK, signing system, API routes, subscriptions database, domain, authentication, or Finance schema were changed. Generic open-source variant retains its own owner authorization and configurable timezone/ledger transport; no production configuration is copied into it.

## Outstanding verification boundary

Live authenticated browser screenshots require an existing authorized browser session. A successful unauthenticated HTTP probe or a PHP-to-API probe alone does not establish visual correctness of the logged-in live page. Record that verification separately if no session is available.
