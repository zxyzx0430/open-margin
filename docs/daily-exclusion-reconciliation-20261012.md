# Daily expense reconciliation transparency — 2026-10-12

## Background

Different clients may report different totals when an Android local transaction
is deleted without being marked `excluded` in the synced Finance ledger. The
Android app currently deletes Room records locally only. This feature makes the
Finance server's included and excluded amounts separately auditable, without
assuming that another client is wrong or changing any historical transaction.

## Changes

- Finance month read-only endpoint adds `daily_excluded_totals`, summarizing previously excluded transaction rows separately (including `excluded`, `transfer`, `refund` statuses or `TRANSFER` category). The existing `daily_totals` remains the sole source for chart bar height, monthly totals, category figures and budget progress.
- PHP produces full calendar-day points as `{day, cents, excluded_cents}` in integer fen. It renders a 4-column full-month table with both actual spend and excluded amounts, inside a horizontally scrollable details region.
- Selecting a day shows the included amount (bar value), separately shows excluded CNY outflows and the original gross CNY outflow, and links to that date's **all records**, including excluded ones.
- The page explains that Android local deletion does not currently sync exclusion status to Finance and points to the current status selector in the Wallos transaction list.
- The live refresh revision hash now also includes `dailyExcludedTotals`; review status changes trigger a correct refresh even when only exclusion totals changed.

## Test boundaries

- The production backend uses consistent SQL predicates for `daily_totals` and `totals`, excluding `review_status IN ('excluded','transfer','refund')` or `category='TRANSFER'`. `daily_excluded_totals` aggregates complementary rows for review only.
- FastAPI mock data tests verify excluded transactions remain in the item list, count only in excluded breakdown, and do not enter category or monthly totals.
- Chromium tests cover calendar lengths, mobile/desktop widths, light/dark, selected day with synthetic excluded amounts and overflow behavior of the opened reconciliation table.
- No changes to Android APK, live review statuses, or real financial transaction rows.
- Do not automatically subtract an unexplained gap from any chart; reconcile it to specific transactions.
