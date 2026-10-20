# ИИ Паук v0.39.1 — целевая архитектура и карта готовности

Дата оценки: 12 октября 2026 г. Проценты — инженерная оценка реализации относительно цели, а не измерение тестового покрытия. «Проверено на Android: 0%» означает, что в этой рабочей среде нет сборки/логов/устройства, а не то, что исходники отсутствуют.



## Latest implementation checkpoint — 18 October 2026

The active source version is `0.39.1-bounded-static-check` (`versionCode 40`). It adds AI-proposed edits for small single text files in the selected private workspace: model candidate → complete diff preview (oversized diffs fail closed) → explicit one-time approval → optional separate overwrite consent → atomic save guarded by source SHA-256 → independent read-back verification. The edit source is limited to 1,900 characters; model proposals do not run tests or build an APK. Cloud modes require explicit consent before transmitting the goal/step/path/source content. The continuation also registers `CHECK_WORKSPACE_PROJECT`, a one-time-approved static checker for strict JSON syntax, secure XML/SVG parsing and UTF-8 reads, with bounded traversal and a second evidence pass. It does not run scripts, tests, compilers, shell or builds.

Verification completed for this source checkpoint: Python tests **111/111**; Agent Core Kotlin/JVM harness **15 grouped smoke scenarios**, including the new static project checker; browser smoke **8/8**; OpenCode Zen and OpenCode connector source checks; local LLM Kotlin source type-check using signature stubs; Python compilation and shell syntax checks. The SQLite task repository type-check command was attempted but timed out, so it is not reported as passed. Android Gradle/NDK build, APK, live provider requests and real-device behavior remain unverified. See `docs/BOUNDED_STATIC_PROJECT_CHECK_REPORT_2026-10-18.md`.

## 1. Полная структура системы

```text
ИИ ПАУК — Android host + trusted HTML shell
│
├── A. SPIDER WIDGET — глобальный плавающий помощник
│   ├── Android overlay bubble: перемещение, сохранение позиции, hide/show
│   ├── Quick menu: Chat / Browser / Media / Terminal / Agent / Code & Build / Home
│   ├── Task status badge — из общего Task Manager, не отдельный фиктивный статус
│   ├── Native permission flow + видимое foreground-уведомление
│   ├── Связь со всеми модулями через typed actions/deep links
│   └── Future: contextual suggestions, opt-in accessibility integrations
│
├── B. SPIDER UI — приложение и встроенные экраны
│   ├── Главный экран, чат, настройки, история
│   ├── Внутренний анимированный Паук
│   ├── Browser / Media / Terminal / Linux / AI routes
│   ├── Code Studio: редактор → изолированный HTML preview → сохранение .html
│   └── APK Build: реальная очередь сборки и артефакт (пока не подключено)
│
├── C. SPIDER AGENT — оркестратор задач (Muse-inspired, provider-independent)
│   ├── Task intake & decomposition
│   ├── Task Planner / state machine / checkpoints
│   ├── Tool Router → типизированные обработчики инструментов
│   ├── Task Executor → ограничения, таймауты, отмена
│   ├── Result Verifier → независимая проверка, не доверяет одному ответу модели
│   ├── Recovery Manager → продолжение задачи без повторения опасных действий
│   ├── Task Reporter → файлы, тесты, ошибки, отчёт
│   └── Spider Code loop: inspect → plan → edit → test → preview/build → verify
│
├── D. SPIDER SENTINEL — безопасность
│   ├── Policy Engine / оценка риска
│   ├── Одноразовые подтверждения, связанные с конкретным запросом
│   ├── Audit Log + редактирование/маскирование секретов
│   ├── URL/network/file/process policy
│   └── Веб-страницы и скачанные файлы считаются недоверенными данными
│
├── E. SPIDER WORKSPACE — локальная рабочая среда
│   ├── Workspace Repository — app-private проекты и канонические пути
│   ├── Spider Terminal — настоящий PTY, stdout/stderr, Ctrl-C, process lifecycle
│   ├── Spider Linux — пользовательское Linux-окружение и пакетный менеджер
│   ├── Project Files — файлы/каталоги/изменения с метаданными
│   └── Optional Remote Worker — отдельная изолированная Linux-машина
│
├── F. SPIDER BROWSER
│   ├── Native WebView вне доверенной HTML-оболочки
│   ├── URL/TLS policy, навигация, история/закладки
│   ├── Download/upload и обработка lifecycle
│   └── Controlled page reader для агента (ещё не подключён)
│
├── G. SPIDER MEDIA
│   ├── Media Core: Media3/ExoPlayer, MediaSessionService
│   ├── Spider Audio: локальные аудио, очередь/плейлисты, фон/уведомления
│   ├── Spider Video: встроенное видео, перемотка, дорожки/субтитры
│   ├── Media Library: метаданные, история, избранное
│   └── Media Tools: FFmpeg / извлечение / конвертация / speech-to-text (план)
│
├── H. SPIDER CODE & BUILD
│   ├── HTML/CSS/JS editor
│   ├── Sandboxed preview без нативного Spider bridge
│   ├── Export/save .html через SAF на Android / download в браузере
│   ├── APK Build Runner: изолированный JDK + Gradle + Android SDK
│   ├── Build logs + тесты + checksum + реальный APK артефакт
│   └── Signing: ключи остаются под контролем пользователя
│
└── I. SPIDER BACKEND / MODEL PROVIDERS (опционально)
    ├── Adapter к одной или нескольким ИИ-моделям
    ├── Серверные ключи, аутентификация и лимиты внутри процесса; ограничения на API-шлюзе ещё нужны
    ├── Task API / persistent state / notifications
    └── Remote worker для длинных задач
```

