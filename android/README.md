# Android payment notification collector

Fork of [DykiSensei/seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping), MIT license — original copyright is preserved in [LICENSE](LICENSE).

This opt-in Android app can observe supported WeChat/Alipay payment notifications, bank transaction SMS if the user grants that separate permission, and manual entries. It stores parsed transactions locally using Room and optionally uploads structured data to **your own** HTTPS finance server.

## Build

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

The GitHub Actions workflow builds debug APKs and unsigned release APKs. The repository does **not** provide a permanent production signing key. Choose your own keystore for release signing. Do not commit `.jks`, `.p12`, passwords or signed historical test builds.

## Permissions and privacy

Notification access is necessary for automatic capture; SMS is entirely optional. The accessibility-service capture path is experimental and should remain disabled unless understood by the user. Notifications and SMS can contain sensitive details: grant only the capabilities you need.

Upload is **disabled by default**. The app uploads only normalized transaction metadata — event ID, time, cents, currency, direction, source, category, merchant — not SMS bodies, OTPs, full card numbers or login information. The network token is a bearer secret, is kept locally on the phone, and should be rotated if exposed.

To enable: install the APK → settings → VPS sync → supply the HTTPS base URL and your `FINANCE_DEVICE_TOKEN` → enable. New local records request immediate upload; every 15 minutes WorkManager schedules an additional upload (timing can be delayed by Doze/battery optimizations). The settings screen offers status and an immediate retry option.

## Critical data limitations

- Android → VPS sync is **one-way**, not a bidirectional backup-and-restore system.
- Uninstalling the app may delete its on-device transaction database. Do not uninstall until all entries are backed up elsewhere.
- Installing APKs with different signing keys cannot upgrade in place without losing or migrating old app data.
- Missing, duplicated or mis-parsed notifications are possible; reconcile with actual bank statements.
- Existing synced rows are deduplicated server-side by stable installation/event ID; manually entering a transaction in both Wallos and Android can still create two different records.
- Some devices aggressively suspend background workers; allow appropriate auto-start/background usage in system settings.

## Upstream

The original project uses Jetpack Compose, Hilt and Room and is MIT licensed. This fork adds opt-in HTTPS upload and exact integer-cent handling; refer to upstream for full device-specific notification support.
