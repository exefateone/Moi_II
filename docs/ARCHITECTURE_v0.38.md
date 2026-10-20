# ИИ Паук v0.38 — целевая архитектура и карта готовности

Дата оценки: 12 октября 2026 г. Проценты — инженерная оценка реализации относительно цели, а не измерение тестового покрытия. «Проверено на Android: 0%» означает, что в этой рабочей среде нет сборки/логов/устройства, а не то, что исходники отсутствуют.

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
    ├── Серверное безопасное хранилище ключей, auth/quotas/timeouts
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
| Spider Terminal / PTY | 15% | Архитектурные контракты; UI не выдаёт себя за shell | 0% | Реальный PTY, процессы, сигналы, cwd containment, lifecycle |
| Spider Linux userland | 5% | Только шаблон/manifest runtime | 0% | Реальный ABI bundle, provenance, безопасная установка и тесты |
| Spider Memory | 25% | Частичные локальные UI/storage элементы | 0% | Хранилище по проектам, поиск, просмотр/удаление/экспорт |
| AI model/provider adapter | 20% | Mocked provider routes, timeouts, process-local rate caps and four-call concurrency guard | 0% | TLS deployment, gateway-wide quotas, real credentials/live requests, monitoring and retention |
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
4. **v0.40 — настоящий PTY + Workspace:** команды, stdout/stderr, Ctrl-C, отмена, рабочая директория, process lifecycle; никакого произвольного shell доступа для web HTML.
5. **v0.41 — Linux userland:** реальная ABI-сборка, безопасная установка/обновление и тесты на устройстве.
6. **v0.42 — Agent Executor + Task Manager:** typed tools, Planner, проверка Sentinel, подтверждение, исполнение, независимый verifier, аудит/отмена; task status поступает в виджет.
7. **v0.43 — Spider Code workflow:** анализировать проект → планировать → править код → запускать тесты → открыть HTML demo или собрать APK → проверить артефакт → отчитаться.
8. **v0.44+ — memory, model provider, backend/remote worker и расширенная обработка медиа.**

## 6. Первый следующий шаг

Выполнить Android сборку в Android Studio/CI: `testDebugUnitTest`, `assembleDebug`, установить debug APK на устройство и сохранить build/device logs. В текущем runtime нет доступной команды Gradle, Android SDK и `adb`, поэтому утверждать, что APK создан или виджет работает на реальном телефоне, пока нельзя.


## Stage C update — 15 October 2026

The active work order is now defined by `STAGE_C_EXECUTION_ORDER.md`: Agent Core first, then PTY terminal, shared workspace, browser/media handlers, widget task-state integration, full Android acceptance, and stabilization. Source foundations include `SpiderTaskCoordinator.kt`, `SpiderToolBroker.kt`, schema-v1 `FileSpiderTaskRepository.kt`, and `SpiderAgentRuntime.kt`, plus one read-only workspace-list handler. The runtime is initialized by `MainActivity`. They are covered by JUnit test sources, a pure-Kotlin/JVM smoke harness and static UI tests. They are not wired to a live model provider or user-visible task/approval UI. The original readiness table above is a snapshot from 12 October and should not be read as proof that these new classes are Android-compiled or device-tested.

## Latest Stage C1 source checkpoint — SQLite task history (16 October 2026)

The active runtime task repository has moved from binary writes to `SqliteSpiderTaskRepository` in `spider-task-history-v1.db`. The former `FileSpiderTaskRepository` remains the versioned migration decoder for `agent/tasks-v1.spider`. Imported task/step rows and a `pending` marker are committed atomically; a separate read-only connection then checks SQLite integrity and compares the reopened data with the source. Only after that check is the marker finalized and the old file archived. Unknown legacy schemas preserve the source and block unsafe task-history use; future SQLite schemas are not downgraded. Task history uses SQL-backed count and latest-30 queries, while startup recovery queries only potentially interrupted states. Android instrumentation cases are present, and CI now runs connected instrumentation on an API 35 emulator before APK packaging. This checkpoint has Python/SQLite contract checks and a Kotlin type-check against Android signature stubs, but no Gradle build, APK, CI run, or device acceptance is claimed.
