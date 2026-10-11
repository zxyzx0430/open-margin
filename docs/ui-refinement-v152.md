# Personal Finance · UI refinement V1.5.2

## Changes
- Android: summary explicitly labels income-minus-expense, rather than suggesting a real account balance.
- Android: transaction row emphasizes source / time / merchant / amount; notification text is one-line by default and expands on tap; destructive removal requires confirmation.
- Android: warning explains that deleting a locally synced transaction does **not** automatically change its server ledger record.
- Wallos: month-to-date sync indicator, clearly labeled 4-field filters, compact budget editor, legible chart tick marks, zero-amount dates display no artificial bar.
- Wallos: avoids ambiguous merchant fallback and reduces large help-text blocks.
- All on-device stored transaction values, Wallos subscriptions and VPS financial data remain untouched by these UI changes.

## Validation
- PHP syntax lint / image build, browser fixture screenshot checks at 320, 390, 768, 1440 pixels, expand forms and check overflow.
- Android unit tests and signed upgrade APK build using the existing permanent private signing key after a successful GitHub Actions build.
- Post-deployment Wallos health and ledger current-month API checks, 35 backend regression tests.
