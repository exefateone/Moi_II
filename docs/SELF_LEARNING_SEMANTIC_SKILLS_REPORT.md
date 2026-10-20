> **Historical checkpoint:** this report describes the file-backed state before the SQLite migration. Its 64 MiB application memory/index limit has been superseded for active runtime storage by `CHANGELOG_C1_SQLITE_LAYERED_MEMORY.md`. The old guard remains only for legacy file import/compatibility.

# ИИ Паук v0.38 — отчёт C1: эпизодическая память, семантический поиск и проверяемые навыки

**Дата контрольной точки:** 16 октября 2026 г.  
**Статус:** исходники и локальные тесты проверены; Android APK, реальное устройство и живой embedding-провайдер не проверены.

## Что изменено в этой контрольной точке

1. Удалён count-based eviction cap на 500 эпизодов. Память хранит записи, пока есть место; предохранительный предел файла — 64 MiB. При превышении лимита сохранение должно завершиться ошибкой без молчаливого удаления старых записей. Это не бесконечное хранилище. Для существенно больших объёмов планируется миграция бинарного whole-store файла на SQLite/постраничное чтение.
2. Реализован локальный offline retrieval: deterministic feature vectors, многоязычные концептуальные алиасы, облегчённая морфология, символьные n-граммы и лексический tie-breaker. Это полезный семантический fallback, но не нейросетевая модель.
3. Реализован отдельный file-backed индекс embeddings, ограниченный 64 MiB, с нормализацией векторов и изоляцией по model ID. Пакетное сохранение векторов пишет индекс один раз, а не заново для каждого элемента.
4. Добавлен optional authenticated HTTPS endpoint `/api/agent/embeddings`. Он включается только при наличии конфигурации провайдера на backend. UI отдельно требует opt-in; по умолчанию передача выключена. Отправляются лишь очищенные тематические слова — не полный prompt, ответы, файлы или историю.
5. Исторические embeddings дозаполняются постепенными партиями до 15 записей за запрос. Поиск рассматривает всю текущую byte-bounded память, а не только первые 512 эпизодов.
6. Библиотека навыков хранит типизированные, allowlisted tool recipes с preconditions, версией инструмента, ID проверяющего компонента и evidence. Навык не содержит скрипты и не запускается автоматически.
7. Базовый встроенный навык остаётся кандидатом до трёх независимых успешных задач. Если уже проверенный навык проваливает независимую проверку, статус понижается; для возврата в `VERIFIED` нужны три свежих последовательных прохода. Повторный task ID, неподходящий verifier или версия инструмента не засчитываются.
8. Добавлены тесты для гибридного dense/local ранжирования, аутентификации endpoint embeddings и трёхпоследовательной перепроверки навыка после регрессии.

## Исследовательская основа

- Reflexion описывает улучшение следующих решений через обратную связь и эпизодическую память без изменения весов базовой модели: [NeurIPS 2023](https://proceedings.neurips.cc/paper_files/paper/2023/hash/1b44b878bb782e6954cd888628510e90-Abstract-Conference.html).
- Sentence Transformers описывает семантический поиск через векторные представления запроса и корпуса и сравнение близости; production-поиск должен оцениваться отдельно на корпусе задачи: [Semantic Search](https://www.sbert.net/examples/sentence_transformer/applications/semantic-search/README.html).
- Anthropic Skill Creator рекомендует писать eval cases, сравнивать варианты и количественно измерять поведение навыка, а не считать сам факт наличия описания навыка готовностью: [Skill Creator](https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md).

## Проверки этой контрольной точки

- Python tests: **66/66 passed**.
- Agent Core Kotlin/JVM smoke: **13 scenario groups passed**, включая 520 записей без count-based eviction, cross-language retrieval, hybrid embedding ranking, skill evidence и свежую повторную валидацию.
- Spider Media URL policy Kotlin/JVM smoke: **15 assertions passed**.
- HTML/JS check: **129 unique IDs**, inline scripts syntax-clean.
- Packaged HTML asset sync: **PASS**.
- Chromium UI smoke: ранее в этой же серии проверки — **8 сценариев PASS**, необработанных ошибок не найдено.
- Release validator Linux runtime: `NOT READY` — ожидаемо, manifest template помечен `not_built`.
- `verify_project.sh` не компилирует Android через Gradle и не собирает APK; в этой среде SDK/Gradle отсутствуют.
- Нет живого запроса к реальному провайдеру embeddings; server tests используют fake provider и проверяют контракт/безопасность, а не качество реальной модели.

## Принципиальные ограничения

- Это адаптация поведения через memory/retrieval, а не fine-tuning и не обновление весов LLM.
- По умолчанию семантический поиск остаётся локальным. Облачный neural embedding требует явного включения, настроенного HTTPS backend и провайдера embeddings.
- Пока реально поддерживается один read-only tool (`LIST_WORKSPACE_FILES`), поэтому библиотека содержит только один built-in candidate. Навык не должен стать проверенным без независимого доказательства.
- Нет доказательств улучшения конечных задач с настоящим провайдером. Следующий этап — A/B evaluation на фиксированном наборе задач: baseline без памяти против памяти, с метриками retrieval precision/recall, корректности конечного состояния, отказов от неподдерживаемых действий и времени ответа.
- На больших объёмах надо измерить производительность file-backed storage и при необходимости перейти на SQLite/пагинацию и отдельный ANN/vector index.
