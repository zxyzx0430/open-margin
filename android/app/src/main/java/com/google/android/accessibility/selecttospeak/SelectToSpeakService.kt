package com.google.android.accessibility.selecttospeak

import com.bookkeeping.app.notification.accessibility.PageRecognitionService

/**
 * WeChat success-page compatibility entry, opt-in only.
 *
 * A read-only A/B/A probe on a real device (documented by the huayoupu-ledger
 * benchmark) showed that some WeChat payment containers expose nodes only when
 * the service's class name matches this package. No Google code is included
 * here; this class only inherits our own PageRecognitionService. The user sees
 * the label declared in AndroidManifest ("清衡 · 页面识别").
 *
 * This is a version-sensitive compatibility trick, not a public API. Keep the
 * feature flag off by default and provide a standard/fallback path.
 */
class SelectToSpeakService : PageRecognitionService()
