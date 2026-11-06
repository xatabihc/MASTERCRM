#!/usr/bin/env bash
# MasterCRM Android — read-only local source audit.
# No uploads, no git writes, no modification of files, no disclosure of key contents.
set -u

printf '\n=== MasterCRM: локальный поиск исходников Android ===\n'
printf 'Режим: только чтение. Файлы, ключи и данные НЕ отправляются.\n\n'

if [[ -f "$HOME/.android/debug.keystore" ]]; then
  printf 'Стандартный Android debug.keystore: существует (содержимое не читается)\n'
else
  printf 'Стандартный Android debug.keystore: не найден\n'
fi

declare -a roots
roots=("$HOME")
[[ -d /workspace ]] && roots+=("/workspace")
[[ -d /workspaces ]] && roots+=("/workspaces")

declare -A seen
found=0
for scan_root in "${roots[@]}"; do
  while IFS= read -r -d '' settings_file; do
    project_dir="$(dirname "$settings_file")"
    [[ -n "${seen[$project_dir]+yes}" ]] && continue
    seen["$project_dir"]=1
    found=$((found+1))
    printf '\n[Проект %d] %s\n' "$found" "$project_dir"
    if [[ -f "$project_dir/gradlew" ]]; then
      printf '  Gradle wrapper: есть\n'
    else
      printf '  Gradle wrapper: нет\n'
    fi
    if [[ -f "$project_dir/app/src/main/AndroidManifest.xml" ]]; then
      printf '  Android app module: есть\n'
    else
      printf '  Android app module: не найден по стандартному пути\n'
    fi
    for gradle_file in "$project_dir/app/build.gradle" "$project_dir/app/build.gradle.kts"; do
      if [[ -f "$gradle_file" ]]; then
        printf '  Идентификатор и версия (если указаны):\n'
        grep -E '^[[:space:]]*(namespace|applicationId|versionName|versionCode)[[:space:]=]' "$gradle_file" 2>/dev/null | head -n 10 || true
      fi
    done
    if find "$project_dir" -maxdepth 5 -type f \( -name '*.keystore' -o -name '*.jks' \) -print -quit 2>/dev/null | grep -q .; then
      printf '  Файл подписи внутри проекта: найден (имя/ключ не выводятся)\n'
    else
      printf '  Файл подписи внутри проекта: не найден\n'
    fi
    if [[ -d "$project_dir/.git" ]]; then
      printf '  Git: локальная история есть\n'
    else
      printf '  Git: локальная история не найдена\n'
    fi
  done < <(
    find "$scan_root" -maxdepth 8 \
      \( -type d \( -name .git -o -name .gradle -o -name .cache -o -name .local -o -name node_modules -o -name build -o -name .venv -o -name sdk \) -prune \) -o \
      \( -type f \( -name settings.gradle -o -name settings.gradle.kts \) -print0 \) 2>/dev/null
  )
done
if [[ "$found" -eq 0 ]]; then
  printf '\nПроекты с settings.gradle не найдены в обследованных папках.\n'
fi

printf '\n=== Архивы и APK с MasterCRM в названии (не открываются) ===\n'
archives=0
while IFS= read -r -d '' file; do
  archives=$((archives+1))
  printf '  %s\n' "$file"
done < <(
  find "$HOME" -maxdepth 8 \
    \( -type d \( -name .git -o -name .gradle -o -name .cache -o -name .local -o -name node_modules -o -name build -o -name .venv -o -name sdk \) -prune \) -o \
    \( -type f \( -iname '*mastercrm*.zip' -o -iname '*mastercrm*.apk' -o -iname '*mastercrm*.tar.gz' \) -print0 \) 2>/dev/null
)
[[ "$archives" -eq 0 ]] && printf '  Совпадений не обнаружено\n'

printf '\nГотово. Никакие файлы не изменены и не загружены.\n'
printf 'Для продолжения разработки достаточно сообщить путь к актуальному Android-проекту.\n'