## 2. Текущая готовность

| Компонент | Готовность исходников/архитектуры | Что уже проверено | Android end-to-end | Основной остаток |
|---|---:|---|---:|---|
| HTML-оболочка и локальный UI | 85% | HTML/JS статическая проверка, Chromium smoke | 0% | Проверка как asset внутри собранного APK |
| Внутренний анимированный Spider | 80% | UI smoke основных маршрутов | 0% | Нативные lifecycle/input/device тесты |
| Глобальный плавающий Spider Widget | 55% | Код службы, разрешения, меню и manifest присутствуют | 0% | Gradle compile, разрешение, drag/tap, уведомление, перезапуск, тест на устройстве |
| Связь виджета с реальными задачами | 15% | Есть status hook и shortcuts | 0% | Подключить к единому Task Manager и событиям задач |
| Spider Browser | 70% | UI smoke; контракты безопасности/маршруты проверены статически | 0% | Android build, сеть/TLS, upload/download, lifecycle |
| Spider Media: аудио/видео | 65% исходники | Media3 PlayerView, SAF multi-file queue, HTTPS, HLS/DASH и MediaSession source добавлены | 0% | Gradle compile, локальные/сетевые тесты, фоновые сессии, кодеки и ротация |
| Code Studio: редактор и HTML preview | 75% | Chromium smoke прошёл; preview изолирован iframe | 0% | Проверка внутри Android WebView и device regression |
| Сохранение HTML | 55% | Браузерная загрузка проверена; SAF bridge в исходниках | 0% Android SAF | Проверить документ-пикер, UTF-8 и длинные файлы на устройстве |
| APK Build Runner | 10% | Только UI состояния «ещё не подключено» и проектный план | 0% | Изолированный worker, SDK/Gradle, тесты, logs, artifacts, подпись |
| Agent contracts / Tool Router | 30% | Типизированные контракты/частичный роутинг | 0% end-to-end | Planner, executor, persistent state, verifier, cancel/recovery |
| Spider Sentinel | 45% | Исходник policy engine и audit log | 0% end-to-end | Подключить к каждому инструменту, подтверждения и тесты |
| Spider Terminal / PTY | 45% | Терминальная библиотека и shell Activity подключены в исходниках; статические contracts | 0% | Gradle dependency/native PTY build, реальный device smoke-test stdin/stdout, Ctrl-C, copy/paste, lifecycle; сейчас доступен только Android `/system/bin/sh` в app sandbox |
| Spider Linux userland | 5% | Только шаблон/manifest runtime | 0% | Реальный ABI bundle, provenance, безопасная установка и тесты |
| Spider Memory | 25% | Частичные локальные UI/storage элементы | 0% | Хранилище по проектам, поиск, просмотр/удаление/экспорт |
| AI model/provider adapter | 20% | Mocked Yandex/OpenAI-compatible routes, timeouts, per-route process-local rate caps and four-call concurrency guard | 0% | TLS deployment, gateway-wide quotas, monitoring, real credentials, live requests and retention policy |
| Release APK / signing | 10% | Gradle scaffold, нет собранного артефакта | 0% | Debug/release build, device QA, конфигурация подписи и релизная проверка |

