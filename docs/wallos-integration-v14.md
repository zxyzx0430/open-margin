# Wallos × Daily Ledger integration (v1.4, 2026-10-09)

Two sources remain **separate**: Wallos owns subscription schedules, billing amounts, categories and payment methods; the personal finance SQLite database owns actual observed payments, transactions and corrections. Never create a fictional payment when adding or renewing a Wallos subscription.

## Shipped workflows

1. **Subscription reconciliation**: the Wallos-native `integration.php` screen shows one line per expected monthly/weekly/annual billing occurrence. An unmatched real transaction may be suggested by matching date/amount/merchant. The user **confirms** the link; ambiguity never causes silent subscription payment assignment. A uniqueness constraint prevents matching one payment twice or filling one subscription billing period twice. Unlinking never deletes the original expense.
2. **Budget / forecast**: actual recorded CNY expense **minus linked refunds** plus **unmatched forecast CNY subscriptions**. Paid-linked cycles contribute once via the actual transaction, not again as scheduled cost. Budget remains user-controlled; no presumed exchange rate.
3. **Merchant rules**: user-confirmed substring → category mapping, applied to newly ingested Android transactions only if existing classification is `OTHER`. Rules are editable/deletable in the Wallos integration screen; generic payment-platform labels cannot form a rule.
4. **Recurring discovery**: possible monthly/yearly vendor repetition, amount tolerance and next due date. Confirmation form offers to add a Wallos subscription; no silent subscription creation, charging or card changes.
5. **Anomaly explanations**: mismatched actual amount vs expected, overdue unmatched billing cycles, possible duplicate captured transactions. These are **on-page diagnostic alerts**, not background push notifications. They are suggestions; a missing match does not prove that a payment failed.
6. **Ledger corrections / refunds**: edit amount/merchant/category in the daily ledger, record before/after field values in transaction_edits. Link an existing refund income transaction to its original expense, check currency and total refund caps, allow unlink. A partial refund is supported when the **entire refund income event** is linked (for instance ¥5 returned on a ¥25 expense). One refund event cannot be split across several purchases in the current model. Mark a duplicate as excluded (never erase raw evidence). Prevent excluded originals from retaining active refund/link semantics.
7. **Long-term trends**: 12-month native view and API 2–36 month statistics by currency; refunds shown as separate deductions in the **month the refund arrived** (cash-flow basis).
8. **AI/MCP**: personal_finance_overview, personal_finance_long_term_trends, personal_finance_recurring_discovery and explicit write tools for payment linking, merchant rule, refund and correction. OAuth finance:write permission required for writes.

## URLs

- Wallos /finance/wallos/expenses.php (now links to integration)
- Wallos /finance/wallos/integration.php?month=YYYY-MM (full native-style reconciliation view)
- Restricted /api/v1/wallos-ledger/integration?month=YYYY-MM (structured dashboard)
- /integration/trends?months=12, /integration/discover?months=12, /integration/rules
- POST /integration/link, DELETE /integration/link/{tx_id}
- POST /integration/rules, DELETE /integration/rules/{id}
- PATCH /integration/transaction/{tx_id}, POST /integration/duplicate/{tx_id}
- POST /integration/refund, DELETE /integration/refund/{refund_tx_id}
- POST /integration/subscribe (confirmed Wallos create only)

All backend URLs above use the `/api/v1/wallos-ledger` prefix and its server-only token.

## Known limits

- This is payment matching and spending attribution, not official bill reconciliation.
- Payment notification merchants can be generic and dates may differ from Wallos planned charges. Candidate matches are never auto-confirmed.
- A change to a Wallos subscription's next due schedule can make a previously confirmed cycle hard to visualize; historical link remains in SQLite and can be removed by transaction ID.
- The future spending number is a **forecast**, never money already spent. Other currencies remain separate.
- Finance ledger budget is separate from upstream Wallos **subscription-only budget**; the combined projected remaining budget is displayed in the integration screen.
- Notifications: existing Wallos subscription reminders remain native, while the new reconciliation warnings are shown when the dashboard is opened (not pushed to SMS or email).
- No live bank reconciliation, no forecasting from statistical models, and no unauthorized background subscription creation.
