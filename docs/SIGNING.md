# Постоянная подпись MasterCRM Preview

## Почему это важно

Каждый запуск GitHub Actions сейчас создаёт **новый debug-ключ**. Android не принимает APK с другой подписью как обновление уже установленного приложения. Удаление Preview ради установки нового APK может уничтожить его локальную базу.

Чтобы обновлять Preview без удаления приложения, необходим **один и тот же постоянный закрытый ключ**, сохранённый вне публичного репозитория.

**Это не восстановит подпись оригинальной MasterCRM 0.9.4.** У неё другой пакет и оригинальный ключ пока не найден. Поэтому Preview по-прежнему имеет пакет `com.mastercrm.offline.preview` и не должен заменять 0.9.4.

## Что автоматизировано

`.github/workflows/signed-preview.yml` создаёт подписанный *release* APK на основной ветке. Работает только после однократной настройки четырёх GitHub Actions Secrets:

| Имя секрета | Содержимое |
|---|---|
| `MASTERCRM_KEYSTORE_BASE64` | Base64-кодированные байты **постоянного** keystore-файла |
| `MASTERCRM_RELEASE_STORE_PASSWORD` | Пароль файла keystore |
| `MASTERCRM_RELEASE_KEY_ALIAS` | Имя ключа в keystore |
| `MASTERCRM_RELEASE_KEY_PASSWORD` | Пароль приватного ключа |

Без этих секретов workflow **намеренно завершается ошибкой**, не публикуя неподписанный APK. Секреты никогда не добавляются в Git, чат или логи. Временный keystore удаляется из runner после сборки.

## Создание ключа на доверенном компьютере

Используйте JDK 17 или новее, не загружайте ключ в чат:

```bash
mkdir -p "$HOME/.local/share/mastercrm/signing"
chmod 700 "$HOME/.local/share/mastercrm/signing"
keytool -genkeypair \
  -keystore "$HOME/.local/share/mastercrm/signing/mastercrm-preview.jks" \
  -storetype PKCS12 \
  -alias mastercrm-preview \
  -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=MasterCRM Preview, O=Local Android Application"
chmod 600 "$HOME/.local/share/mastercrm/signing/mastercrm-preview.jks"
```

Запомните пароль, который запросит `keytool`. Не удаляйте файл: **потеря ключа означает невозможность подписать совместимые будущие обновления**.

Для настройки секретов через GitHub CLI на том же компьютере:

```bash
gh auth status
base64 -w0 "$HOME/.local/share/mastercrm/signing/mastercrm-preview.jks" \
  | gh secret set MASTERCRM_KEYSTORE_BASE64 --repo xatabihc/MASTERCRM
gh secret set MASTERCRM_RELEASE_KEY_ALIAS --repo xatabihc/MASTERCRM --body "mastercrm-preview"
read -rsp "Пароль хранилища: " PASS; echo
printf '%s' "$PASS" | gh secret set MASTERCRM_RELEASE_STORE_PASSWORD --repo xatabihc/MASTERCRM
printf '%s' "$PASS" | gh secret set MASTERCRM_RELEASE_KEY_PASSWORD --repo xatabihc/MASTERCRM
unset PASS
```

`PKCS12` обычно использует один пароль для хранилища и ключа. Если они различаются, задайте соответствующий пароль ключа отдельно.

После настройки: GitHub → **Actions → Signed preview APK → Run workflow** на `main`. Скачайте артефакт `mastercrm-preview-signed-release`.

## Проверка обновлений

1. Сохраните копию данных Preview через **Настройки → Создать защищённую копию**.
2. Установите первый *постоянно подписанный* релиз на пустую Preview (или на тестовый телефон), проверьте, что он запускается.
3. Для следующей версии используйте **тот же keystore**, alias и пароли. Убедитесь, что Android устанавливает её поверх предыдущей без удаления и сохраняет записи.
4. Проверяйте отпечаток сертификата из `apksigner verify --print-certs` для каждой релизной сборки — он должен быть одинаковым.

**Нельзя обновить старый debug-APK новой release-подписью напрямую.** Если на нём есть данные, экспортируйте и проверьте копию до удаления debug-версии. Оригинальную 0.9.4 не удаляйте.

## Ограничение доступа

Репозиторий пока публичный. Код приложений может быть публичным, но **реальные клиентские данные, ключи подписи и токены никогда не должны попадать в GitHub репозиторий или сообщения**. Если доступно, рекомендуется включить Private и ограничить доступ к Actions Secrets.

В этой среде ChatGPT может изменять файлы репозитория через GitHub, но **не может безопасно завести постоянный закрытый ключ и записать секреты через текущий GitHub-коннектор**. Поэтому эта однократная локальная настройка остаётся отдельным шагом владельца проекта, пока доступного защищённого механизма нет.
