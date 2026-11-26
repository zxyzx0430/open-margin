# V4 performance measurement

Use only the existing sync3 test package; do not uninstall or clear user data. For repeatable numbers use a separate test device or emulator with a fixed synthetic ledger (100 / 1000 / 10000 entries), complete onboarding, and open the ledger. Use the same fixture, refresh rate, OS, thermal conditions and compilation mode for P8 and V4 comparisons.

## Build optimized test APK
From android:
\`./gradlew :app:assembleDebug -PopenMarginPerformancePreview=true -PopenMarginScreenshotWatcher=true\`

R8 and profileable are opt-in for the sync3 preview. Production R8 is a separate opt-in \`-PopenMarginReleaseR8=true\`; enable by default only after OCR/ASR, notifications, accessibility, screenshot import, sync, Room, Hilt and update regressions pass on device.

## Macrobenchmark
\`./gradlew :benchmark:connectedDebugAndroidTest -PopenMarginBenchmarks=true -PopenMarginPerformancePreview=true -PopenMarginScreenshotWatcher=true\`

The benchmark module uses AndroidX Macrobenchmark, measures cold startup and ledger FrameTimingMetric, and includes a BaselineProfileRule. It is opt-in because dependencies and a device are required. Collect results from the test output folder; use frameDurationCpuMs/frameOverrunMs percentiles and trace files, not FPS guesses. To generate a production Baseline Profile, collect on an API 33+ test device, copy the generated profile text into app/src/main/baseline-prof.txt and verify the release APK bundles it. Do not invent generated profile rules. No profile or benchmark result is claimed before collection.

See https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview and https://developer.android.com/topic/performance/baselineprofiles/overview .
