# Мини-лаунчер

Домашний экран для Android без единой сторонней библиотеки. Только `android.*` из фреймворка,
поэтому APK весит ~21 КБ вместо десятков мегабайт.

- Пакет: `io.github.bars2282016.minilauncher`
- minSdk 23 (Android 6+), targetSdk 34
- Язык: Java 8, сборка без Gradle

## Что умеет

- Сетка всех установленных приложений (4 колонки), сортировка по алфавиту.
- Поиск по названию и по имени пакета — сразу под часами.
- Тап по иконке — запуск; долгое нажатие — «Открыть / О приложении / Удалить».
- Часы в шапке, обновление раз в 20 секунд.
- Автообновление списка при установке/удалении приложений и при возврате на домашний экран.
- Работает как домашний экран (`CATEGORY_HOME`), поэтому Android предложит выбрать его
  вместо штатного лаунчера. Штатный лаунчер при этом не удаляется — переключиться можно в любой момент.

## Состав

```
AndroidManifest.xml                          манифест, minSdk 23, targetSdk 34
res/layout/main.xml                          часы + поиск + GridView
res/layout/item_app.xml                      иконка + подпись
res/drawable/ic_launcher.xml                 иконка приложения (vector)
src/io/github/bars2282016/minilauncher/      MainActivity, AppEntry
build.ps1                                    сборка без Gradle
```

## Требования для сборки

- JDK 17 (ищется в `C:\Program Files\Eclipse Adoptium\jdk-17*`, можно указать `-Jdk`).
- Android SDK: `platforms;android-34`, `build-tools;35.0.0` (путь задаётся через `-Sdk`).

## Сборка

Важно: `aapt2` падает на путях с кириллицей, поэтому проект нужно скопировать в путь
из латинских букв (например, `C:\ml`) и запускать скрипт оттуда.

```powershell
robocopy . C:\ml /E /XD build dist
cd C:\ml
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Шаги внутри: `aapt2 compile` → `aapt2 link` → `javac --release 8` → `d8` → вставка
`classes.dex` в APK → `zipalign -f 4` → `apksigner` (создаёт `debug.keystore` при первом запуске).

Результат: `dist\minilauncher-debug.apk`.

Параметры: `-Sdk <путь>`, `-BuildTools <версия>`, `-Platform <android-XX>`, `-Jdk <путь>`.

## Установка

```powershell
adb install -r dist\minilauncher-debug.apk
adb shell cmd package set-home-activity io.github.bars2282016.minilauncher/.MainActivity
```

Вторая команда сразу делает его домашним экраном (Android 10+). Без неё система сама спросит
при нажатии «Домой».

## Обновление и удаление

- Пересобрать и `adb install -r` — данные сохраняются.
- Удалить: `adb uninstall io.github.bars2282016.minilauncher`.
- Вернуть штатный лаунчер:
  ```powershell
  adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME
  adb shell cmd package set-home-activity <пакет штатного лаунчера>/.<Activity>
  ```

## Разрешения и приватность

Единственное разрешение — `REQUEST_DELETE_PACKAGES` (системный диалог удаления приложения).
Разрешения `INTERNET` нет: лаунчер не имеет доступа к сети и ничего никуда не отправляет.
Данные не собираются, аналитики нет.

## Лицензия

MIT — см. [LICENSE](LICENSE).

Автор: Bars2282016
