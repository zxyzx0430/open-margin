# English Add Transaction Dialog Overflow
- Date button now uses compact padding and a bounded adaptive width; date stays on one line while amount uses remaining space.
- Category value stays single-line with more width; merchant label ellipsizes instead of wrapping; account chips remain single-line and horizontally scrollable.
- Existing dialog labels already have EnglishUiText mappings; no new keys or transaction/save logic changes were needed.
- No extra form rows were added, preserving the compact dialog height and bottom actions.
