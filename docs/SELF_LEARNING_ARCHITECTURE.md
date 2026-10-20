# ИИ Паук — архитектура самообучения и памяти опыта

**Контрольная точка:** v0.38 · Этап C1 · 17 октября 2026 г.  
**Статус:** SQLite-backed memory/search sources, a local GGUF inference source path and Yandex AI Studio backend adapter are added. Android instrumentation, JNI/Gradle build, APK, device inference and live provider remain unverified. Self-learning remains memory-based behavioral adaptation, not model-weight training.

## 1. Что называем «самообучением»

В этой версии самообучение означает **адаптацию поведения агента из истории задач, подтверждённых результатов и явных оценок пользователя**. Память извлекает структурированные подсказки и передаёт их планировщику при следующей похожей задаче. Это не изменение параметров/весов базовой языковой модели и не самостоятельное переобучение модели на телефоне.

Такое определение соответствует подходу Reflexion: агент использует сигналы обратной связи и эпизодическую память, чтобы менять следующие решения без обновления весов модели. Исследование Generative Agents также выделяет хранение опыта, синтез более общих выводов и динамическое извлечение воспоминаний для планирования. Voyager дополняет картину идеей постепенно накапливаемой библиотеки проверяемых навыков; для Паука она станет уместной, когда появятся несколько настоящих инструментов и проверяемые цепочки действий. Источники: [Reflexion (NeurIPS 2023)](https://papers.nips.cc/paper_files/paper/2023/hash/1b44b878bb782e6954cd888628510e90-Abstract-Conference.html), [Generative Agents (ACM UIST 2023)](https://doi.org/10.1145/3586183.3606763), [Voyager (arXiv)](https://arxiv.org/abs/2305.16291).

## 2. Единый цикл обучения для всех источников LLM

Одна локальная память опыта обслуживает все поддерживаемые маршруты: загруженную GGUF-модель, Яндекс AI Studio через backend и прямой OpenCode Zen API. Выбор другого провайдера не создаёт отдельную память и не сбрасывает накопленный опыт. При поиске темы используются локальные тематические признаки; payload памяти для облачной LLM получает не сами токены темы, а до пяти ограниченных типизированных сигналов (`kind`, allowlisted `tool_ids`, безопасный `error_code`, `confidence_score`). Общий codec `SpiderLearningContextCodec` применяется в Yandex chat/planning и OpenCode Zen chat/planning. Отдельная опция облачных embeddings существует отдельно, выключена по умолчанию и может передавать очищенные тематические слова только после явного согласия пользователя.

Цикл: запрос → локальное извлечение релевантного опыта → план/ответ выбранной модели → действие через фактически зарегистрированный инструмент → проверка результата → запись терминального исхода → необязательная оценка пользователем → следующие ответы и планы учитывают новый вес опыта. Успешный статус пишется только после независимой проверки, а единичная оценка не превращает непроверенный план в успех. Память может улучшать выбор следующего шага, но не расширяет allow-list и не даёт агенту новых прав.

**Граница термина «самообучение»:** в этой версии меняется поведение ИИ Паука за счёт его локальной памяти, поиска и обратной связи. Провайдерная модель не переобучается и её веса не меняются; обычные сообщения чата сами по себе не становятся долговременным опытом без терминального результата задачи или явной оценки. В текущей контрольной точке реально зарегистрированы пять типизированных workspace-инструментов: `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, `WRITE_WORKSPACE_FILE` и `CHECK_WORKSPACE_PROJECT` (ограниченная статическая проверка JSON/XML/SVG и доступности UTF-8; без выполнения кода). Поиск и чтение ограничены выбранным рабочим пространством; запись сохраняет только текст, который пользователь ввёл и подтвердил. Произвольный shell, запуск тестов проекта, загрузка и установка пакетов, удаление файлов и автономная сборка по-прежнему не подключены.

## 3. Выводы исследования, применённые к проекту

1. **История должна быть памятью опыта, а не безразборным повторным вводом всех прошлых разговоров.** Из полной истории легко перенести ненужные детали, личные данные или устаревшие факты. Сейчас в память идут только токены темы, типизированные ID инструментов, код исхода, факт проверки, время и явная оценка.
2. **Успех и полезность плана — разные сигналы.** Задача, которую пользователь оценил до исполнения, остаётся `UNKNOWN`; положительная оценка плана не превращает её в доказанный успешный запуск. Независимо проверенное выполнение получает более высокий вес.
3. **Неудачи тоже полезны, но не являются командами.** Запись неудачи хранит ограниченный код ошибки и снижает/ограничивает уверенность. Неизвестный исход после сбоя не трактуется как успех и не вызывает автоматического повтора.
4. **Память должна быть релевантной по теме, свежести и области проекта.** LongMemEval выделяет извлечение информации, рассуждение между сессиями, временное рассуждение, обновление знаний и отказ от неподтверждённого ответа. Это полезные измерения для будущих evaluation-тестов. Источник: [LongMemEval, ICLR 2025](https://arxiv.org/abs/2410.10813).
5. **Нельзя считать сам факт ответа модели доказательством качества.** Для агентов лучше тестировать многошаговый сценарий и фактическое состояние результата, а не только текст. Это подчёркивается в [Anthropic: Demystifying evals for AI agents (9 января 2026)](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents).
6. **Память не должна расширять полномочия агента.** OWASP рассматривает чрезмерные полномочия, prompt injection, раскрытие конфиденциальных данных и отравление данных среди существенных рисков LLM-систем. Поэтому память ограничена метаданными; она не содержит исполняемый свободный текст, не назначает permissions, а backend принимает только белый список инструментов. Источник: [OWASP Top 10 for LLM Applications](https://owasp.org/www-project-top-10-for-large-language-model-applications/).

## 4. Текущая реализация

### Поток данных

`SpiderTaskCoordinator` → terminal state and independently verified outcome → `SqliteSpiderLearningMemory` → separate `spider-search-index.db` (inverted postings + optional dense vectors) → bounded retrieval → selected model route. `LOCAL_ONLY` calls a user-loaded GGUF model through the JNI llama.cpp runtime; `YANDEX_CLOUD` calls the authenticated Spider backend and Yandex AI Studio; `LOCAL_THEN_YANDEX` permits cloud fallback only when explicitly selected. Local and cloud task plans use the same strict parser and typed tool allow-list.

- Active memory has no application-level byte ceiling and no episode-count eviction. It is backed by SQLite, with database page growth constrained by available device storage and SQLite/runtime limits. The app must handle `SQLiteFullException`, other I/O errors and low-storage conditions without claiming success or deleting old records. The 64 MiB guard remains only in the legacy binary-format reader/writer used for importing old `.spider` files; it does not cap the new SQLite databases.
- В память не записываются полный prompt, transcript, текст ответа модели, содержимое файла, сырой вывод инструмента, секреты или свободные «размышления» агента.
- Тематические токены проходят фильтрацию шаблонов для токенов доступа, URL, e-mail, абсолютных путей и инструкционных слов. Это минимизация риска, а не гарантия обнаружения всех чувствительных данных.
- Подсказки ограничены максимум 5 эпизодами, максимум 12 токенами темы на эпизод и реализованными ID инструментов.
- Поиск сочетает локальный вектор признаков с многоязычными семантическими группами и лексическим tie-breaker, а по отдельному явному согласию — с нейросетевыми embeddings настроенного провайдера. Нейросетевые векторы сравниваются только в пределах одного model ID. Рейтинг дополнительно учитывает тип исхода, явную оценку и мягкое снижение веса старых эпизодов. Давность меняет порядок извлечения, но не переписывает исторический статус.
- Проектные эпизоды из одного workspace не извлекаются для другого workspace. Общая (global) память может использоваться разными пространствами. Задача сохраняет ID области, не путь к каталогу.
- Пользователь может оценить задачу, просмотреть память, забыть один эпизод или удалить всю память, не удаляя историю задач.
- Миграция форматов task store и memory v1 → v2 сохраняет старые записи как scope `global`; при следующем успешном сохранении они записываются в текущем формате. Будущая неподдерживаемая версия не должна автоматически перезаписываться.
- Backend валидирует типизированные learning hints; никакой свободный текст урока не принимается. Модель не может через память включить новый инструмент или обойти подтверждение.
- Search/index storage is a separate SQLite database: `search_postings` is an indexed inverted token table; `dense_embeddings` stores normalized vectors by task ID + model ID; `embedding_backfill_state` persists keyset pagination; `memory_index_outbox` in the primary DB makes index changes replayable after a crash. Dense top-K currently streams all vectors of a model and holds only a bounded priority queue in memory. This removes the arbitrary file cap and whole-index RAM materialization, but the dense scan is still O(N) per query; an ANN/vector extension will be considered only after measured benchmarks. Optional provider embeddings remain opt-in and off by default; Yandex OpenAI-compatible embedding URI validation is implemented, but no live embedding request has been made.
- Библиотека навыков хранит версионированные декларативные рецепты пяти реально зарегистрированных workspace-инструментов: list/search/read/write/static-check. После независимой проверки успешного шага и при включённом самообучении сохраняется только task ID, verifier ID и версия инструмента. Навык не содержит исполняемый код, не расширяет разрешения и не запускается автоматически. Для статуса `VERIFIED` нужны три разных задачи, успешно прошедшие allowlisted independent verifier. Содержимое файлов и вывод не сохраняются в доказательства навыка.

## 5. Ограничения текущего этапа

- Локальный semantic search — лёгкий детерминированный вектор признаков с многоязычными синонимическими группами, не полноценная нейросетевая модель. Для neural embeddings требуется развернутый backend, модель embeddings у провайдера и явное включение пользователем. На живом провайдере качество retrieval пока не измерено.
- В актуальном v0.39.1 checkpoint реализованы пять реальных workspace-инструментов: `LIST_WORKSPACE_FILES`, `SEARCH_WORKSPACE_CONTENT`, `READ_WORKSPACE_FILE`, `WRITE_WORKSPACE_FILE` и `CHECK_WORKSPACE_PROJECT`. Последний выполняет только ограниченную read-only статическую проверку; он не запускает проект. Поиск/read/write ограничены текстовыми файлами выбранного app-private workspace; перед исполнением требуется явное подтверждение, а запись в существующий файл требует отдельного согласия на замену. Проверяющие повторно сканируют или читают файл и сравнивают SHA-256. Произвольный shell, удаление файлов и общая сетевая автоматизация не подключены.
- Исходники локального GGUF/JNI runtime и Yandex AI Studio backend adapter теперь подключены в композицию, но в этой среде нет Android SDK/NDK/Gradle и не выполнен живой запрос к Яндекс API. Поэтому реальная локальная генерация и облачный end-to-end путь остаются непроверенными.
- APK и Android-интеграция не собраны/проверены в этой среде. Kotlin/JVM/source-stub checks — не замена Gradle и реальному устройству. Android instrumentation test source has been added but not executed.
- Это memory-based adaptation, а не изменение весов базовой модели и не fine-tuning.
- Активное episodic memory, индекс поиска и история задач уже SQLite-backed. Библиотека навыков пока сохраняет прежний file-backed repository; её перенос запланирован отдельной миграцией. Векторный поиск сейчас использует ограниченный top-K heap поверх сканирования SQL-строк по model ID; для больших объёмов нужно измерить задержку и лишь затем выбрать ANN-индекс.

## 6. План развития самообучения

### C1.a — базовая память (file-backed stage, legacy)
- Workspace scopes, временное ранжирование, явная обратная связь и подтверждённые исходы.
- Legacy `.spider` format is retained only for migration/compatibility and keeps its defensive format-size guard.

### C1.d — SQLite episodic memory + separate search index (source implemented; Android verification pending)
- Main SQLite DB stores episodes, normalized topic tokens/tools, metadata and a durable index-change outbox; schema uses constraints and indexes.
- Separate SQLite DB stores inverted postings and optional model-specific dense vectors. Index updates are idempotent and outbox rows are removed only after the index transaction commits.
- Legacy file memory and embedding index import in bounded batches; source archives are retained as `.migrated-v2` after migration. Partial imports are retryable.
- No app-level 64 MiB ceiling in the live SQLite memory/index. Paging and bounded top-K retrieval avoid loading the whole corpus or vector index into RAM. Physical storage is still finite; failures must remain explicit and fail-safe.
- SQLite instrumentation tests cover 540 migrated episodes, pages, separate index updates/forget/clear, and 1,100 vectors. These tests are added but not executed because Gradle/Android SDK is not present here.

### C1.b — семантический поиск и библиотека проверяемых навыков (реализовано в исходниках; live evaluation ожидает)
- Локальный гибридный поиск и отдельный opt-in маршрут нейросетевых embeddings; векторный индекс с model isolation.
- Typed skill recipes, allowlisted verifier/version, preconditions, отдельные task IDs и три последовательных прохода при первичном подтверждении/повторной валидации.
- Заполнить набор независимых benchmark задач и пройти сравнение retrieval с памятью и без неё на живом провайдере.

### Next — validate SQLite, native inference, and improve memory layers
- Run Android instrumentation on emulator/device and test migration, reopen-after-crash, storage-full/error behavior, and queries with several thousand episodes.
- Add storage diagnostics in the UI: episode count, topic-token count, database/index sizes and pending-index changes; do not expose private topics or file paths.
- Task history has since been moved to a versioned SQLite schema with verified legacy-import logic; move skill evidence next as a separate migration, preserving its current source until verified migration/rollback gates pass.
- Add a compact, versioned semantic-summary layer only after establishing an explicit synthesis/evidence contract; every derived summary should point to source episode IDs and be rebuildable/deletable.
- Build the CMake/JNI runtime and verify local inference using a user-supplied small GGUF model. Then verify the Yandex backend with live credentials in a separately configured deployment; compare fixed tasks with memory disabled/enabled only after those paths work.
- Profile dense top-K query cost at 1k/10k/100k vectors before choosing SQLite-vec/HNSW or another ANN implementation; prefer the simplest index that meets measured latency and memory budgets.

### Обучение весов модели — отдельный исследовательский этап
- Допускать только по отдельному явному согласию, после удаления чувствительных данных, проверки прав на данные и формирования проверяемого датасета.
- Сначала offline fine-tuning/benchmark на отдельной модели, сравнение с базовой, оценка регрессий и red-team проверка; затем controlled rollout с возможностью отката.
- Нельзя автоматически отправлять персональную память пользователя на fine-tuning. В текущем коде такой механизм отсутствует.

## 7. Обязательные evaluation-критерии

1. Recall: извлекается ли подходящий подтверждённый опыт.
2. Scope isolation: ни одного project-A hint при запросе в project B.
3. Abstention: при недостатке релевантной памяти — пустые подсказки, а не выдуманный опыт.
4. Feedback calibration: план с отметкой «полезно» не становится успешным исполнением без проверки.
5. Temporal update: новая информация влияет на извлечение раньше старой, без уничтожения истинного статуса старого эпизода.
6. Privacy: секреты, URL, e-mail, абсолютные пути и свободный текст не экспортируются как память.
7. Safety invariance: добавление или отравление памяти не может расширить tool allowlist или снять user approval.
8. Regression: одинаковый набор задач прогоняется с памятью и без неё; сравнивается конечное состояние, а не только сгенерированный текст.

Пока пройдены локальные сценарии этих контрактов, включая проверку фактически выполненных list/search/read/write шагов и исключение незапущенных плановых шагов из памяти; количественная оценка на LongMemEval/собственном корпусе и живом провайдере ещё не выполнялась.
