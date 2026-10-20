# QA report — ИИ Паук v0.36 Browser QA

## Результат автоматических проверок

Дата прогона: 2026-10-12.

- Python unit/contract tests: **30/30 passed**.
- HTML shell: version marker `0.36`, **117 уникальных HTML id**, встроенный JavaScript прошёл `node --check`.
- Packaged UI parity: `app-web/ii-pauk.html` и Android asset совпадают побайтно.
- Chromium / Playwright UI smoke test: **passed**.
  - загрузка страницы;
  - создание рабочего пространства;
  - обнаружение дубликата имени;
  - переименование, переключение и удаление;
  - ограничение количества рабочих пространств;
  - URL-policy smoke checks: credential-bearing URLs, `javascript:` and control-character input are rejected; plain-text input routes to a DuckDuckGo query;
  - browser address bar, закладка и новая вкладка в HTML-прототипе;
  - терминальная консоль ясно обозначена как не-shell, а PTY как неподключённая;
  - необработанных page errors не обнаружено.
- Runtime release gate вернул `NOT READY` ожидаемо: в архиве только манифест `not_built`, готовый Linux userland bundle отсутствует.

## Native build/device QA — не выполнено

В текущем окружении не найдены Gradle, Android SDK / `android.jar`, `adb` и `ANDROID_HOME`; исходящий DNS недоступен. Поэтому `assembleDebug`, Kotlin/AndroidX компиляция, установка APK и проверки физического устройства не выполнялись. Исходники не следует считать скомпилированными только на основании Python/HTML tests.

Не подтверждены на устройстве:

- реальная HTTP/HTTPS-навигация в SpiderBrowserActivity;
- обработка TLS и Safe Browsing;
- загрузка/отправка файлов;
- загрузка через DownloadManager и поведение на старых Android;
- перезапуск WebView после падения renderer;
- Android lifecycle, поворот экрана и восстановление состояния;
- запуск PTY (он ещё не интегрирован);
- установка Linux userland (она не включена в сборку).

## Обнаруженные и исправленные дефекты во время QA

- Удалён глобальный capture-phase click handler, который мог обойти выбранную вкладку HTML UI и открыть неправильный URL.
- Добавлена проверка, что native bridge срабатывает только в главном frame и когда текущий URL остаётся точным URL доверенной оболочки.
- Поиск обычного текста адресной строки направляется в DuckDuckGo; явные не-web схемы отклоняются, а не превращаются в поисковый запрос.
- DownloadManager больше не получает сессионные cookies WebView, поскольку redirect-цепочка может увести секреты. Публичные загрузки поддерживаются; для авторизованных загрузок понадобится специальный redirect-aware downloader. Для Android 8.1 и ниже добавлена запрашиваемая runtime-пермиссия записи в публичную Downloads-папку (проверена на уровне исходного контракта; устройство не запускалось).
- Исправлена интерполяция текста сетевых/HTTP ошибок Kotlin и сохранение TLS/Safe Browsing ошибок в статусе. Добавлены регрессионные assertions.
- Удалена устаревшая метка v0.35 на placeholder-экране PTY.

## Решение QA

**HTML/UI regression: PASS. Source contracts: PASS. Native Android build: NOT TESTED. Release: NOT READY.**
