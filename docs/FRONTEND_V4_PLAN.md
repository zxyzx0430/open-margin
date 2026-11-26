# Frontend V4 — confirmed design contract

Baseline: 9fcd5e3, feat/frontend-p0-tokens. Development branch feat/frontend-v4; never merge or replace production without acceptance.

## Confirmed
- Separate expanded title, physically collapses into compact navigation.
- Single coherent card-to-detail container transform and inverse; no nested/flying fragments.
- Full-sheet drag, scroll/velocity arbitration; preserve save-in-progress dismissal guard.
- Day-grouped ledger cards, quieter borders; amount remains complete and single line.
- Six customizable subscription cards, muted brand colors, directional satin highlight and contact shadow.
- Income/expense summary first; secondary status foldable, critical notices remain discoverable.
- Keep always-clear translucent floating bar (no blur), C indicator, direct main tabs, calendar live resize, VoiceFab three gestures, all financial/capture/sync features. Preserve test applicationId and data.
- Respect reduce motion, font scaling, screen reader, and light/dark modes.

## Workstreams / gates
1. R8 opt-in test build and measurement harness; production R8 gated on native OCR, ASR, Room/Hilt, sync and capture checks. Real Baseline Profile requires device collection.
2. Separate immutable display projections, move expensive computation off UI thread, lazy row reuse, stable icon cache, amount measurement.
3. Shared title/sheet/navigation/gesture system; no conflicting animation layers.
4. Refine ledger, calendar, statistics, budget, settings, wallet and detail. Preserve feature discoverability.
5. Debug + performance variant build, unit tests, CI, screenshot and frame-time A/B on same device. No visual/performance claim without device evidence.
