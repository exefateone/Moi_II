# ИИ Паук — provider guard validation checkpoint

**Дата:** 17 октября 2026 г.  
**Версия backend:** `0.38.3-stage-c1-provider-guard`  
**Статус:** исходники и тесты; не production deployment, не APK.

## Что добавлено

1. Аутентифицированные маршруты `/api/chat`, `/api/agent/plan` и `/api/agent/embeddings` проходят ограничение частоты до вызова провайдера. По умолчанию — 20 запросов на маршрут за скользящие 60 секунд для настроенного bearer token. Переменная `SPIDER_PROVIDER_RATE_LIMIT_PER_MINUTE` допускает значения 1–600; некорректное значение приводит к закрытому отказу HTTP 503.
2. Для исходящих запросов к провайдеру добавлен предел в четыре одновременно выполняемых сетевых вызова. При перегрузке новый запрос отклоняется HTTP 429 с `Retry-After: 2`.
3. При ограничении частоты возвращается HTTP 429 с `Retry-After`, а незапланированный запрос не отправляется провайдеру. Ключ buckets — SHA-256 от настроенного client token; сырой токен не сохраняется в структуре лимитера и не логируется.
4. Проверка времени внутри lock сохраняет порядок временных меток при конкурирующих потоках. Добавлены проверки конкурентного доступа, rolling-window expiration, неверной конфигурации, отказа до upstream-запроса и освобождения concurrency-slot при завершении запроса.
5. Настройка добавлена в `backend/.env.example`; обновлены backend security guidance, архитектурная карта и changelog.

## Проверки, выполненные успешно

- `python -m unittest discover -s tests -p 'test_*.py' -v`: **93/93** теста пройдены одним запуском.
  - backend/provider: 30;
  - package/source contracts: 38;
  - runtime bundle validator: 12;
  - SQLite memory contracts: 8;
  - SQLite task-history contracts: 5.
- `python -m py_compile backend/app/main.py scripts/*.py tests/*.py`: пройдено.
- `bash scripts/check_local_llm_kotlin_sources.sh`: пройдено против сигнатурных Android API stubs; это не Android SDK/Gradle build.
- `bash scripts/typecheck_sqlite_task_repository.sh`: пройдено против сигнатурных stubs; это не полноценная Android-компиляция.
- `bash scripts/test_agent_core_jvm.sh`: **13/13** Agent Core persistence/recovery smoke scenarios passed.
- `bash scripts/test_media_url_policy_jvm.sh`: **15 assertions** passed.
- HTML check: **130 уникальных ID**, inline JavaScript syntax-clean, Android packaged HTML совпадает с исходным HTML.
- Chromium/Playwright UI smoke: **8 сценариев** прошли; ошибок JavaScript страницы не зарегистрировано. Нативная интеграция WebView/PTY и тесты устройства в этот smoke не входят.

Полный `scripts/verify_project.sh` в совокупности не завершился с нулевым кодом: запуск упёрся в таймаут среды во время отдельной Kotlin-компиляции Media URL policy. После этого соответствующие Media URL smoke и другие JVM/type-check команды были повторно запущены отдельно и завершились успешно. Этот таймаут не трактуется как успешный итог полного shell-скрипта.

## Что всё ещё не подтверждено

- Android SDK, Gradle и `adb` недоступны в этой среде, поэтому `testDebugUnitTest`, `connectedDebugAndroidTest`, CMake/NDK native build и `assembleDebug` не выполнены; APK не создан.
- Реальная GGUF-модель на телефоне и реальный запрос к Yandex AI Studio не проверялись.
- GitHub Actions не запускался: через подключённый GitHub connector сейчас не обнаружено доступных репозиториев, поэтому эти изменения не отправлены в GitHub.
- Лимитер является **процессным**: состояние сбрасывается после рестарта и не разделяется между несколькими worker-процессами/репликами. `SPIDER_API_TOKEN` пока один общий, а лимит разделён по маршрутам, поэтому это не индивидуальная пользовательская квота и не защита периметра.

## Следующий обязательный шаг

Настроить внешний reverse proxy/API gateway: TLS, распределённый per-IP/per-user rate limiting, бюджет расходов провайдера и мониторинг. Затем загрузить checkpoint в доступную GitHub-ветку и получить успешные Android CI gates: unit tests, instrumentation tests на эмуляторе, native CMake build и debug APK. Только после этого проводить тесты на телефоне с небольшой совместимой GGUF-моделью и отдельную live-проверку Yandex backend.
