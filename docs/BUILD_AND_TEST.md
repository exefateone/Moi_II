# Сборка и тестирование

## Текущий аудит v0.39.3 (19 октября 2026)

- В проекте больше нет коннектора OpenCode Server. OpenCode Zen оставлен как провайдер модели; его API не даёт доступа к файлам и исполнению действий.
- `./scripts/verify_project.sh` выполняет Python-контракты, проверку версии/HTML/дубликатов ID, сверку HTML-ассетов, браузерный smoke test (если доступны Chromium/Playwright), Agent Core JVM, Media URL policy и OpenCode Zen smoke. Отдельные source-type-check сценарии можно запускать ниже.
- В этой среде отдельные Python, браузерные, Agent Core, Media, OpenCode Zen, локальной LLM и SQLite repository checks были выполнены. Полноценная Android-сборка не подтверждена: нет Android SDK, Gradle и `adb`.


## Быстрые проверки без Android SDK

```bash
./scripts/verify_project.sh
```

Проверяются Python unit/contract tests, HTML/JS синтаксис, версия, отсутствие duplicate IDs, совпадение asset-файлов и Linux runtime release gate. Если доступны Python Playwright и Chromium, автоматически выполняется браузерный UI smoke test.

Отдельно запустить интерактивный smoke test HTML-прототипа (только UI, без проверки живого интернета и без Android-моста):

```bash
python3 scripts/smoke_runtime_ui.py
```

Он проверяет CRUD рабочих пространств, ограничение количества, UI адреса/закладок/вкладок браузера, честное отображение неподключённого PTY и необработанные ошибки страницы. Это не заменяет native WebView/device QA.

## Android Studio

1. Открыть `android-project/` как Gradle project.
2. Установить JDK 17 и Android SDK Platform 35.
3. Выполнить Gradle Sync. Gradle dependencies требуют доступ к Maven repositories.
4. Выбрать `app` → `assembleDebug`.
5. Установить debug APK на тестовый телефон.
6. Проверить основной экран, открытие Spider Browser, HTTPS-навигацию, back, reload, загрузки и блокировку опасных схем.
7. Сохранить Android Studio build log и результаты device tests в `docs/device-qa/`.

## Что проверять в browser module

- открывается HTTPS сайт и меняется адресная строка;
- back/reload/home работают;
- `javascript:`, `file:`, `content:`, `data:`, `intent:` не открываются;
- SSL errors отменяются;
- загрузка сохраняет файл через DownloadManager;
- поворот экрана и возврат из фона не приводят к сбоям;
- с произвольного сайта невозможно получить `window.Android` или `spiderNative`.

## Что ещё нельзя заявить

- Android/Kotlin compile success, если `assembleDebug` не был выполнен;
- работа PTY, пока не завершены PTY integration tests;
- установленный Linux userland;
- готовый APK для распространения, пока не проверены подпись, package id и обновление;
- безопасность публичного backend до его отдельного security review.


## v0.38 Spider Media / Agent Foundation checks

- Static tests check Media3 dependencies, the native media Activity/service source, queue and HTTPS source wiring, HLS/DASH modules, and the allowlisted `open_media` route.
- `SpiderMediaUrlPolicyTest` covers HTTPS URL validation, HLS/DASH MIME detection, rejection cases and avoidance of query-token display.
- A Kotlin `SpiderPolicyEngineTest` covers low-risk local reads, requests requiring network permission, explicit write/delete approvals and malformed requests. These JUnit tests only run when Gradle test tasks are executed.
- The static suite does not compile Kotlin or confirm Media3 API compatibility. Run `app:assembleDebug`, `testDebugUnitTest`, install the APK and verify media playback on a device.


## v0.39.1 bounded static project checker (18 October 2026; historical checkpoint)

- Python contract/unit tests: `python3 -m unittest discover -s tests -p 'test_*.py'` — 111/111 passed.
- Agent Core harness: `bash scripts/test_agent_core_jvm.sh` — 15 grouped scenarios passed, including the bounded `CHECK_WORKSPACE_PROJECT` checker.
- Browser smoke: `python3 scripts/smoke_runtime_ui.py` — 8/8 scenarios passed; native WebView/device integration is not covered.
- Source checks in the prior checkpoint covered OpenCode Zen, the then-present OpenCode Server connector, local LLM Kotlin source type-check against signature-only Android stubs, Python `py_compile`, and `bash -n scripts/*.sh`. The Server connector was removed in v0.39.3; this historical result is not a current connector test.
- The SQLite task repository type-check timed out in this session and is not counted as a pass. No Android Gradle build, APK install, live Yandex/OpenCode request or physical device acceptance is claimed.
- `CHECK_WORKSPACE_PROJECT` performs a bounded read-only scan: strict JSON grammar, secure XML/SVG parse and UTF-8 readability for other supported text files. It does not launch tests or execute arbitrary code.
