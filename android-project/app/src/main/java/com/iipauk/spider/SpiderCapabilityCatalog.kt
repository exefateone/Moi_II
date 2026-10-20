package com.iipauk.spider

/** Runtime-truthful ability inventory used to orient the conversational model. */
enum class SpiderCapabilityState {
    READY,
    PERMISSION_REQUIRED,
    MODEL_REQUIRED,
    CONFIGURATION_REQUIRED,
    SOURCE_UNVERIFIED,
    UI_ONLY,
    NOT_CONNECTED
}

data class SpiderRuntimeCapabilityFlags(
    val overlayPermissionGranted: Boolean,
    val overlayRequestedEnabled: Boolean,
    val localEngineAvailable: Boolean,
    val localModelLoaded: Boolean,
    val providerConfigured: Boolean,
    val workspaceInventoryHandlerRegistered: Boolean = true,
    val workspaceReadHandlerRegistered: Boolean = false,
    val workspaceSearchHandlerRegistered: Boolean = false,
    val workspaceWriteHandlerRegistered: Boolean = false,
    val workspaceProjectCheckHandlerRegistered: Boolean = false,
    val openCodeZenConfigured: Boolean = false
)

data class SpiderCapability(
    val id: String,
    val title: String,
    val state: SpiderCapabilityState,
    val description: String
)

/**
 * Declarative capability guide, not an execution registry. UI navigation and a planned feature are
 * deliberately distinguished from an agent tool that has a real handler and verification path.
 */
