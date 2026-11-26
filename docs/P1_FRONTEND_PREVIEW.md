# P1 preview migration

Base: d2cec9a95bcf7944ba4ec8aadf6e49a4608c8799

- Existing all-time ledger remains unchanged; default hero summarizes the current month using the existing ledger totals predicate. Explicit drilldowns preserve their scope.
- Appearance preferences are persisted locally, with green-income/red-expense and red-income/green-expense choices. The source document names the same color assignment twice, so the two labels name their actual colors.
- Material preference is persisted; P1 intentionally keeps all material tiers solid until P4.
- No navigation or VoiceFab source edits.
- No ADB screenshots per revised execution instruction. Visual checks remain manual after final preview.
- All CI gates must pass before P2. Do not merge to main.
