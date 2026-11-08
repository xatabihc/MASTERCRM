#!/usr/bin/env bash
# REFUSE destructive integration tests unless exactly one disposable emulator
# is connected. Never run these tests against a person's Android phone.
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "Android adb is unavailable" >&2
  exit 2
fi

mapfile -t connected < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if (( ${#connected[@]} != 1 )); then
  echo "Destructive tests require exactly one connected Android emulator" >&2
  exit 2
fi

serial="${connected[0]}"
if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
  echo "Refusing destructive tests on a physical device" >&2
  exit 2
fi

qemu="$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r\n')"
if [[ "$qemu" != "1" ]]; then
  echo "Device is not confirmed to be an Android emulator" >&2
  exit 2
fi

# This is the only stdout output, so callers can safely set ANDROID_SERIAL.
printf '%s\n' "$serial"