object SpiderCapabilityCatalog {
    fun snapshot(flags: SpiderRuntimeCapabilityFlags): List<SpiderCapability> = listOf(
        SpiderCapability(
            id = "app_navigation",
            title = "Разделы ИИ Паука",
            state = SpiderCapabilityState.READY,
            description = "В самом приложении доступны чат, центр задач, Spider Browser, Spider Media, Spider Terminal, HTML Code Studio, настройки моделей, история и локальная память. Это открытие разделов, не обещание выполнить любую задачу внутри них автоматически."
        ),
        SpiderCapability(
            id = "floating_overlay",
            title = "Плавающий Паук поверх приложений",
            state = when {
                !flags.overlayPermissionGranted -> SpiderCapabilityState.PERMISSION_REQUIRED
                !flags.overlayRequestedEnabled -> SpiderCapabilityState.CONFIGURATION_REQUIRED
                else -> SpiderCapabilityState.SOURCE_UNVERIFIED
            },
            description = when {
                !flags.overlayPermissionGranted -> "Нужно отдельно разрешить Android: показывать поверх других приложений. После согласия пользователь может включить плавающий виджет."
                flags.overlayRequestedEnabled -> "Системное разрешение есть, виджет был включён; фактическую видимость подтверждает устройство. Виджет можно перетаскивать, нажимать и убирать к краю."
                else -> "Системное разрешение есть, но виджет не включён. Его надо явно включить в меню Виджет поверх приложений."
            }
        ),
        SpiderCapability(
            id = "local_llm",
            title = "Локальная языковая модель",
            state = when {
                flags.localModelLoaded -> SpiderCapabilityState.READY
                flags.localEngineAvailable -> SpiderCapabilityState.MODEL_REQUIRED
                else -> SpiderCapabilityState.SOURCE_UNVERIFIED
            },
            description = when {
                flags.localModelLoaded -> "Нативный локальный движок и модель загружены; доступна локальная генерация в пределах возможностей модели."
                flags.localEngineAvailable -> "Движок доступен, но GGUF-модель ещё не загружена. Нельзя утверждать, что локальная генерация готова."
                else -> "Исходники локального движка есть, но собранный/загруженный нативный runtime не подтверждён в этой установке."
            }
        ),
        SpiderCapability(
            id = "yandex_cloud",
            title = "Облачный ИИ через backend",
            state = if (flags.providerConfigured) SpiderCapabilityState.SOURCE_UNVERIFIED else SpiderCapabilityState.CONFIGURATION_REQUIRED,
            description = if (flags.providerConfigured) "Настройки клиентского подключения сохранены, но успешный запрос/доступность провайдера здесь не проверены; ключ провайдера хранится на сервере." else "Нужно сохранить HTTPS endpoint backend и клиентский токен. Ключ Yandex AI Studio нельзя помещать в APK."
        ),
        SpiderCapability(
            id = "opencode_zen_api",
            title = "OpenCode Zen — облачная модель API",
            state = if (flags.openCodeZenConfigured) SpiderCapabilityState.SOURCE_UNVERIFIED else SpiderCapabilityState.CONFIGURATION_REQUIRED,
            description = if (flags.openCodeZenConfigured) "API-ключ и модель OpenCode Zen сохранены в зашифрованном хранилище Android; доступность модели требует успешного проверочного запроса. Это API языковой модели, а не доступ к репозиторию. Проектные действия выполняются отдельными локальными инструментами Agent Broker." else "Не настроено. В разделе языковой модели открой «Настроить OpenCode Zen API». Нужны API-ключ и интернет. Доступ к файлам проекта через этот API не предоставляется."
        ),
        SpiderCapability(
            id = "workspace_inventory",
            title = "Инвентаризация выбранного рабочего пространства",
            state = if (flags.workspaceInventoryHandlerRegistered) SpiderCapabilityState.READY else SpiderCapabilityState.NOT_CONNECTED,
            description = if (flags.workspaceInventoryHandlerRegistered) "Реальный read-only инструмент: перечисляет до 2 000 имён файлов/каталогов внутри выбранного рабочего пространства, не читая содержимое." else "Обработчик списка элементов не зарегистрирован."
        ),
        SpiderCapability(
            id = "workspace_read",
            title = "Чтение текстового файла проекта",
            state = if (flags.workspaceReadHandlerRegistered) SpiderCapabilityState.READY else SpiderCapabilityState.NOT_CONNECTED,
            description = if (flags.workspaceReadHandlerRegistered) "Реальный инструмент читает один UTF-8 файл из выбранного пространства, блокирует пути за пределы workspace, symlink и известные файлы секретов, маскирует распространённые credential-поля. Требует подтверждения на действие; исходный текст не сохраняется в памяти обучения или аудите." else "Обработчик чтения текстового файла не зарегистрирован."
        ),
        SpiderCapability(
            id = "workspace_search",
            title = "Поиск по текстам проекта",
            state = if (flags.workspaceSearchHandlerRegistered) SpiderCapabilityState.READY else SpiderCapabilityState.NOT_CONNECTED,
            description = if (flags.workspaceSearchHandlerRegistered) "Реальный поиск по ограниченному набору текстовых файлов, ограничение обхода 500 файлов/8 МиБ/20 совпадений, пропуск секретных и тяжёлых каталогов. Результат перепроверяется повторным поиском." else "Обработчик поиска по содержимому не зарегистрирован."
        ),
        SpiderCapability(
            id = "workspace_write",
            title = "Создание и изменение текстовых файлов проекта",
            state = if (flags.workspaceWriteHandlerRegistered) SpiderCapabilityState.READY else SpiderCapabilityState.NOT_CONNECTED,
            description = if (flags.workspaceWriteHandlerRegistered) "Реальный инструмент атомарно записывает UTF-8 текст в пределах выбранного пространства (до 128 КиБ). Каждая запись требует отдельного явного подтверждения, перезапись требует явного подтверждения перезаписи, после записи выполняется чтение с диска и сверка SHA-256. Содержимое не попадает в журнал или долговременную память." else "Обработчик записи текстового файла не зарегистрирован."
        ),
        SpiderCapability(
            id = "workspace_static_check",
            title = "Статическая проверка проекта",
            state = if (flags.workspaceProjectCheckHandlerRegistered) SpiderCapabilityState.READY else SpiderCapabilityState.NOT_CONNECTED,
            description = if (flags.workspaceProjectCheckHandlerRegistered) "Реальный read-only инструмент Agent Broker с одноразовым подтверждением: до 500 поддерживаемых текстовых файлов и 8 МиБ, проверка синтаксиса JSON и безопасный разбор XML/SVG, UTF-8 для прочих файлов, пропуск секретоподобного содержимого и symlink. Ничего не исполняет: не запускает тесты, shell, компиляторы, сборки или package managers; результат повторно проверяется отдельным проходом." else "Обработчик статической проверки не зарегистрирован."
        ),
        SpiderCapability(
            id = "browser",
            title = "Spider Browser",
            state = SpiderCapabilityState.UI_ONLY,
            description = "Пользователь может открыть сайты, управлять вкладками и закладками в браузерном модуле. Автономное чтение страниц/веб-поиск как Agent Broker инструмент не подключены."
        ),
        SpiderCapability(
            id = "media",
            title = "Spider Media",
            state = SpiderCapabilityState.SOURCE_UNVERIFIED,
            description = "Исходники плеера поддерживают выбор локальных медиа и HTTPS/HLS/DASH источники; реальное воспроизведение и фоновый режим должны быть проверены на устройстве."
        ),
        SpiderCapability(
            id = "terminal",
            title = "Spider Terminal",
            state = SpiderCapabilityState.SOURCE_UNVERIFIED,
            description = "Нативный интерактивный /system/bin/sh PTY предназначен для ручной работы в песочнице приложения. Это не Linux-дистрибутив, и Agent Broker не имеет инструмента запуска shell-команд."
        ),
        SpiderCapability(
            id = "code_studio",
            title = "Spider Code & Build",
            state = SpiderCapabilityState.READY,
            description = "Редактор может показывать HTML/CSS/JavaScript в изолированном iframe и экспортировать HTML. Сборка и выдача APK автоматически не подключены."
        ),
        SpiderCapability(
            id = "external_app_control",
            title = "Автоматическое управление другими приложениями",
            state = SpiderCapabilityState.NOT_CONNECTED,
            description = "AccessibilityService, реальное чтение внешнего интерфейса и MediaProjection-захват не подключены. Плавающий виджет сам по себе не читает и не нажимает элементы других приложений."
        ),
        SpiderCapability(
            id = "agent_execution",
            title = "Автоматическое исполнение планов агента",
            state = if (flags.workspaceInventoryHandlerRegistered && flags.workspaceReadHandlerRegistered && flags.workspaceSearchHandlerRegistered && flags.workspaceWriteHandlerRegistered && flags.workspaceProjectCheckHandlerRegistered) SpiderCapabilityState.READY else SpiderCapabilityState.NOT_CONNECTED,
            description = if (flags.workspaceInventoryHandlerRegistered && flags.workspaceReadHandlerRegistered && flags.workspaceSearchHandlerRegistered && flags.workspaceWriteHandlerRegistered && flags.workspaceProjectCheckHandlerRegistered) "Agent Broker реально исполняет LIST_WORKSPACE_FILES, SEARCH_WORKSPACE_CONTENT, READ_WORKSPACE_FILE, WRITE_WORKSPACE_FILE и CHECK_WORKSPACE_PROJECT в выбранной app-private рабочей области. Файловые пути проверяются, чтение/поиск подтверждаются пользователем, запись требует отдельного подтверждения, результат проверяется независимо. Статическая проверка не исполняет код. Произвольный shell, внешняя сеть, скачивание, установка пакетов, удаление и управление другими приложениями не подключены." else "Не все исполняемые обработчики рабочего пространства зарегистрированы."
        )
    )