**Общая оценка:** основа исходников/архитектуры около **45%**; готовность как надёжного рабочего приложения для пользователя около **15–20%**. Это не тестовое покрытие. В этой среде APK не собран и Android end-to-end не подтверждён.

## 3. Роль виджета Паука

Виджет — постоянная точка входа во все функции, а не отдельная игрушка или декорация. Из плавающего меню пользователь должен попасть в чат, браузер, медиа, терминал, агент, Code Studio и главный экран. По мере реализации Agent Task Manager будет отправлять в виджет реальные статусы: ожидание, выполнение, требуется подтверждение, проверка, готово, ошибка, пауза. Виджет должен получать их из общего repository событий, а не придумывать самостоятельно.

Android overlay показывает себя поверх обычных окон только после явного согласия пользователя, пока служба активна и система это допускает. Он не может гарантированно накрывать защищённые системные экраны, некоторые диалоги разрешений или все экраны блокировки. Виджет сам по себе не читает и не управляет интерфейсами других приложений; для этого нужен отдельный opt-in механизм доступности и отдельная оценка безопасности.

## 4. Код → HTML demo / APK

**HTML:** редактор → sandboxed iframe preview → сохранение `.html` через Android SAF либо браузерная загрузка. Проверка Chromium прошла. Нативный путь сохранения пока только исходник и требует сборки/устройства.

**APK:** это отдельный реальный build pipeline, не переименование HTML. Сборщик должен получать Android-проект, собирать его в изолированном окружении с JDK/Gradle/SDK, запускать тесты, возвращать полный лог, checksum и APK. Пользователь управляет ключом подписи. Пока Build Runner не готов, интерфейс честно сообщает, что APK не собран.

## 5. Порядок реализации — строгие ворота

1. **v0.38-A — текущий source этап:** overlay widget + Code Studio, статические проверки и Chromium UI smoke. Это завершено на уровне исходников.
2. **v0.38-B — Android build + device acceptance:** Gradle Sync, unit tests, `assembleDebug`; затем проверить widget над обычным приложением, разрешения, перетаскивание, notification, открытие маршрутов, browser, media и HTML SAF save. До этого Android готовность не подтверждена.
3. **v0.39 — Code-to-output / APK Build Runner:** редактор → HTML demo → `.html` export; отдельно — настоящий изолированный APK сборщик с логами, тестами и артефактом.
4. **v0.40 — PTY + Workspace acceptance:** TerminalView/TerminalSession подключены к Android `/system/bin/sh`; следующий gate — Gradle/CMake, device smoke test, stdin/stdout, Ctrl-C, copy/paste и lifecycle. Это всё ещё не полноценный Linux userland; произвольный shell не предоставляется HTML-прототипу и не доступен агенту как инструмент.
5. **v0.41 — Linux userland:** реальная ABI-сборка, безопасная установка/обновление и тесты на устройстве.
6. **v0.42 — Agent Executor + Task Manager:** typed tools, Planner, проверка Sentinel, подтверждение, исполнение, независимый verifier, аудит/отмена; task status поступает в виджет.
7. **v0.43 — Spider Code workflow:** анализировать проект → планировать → править код → запускать тесты → открыть HTML demo или собрать APK → проверить артефакт → отчитаться.
8. **v0.44+ — memory, model provider, backend/remote worker и расширенная обработка медиа.**

## 6. Первый следующий шаг

Выполнить Android сборку в Android Studio/CI: `testDebugUnitTest`, `assembleDebug`, установить debug APK на устройство и сохранить build/device logs. В текущем runtime нет доступной команды Gradle, Android SDK и `adb`, поэтому утверждать, что APK создан или виджет работает на реальном телефоне, пока нельзя.

## C1 checkpoint — durable replay protection and restart recovery (15 October 2026)

