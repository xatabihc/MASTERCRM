#!/usr/bin/env bash
# Download the latest successful, permanently signed MasterCRM Preview APK
# from YOUR GitHub account, verify its certificate when Android SDK is present,
# then install WITHOUT deleting any existing app or its private data.
set -euo pipefail
REPO="xatabihc/MASTERCRM"
PACKAGE="com.mastercrm.offline.preview"
EXPECTED_CERT="8a0568717d5e5404c02d321ca6b4e9df830abba7ad0ca5cd4e799850e77e372f"
DEST="${HOME}/Downloads/MasterCRM_signed_latest.apk"
for tool in gh adb unzip; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Не найдена программа: $tool"
        echo "Ubuntu: sudo apt install adb gh unzip"
        exit 1
    fi
done
gh auth status >/dev/null || { echo "Сначала выполните: gh auth login"; exit 1; }
mkdir -p "${HOME}/Downloads"
RUN_ID="$(gh run list --repo "$REPO" --workflow signed-preview.yml \
    --branch main --status success --limit 1 --json databaseId --jq '.[0].databaseId')"
if [[ ! "$RUN_ID" =~ ^[0-9]+$ ]]; then
    echo "Не найдена успешная подписанная сборка GitHub Actions"; exit 1
fi
TEMP="$(mktemp -d)"
trap 'rm -rf -- "$TEMP"' EXIT
echo "Загружаю проверенный подписанный релиз GitHub Actions #$RUN_ID..."
gh run download "$RUN_ID" --repo "$REPO" \
    --name mastercrm-preview-signed-release --dir "$TEMP"
APK="$TEMP/app-release.apk"
[[ -s "$APK" ]] || { echo "В загруженном артефакте отсутствует APK"; exit 1; }

# The CI workflow pins the signing certificate; also verify locally where possible.
SIGNER="$(command -v apksigner || true)"
if [[ -z "$SIGNER" ]]; then
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        [[ -d "$sdk/build-tools" ]] || continue
        SIGNER="$(find "$sdk/build-tools" -maxdepth 2 -type f -name apksigner | sort -V | tail -1)"
        [[ -n "$SIGNER" ]] && break
    done
fi
if [[ -n "$SIGNER" ]]; then
    "$SIGNER" verify "$APK"
    ACTUAL="$("$SIGNER" verify --print-certs "$APK" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)"
    if [[ "$ACTUAL" != "$EXPECTED_CERT" ]]; then
        echo "ОШИБКА: неожиданный сертификат APK. Установка отменена."
        exit 1
    fi
    echo "Подпись подтверждена: $ACTUAL"
else
    echo "Примечание: apksigner локально не найден. Сертификат уже закреплён в GitHub Actions."
fi
install -m 0644 "$APK" "$DEST"
echo "APK сохранён: $DEST"
sha256sum "$DEST"

echo "Устройства Android:"
adb devices -l
echo "Установлю приложение в выбранное adb устройство."
echo "Если устройств несколько, заранее задайте ANDROID_SERIAL."
# -r replaces the installed same-package/same-certificate APK while retaining data.
# NEVER use adb uninstall or downgrade, even after a signature/version error.
if adb install -r "$DEST"; then
    echo "Установка прошла успешно, пользовательские данные не удалялись."
    adb shell am start -n "$PACKAGE/com.mastercrm.offline.MainActivity" || true
    adb shell dumpsys package "$PACKAGE" |
        grep -m 1 -E 'versionName=' || true
else
    echo "Не удалось обновить APK. Приложение НЕ удалено."
    echo "Если подпись старой Preview несовместима, сохраните и проверьте .mcrm5"
    echo "до любых ручных действий. Оригинальную MasterCRM 0.9.4 не удаляйте."
    exit 1
fi
