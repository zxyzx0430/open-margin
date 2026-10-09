# Personal Finance System — functional audit (2026-10-09)

Scope: mobile-collected events -> Finance SQLite -> Wallos subscription schedules -> reconciliation dashboard -> MCP. Functional correctness/UX only; no security evaluation.

## Verified fixes in v1.4.1

1. **Billing date drift**: monthly anchor Jan 31 now projects Feb 28, Mar 31, Apr 30, May 31; leap-year annual anchor Feb 29 projects Feb 28 outside leap years and Feb 29 in leap years. Cycles project directly from Wallos anchor, not repeated clamping.
2. **Cross-month refund cashflow**: refunds now reduce net cash outflow in the month **refund income arrives**, rather than rewriting the original purchase month. Historical long-term trends use the same basis. Gross expenses are still reported separately.
3. **Refund allocation integrity**: an observed income event must be fully allocated to its original purchase; a ¥5 refund of a ¥25 purchase is supported, but allocating only ¥4 of a ¥5 observed refund is rejected because the remaining ¥1 would silently disappear. Splitting one refund income across purchases is not implemented.
4. **Refund review**: direct review as refund without linking a real income event to a real purchase is rejected; otherwise it could disappear from income without offsetting any purchase.
5. **Rule-driven recurrence discovery**: optional suggestions now include weekly, monthly, quarterly and yearly intervals. Active Wallos subscriptions are filtered from duplicate suggestions by merchant and currency. User confirmation is still required.
6. **Wallos prices**: Decimal conversion avoids binary float rounding in unusual currency amounts.

Regression tests cover the fixes using temporary SQLite databases, not the production ledger.

## Comparison with maintained features in existing products

| Product | Grounded existing capabilities | What we should learn |
|---|---|---|
| [Actual Budget](https://actualbudget.org/docs/transactions/importing/) | Imports CSV/QIF/OFX/QFX/CAMT; reconciles imports by bank IDs, then amount/date/payee. Manual account reconciliation tracks cleared vs pending statements. | Read-only preview of official exported statements; matching and user confirmation should be a first-class task, distinct from notification capture |
| [Actual rules](https://actualbudget.org/docs/budgeting/rules/) | Conditions by imported payee, amount, account, date, category; normalizes payees; can learn reusable rules from edits | Upgrade existing merchant substring rule to controlled conditions and merchant aliases only after enough real data |
| [Actual splits](https://actualbudget.org/docs/transactions/split-transactions/) | Multiple categories in one payment; child amounts must sum to original | Transaction split accounting (e.g. groceries + household on one purchase), careful no double counting |
| [Lunch Money recurring](https://support.lunchmoney.app/finances/recurring-items/creating-recurring-items) | Suggested recurring items require approval; recurring rule can match merchant/amount/billing date and auto-link approved future recurring transactions | After confirmed first link, optionally enable **high-confidence** follow-up linking, with a safe revert path; not general amount-only matching |
| [Firefly III](https://docs.firefly-iii.org/how-to/firefly-iii/finances/budgets/) | Category budgets, recurring rules, rollover/growing/correcting overspend | Optional budget rollover and categories after statement import quality improves |
| [Wallos](https://github.com/ellite/Wallos) | Subscription reminders via email, Telegram, Gotify, Discord, push and webhooks; currency conversion, statistics, mobile UI | Reuse upstream notifications/FX instead of reimplementing those parts |

The former [Maybe open-source repository](https://github.com/maybe-finance/maybe) was archived in 2025; useful as a reference for financial dashboard and investments, but not a current upstream to rely on.

## Remaining gaps, ordered by impact

**P0: Actual ledger completeness**

- Notifications can be missing, delayed, mistaken or duplicated. The system currently has no *official payment statement comparison* (with checked, missing, excluded, merged outcomes). Record correctness cannot be inferred from successful WorkManager uploads.
- Same economic event may arrive through WeChat, SMS and a later CSV import with different identifiers. The current cross-source near-time duplicate warning does not safely merge these. Add an independent import-dry-run and reconciliation queue before automatically excluding or merging.
- Current owner ledger is intentionally reset to begin Oct 10, 2026 (Asia/Shanghai). Do not reimport prior transactions while adding reconciliation.

**P1: Streamlined daily workflows**

- Merchant renaming/aliases and editable rule conditions (payee / source / direction / amount / date); user-confirmed suggestions from manual edits. This is more useful than immediately adding complex AI categorization.
- Allow one purchase to be divided among multiple budget categories while preserving the original transaction amount (splits).
- Provide optional high-confidence auto-link for future cycles only after a user confirms a pattern. Current code intentionally requires manual confirmation every time.
- Explicit status for cancelled/paused/skipped recurring cycles; historical projections based on current Wallos schedule are *estimates*, not authoritative evidence of prior bills.
- Current refund event model links exactly one income record to one original purchase; allocations covering multiple purchases require a separate design.

**P2: Quality of life**

- Rollover budgets, reminders for unmatched transactions, monthly personal-finance digest, spendable balance after committed costs (only if tracked accounts are available).
- Better transaction detail / batch editing on mobile; monthly chart without misleading comparisons when history is absent.

## Out of scope for now

- Bank connections to financial institutions without user consent or trusted source, automatic payments, double-entry bookkeeping/investment portfolio as a default, costly predictive AI models, or replacing Wallos's mature scheduling/notification features.

## Testing constraints

- Backend unit and integration tests + separate throwaway Docker install evaluate correctness and deployment.
- No test substitutes for a real WeChat/Alipay payment notification on the actual Android device.
- Production Wallos subscriptions and the Oct 10 ledger start are preserved. No synthetic finance transactions are inserted in production.
