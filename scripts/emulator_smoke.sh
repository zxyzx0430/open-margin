#!/usr/bin/env bash
# Emulator launch + accessibility-bind smoke test.
#
# This logic used to live inline in .github/workflows/android-emulator-smoke.yml.
# The android-emulator-runner action feeds its `script:` input to the shell in a
# way that can split multi-line constructs, which broke the for/do/done loop with
# "/usr/bin/sh: Syntax error: end of file unexpected (expecting "done")".
# Keeping the real logic in this file and calling it with a single line from the
# workflow makes the test immune to that failure mode.
#
# Usage (run from the android/ directory, emulator already booted):
#   bash ../scripts/emulator_smoke.sh <api-level>
set -e
set -o pipefail

API_LEVEL="${1:?api-level argument is required}"

chmod +x gradlew
./gradlew --no-daemon --stacktrace connectedDebugAndroidTest

# Instrumentation tests may uninstall the app when they finish.
# Reinstall explicitly before the independent application-start check.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm list packages | grep -F 'package:com.bookkeeping.app.sync2'
adb shell am start -W -n com.bookkeeping.app.sync2/com.bookkeeping.app.MainActivity
sleep 8
adb shell pidof com.bookkeeping.app.sync2

# Enable the accessibility service via emulator-only ADB settings,
# then prove Android actually invoked onServiceConnected.
# These commands never run on a personal / production device.
adb logcat -c
adb shell settings put secure enabled_accessibility_services "com.bookkeeping.app.sync2/com.bookkeeping.app.notification.accessibility.AccessibilityCaptureService"
adb shell settings put secure accessibility_enabled 1

success=0
for i in $(seq 1 15); do
  if adb logcat -d -s BkAccessibility:D | grep -q "AccessibilityCaptureService connected"; then
    success=1
    break
  fi
  sleep 2
done
test "$success" -eq 1

adb shell pidof com.bookkeeping.app.sync2
mkdir -p emulator-proof
adb shell dumpsys accessibility > "emulator-proof/accessibility-api-${API_LEVEL}.txt"
adb logcat -d -s BkAccessibility:D > "emulator-proof/accessibility-lifecycle-api-${API_LEVEL}.txt"
adb shell screencap -p /sdcard/openmargin_app_launch.png
adb pull /sdcard/openmargin_app_launch.png emulator-proof/app_launch.png
echo "EMULATOR_INSTRUMENTATION_APP_START_AND_ACCESSIBILITY_BIND_PASS_API_${API_LEVEL}"
