# ИИ Паук v0.39.3 — глубокий аудит кода и удаление OpenCode Server

**Дата:** 19 октября 2026 г.  
**Область:** исходный архив v0.39.1 → рабочая контрольная точка v0.39.3.  
**Тип проверки:** исходники, архитектурные контракты, Python/FastAPI unit tests, HTML/JavaScript, Chromium smoke, Kotlin/JVM smoke и типизация отдельных Kotlin-модулей на сигнатурных заглушках.

> Эта работа выполнена в локальной копии архива. Подключённый GitHub-репозиторий не изменялся. Настоящая Android-сборка и тесты на физическом устройстве не выполнялись, поскольку в среде отсутствуют Android SDK, Gradle и `adb`; установленный JDK также не совпадает с ожидаемым CI JDK 17. Реальные запросы к Yandex AI Studio/OpenCode Zen не выполнялись.

## 1. Решение по OpenCode Server

OpenCode Server удалён из текущего продукта. Из исходников удалены его Android-коннектор и UI-точка входа, настройки/клиент/подключение, manifest-регистрация, специальный backend alias и специфичные тесты/скрипты/текущие интеграционные документы.

В текущей версии остаётся только **OpenCode Zen как прямой hosted language-model API**. Это отдельная интеграция модели и она не требует установки или запуска OpenCode Server. Действия с файлами проекта остаются задачей нативного Agent Broker внутри выбранного app-private workspace.

Сохранённые исторические отчёты могут упоминать старый OpenCode Server коннектор, чтобы не переписывать доказательства прошлых контрольных точек. Это исторические сведения, не активная возможность продукта.

## 2. Существенные ошибки, найденные и исправленные

### P1 — веб-чат не соответствовал реальному FastAPI backend

До исправления HTML выполнял проверку `/api/health`, ожидая `ready === true` и `models`, хотя сервер фактически возвращал `status`, `version` и `provider_configured`. Из-за этого исправный backend ошибочно считался неподключённым.

Обычный пользовательский чат направлялся на `/api/agent/step`, который преднамеренно отвечает `503 agent_execution_not_enabled`. При этом имевшийся `/api/chat` клиент не передавал `Authorization: Bearer ...` и отправлял лишние поля `profile`, `memory` и `client`, несовместимые со строгим `ChatRequest(extra="forbid")`. Таким образом, веб-интерфейс не мог надёжно общаться с существующим серверным контрактом.

**Исправлено:**
- обычный чат теперь обращается к `POST /api/chat`;
- добавлен `GET /api/client/check`, который проверяет client bearer token и конфигурацию без вызова платной генерации;
- UI после успешной проверки получает модель из `GET /api/models`;
- запрос содержит Bearer-токен и только валидные поля `messages` и `model`;
- backend отклоняет переданный клиентом `model`, если он не совпадает с моделью, настроенной на сервере;
- история ограничена 12 сообщениями, каждое сообщение соответствует лимиту 2400 символов, транспортный JSON ограничивается 14 000 байт, оставаясь внутри серверного лимита тела 16 KiB;
- токен HTML-клиента хранится только в памяти текущей вкладки/WebView и не сохраняется в `localStorage`. После перезапуска его нужно ввести снова. Ключ провайдера по-прежнему остаётся на сервере.
- удалён недействующий старый браузерный loop, пытавшийся исполнять клиентские псевдо-инструменты через отключённый `/api/agent/step`.

`/api/agent/step` оставлен явно отключённым. Это сделано намеренно: планирование задач само по себе не даёт права выполнять команды.

### P1 — CORS не был согласован с Android WebView

У backend не было CORS middleware. При запросах из доверенного WebView `https://appassets.androidplatform.net` браузер мог блокировать preflight для `Authorization`/`Content-Type`, даже если endpoint и токен были настроены верно.

**Исправлено:** добавлена переменная `SPIDER_CORS_ORIGINS` со списком явно заданных HTTP(S) origins. Значение по умолчанию — `https://appassets.androidplatform.net`. Wildcard `*`, URL с путём/query/fragment/учётными данными и недопустимым портом не принимаются. CORS не заменяет bearer-аутентификацию. Для размещённого веб-интерфейса оператор должен добавить его точный origin в переменную среды.

### P1 — текстовая команда «стоп» могла блокироваться во время задачи

Ранний guard `if(demoBusy)return` в `send()` препятствовал обработке введённой команды «стоп» во время активного запроса, хотя отдельная кнопка остановки работала. Теперь стоп-команда допускается даже при занятости; `stopAll()` отменяет активный backend fetch через `AbortController` и инвалидирует текущий action epoch. Другие новые сообщения при занятости по-прежнему не запускаются параллельно.

### P2 — риск несовместимости Android toolchain

Исходник использовал AGP 8.9.2 вместе с Kotlin Android plugin 2.2.0. Официальная таблица Android указывает для Kotlin 2.2 R8 8.10.21, а релизные примечания AGP 8.10 устанавливают Gradle 8.11.1 и JDK 17. Для снижения риска несовпадения toolchain AGP поднят до 8.10.2, при сохранении Kotlin 2.2.0, Gradle 8.11.1 в CI, JDK 17 и SDK 35. Это мера согласования версий, а не доказательство того, что прежняя настройка гарантированно ломалась.