`SpiderToolBroker` now uses a persistent one-time request ledger in the Android runtime. The ledger stores request-ID digests, and an invalid/unwritable ledger blocks dispatch instead of silently resetting replay history. On runtime start, `SpiderTaskCoordinator.recoverInterruptedTasks()` converts interrupted planning, execution, approval and verification states to explicit failure/skip states. A possibly in-flight step is labelled with unknown outcome and is not automatically rerun. See `docs/CHANGELOG_C1_REPLAY_RECOVERY.md` and `docs/NEXT_SESSION.md` for verification and remaining gates.


## Latest continuation — optional dependencies (17 October 2026)

- `android-project/app/build.gradle.kts` now declares the upstream Termux `terminal-view:0.118.0` JitPack dependency; `terminal-emulator` is transitive. The code opens Android `/system/bin/sh` under the app UID, with cwd/Home in the app-private directory. It does not bundle `termux-shared`, Termux bootstrap, a root path or Linux package manager.
- `scripts/download_qwen_smoke_model.sh` downloads an optional small Qwen2.5-0.5B-Instruct Q4_K_M GGUF (about 491 MB) and verifies SHA-256. Download is explicit only. It was not possible to physically download the model/AAR in this network-restricted execution environment.
- `terminalPty=true` indicates source integration; `terminalPtyVerified=false` and `linuxRuntime=false` remain until the dependency resolves, Android APK builds and PTY is verified on a device.


## OpenCode Zen direct model API (source added; device/live key check pending)

The native Android LLM settings now support the hosted OpenCode Zen API directly over fixed HTTPS endpoints. The API key/model are encrypted with Android Keystore. This provider calls the hosted model API directly over HTTPS; model inference does not provide project-file access or project execution. A minimal one-output-token inference request verifies a key/model before saving settings. Source smoke tests cover model protocol selection and validation, but no live user-key call or APK/device check has been performed. See `docs/OPENCODE_ZEN_DIRECT_API.md`.

## Latest source addendum — provider-independent adaptive memory (17 October 2026)

The earlier v0.38 checkpoint introduced a single device-local SQLite episodic-memory/search layer shared by local GGUF, Yandex AI Studio via backend, and OpenCode Zen direct hosted API. A common metadata codec constrains what goes to hosted providers; topic-token retrieval stays local. OpenCode Zen chat and task planning now both use memory hints, matching the already-integrated local/Yandex paths. Feedback and independently verified task outcomes affect future retrieval ranking, but model weights are not changed. This remains source-level implementation: Android Gradle/device execution and live-provider benchmark have not been verified. The current v0.39.3 Agent Broker registers five bounded typed tools: `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, `WRITE_WORKSPACE_FILE`, and `CHECK_WORKSPACE_PROJECT`. The last performs bounded static JSON/XML/SVG/UTF-8 checks and is independently repeated; it never runs code, tests, shell or builds. The content search/read/write handlers use one-time approval, safe relative-path checks, output redaction and independent repeat/read-back verification. Only actually executed tool IDs from verified terminal outcomes feed the shared episodic memory; planned steps and raw file contents are excluded. The durable skill library now seeds one typed candidate per real workspace tool and adds verifier/version evidence after independently verified successes; three distinct successful tasks are required before recommendation, and skills never execute automatically. There is no arbitrary shell runner, delete tool or external-app control. See `docs/SELF_LEARNING_ARCHITECTURE.md`, `docs/CHANGELOG_C1_UNIFIED_PROVIDER_LEARNING.md` and `docs/CHANGELOG_C1_REAL_WORKSPACE_TOOLS_LEARNING.md`.


## v0.39.3 deep review (19 October 2026)

The OpenCode Server connector has been removed from current product code; direct OpenCode Zen hosted-model API support remains. A full source-level review also uncovered and fixed the Web UI/backend integration mismatch: the UI had checked nonexistent `ready/models` fields on `/api/health`, regular chat had called deliberately disabled `/api/agent/step`, and the chat payload lacked bearer authentication while sending forbidden extra fields. The current UI verifies a bearer token at `/api/client/check`, reads the configured model from `/api/models`, and calls authenticated `/api/chat` with bounded history. Backend CORS is controlled by an explicit `SPIDER_CORS_ORIGINS` allow-list. Current code tests run in this environment, but the Android SDK/Gradle/JDK 17 toolchain is not installed here, so APK build/device/live provider integration remain unverified. See `docs/DEEP_AUDIT_AND_REMOVAL_REPORT_2026-10-19.md`.
