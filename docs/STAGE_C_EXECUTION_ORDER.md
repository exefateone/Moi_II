# Этап C — развитие ИИ Паука

Рабочий порядок этапа C зафиксирован по приоритету пользовательской ценности. Версия продукта остаётся v0.38 до отдельного оформленного релизного изменения; этот документ фиксирует порядок работ, а не объявляет новую release-версию.

## Порядок работ

1. **C1 — интеллектуальное ядро, task orchestration и самообучение по истории.** Состояния задач и шагов, порт планировщика, безопасная диспетчеризация только типизированных инструментов, Sentinel policy gate, одноразовые request ID, обязательная pre-action запись в аудит, независимая проверка результата, persistent task history, user feedback и project-scoped episodic memory.
2. **C2 — настоящий терминал и рабочая Linux-среда.** Выбрать и проверить PTY, процессы, stdout/stderr, Ctrl-C, отмену и изоляцию рабочей директории. Агент не получает произвольный shell-интерфейс; команды должны запускаться через отдельную реализацию и её политику.
3. **C3 — рабочее пространство.** Связать проекты/файлы, редактор, браузер, терминал и задачи через стабильные ID и repository interfaces.
4. **C4 — браузер и мультимедиа.** Подключать действия агента через индивидуальные tool handlers; контент страницы и скачанные файлы считать недоверенными данными.
5. **C5 — глобальный виджет Spider.** Подавать в виджет настоящие task-state события от общего координатора, а не локальные фиктивные статусы.
6. **C6 — сборка APK и интеграционная проверка.** Gradle compile, unit tests, debug APK и Android device acceptance. Сборка/быстрые проверки должны также запускаться по возможности на каждом подэтапе; это не отменяет комплексную проверку здесь.
7. **C7 — стабилизация.** Исправления, регрессионные тесты, аудит ограничений и решение о следующем этапе.

## Текущий срез C1

### Реализовано в исходниках текущего рабочего архива

- `SpiderTaskCoordinator`: валидирует цель, состояния задач и шагов, план, подтверждение, отмену, pause/resume и обязательное отдельное состояние `VERIFYING`. Терминальные состояния наблюдаются `FileSpiderLearningMemory` без возможности памяти менять task status.
- `SpiderTaskPlanner`: provider-independent порт с реализацией `HttpSpiderTaskPlanner`, которая запрашивает типизированный план у защищённого backend endpoint. Для живых запросов нужны размещённый HTTPS backend и конфигурация провайдера.
- `SpiderToolBroker`: типизированные обработчики по `SpiderToolId`, политика перед dispatch, отсутствие обработчика приводит к fail-closed, request ID нельзя повторно исполнить в рамках процесса.
- Broker требует успешную запись события `execution_started` в аудит прежде чем вызвать обработчик.
- `SpiderAuditLog` реализует общий интерфейс аудита и использует общий редактировщик известных шаблонов секретов.
- Unit tests покрывают ключевые переходы задачи и защитные решения broker.

### Ограничения, которые остаются явными

- `SqliteSpiderTaskRepository` теперь является активным хранилищем истории задач; legacy `FileSpiderTaskRepository` оставлен декодером для атомарного импорта. Последние 30 задач читаются отдельным SQL-запросом, счётчик — через `COUNT(*)`, а стартовое восстановление фильтрует только потенциально прерванные задачи. Это изменение есть в исходниках; Gradle/device acceptance ещё не выполнены.
- Зарегистрирован первый реальный обработчик `LIST_WORKSPACE_FILES`: только чтение имён, ограничение глубины/количества записей и блокировка путей вне выбранного workspace. Он проходит через `SpiderToolBroker`, policy и audit.
- Вызов модели реализован через backend-адаптер, но живой провайдер не настроен и end-to-end запрос не выполнялся. В Android клиенте ключ провайдера отсутствует; он должен быть задан только на сервере.
- `SpiderAgentActivity` показывает задачу/план, сохраняет HTTPS endpoint и клиентский токен (шифруется Android Keystore), позволяет выбрать/создать app-private workspace и просит явное одноразовое подтверждение перед вызовом единственного поддержанного read-only handler `LIST_WORKSPACE_FILES`. `WorkspaceListingResultVerifier` проверяет код/формат сводки, запись аудита и наличие workspace, а также повторно перечисляет каталог через отдельный NIO-обход и сопоставляет количество элементов и SHA-256 инвентаризации. Если инвентаризация неполная или каталог изменился после действия, задача не получает статус `COMPLETED`.
- Одноразовые request ID сохраняются в `FileSpiderRequestLedger` в app-private хранилище; чистый Kotlin/JVM smoke проверяет replay denial после пересоздания broker. Реальное Android-поведение после уничтожения процесса ещё нужно подтвердить на устройстве.
- `FileSpiderLearningMemory` stores bounded episode metadata and explicit helpful/not-helpful feedback; retrieval is lexical + recency weighted, and project-scoped entries are not returned to another workspace. Plan feedback remains `UNKNOWN` until an independently verified terminal outcome. Memory is not model weight training. See `SELF_LEARNING_ARCHITECTURE.md`.
- `SpiderAgentActivity` shows task history, safe audit references, learning memory records, per-entry forgetting and full memory erasure. Learning memory has its own versioned legacy migration; task history now imports the old binary repository into a versioned SQLite schema. Unknown/future schemas fail closed.
- Kotlin/Android сборка и тесты должны считаться непроверенными до настоящего `gradle testDebugUnitTest`/`assembleDebug`.

