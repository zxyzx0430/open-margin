# Daily spending chart selection and reconciliation-feature retirement (2026-10-11)

## Product changes

- On bar or date-column click, the chosen day becomes selected. Its bar gains a gentle scale/brightness/shadow transition; other bars dim; a translucent vertical date band marks its x-axis position, even if the daily total is zero or too small to draw a visible bar.
- Every click (including repeated selection) replays a brief confirmation animation. The summary panel below has a contrasting primary-color border, subtle tinted background, bold amount, explicit "已选日期" label and "查看当日交易" action.
- Keyboard date selection and the accessible day table still function. Choosing a day beyond the visible horizontal chart area scrolls just the chart horizontally to make that day visible. Reduced-motion preferences disable animation.
- Completely remove the optional `integration.php` subscription/payment matching screen, all Wallos links to it, its write forms, and its image content (do not leave a hidden, addressable reconciliation page). Previously cached screen HTML must never be served from the service worker.
- Retire dedicated HTTP and MCP entry points for subscription-payment cycle matching, matching dashboard, recurring discovery and creating subscriptions through that dashboard. General Wallos subscription management (`subscriptions.php`), real transactions, merchant classification, transaction correction, and refund/duplicate integrity rules remain.
- No migration or deletion of user financial records, subscription database rows, links or audit history. Internal legacy calculation and tables are deliberately retained as historical data, but retired interfaces are not exposed to users.

## Source boundaries

Wallos is the subscription schedule manager; Finance SQLite is the true transaction ledger. Neither planned charges nor unclaimed vouchers become real spending. Nothing in the dashboard feature removal changes actual-spending totals.

## Verification checklist

- `python -m unittest discover` using the existing isolated-test DBs; removed routes must return 404 and normal transaction edit, month sum, merchant rules and refund linkage still pass.
- PHP syntax checks in the pinned vendor container; Node JS syntax, Bash syntax, git diff checks.
- Playwright 11 synthetic test scenarios × widths 320, 390, 768, 1440 × light/dark = 88 baseline interaction cases; verify selected SVG bar has the correct day index, 2px summary border and date band exists. Special mobile retest for scrolling far days.
- Vendor-image staging test must confirm `/var/www/html/integration.php` no longer exists. Production asset hashes must equal committed source.
- Prior to deployment make online-consistent Finance and Wallos database snapshots and keep the previous Wallos container for rollback.
- After deployment test normal finance-api / Wallos health, an authenticated worker-side Finance-read route, and HTTP 404 for retired page and endpoints. Do not disclose production amounts, access tokens or individual records.

## Known boundary

Actual signed-in Wallos browser screenshot may not be available with existing credentials. Treat simulated Chromium screenshots as fixture visual evidence only; do not claim full live session screenshot validation.

## Synthetic visual result

[390px light: selected day 13, enlarged glowing bar and selected expense card](assets/selected-day-390-light.png). This contains fixture-only values; no user transaction data.
