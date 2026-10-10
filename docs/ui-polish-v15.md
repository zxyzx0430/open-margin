# Wallos finance UI polish · V1.5

The Wallos-native daily ledger and subscription reconciliation pages now share
compact Wallos-theme typography, responsive summary grids, readable money
amounts, consistent focus states and mobile-friendly form controls.

- **Reconciliation:** six summary cards form an even 3 × 2 grid on desktop
  (2 × 3 on narrow screens); the old oversized 42px values and full-width
  orphan card are removed.
- **Subscriptions:** a four-column desktop table becomes accessible labeled
  cards on mobile, without truncated names or horizontal scrolling.
- **Controls:** frequently used reconciliation and anomaly items remain
  visible; merchant rules, refund entry, subscription discovery and long-term
  trends are organized as opt-in disclosure panels.
- **Daily expenses:** matching compact 3-column/2-column summary grids,
  mobile-friendly amount and category editors, visible focus styling.

Testing was performed on anonymous, synthetic records rendered from the PHP
template, not real user payments. Responsive checks used Chromium / Playwright
at **320, 390, 768 and 1440 CSS pixels**, with all optional panels expanded,
and asserted document width and form-control clipping. PHP lints and existing
backend tests were run before deployment.

This is a presentation-layer change. Existing Finance SQLite and Wallos
subscription databases were not rewritten, cleared or migrated.