## Gate для перехода из C1

- [ ] Kotlin unit tests проходят в Gradle.
- [x] На основные состояния задачи добавлены UI/history mapping; дальнейшее событие/виджетное объединение остаётся в плане.
- [x] Реализован и подключён к app composition root durable repository со схемой v2 (читает v1); pure Kotlin/JVM smoke-тесты проходят.
- [ ] Подтвердить Gradle build и восстановление задач на реальном Android-процессе после перезапуска.
- [x] Описать и протестировать миграцию binary task history → SQLite и v1→v2 для learning memory на уровне исходников/контрактов; Android instrumentation task-history migration ещё нужно запустить.
- [x] Provider adapter имеет HTTPS, ограничение тела запроса/ответа, тайм-аут, структурированный план, фильтр разрешённых инструментов и редактирование ошибок.
- [ ] Настроить/разместить backend и выполнить живой end-to-end плановый запрос с настоящим провайдером.
- [x] Первый read-only typed handler подключён через policy broker и audit, pure JVM smoke проходит.
- [x] Первый handler снабжает результат версионированным доказательством (count + digest + workspace ID); verifier делает свежий обход и отказывает при усечённом/изменившемся инвентаре.
- [ ] Следующие handlers подключаются по одному, с ограничением ресурсов и проверяемыми результатами.
- [x] Добавлены bounded episodic self-learning memory, user feedback, safe retrieval, workspace scope isolation and forget/clear controls; live model effect/device QA still pending.
- [x] Интеграционные JVM-smoke сценарии проверяют независимую повторную инвентаризацию: пропущенное доказательство, неподтверждённый аудит, изменение файлов или несовпадение кода блокируют `COMPLETED`.
- [ ] Интеграционный Android-тест подтверждает, что отмена UI-подтверждения не вызывает обработчик, а повторный request ID не повторяет действие после перезапуска.


## Дополнение контрольной точки — SQLite task history, 16 октября 2026

- Активный runtime переключён на `SqliteSpiderTaskRepository`, база `spider-task-history-v1.db`; бинарный `tasks-v1.spider` остаётся входом миграции. Импортированные строки и маркер `pending` фиксируются одной транзакцией; затем отдельное read-only соединение выполняет `PRAGMA quick_check(1)` и сравнивает повторно прочитанные задачи. Маркер `imported` фиксируется только после этой проверки, и лишь затем исходник перемещается в `.migrated-sqlite-v1`. После аварии с `pending` проверка повторяется; неизвестная версия источника блокирует миграцию.
- Структура разделяет `task_history`, `task_steps` и метаданные импорта; включены внешние ключи, индексы истории/статусов и WAL. `recent(limit)` и `count()` реализованы запросами SQL; startup recovery загружает только возможные in-flight задачи.
- Добавлены Android instrumentation tests на перенос/повторное открытие, восстановление `pending` после имитации аварии, 120 задач, порядок последних задач, защиту от неизвестной legacy-схемы и отказ от downgrade будущей SQLite-схемы. Эти tests добавлены, но в данной среде не запускались.
