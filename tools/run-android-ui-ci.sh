#!/usr/bin/env bash
# This script is intentionally run as one shell process by the Android emulator
# action. Its "script" property executes each line in a separate shell; keeping
# state across separate lines there does not work.
set -uo pipefail

status=0
gradle --no-daemon connectedDebugAndroidTest || status=$?

# Screenshot is captured even if tests failed, for visual diagnosis.
adb shell am force-stop com.mastercrm.offline.preview || true
adb shell am start -n com.mastercrm.offline.preview/com.mastercrm.offline.MainActivity || true
sleep 4
adb exec-out screencap -p > mastercrm-emulator-overview.png || true

exit "$status"