Это устраняет обнаруженный риск версии в настройке исходников, но **не считается доказательством сборки**: Gradle Sync, Kotlin/Java compilation, CMake/JNI, instrumentation и выпуск APK всё ещё должны пройти в настоящей Android-среде.

Официальные источники:
- Android — [AGP, D8 и R8 versions required for Kotlin versions](https://developer.android.com/build/kotlin-support?hl=en)
- Android — [Android Gradle Plugin 8.10 release notes](https://developer.android.com/build/releases/agp-8-10-0-release-notes?hl=en)

### P2 — несогласованные текущие метки версии и устаревшие инструкции

Текущие метки согласованы на `0.39.3`: HTML shell и его Android asset, `versionName`/`versionCode`, backend health version, native capability string, OpenCode Zen User-Agent и CI artifact names. `versionCode` увеличен до 42. `docs/NEXT_SESSION.md` получил новую актуальную шапку, а старый план помечен как исторический, чтобы старые указания об одном инструменте не выглядели текущим состоянием. Backend README обновлён: веб-чат принимает base URL backend, а нативный planner — полный `/api/agent/plan` endpoint.

## 3. Состояние ядра, инструментов и памяти по исходникам

- Agent Broker использует типизированные tool ID; в текущем перечне пять операций рабочего пространства: список, поиск, чтение, запись и ограниченная статическая проверка проекта.
- Нет общего `EXECUTE_SHELL` инструмента для модели. Ручной терминал Android — отдельная нативная функция и не означает разрешение облачной модели произвольно запускать команды.
- Запись в workspace предусмотрена через отдельное подтверждение, проверки путей и независимое подтверждение результата. AI edit proposal требует предпросмотра, а несовпадение исходного файла должно прекращать применение.
- SQLite task history, learning memory/search index, scope-aware retrieval и библиотека навыков имеют тестовые контракты; внутреннее «обучение» сейчас означает использование памяти и результатов проверенных задач, а не обновление весов модели.
- Linux runtime остаётся `not_built`. Его отсутствие показывается явно; release gate отклоняет такой runtime, а не выдаёт template за установленную среду.
- Нативные LLM/Media/PTY/Overlay модули есть в исходниках, но их наличие не доказывает, что они собираются и работают на Android-устройстве.

## 4. Проверки, выполненные в этой контрольной точке

| Проверка | Результат | Ограничение |
|---|---:|---|
| Python unit/contract suite | **119/119 PASS** | Мокированные провайдеры, не live API |
| HTML/JavaScript source check | **132 уникальных HTML ID; inline JS syntax PASS** | Source/DOM checks, не Android WebView build |
| Синхронность веб-HTML с Android asset | **PASS** | Проверка байтовой идентичности |
| Chromium UI smoke | **8/8 PASS**, нет uncaught page errors | Нативный bridge/PTY/device не тестировались |
| Agent Core Kotlin/JVM smoke | **15 grouped scenarios PASS** в отдельном запуске | Есть тестовые doubles и signature stubs; это не Android Gradle compilation |
| Media URL policy JVM | **15 assertions PASS** | Только URL policy |
| OpenCode Zen source smoke | **PASS** | Не реальный запрос к API |
| Local LLM Kotlin source check | **PASS** | Сигнатурные Android stubs, не SDK build/JNI device test |
| SQLite task repository type-check | **PASS** | Сигнатурные stubs, не инструментальная сборка |
| `python3 -m compileall -q backend tests scripts` | **PASS** | Синтаксис Python, не поведение production |
| `bash -n scripts/*.sh` | **PASS** | Синтаксис shell |
| `scripts/verify_project.sh` как единый aggregate | **TIMEOUT** | Не заявляется aggregate PASS; соответствующие focused checks выполнены отдельно |

## 5. Что не подтверждено и должно оставаться блокирующим gate

1. `gradle testDebugUnitTest`, `connectedDebugAndroidTest`, `assembleDebug` и итоговый APK.
2. Установка APK и Android-поведение: разрешения, WebView bridge, Overlay, PTY, локальная GGUF/CMake/JNI, SQLite миграции/восстановление и media playback.
3. Сквозной запрос на фактический Yandex AI Studio deployment и OpenCode Zen API с корректными credentials.
4. Benchmark памяти/skill library на реальных задачах, а не только contract tests.
5. Распределённый rate-limit/spend policy, production logging/monitoring и настройка TLS/API gateway на хостинге backend.

## 6. Очередь дальнейшей разработки

1. Запустить и исправить реальную Android/CI сборку; сохранить полные логи Gradle, reports и APK как артефакты.
2. Проверить сквозной сценарий: UI → CORS → bearer auth → `/api/client/check` → `/api/models` → `/api/chat` и отдельно native UI → `/api/agent/plan`.
3. Провести device acceptance для остановки/отмены, workspace tools, approvals, аудита, SQLite, локальной модели и overlay.
4. Только после прохождения этих gate расширять проверку проектов: компиляторы/тесты через ограниченный изолированный runner, затем многофайловые патчи с rollback.
5. Доказать эффективность self-learning сравнением одинаковых задач с retrieval и без него; не разрешать памяти повышать привилегии инструмента.

**Итог:** текущий источник стал честнее и цельнее по интерфейсу подключения модели: прежний веб-путь к облачному чату был несовместим с backend и исправлен. OpenCode Server удалён. На этой точке есть набор успешных source/unit/smoke checks, но сборка Android и реальные provider/device сценарии остаются обязательными следующими проверками.
