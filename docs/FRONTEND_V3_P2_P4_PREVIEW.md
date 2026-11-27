# V3 frontend preview: P2–P4

Preview branch: feat/frontend-p0-tokens. Baseline P1: df5438a.

## Scope
- P2: passive initial pointer observation, non-consuming press feedback, system animation-scale observer, spring sheet handle for entry/filter/subscription detail. VoiceFab gesture state machine unchanged; only its visual press scale/spec changes.
- P3: local ledger/detail shared bounds and elements with an initial transaction snapshot; other routes keep their original instant-transition safeguards. Three-card subscription preview (176dp cards, 28dp corners, 52dp exposed headers), spring drag, bring selected card forward, full grouped management list. The deck previews the next three active subscriptions, not the whole list. Touch exploration and large font sizes fall back to list.
- P4: native API 31+ background-only RenderEffect, clipped bottom chrome and contrast film; opaque financial content. Four scrolling tabs reserve 32dp overlap and restore end padding; the calendar keeps its existing safe layout. Low RAM, non-accelerated rendering, touch exploration and known reduced-transparency/high-contrast settings fall back to solid. Older API versions use tinted chrome. Android does not expose a universal reduced-transparency setting across OEMs. No new runtime dependencies or compileSdk changes.

## Verification
P1 already passed all three Linux GitHub workflows. On the local Windows checkout, P1 full unit testing had 1607 tests, 52 failures and 1 skipped; P2 and P3 have the exact same failing test names. The known capture-store failures reproduce without UI changes (Windows File.renameTo cannot replace an existing target). Do not weaken capture tests or alter persistence to make the Windows baseline green. Final Linux CI is the authoritative full regression gate.

No device/ADB run is performed. Verify list/detail round trips, rapid repeated clicks, keyboard and save, VoiceFab single/double/750ms long press, subscription card selection and list fallback, all chrome tiers and device performance on a real device. Debug APK signing differs from release signing; do not uninstall existing data merely to test a debug APK.
