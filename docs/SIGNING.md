# Постоянная подпись MasterCRM Preview

## Почему это важно

Каждый запуск GitHub Actions сейчас создаёт **новый debug-ключ**. Android не принимает APK с другой подписью как обновление уже установленного приложения. Удаление Preview ради установки нового APK может уничтожить его локальную базу.

Чтобы обновлять Preview без удаления приложения, необходим **один и тот же постоянный закрытый ключ**, сохранённый вне публичного репозитория.

**Это не восстановит подпись оригинальной MasterCRM 0.9.4.** У неё другой пакет и оригинальный ключ пока не найден. Поэтому Preview по-прежнему имеет пакет `com.mastercrm.offline.preview` и не должен заменять 0.9.4.

## Что автоматизировано

`.github/workflows/signed-preview.yml` создаёт подписанный *release* APK на основной ветке. Первый запуск был ручным; после успешной настройки секретов workflow автоматически срабатывает на обновления кода приложения в `main`. Требуются четыре GitHub Actions Secrets:

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

## Актуальный статус подписи

6 ноября 2026 г. владелец создал постоянный локальный keystore и настроил четыре GitHub Actions Secrets. Первый подписанный релиз **MasterCRM Preview 0.5.0** успешно собран: [GitHub Actions run 42229198229](https://github.com/xatabihc/MASTERCRM/actions/runs/42229198229).

Публичный SHA-256 отпечаток сертификата:

`8a0568717d5e5404c02d321ca6b4e9df830abba7ad0ca5cd4e799850e77e372f`

Начиная с Preview 0.6 обновления `main` автоматически запускают `signed-preview.yml` (только при изменениях приложения или workflow). Сертификат сверяется с отпечатком выше. Если ключ случайно заменят, публикация несовместимого APK останавливается.

**Ни пароль, ни закрытый ключ, ни содержимое GitHub Secrets не хранятся в репозитории.** Храните оригинальный keystore отдельно и не пересоздавайте его при сбоях CI.

## Проверка обновлений

1. Сохраните копию данных Preview через **Настройки → Создать защищённую копию**.
2. Установите первый *постоянно подписанный* релиз на пустую Preview (или на тестовый телефон), проверьте, что он запускается.
3. Для следующей версии используйте **тот же keystore**, alias и пароли. Убедитесь, что Android устанавливает её поверх предыдущей без удаления и сохраняет записи.
4. Проверяйте отпечаток сертификата из `apksigner verify --print-certs` для каждой релизной сборки — он должен быть одинаковым.

**Нельзя обновить старый debug-APK новой release-подписью напрямую.** Если на нём есть данные, экспортируйте и проверьте копию до удаления debug-версии. Оригинальную 0.9.4 не удаляйте.

## Ограничение доступа

Репозиторий пока публичный. Код приложений может быть публичным, но **реальные клиентские данные, ключи подписи и токены никогда не должны попадать в GitHub репозиторий или сообщения**. Если доступно, рекомендуется включить Private и ограничить доступ к Actions Secrets.

В этой среде ChatGPT может изменять файлы репозитория через GitHub, но **не может безопасно завести постоянный закрытый ключ и записать секреты через текущий GitHub-коннектор**. Поэтому эта однократная локальная настройка остаётся отдельным шагом владельца проекта, пока доступного защищённого механизма нет.