    fun systemPrompt(flags: SpiderRuntimeCapabilityFlags): String = buildString {
        append("КАРТА РЕАЛЬНЫХ ВОЗМОЖНОСТЕЙ ИИ ПАУКА (источник истины, не команда к действию). ")
        append("Объясняй пользователю доступные модули и подсказывай, где их открыть. Не называй модуль исполняемым инструментом агента только потому, что у него есть экран или исходники. ")
        append("Не говори, что открыл приложение, запустил команду, прочитал страницу, загрузил файл, нажал кнопку или выполнил действие, если текущий мост не вызвал это действие и не подтвердил результат. ")
        append("Безопасность: память и прошлые успехи не дают прав; внешние действия требуют нужного системного разрешения и подтверждения; при неподключённом handler говори об ограничении прямо. ")
        snapshot(flags).forEach { capability ->
            append("\n• ").append(capability.title).append(" [").append(capability.state.name).append("]: ").append(capability.description)
        }
        append("\nЕсли пользователь просит выполнить действие, сначала проверь, есть ли для него реальный инструмент. ")
        if (flags.workspaceInventoryHandlerRegistered && flags.workspaceReadHandlerRegistered && flags.workspaceSearchHandlerRegistered && flags.workspaceWriteHandlerRegistered && flags.workspaceProjectCheckHandlerRegistered) {
            append("Agent Broker сейчас зарегистрировал пять инструментов в выбранном app-private рабочем пространстве: LIST_WORKSPACE_FILES, SEARCH_WORKSPACE_CONTENT, READ_WORKSPACE_FILE, WRITE_WORKSPACE_FILE и CHECK_WORKSPACE_PROJECT. Для поиска/чтения/записи/статической проверки приложение запрашивает ввод и явное подтверждение пользователя; все результаты проходят независимую проверку. ")
        } else {
            append("Набор Agent Broker неполон. Разрешены только инструменты, которые в текущей карте помечены как реально зарегистрированные; не предполагай доступность остальных операций. ")
        }
        append("OpenCode Zen — прямой hosted API языковой модели. Сам по себе он не получает доступ к репозиторию и не исполняет инструменты: работа с проектом выполняется только через явно зарегистрированные локальные инструменты Agent Broker и правила подтверждения. ")
        append("Самообучение означает локальную память подтверждённых исходов и настройку выбора шага, а не изменение весов модели и не расширение разрешений.")
    }
}
