#!/usr/bin/env bash
# Entirely local shell self-test; no real adb, emulator or user data touched.
set -euo pipefail

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
if [[ "${1:-}" == "devices" ]]; then
  case "${SCENARIO:-}" in
    emulator|spoof)
      printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
    physical)
      printf 'List of devices attached\nR58SYNTHETIC\tdevice\n' ;;
    multiple)
      printf 'List of devices attached\nemulator-5554\tdevice\nR58SYNTHETIC\tdevice\n' ;;
    disconnected)
      printf 'List of devices attached\nemulator-5554\toffline\n' ;;
    *) exit 3 ;;
  esac
elif [[ "${1:-}" == "-s" && "${3:-}" == "shell" && "${4:-}" == "getprop" && "${5:-}" == "ro.kernel.qemu" ]]; then
  if [[ "${SCENARIO:-}" == "emulator" ]]; then printf '1\n'
  else printf '0\n'; fi
else
  printf 'Unexpected mock adb invocation: %s\n' "$*" >&2
  exit 3
fi
FAKE_ADB
chmod +x "$tmp/adb"
guard="tools/assert-disposable-emulator.sh"

for scenario in physical multiple disconnected spoof; do
  if SCENARIO="$scenario" PATH="$tmp:$PATH" bash "$guard" >"$tmp/result" 2>"$tmp/error"; then
    echo "FAIL: $scenario was unexpectedly accepted" >&2
    exit 1
  fi
done

found="$(SCENARIO=emulator PATH="$tmp:$PATH" bash "$guard")"
[[ "$found" == "emulator-5554" ]] || { echo "FAIL: wrong emulator serial" >&2; exit 1; }
echo "PASS: physical, multiple, disconnected and spoofed devices are rejected"
