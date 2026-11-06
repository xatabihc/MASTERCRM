#!/usr/bin/env bash
# This script is intentionally run as one shell process by the Android emulator
# action. Its "script" property executes each line in a separate shell; keeping
# state across separate lines there does not work.
set -uo pipefail

status=0
gradle --no-daemon connectedDebugAndroidTest || status=$?

# The Gradle instrumentation task may uninstall the app during teardown.
# Reinstall the *same* debug APK before capturing its UI.
adb install -r app/build/outputs/apk/debug/app-debug.apk || true
adb shell am force-stop com.mastercrm.offline.preview || true
adb shell am start -W -n com.mastercrm.offline.preview/com.mastercrm.offline.MainActivity || true
sleep 5
adb exec-out screencap -p > mastercrm-emulator-overview.png || true
adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' || true

exit "$status"
