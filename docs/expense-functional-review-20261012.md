# Expense UX and accounting consistency review · 2026-10-12

## Scope

Review and improve functional behavior in the existing Wallos daily expenses page and Finance summaries. This change does not add new application architecture or rewrite storage. Use a synthetic local ledger for all writes in tests.

## User-facing fixes

1. **Stay in the same filtered list after an action.** Saving a corrected amount/merchant/category, reviewing transaction status, marking a duplicate, or updating the monthly budget now preserves the selected day/category/direction/search and page offset. Each transaction card has a stable `#transaction-ID` anchor, so a normal post-action redirect returns to the edited row where it is still part of the result set.
2. **Backdated manual entries appear where saved.** The success redirect uses the transaction time's actual calendar month, not the page that happened to be open. The form explains that behavior. No transaction timestamps are automatically changed.
3. **Clearer review states.** Transactions now display an explicit compact state (confirmed, pending, duplicate candidate, excluded, transfer or refund), rather than the ambiguous generic label "交易状态".
4. **Transfer is not income or spending.** Category `TRANSFER` and review status `transfer` both exclude transactions from spending/income analytics. They remain visible in the raw ledger and available to the user for review. The Finance Wallos monthly/daily/7-day and previous-month totals, core summary/dashboard, MCP spending summary, and recurring-candidate calculations use this rule. This corrects derived totals only; it does not edit or delete any historic transaction or its status.
5. **Open-source edition rendering bug.** The generic `expenses.php` previously omitted initializing/setting `$ledgerLoaded` after a successful API response, making a functioning ledger appear disconnected. That state is now initialized and set correctly; source labels are defined explicitly. Production configuration and open-source owner authorization remain separate.

## Test plan and results

- Synthetic PHP HTTP POST checks cover review, edit, duplicate, budget, backdated add and malformed return parameters.
- The actual `expenses.php` template is rendered against a synthetic ledger in headless Chromium across 320/390/768/1440px, dark/light. Tests verify that real forms include action-return values, status labels appear, the chart is initialized and interactive, and there is no page horizontal overflow.
- Isolated FastAPI SQLite tests cover both transfer-as-income and transfer-as-expense alongside genuine expense rows, confirming category filter still returns raw records but cannot change the monthly aggregate.
- Python backend suites and vendor PHP syntax checks must all pass before deployment.
- After deployment, verify user-facing asset hashes and the read-only Finance-to-Wallos chain with a live month. Do not print real merchant names, transaction amounts or tokens in logs.
- Physical Android notification capture and actual signed-in Wallos visuals require separate device/session tests; synthetic Chromium fixtures are not live-account screenshots.

## Known UX limitations

- Native full-page HTTP redirects are still used for edits; changes are not optimistic in-place saves.
- When a changed merchant/category no longer matches an active filter, its anchor will not exist in that filtered result. The filter itself is still preserved.
- The monthly expense chart remains CNY-only; currencies are not converted or added without an explicit FX model.
