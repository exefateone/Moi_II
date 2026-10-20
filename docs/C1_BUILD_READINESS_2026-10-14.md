# Этап C1 — фиксация исходников и подготовка Android-сборки

Дата контрольной точки: 14 октября 2026 г.
База: ИИ Паук v0.38 (`ii-pauk-v0.38-github-gradle-prepared.zip`).

## Выполнено в этой контрольной точке

- Распакован и повторно проверен архив v0.38; структура Android-проекта и GitHub Actions workflow присутствуют.
- Запущен `bash scripts/verify_project.sh` из распакованного архива.
- Python-контрактные тесты: **34/34 PASS**.
- HTML: 128 уникальных ID; синтаксис встроенного JavaScript проверен Node.js.
- Android Web asset совпадает с `app-web/ii-pauk.html`.
- Chromium UI smoke: страницы и основные сценарии PASS, необработанных ошибок страницы нет.
- Архив проходит `unzip -t`.
- В исходном архиве обнаружены четыре устаревшие записи SHA256 в `SHA256SUMS.txt` (`README.md`, `android-project/README.md`, `android-project/gradle.properties`, `docs/QA_v0.38.md`). В этом пакете контрольные суммы пересчитаны после всех изменений.
- Добавлен `scripts/android_build_local.sh`: он проверяет реальные требования Android toolchain, запускает `testDebugUnitTest` и `assembleDebug`, а затем вычисляет SHA-256 APK. Скрипт не подменяет сборку статическими тестами и завершается блокирующей ошибкой при отсутствии нужных инструментов.

## Что не выполнено и почему

- В текущей среде отсутствуют команды `gradle` и `adb`, Android SDK не настроен, `ANDROID_HOME`/`ANDROID_SDK_ROOT` не заданы. JDK 21 доступен, но документированный Android build gate требует JDK 17.
- В архив намеренно не добавлен непроверенный бинарный Gradle Wrapper JAR.
- `testDebugUnitTest`, Kotlin compile, `assembleDebug`, установка APK и физическое устройство здесь **не проверены**.
- GitHub Actions workflow включён в архив, но репозиторий ещё не получил успешный push/commit через подключённую интеграцию; выполнения workflow и APK artifact пока нет.

## Текущий gate

**C1: локальные/веб-проверки PASS; Android toolchain / APK BUILD BLOCKED.**

Следующий приоритет: обеспечить рабочую запись исходников v0.38 в GitHub или открыть проект в Android Studio с JDK 17 и Android SDK Platform 35, выполнить unit tests и `assembleDebug`, затем сохранить лог сборки и SHA-256 реального APK. Не переходить к объявлению C2 завершённым, пока не появится этот артефакт и не будет проверена установка.
