package com.iipauk.spider

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID

/** Native task UI for provider-independent planning and explicitly approved, verified workspace file tools. */
class SpiderAgentActivity : Activity() {
    private lateinit var runtime: SpiderAgentRuntime
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var goalInput: EditText
    private lateinit var statusView: TextView
    private lateinit var planView: TextView
    private lateinit var saveButton: Button
    private lateinit var planButton: Button
    private lateinit var clearButton: Button
    private lateinit var workspaceSpinner: Spinner
    private lateinit var createWorkspaceButton: Button
    private lateinit var executeStepButton: Button
    private lateinit var refreshHistoryButton: Button
    private lateinit var taskHistorySummary: TextView
    private lateinit var taskHistoryList: LinearLayout
    private lateinit var learningSummary: TextView
    private lateinit var learningToggleButton: Button
    private lateinit var refreshLearningButton: Button
    private lateinit var memoryDiagnosticsButton: Button
    private lateinit var viewLearningButton: Button
    private lateinit var clearLearningButton: Button
    private lateinit var cloudSemanticToggleButton: Button
    private lateinit var skillSummary: TextView
    private lateinit var viewSkillsButton: Button
    private lateinit var workspaceChoices: List<WorkspaceChoice> = emptyList()
    @Volatile private var historyRefreshGeneration: Int = 0
    private var currentTaskId: String? = null
    private var currentStepId: String? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = SpiderAgentRuntime.get(applicationContext)
        title = "ИИ Паук — задачи"
        window.statusBarColor = Color.rgb(22, 19, 31)
        window.navigationBarColor = Color.rgb(22, 19, 31)
        setContentView(buildLayout())
        endpointInput.setText(runtime.providerEndpoint().orEmpty())
        refreshWorkspaceChoices()
        refreshConfiguredStatus()
        refreshTaskHistory()
        refreshLearningMemory()
        refreshSkillLibrary()
    }

    private fun buildLayout(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 16, 25)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(content)
        content.addView(text("🕷  ИИ Паук · Центр задач", 23f, Color.rgb(232, 220, 255), true))
        content.addView(text(
            "ИИ предлагает типизированный план и может подготовить правки небольших текстовых файлов в выбранном рабочем пространстве. Перед записью показывается предпросмотр; изменения подтверждаются отдельно и защищены проверкой исходной версии. Произвольные shell-команды и запуск тестов агентом пока не подключены.",
            14f, Color.rgb(190, 181, 207), false, top = 8
        ))
        content.addView(button("Локальная LLM · режим Яндекс API · импорт GGUF").apply {
            setOnClickListener { startActivity(Intent(this@SpiderAgentActivity, SpiderLlmActivity::class.java)) }
        }, marginParams(top = 10))
        content.addView(text(
            "Текущий режим модели: ${runtime.llmMode().title}. Для локальной работы импортируй GGUF в разделе LLM; для облачной выбери режим Яндекс и настрой backend. API-ключ провайдера остаётся только на сервере.",
            12f, Color.rgb(174, 165, 191), false, top = 4
        ))
        content.addView(button("🧭 Показать карту возможностей и ограничений Паука").apply {
            setOnClickListener { showCapabilityMap() }
        }, marginParams(top = 10))
        content.addView(sectionTitle("1. Подключение сервиса ИИ"))
        content.addView(label("HTTPS-адрес backend endpoint"))
        endpointInput = EditText(this).apply {
            hint = "https://ваш-сервер.example/api/agent/plan"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setBackgroundColor(Color.rgb(40, 35, 50))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            filters = arrayOf(InputFilter.LengthFilter(2048))
        }
        content.addView(endpointInput, marginParams(top = 5))
        content.addView(label("Клиентский токен доступа к вашему backend"), marginParams(top = 10))
        tokenInput = EditText(this).apply {
            hint = "Вставь токен только для своего backend"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setBackgroundColor(Color.rgb(40, 35, 50))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            filters = arrayOf(InputFilter.LengthFilter(4096))
        }
        content.addView(tokenInput, marginParams(top = 5))
        content.addView(text(
            "Ключ провайдера модели сюда не вводится: он хранится только на сервере. Клиентский токен приложения шифруется Android Keystore.",
            12f, Color.rgb(174, 165, 191), false, top = 6
        ))
        saveButton = button("Сохранить подключение")
        content.addView(saveButton, marginParams(top = 10))
        saveButton.setOnClickListener { saveConnection() }
        clearButton = button("Забыть токен на этом устройстве")
        content.addView(clearButton, marginParams(top = 6))
        clearButton.setOnClickListener {
            runCatching { runtime.clearProviderConnection() }
            endpointInput.setText("")
            tokenInput.setText("")
            statusView.text = "Локальное подключение удалено. Сам токен на сервере нужно отозвать отдельно при необходимости."
        }

        content.addView(sectionTitle("2. Рабочее пространство"))
        content.addView(text(
            "Инструменты работают только во внутреннем пространстве приложения: перечисление имён, поиск по тексту, чтение текстового файла, атомарная запись и ограниченная статическая проверка JSON/XML/SVG/UTF-8. Поиск/чтение требуют подтверждения; запись требует явного согласия, а перезапись — отдельного подтверждения. Проверка не исполняет код, тесты или команды. Пути за пределы workspace, symlink и известные файлы с секретами блокируются.",
            13f, Color.rgb(190, 181, 207), false, top = 4
        ))
        workspaceSpinner = Spinner(this)
        content.addView(workspaceSpinner, marginParams(top = 8))
        createWorkspaceButton = button("Создать рабочее пространство")
        content.addView(createWorkspaceButton, marginParams(top = 6))
        createWorkspaceButton.setOnClickListener { showCreateWorkspaceDialog() }

        content.addView(sectionTitle("3. Задача"))
        goalInput = EditText(this).apply {
            hint = "Например: изучи структуру файлов проекта"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setBackgroundColor(Color.rgb(40, 35, 50))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            filters = arrayOf(InputFilter.LengthFilter(1200))
        }
        content.addView(goalInput, marginParams(top = 5))
        planButton = button("Сформировать план через ИИ")
        content.addView(planButton, marginParams(top = 10))
        planButton.setOnClickListener { planTask() }
        executeStepButton = button("Подтвердить и выполнить следующий шаг")
        executeStepButton.visibility = View.GONE
        content.addView(executeStepButton, marginParams(top = 8))
        executeStepButton.setOnClickListener { showExecutionConfirmation() }

        content.addView(sectionTitle("Статус"))
        statusView = text("Проверяем подключение…", 14f, Color.rgb(220, 211, 235), false)
        content.addView(statusView, marginParams(top = 5))
        planView = text("Планы и результаты появятся здесь.", 14f, Color.WHITE, false)
        planView.setBackgroundColor(Color.rgb(31, 27, 41))
        planView.setPadding(dp(12), dp(12), dp(12), dp(12))
        content.addView(planView, marginParams(top = 8))

        content.addView(sectionTitle("История задач"))
        content.addView(text(
            "История сохраняется на устройстве. В карточках и деталях скрываются URL, секреты и внутренние пути; для аудита показываются только безопасные ссылки на события.",
            12f, Color.rgb(174, 165, 191), false, top = 4
        ))
        taskHistorySummary = text("Загружаем сохранённые задачи…", 13f, Color.rgb(220, 211, 235), false, top = 8)
        content.addView(taskHistorySummary)
        refreshHistoryButton = button("Обновить историю")
        content.addView(refreshHistoryButton, marginParams(top = 6))
        refreshHistoryButton.setOnClickListener { refreshTaskHistory() }
        taskHistoryList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(taskHistoryList, marginParams(top = 4))

        content.addView(sectionTitle("Память и самообучение"))
        content.addView(text(
            "Общая локальная память опыта применяется независимо от выбранной модели: локальная GGUF, Яндекс AI Studio или OpenCode Zen. После завершения и проверки задачи Паук сохраняет безопасные признаки исхода и использованных инструментов; оценки 👍/👎 меняют вес опыта. Релевантная память участвует в следующих ответах и планах. В контекст облачной модели уходят только ограниченные типизированные метаданные, без тематических слов памяти, содержимого файлов, полного архива памяти или ответов модели. Отдельная настройка облачных embeddings выключена по умолчанию и при явном согласии может отправлять очищенные тематические слова на настроенный backend. Это адаптация поведения через память, не изменение весов подключённой модели. Память не выдаёт разрешения и не меняет правила безопасности.",
            12f, Color.rgb(174, 165, 191), false, top = 4
        ))
        learningSummary = text("Загружаем память…", 13f, Color.rgb(220, 211, 235), false, top = 8)
        content.addView(learningSummary)
        learningToggleButton = button("Самообучение: проверяем…")
        content.addView(learningToggleButton, marginParams(top = 6))
        learningToggleButton.setOnClickListener { toggleLearning() }
        refreshLearningButton = button("Обновить память")
        content.addView(refreshLearningButton, marginParams(top = 6))
        refreshLearningButton.setOnClickListener { refreshLearningMemory() }
        memoryDiagnosticsButton = button("Диагностика памяти и поиска")
        content.addView(memoryDiagnosticsButton, marginParams(top = 6))
        memoryDiagnosticsButton.setOnClickListener { showMemoryDiagnostics() }
        viewLearningButton = button("Просмотреть сохранённый опыт")
        content.addView(viewLearningButton, marginParams(top = 6))
        viewLearningButton.setOnClickListener { showLearningMemory() }
        clearLearningButton = button("Удалить всю память обучения")
        content.addView(clearLearningButton, marginParams(top = 6))
        clearLearningButton.setOnClickListener { confirmClearLearningMemory() }
        content.addView(text(
            "Локальный векторный поиск работает без сети. Дополнительно можно включить нейросетевые embeddings на твоём настроенном backend: туда отправятся только очищенные тематические слова, не полный текст задачи. По умолчанию эта опция выключена.",
            12f, Color.rgb(174, 165, 191), false, top = 8
        ))
        cloudSemanticToggleButton = button("Облачные embeddings: проверяем…")
        content.addView(cloudSemanticToggleButton, marginParams(top = 6))
        cloudSemanticToggleButton.setOnClickListener { toggleCloudSemanticSearch() }

        content.addView(sectionTitle("Библиотека проверяемых навыков"))
        content.addView(text(
            "Навык — это типизированная последовательность поддерживаемых инструментов, а не исполняемый скрипт. Навык станет проверенным только после нескольких независимых успешных запусков; он не отменяет разрешения и подтверждения.",
            12f, Color.rgb(174, 165, 191), false, top = 4
        ))
        skillSummary = text("Загружаем библиотеку навыков…", 13f, Color.rgb(220, 211, 235), false, top = 8)
        content.addView(skillSummary)
        viewSkillsButton = button("Просмотреть навыки и доказательства")
        content.addView(viewSkillsButton, marginParams(top = 6))
        viewSkillsButton.setOnClickListener { showSkillLibrary() }

        val back = button("Закрыть")
        content.addView(back, marginParams(top = 16))
        back.setOnClickListener { finish() }
        return scroll
    }

    private fun showCapabilityMap() {
        val lines = SpiderCapabilityCatalog.snapshot(runtime.capabilityFlags()).joinToString("\n\n") { capability ->
            val label = when (capability.state) {
                SpiderCapabilityState.READY -> "ДОСТУПНО В ПРИЛОЖЕНИИ"
                SpiderCapabilityState.PERMISSION_REQUIRED -> "НУЖНО РАЗРЕШЕНИЕ"
                SpiderCapabilityState.MODEL_REQUIRED -> "НУЖНА МОДЕЛЬ"
                SpiderCapabilityState.CONFIGURATION_REQUIRED -> "НУЖНА НАСТРОЙКА"
                SpiderCapabilityState.SOURCE_UNVERIFIED -> "НЕ ПРОВЕРЕНО НА УСТРОЙСТВЕ"
                SpiderCapabilityState.UI_ONLY -> "ТОЛЬКО РУЧНОЙ МОДУЛЬ"
                SpiderCapabilityState.NOT_CONNECTED -> "ИНСТРУМЕНТ НЕ ПОДКЛЮЧЁН"
            }
            "${capability.title} — $label\n${capability.description}"
        }
        val body = TextView(this).apply {
            text = lines
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(body)
        }
        AlertDialog.Builder(this)
            .setTitle("Карта возможностей ИИ Паука")
            .setView(scroll)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun saveConnection() {
        val endpoint = endpointInput.text.toString().trim()
        val token = tokenInput.text.toString()
        try {
            if (token.isNotBlank()) {
                runtime.saveProviderConnection(endpoint, token)
                tokenInput.setText("")
                statusView.text = "Подключение сохранено. Токен зашифрован в Android Keystore."
            } else if (runtime.isProviderConfigured()) {
                runtime.updateProviderEndpoint(endpoint)
                statusView.text = "Адрес обновлён. Ранее сохранённый токен сохранён в зашифрованном виде."
            } else {
                throw IllegalArgumentException("Введи клиентский токен backend (минимум 24 символа).")
            }
        } catch (error: Exception) {
            statusView.text = "Не удалось сохранить подключение: ${safeMessage(error)}"
        }
        refreshConfiguredStatus()
    }

    private fun planTask() {
        val goal = goalInput.text.toString().trim()
        if (goal.isBlank()) {
            statusView.text = "Сначала опиши задачу."
            return
        }
        // Read selection on the UI thread and freeze it into the task's scope before dispatch.
        val workspaceScopeId = selectedWorkspace()?.id
        saveButton.isEnabled = false
        planButton.isEnabled = false
        executeStepButton.visibility = View.GONE
        currentTaskId = null
        currentStepId = null
        statusView.text = "ИИ формирует план. Инструменты не запускаются."
        planView.text = "Ожидание ответа сервиса…"
        worker.execute {
            val result = runCatching {
                // Create the durable task first. Planner setup/provider failures then flow through
                // planTask and are recorded as a safe FAILED history entry instead of disappearing.
                val task = runtime.taskCoordinator.createTask(goal, workspaceScopeId = workspaceScopeId)
                val planner = SpiderTaskPlanner { candidate ->
                    runtime.createTaskPlanner().proposePlan(candidate)
                }
                runtime.taskCoordinator.planTask(task.id, planner)
            }
            runOnUiThread {
                saveButton.isEnabled = true
                planButton.isEnabled = true
                result.fold(
                    onSuccess = { task ->
                        currentTaskId = task.id
                        val candidate = task.steps.firstOrNull { it.status == SpiderStepStatus.PENDING }
                        currentStepId = candidate?.id
                        executeStepButton.visibility = if (
                            task.status == SpiderTaskStatus.RUNNING && candidate?.toolId?.let { it in SUPPORTED_WORKSPACE_TOOLS } == true
                        ) View.VISIBLE else View.GONE
                        executeStepButton.text = candidate?.let { "Подтвердить и выполнить: ${toolShortName(it.toolId)}" } ?: "Нет ожидающего шага"
                        statusView.text = "Задача ${task.id} · статус ${task.status} · ${task.steps.size} шагов."
                        planView.text = buildString {
                            append("Цель: ").append(safeHistoryText(task.goal, 500)).append("\n\n")
                            if (task.steps.isEmpty()) {
                                append(task.resultSummary ?: "План не создан.")
                            } else {
                                task.steps.forEachIndexed { index, step ->
                                    append(index + 1).append(". ").append(safeHistoryText(step.title, 200))
                                        .append("\n   Инструмент: ").append(step.toolId)
                                        .append("\n   Статус: ").append(step.status).append("\n\n")
                                }
                                if (executeStepButton.visibility == View.VISIBLE) {
                                    append("Каждый шаг запускается отдельно: приложение запросит нужный путь, поисковую фразу или текст записи и покажет подтверждение. Путь и текст не исполняются как shell-команда.")
                                } else {
                                    append("Исполнение доступно только для перечисленных выше типизированных workspace-инструментов. Если план содержит иной инструмент, он не будет запущен.")
                                }
                            }
                            if (task.lastErrorCode != null) append("\nКод ошибки: ").append(task.lastErrorCode)
                            val skillRecommendations = runtime.skillLibrary.recommend(task.goal, workspaceSelected = task.workspaceScopeId != null)
                            if (skillRecommendations.isNotEmpty()) {
                                append("\n\nРекомендуемые проверенные навыки (не запускаются автоматически):")
                                skillRecommendations.forEach { recommendation ->
                                    append("\n• ").append(recommendation.skill.title)
                                        .append(" · доказанные успехи ").append(recommendation.skill.verifiedRuns)
                                        .append(" · релевантность ").append((recommendation.relevanceScore * 100).toInt()).append("%")
                                }
                            } else {
                                append("\n\nПроверенный навык пока не найден. Кандидаты не рекомендуются до подтверждения независимыми проверками.")
                            }
                        }
                    },
                    onFailure = { error ->
                        statusView.text = "Не удалось сформировать план: ${safeHistoryText(safeMessage(error), 240)}"
                        planView.text = "Проверь HTTPS-адрес backend, токен доступа, конфигурацию модели на сервере и сетевое подключение."
                    }
                )
                refreshTaskHistory()
            }
        }
    }

    private fun refreshWorkspaceChoices(preferredId: String? = null) {
        if (!::workspaceSpinner.isInitialized) return
        val items = runtime.listWorkspaces().map { WorkspaceChoice(it.id, it.name) }
        workspaceChoices = listOf(WorkspaceChoice("", "— выбери рабочее пространство —")) + items
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, workspaceChoices).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        workspaceSpinner.adapter = adapter
        val selected = workspaceChoices.indexOfFirst { it.id == preferredId }.takeIf { it >= 0 } ?: 0
        workspaceSpinner.setSelection(selected)
    }

    private fun selectedWorkspace(): WorkspaceChoice? =
        (workspaceSpinner.selectedItem as? WorkspaceChoice)?.takeIf { it.id.isNotBlank() }

    private fun showCreateWorkspaceDialog() {
        val input = EditText(this).apply {
            hint = "Например, Мой проект"
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(40))
        }
        AlertDialog.Builder(this)
            .setTitle("Новое рабочее пространство")
            .setMessage("Будет создана отдельная папка во внутреннем хранилище приложения.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                runCatching { runtime.createWorkspace(name) }
                    .onSuccess { workspace ->
                        refreshWorkspaceChoices(workspace.id)
                        statusView.text = "Рабочее пространство «${workspace.name}» создано."
                    }
                    .onFailure { error -> statusView.text = "Не удалось создать пространство: ${safeMessage(error)}" }
            }
            .show()
    }

    private fun showExecutionConfirmation() {
        val taskId = currentTaskId ?: run { statusView.text = "Сначала сформируй план."; return }
        val workspace = selectedWorkspace() ?: run {
            statusView.text = "Сначала выбери или создай рабочее пространство."
            return
        }
        val task = runtime.taskCoordinator.getTask(taskId) ?: run { statusView.text = "Задача больше не найдена."; return }
        if (task.workspaceScopeId != workspace.id) {
            statusView.text = "Рабочее пространство изменилось после планирования. Выбери исходное пространство или сформируй новый план."
            return
        }
        if (task.status != SpiderTaskStatus.RUNNING) {
            statusView.text = "Задача не находится в состоянии выполнения."
            return
        }
        val step = task.steps.firstOrNull { it.status == SpiderStepStatus.PENDING && it.toolId?.let { tool -> tool in SUPPORTED_WORKSPACE_TOOLS } == true }
            ?: run { statusView.text = "Нет ожидающего шага с подключённым исполнителем."; return }
        currentStepId = step.id
        when (step.toolId) {
            SpiderToolId.LIST_WORKSPACE_FILES -> confirmWorkspaceTool(task, step, workspace, workspace.id, null, false)
            SpiderToolId.READ_WORKSPACE_FILE -> promptForWorkspacePath(task, step, workspace, isWrite = false)
            SpiderToolId.SEARCH_WORKSPACE_CONTENT -> promptForSearchQuery(task, step, workspace)
            SpiderToolId.WRITE_WORKSPACE_FILE -> promptForWorkspacePath(task, step, workspace, isWrite = true)
            SpiderToolId.CHECK_WORKSPACE_PROJECT -> confirmWorkspaceTool(task, step, workspace, null, null, false)
            else -> statusView.text = "Для выбранного инструмента нет зарегистрированного обработчика."
        }
    }

    private fun promptForWorkspacePath(
        task: SpiderAgentTask,
        step: SpiderPlanStep,
        workspace: WorkspaceChoice,
        isWrite: Boolean
    ) {
        if (isWrite) {
            promptForAiWorkspaceEdit(task, step, workspace)
            return
        }
        val pathInput = EditText(this).apply {
            hint = "Например, README.md или src/main.kt"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(240))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setBackgroundColor(Color.rgb(40, 35, 50))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Какой файл прочитать?")
            .setMessage("Укажи относительный путь внутри «${safeHistoryText(workspace.name, 80)}». Абсолютные пути, переходы ../, символические ссылки и файлы с известными признаками секретов запрещены.")
            .setView(pathInput)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val path = pathInput.text.toString().trim()
                        if (path.isBlank()) {
                            pathInput.error = "Укажи путь относительно рабочего пространства"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        confirmWorkspaceTool(task, step, workspace, path, null, false)
                    }
                }
                dialog.show()
            }
    }

    private fun promptForSearchQuery(task: SpiderAgentTask, step: SpiderPlanStep, workspace: WorkspaceChoice) {
        val queryInput = EditText(this).apply {
            hint = "Текст или название, которое ищем"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(WorkspaceToolSafety.MAX_SEARCH_QUERY_CHARS))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setBackgroundColor(Color.rgb(40, 35, 50))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Поиск по файлам проекта")
            .setMessage("Будут просканированы только поддерживаемые текстовые файлы выбранного пространства (до 500 файлов/8 МиБ/20 совпадений). Каталоги сборки, .git и известные файлы с секретами пропускаются.")
            .setView(queryInput)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val query = queryInput.text.toString().trim()
                        if (query.isBlank()) {
                            queryInput.error = "Введи поисковую фразу"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        confirmWorkspaceTool(task, step, workspace, null, query, false)
                    }
                }
                dialog.show()
            }
    }

    /** Collects a workspace-relative path, then asks the selected model for a candidate file body. */
    private fun promptForAiWorkspaceEdit(task: SpiderAgentTask, step: SpiderPlanStep, workspace: WorkspaceChoice) {
        val pathInput = EditText(this).apply {
            hint = "Например, README.md или src/main.kt"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(240))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setBackgroundColor(Color.rgb(40, 35, 50))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val cloudContentConsent = if (runtime.workspaceEditMaySendContentToCloud()) CheckBox(this).apply {
            text = "Разрешаю отправить цель и конкретный шаг задачи, путь и исходный текст файла выбранной облачной модели для подготовки предложения. Содержимое не сохраняется в памяти Паука или журнале аудита."
            textSize = 13f
            setTextColor(Color.WHITE)
            isChecked = false
        } else null
        val entryLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(TextView(this@SpiderAgentActivity).apply {
                text = "ИИ подготовит полный вариант небольшого текстового файла по цели задачи. Перед записью ты увидишь предпросмотр; файл не меняется до отдельного подтверждения. Лимит исходника — ${SpiderWorkspaceEditParser.MAX_SOURCE_CHARS} символов."
                textSize = 14f
                setTextColor(Color.WHITE)
            })
            addView(pathInput, marginParams(top = 10))
            if (cloudContentConsent != null) addView(cloudContentConsent, marginParams(top = 8))
        }
        AlertDialog.Builder(this)
            .setTitle("Какой файл изменить с помощью ИИ?")
            .setView(entryLayout)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подготовить предложение", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val path = pathInput.text.toString().trim()
                        if (path.isBlank()) {
                            pathInput.error = "Укажи путь внутри выбранного рабочего пространства"
                            return@setOnClickListener
                        }
                        if (cloudContentConsent != null && !cloudContentConsent.isChecked) {
                            Toast.makeText(this, "Подтверди отправку данных выбранной облачной модели или отмени действие.", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        planButton.isEnabled = false
                        executeStepButton.isEnabled = false
                        statusView.text = "Проверяем исходник и готовим предложение. Записи пока нет."
                        worker.execute {
                            val outcome = runCatching {
                                val source = runtime.loadWorkspaceEditSource(workspace.id, path)
                                val proposal = runtime.proposeWorkspaceEdit(task.id, task.goal, step.title, path, source.content)
                                Triple(source, proposal, path)
                            }
                            runOnUiThread {
                                planButton.isEnabled = true
                                executeStepButton.isEnabled = true
                                outcome.fold(
                                    onSuccess = { (source, proposal, relativePath) ->
                                        if (isFinishing || isDestroyed) return@runOnUiThread
                                        statusView.text = "Предложение подготовлено моделью; файл ещё не изменён."
                                        showWorkspaceEditReview(task, step, workspace, relativePath, source, proposal)
                                    },
                                    onFailure = { error ->
                                        statusView.text = "Предложение не подготовлено: ${safeHistoryText(safeMessage(error), 280)}. Файл не изменён."
                                    }
                                )
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    /** Displays a bounded diff and binds approval to the exact source hash and exact proposed text. */
    private fun showWorkspaceEditReview(
        task: SpiderAgentTask,
        step: SpiderPlanStep,
        workspace: WorkspaceChoice,
        relativePath: String,
        source: SpiderWorkspaceEditSource,
        proposal: SpiderWorkspaceEditProposal
    ) {
        if (proposal.updatedContent == source.content) {
            statusView.text = "Модель не предложила фактических изменений. Файл не изменён."
            planView.text = safeHistoryText(proposal.summary, 500)
            return
        }
        val overwriteBox = if (source.expectedPreimageSha256 != "MISSING") CheckBox(this).apply {
            text = "Я явно разрешаю заменить эту версию файла после проверки предпросмотра"
            setTextColor(Color.WHITE)
            isChecked = false
        } else null
        val diffPreview = SpiderWorkspaceEditDiff.create(source.content, proposal.updatedContent)
        if (diffPreview == null) {
            statusView.text = "Предпросмотр превышает безопасный размер. Файл не изменён; сократи задачу или раздели её на небольшие изменения."
            planView.text = safeHistoryText(proposal.summary, 500)
            return
        }
        val detail = buildString {
            append("Задача: ").append(safeHistoryText(task.goal, 500)).append("\n")
            append("Шаг: ").append(safeHistoryText(step.title, 200)).append("\n")
            append("Рабочее пространство: ").append(safeHistoryText(workspace.name, 80)).append("\n")
            append("Файл: ").append(safeHistoryText(relativePath, 240)).append("\n")
            append("Предложение модели: ").append(safeHistoryText(proposal.summary, 240)).append("\n")
            append("Исходная версия: ").append(if (source.expectedPreimageSha256 == "MISSING") "файла не было" else "SHA-256 ").append("\n")
            if (source.expectedPreimageSha256 != "MISSING") append(source.expectedPreimageSha256).append("\n")
            append("Размер: ").append(source.content.toByteArray(Charsets.UTF_8).size).append(" → ")
                .append(proposal.updatedContent.toByteArray(Charsets.UTF_8).size).append(" байт UTF-8\n\n")
            append("ПРЕДПРОСМОТР — строки с «-» удаляются, с «+» добавляются:\n")
            append(diffPreview)
            append("\n\nТесты и сборка не запускались. После подтверждения приложение запишет текст атомарно и проверит SHA-256 сохранённой версии.")
        }
        val body = TextView(this).apply {
            text = detail
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (overwriteBox != null) addView(TextView(this@SpiderAgentActivity).apply {
                text = "Существующий файл не будет заменён, пока не отметишь отдельное согласие ниже."
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(8), dp(16), dp(4))
            })
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))
            if (overwriteBox != null) addView(overwriteBox, marginParams(top = 4))
        }
        AlertDialog.Builder(this)
            .setTitle("Проверь изменения перед записью")
            .setView(layout)
            .setNegativeButton("Отмена") { _, _ -> statusView.text = "Предложение отклонено; файл не изменён." }
            .setPositiveButton("Записать изменения", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (overwriteBox != null && !overwriteBox.isChecked) {
                            Toast.makeText(this, "Для существующего файла нужно отдельно подтвердить замену.", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        // A unique request ID is created only after the user approves this exact preview.
                        val requestId = UUID.randomUUID().toString()
                        dialog.dismiss()
                        executeApprovedWorkspaceTool(
                            taskId = task.id,
                            stepId = step.id,
                            workspace = workspace,
                            tool = SpiderToolId.WRITE_WORKSPACE_FILE,
                            resourceReference = relativePath,
                            inputText = proposal.updatedContent,
                            overwriteExisting = overwriteBox?.isChecked == true,
                            approvedRequestId = requestId,
                            expectedPreimageSha256 = source.expectedPreimageSha256
                        )
                    }
                }
                dialog.show()
            }
    }


    private fun promptForWriteContent(task: SpiderAgentTask, step: SpiderPlanStep, workspace: WorkspaceChoice, relativePath: String) {
        val contentInput = EditText(this).apply {
            hint = "Текст, который нужно записать в файл"
            minLines = 6
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(65_536))
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setBackgroundColor(Color.rgb(40, 35, 50))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Текст файла")
            .setMessage("Паук запишет ровно введённый текст UTF-8. Максимум 128 КиБ. Содержимое не попадёт в журнал аудита или долговременную память самообучения.")
            .setView(contentInput)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Проверить перед записью", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = contentInput.text.toString()
                        if (value.isBlank()) {
                            contentInput.error = "Нельзя записать пустое содержимое"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        confirmWorkspaceTool(task, step, workspace, relativePath, value, false)
                    }
                }
                dialog.show()
            }
    }

    private fun confirmWorkspaceTool(
        task: SpiderAgentTask,
        step: SpiderPlanStep,
        workspace: WorkspaceChoice,
        resourceReference: String?,
        inputText: String?,
        initialOverwrite: Boolean
    ) {
        val tool = step.toolId ?: run { statusView.text = "В шаге отсутствует типизированный инструмент."; return }
        if (tool !in SUPPORTED_WORKSPACE_TOOLS) {
            statusView.text = "Инструмент не входит в разрешённый список. Ничего не запускалось."
            return
        }
        val overwriteBox = if (tool == SpiderToolId.WRITE_WORKSPACE_FILE) CheckBox(this).apply {
            text = "Я явно разрешаю заменить существующий файл, если он уже есть"
            setTextColor(Color.WHITE)
            isChecked = initialOverwrite
        } else null
        val details = buildString {
            append("Задача: ").append(safeHistoryText(task.goal, 500)).append("\n")
            append("Шаг: ").append(safeHistoryText(step.title, 200)).append("\n")
            append("Инструмент: ").append(tool.name).append("\n")
            append("Рабочее пространство: ").append(safeHistoryText(workspace.name, 80)).append("\n")
            when (tool) {
                SpiderToolId.LIST_WORKSPACE_FILES -> append("Действие: перечислить имена файлов и каталогов; содержимое не читается.\n")
                SpiderToolId.READ_WORKSPACE_FILE -> append("Путь: ").append(safeHistoryText(resourceReference.orEmpty(), 240)).append("\nДействие: прочитать один текстовый файл, замаскировать очевидные секреты, показать вывод только на этом экране.\n")
                SpiderToolId.SEARCH_WORKSPACE_CONTENT -> append("Поисковая фраза: ").append(safeHistoryText(inputText.orEmpty(), 120)).append("\nДействие: локально искать по ограниченному набору текстовых файлов.\n")
                SpiderToolId.WRITE_WORKSPACE_FILE -> {
                    append("Путь: ").append(safeHistoryText(resourceReference.orEmpty(), 240)).append("\n")
                    append("Размер текста: ").append(inputText?.toByteArray(Charsets.UTF_8)?.size ?: 0).append(" байт.\n")
                    append("Предпросмотр (до 800 символов):\n").append(safeHistoryText(inputText.orEmpty(), 800)).append("\n")
                    append("Текст не будет записан в историю или память обучения.\n")
                }
                SpiderToolId.CHECK_WORKSPACE_PROJECT -> append("Действие: проверить ограниченное количество поддерживаемых текстовых файлов; JSON — по синтаксису, XML/SVG — безопасным парсером, прочие файлы — только по UTF-8. Секретоподобное содержимое, symlink и внутренние каталоги пропускаются. Ничего не запускается: нет shell, запуска проекта, компиляции, сборки или выполнения тестов. Результат будет повторён независимым контрольным проходом.\n")
                else -> Unit
            }
            append("\nЗапрос будет использован один раз; исполнение и результат запишутся в защищённый журнал без содержимого файла.")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            if (overwriteBox != null) {
                addView(TextView(this@SpiderAgentActivity).apply {
                    text = details
                    textSize = 14f
                    setTextColor(Color.WHITE)
                })
                addView(overwriteBox)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Подтверди действие")
            .apply { if (overwriteBox == null) setMessage(details) else setView(layout) }
            .setNegativeButton("Отмена") { _, _ -> statusView.text = "Действие отменено; инструмент не запускался." }
            .setPositiveButton("Разрешить один раз", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    // WRITE always requires an explicit per-call approval. Existing files also require
                    // ticking the separate overwrite checkbox; unchecked means fail-closed refusal.
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val exists = tool == SpiderToolId.WRITE_WORKSPACE_FILE && runtime.workspaceFileExists(workspace.id, resourceReference.orEmpty())
                        if (exists && overwriteBox?.isChecked != true) {
                            Toast.makeText(this, "Файл уже существует. Отметь явное согласие на перезапись.", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        val requestId = UUID.randomUUID().toString()
                        dialog.dismiss()
                        executeApprovedWorkspaceTool(task.id, step.id, workspace, tool, resourceReference, inputText,
                            overwriteExisting = overwriteBox?.isChecked == true, approvedRequestId = requestId)
                    }
                }
                dialog.show()
            }
    }

    private fun executeApprovedWorkspaceTool(
        taskId: String,
        stepId: String,
        workspace: WorkspaceChoice,
        tool: SpiderToolId,
        resourceReference: String?,
        inputText: String?,
        overwriteExisting: Boolean,
        approvedRequestId: String,
        expectedPreimageSha256: String? = null
    ) {
        executeStepButton.isEnabled = false
        planButton.isEnabled = false
        statusView.text = "Выполняется подтверждённый инструмент; проверяем путь и результат."
        worker.execute {
            val outcome = runCatching {
                runtime.taskCoordinator.startStep(taskId, stepId)
                runtime.taskCoordinator.requireApproval(taskId, stepId)
                runtime.taskCoordinator.resolveApproval(taskId, stepId, approved = true)
                runtime.taskCoordinator.startStep(taskId, stepId)
                val request = SpiderToolRequest(
                    requestId = approvedRequestId,
                    taskId = taskId,
                    toolId = tool,
                    summary = "Пользователь подтвердил одноразовое выполнение $tool в выбранном рабочем пространстве.",
                    resourceReference = if (tool == SpiderToolId.LIST_WORKSPACE_FILES) workspace.id else resourceReference,
                    workspaceId = workspace.id,
                    inputText = inputText,
                    overwriteExisting = overwriteExisting,
                    expectedPreimageSha256 = expectedPreimageSha256
                )
                val toolResult = runtime.toolBroker.execute(
                    request,
                    SpiderPolicyContext(approvedRequestIds = setOf(approvedRequestId))
                )
                val initialVerification = when (tool) {
                    SpiderToolId.LIST_WORKSPACE_FILES -> runtime.workspaceListingVerifier.verify(workspace.id, toolResult)
                    SpiderToolId.READ_WORKSPACE_FILE, SpiderToolId.SEARCH_WORKSPACE_CONTENT, SpiderToolId.WRITE_WORKSPACE_FILE ->
                        runtime.workspaceTextToolVerifier.verify(request, toolResult)
                    SpiderToolId.CHECK_WORKSPACE_PROJECT -> runtime.workspaceProjectCheckVerifier.verify(workspace.id, toolResult)
                    else -> SpiderResultVerification(false, "verifier_missing", "Для этого инструмента нет проверяющего контракта.")
                }
                val succeeded = toolResult.status == SpiderToolCallStatus.SUCCEEDED && toolResult.auditRecorded && initialVerification.verified
                val combinedSummary = if (succeeded) initialVerification.summary else
                    "${toolResult.message} Проверка: ${initialVerification.summary}"
                val taskAfterStep = runtime.taskCoordinator.recordStepResult(
                    taskId, stepId, succeeded = succeeded, summary = combinedSummary,
                    errorCode = if (succeeded) null else if (toolResult.status != SpiderToolCallStatus.SUCCEEDED) toolResult.resultCode else initialVerification.code
                )
                val verification = if (taskAfterStep.status == SpiderTaskStatus.VERIFYING) {
                    runtime.taskCoordinator.finishVerification(
                        taskId, verified = true,
                        summary = "Все шаги выполнены; результаты инструментов независимо проверены. Содержимое файлов не записывалось в память опыта."
                    ).let { SpiderResultVerification(it.status == SpiderTaskStatus.COMPLETED, if (it.status == SpiderTaskStatus.COMPLETED) "task_verified" else (it.lastErrorCode ?: "task_verification_failed"), it.resultSummary ?: "Итоговая проверка не пройдена.") }
                } else initialVerification
                val finalTask = runtime.taskCoordinator.getTask(taskId) ?: taskAfterStep
                // The internal brain promotes only successful, independently verified real actions.
                // Learning pause disables both episodic memory and verified-skill evidence recording.
                if (succeeded && runtime.isLearningEnabled()) {
                    runCatching { runtime.skillLibrary.recordVerifiedBuiltInToolSuccess(tool, finalTask.id) }
                }
                ExecutionSummary(finalTask, tool, toolResult.status, combinedSummary, toolResult.auditRecorded, workspace.id, verification, toolResult.outputPayload)
            }
            runOnUiThread {
                executeStepButton.isEnabled = true
                planButton.isEnabled = true
                outcome.fold(
                    onSuccess = { result ->
                        statusView.text = "Задача ${result.task.id} · ${statusLabel(result.task.status)}. Инструмент: ${result.toolId}; аудит: ${if (result.auditRecorded) "записан" else "не подтверждён"}."
                        planView.text = buildString {
                            append("Цель: ").append(safeHistoryText(result.task.goal, 500)).append("\n")
                            append("Рабочее пространство: ").append(safeHistoryText(workspace.name, 80)).append("\n")
                            append("Результат: ").append(safeHistoryText(result.message, 600)).append("\n")
                            append("Проверка: ").append(safeHistoryText(result.verification.summary, 400)).append("\n")
                            result.outputPayload?.let { output ->
                                append("\nВывод инструмента (показан только здесь; не сохраняется в память опыта):\n")
                                append(output.take(MAX_TOOL_OUTPUT_DISPLAY_CHARS))
                                if (output.length > MAX_TOOL_OUTPUT_DISPLAY_CHARS) append("\n… вывод обрезан только для отображения; внутренний размер был проверен.")
                            }
                            if (result.task.status == SpiderTaskStatus.COMPLETED) {
                                append("\n\nЗадача завершена: каждый инструмент и итог независимо проверены; вывод файлов не сохранён в памяти.")
                            } else if (result.task.status == SpiderTaskStatus.RUNNING) {
                                val next = result.task.steps.firstOrNull { it.status == SpiderStepStatus.PENDING }
                                append("\n\nШаг выполнен. Следующий шаг: ").append(safeHistoryText(next?.title ?: "не найден", 200))
                            } else {
                                append("\n\nЗадача не отмечена завершённой. Проверь результат и код ошибки; автоматического повтора не будет.")
                            }
                        }
                        val nextStep = result.task.steps.firstOrNull { it.status == SpiderStepStatus.PENDING && it.toolId?.let { tool -> tool in SUPPORTED_WORKSPACE_TOOLS } == true }
                        currentStepId = nextStep?.id
                        executeStepButton.visibility = if (result.task.status == SpiderTaskStatus.RUNNING && nextStep != null) View.VISIBLE else View.GONE
                        executeStepButton.text = nextStep?.let { "Подтвердить и выполнить: ${toolShortName(it.toolId)}" } ?: "Все шаги обработаны"
                    },
                    onFailure = { error ->
                        statusView.text = "Действие не завершено штатно: ${safeHistoryText(safeMessage(error), 240)}"
                        planView.text = "Проверь состояние сохранённой задачи и журнала аудита. Автоматический повтор не запускается."
                        executeStepButton.visibility = View.GONE
                    }
                )
                refreshTaskHistory()
                refreshLearningMemory()
            }
        }
    }

    private fun toolShortName(tool: SpiderToolId?): String = when (tool) {
        SpiderToolId.LIST_WORKSPACE_FILES -> "список файлов"
        SpiderToolId.SEARCH_WORKSPACE_CONTENT -> "поиск по тексту"
        SpiderToolId.READ_WORKSPACE_FILE -> "чтение файла"
        SpiderToolId.WRITE_WORKSPACE_FILE -> "запись файла"
        SpiderToolId.CHECK_WORKSPACE_PROJECT -> "статическая проверка проекта"
        else -> "недоступный инструмент"
    }

    private companion object {
        val SUPPORTED_WORKSPACE_TOOLS = setOf(
            SpiderToolId.LIST_WORKSPACE_FILES,
            SpiderToolId.SEARCH_WORKSPACE_CONTENT,
            SpiderToolId.READ_WORKSPACE_FILE,
            SpiderToolId.WRITE_WORKSPACE_FILE,
            SpiderToolId.CHECK_WORKSPACE_PROJECT
        )
        const val MAX_TOOL_OUTPUT_DISPLAY_CHARS = 20_000
    }

    private fun refreshTaskHistory() {
        if (!::taskHistoryList.isInitialized || isFinishing || isDestroyed) return
        val generation = ++historyRefreshGeneration
        refreshHistoryButton.isEnabled = false
        taskHistorySummary.text = "Обновляем историю…"
        worker.execute {
            val snapshot = runCatching {
                runtime.taskCoordinator.taskCount() to runtime.taskCoordinator.listRecentTasks(30)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != historyRefreshGeneration) return@runOnUiThread
                refreshHistoryButton.isEnabled = true
                val history = snapshot.getOrNull()
                if (history == null) {
                    taskHistorySummary.text = "Не удалось прочитать историю. Данные не сбрасывались; проверь доступность хранилища."
                    taskHistoryList.removeAllViews()
                    return@runOnUiThread
                }
                val totalTaskCount = history.first
                val tasks = history.second
                taskHistoryList.removeAllViews()
                val recoveryNotes = buildList {
                    if (runtime.taskRecoveryBlocked) add("Хранилище задач требует проверки. Новые действия могут быть заблокированы.")
                    if (runtime.recoveredTasksAtStartup.isNotEmpty()) add(
                        "После перезапуска безопасно остановлено задач: ${runtime.recoveredTasksAtStartup.size}. Автоматический повтор не выполнялся."
                    )
                    runtime.taskRepository.recoveryNotice?.let { add(safeHistoryText(it, 180)) }
                    runtime.taskRepository.lastStorageError?.let { add(safeHistoryText(it, 180)) }
                    if (isEmpty()) add("Восстановление: незавершённые задачи не запускаются автоматически.")
                }
                taskHistorySummary.text = "Сохранено задач: $totalTaskCount. Показаны последние ${tasks.size}.\n${recoveryNotes.joinToString(" ")}"
                if (tasks.isEmpty()) {
                    taskHistoryList.addView(text("История пока пуста. Создай первую задачу выше.", 14f, Color.rgb(190, 181, 207), false, top = 8))
                } else {
                    tasks.forEach { task -> taskHistoryList.addView(buildTaskHistoryCard(task), marginParams(top = 6)) }
                }
                refreshLearningMemory()
            }
        }
    }

    private fun buildTaskHistoryCard(task: SpiderAgentTask): View {
        val completedSteps = task.steps.count { it.status == SpiderStepStatus.SUCCEEDED }
        val totalSteps = task.steps.size
        val progress = if (totalSteps == 0) {
            when (task.status) {
                SpiderTaskStatus.COMPLETED -> "Проверено"
                SpiderTaskStatus.FAILED -> "Завершилась с ошибкой"
                SpiderTaskStatus.CANCELLED -> "Отменена"
                else -> "Ожидает плана"
            }
        } else "Шаги: $completedSteps/$totalSteps"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(31, 27, 41))
            isClickable = true
            isFocusable = true
            setOnClickListener { showTaskHistoryDetails(task.id) }
        }
        card.addView(text("${statusLabel(task.status)} · $progress", 14f, statusColor(task.status), true))
        card.addView(text(safeHistoryText(task.goal, 180), 14f, Color.WHITE, false, top = 4))
        card.addView(text("Пространство: ${safeHistoryText(workspaceScopeLabel(task.workspaceScopeId), 80)}", 11f, Color.rgb(174, 165, 191), false, top = 4))
        card.addView(text("Обновлена: ${formatTime(task.updatedAtEpochMs)} · ID ${shortId(task.id)} · нажми для деталей", 11f, Color.rgb(166, 157, 184), false, top = 4))
        return card
    }

    private fun showTaskHistoryDetails(taskId: String) {
        val task = runCatching { runtime.taskCoordinator.getTask(taskId) }.getOrNull() ?: run {
            statusView.text = "Задача больше не доступна в хранилище."
            refreshTaskHistory()
            return
        }
        val events = runtime.safeAuditReferences(task.id, 8)
        val message = buildString {
            append("Цель: ").append(safeHistoryText(task.goal, 500)).append("\n\n")
            append("Статус: ").append(statusLabel(task.status)).append("\n")
            append("Рабочее пространство: ").append(safeHistoryText(workspaceScopeLabel(task.workspaceScopeId), 80)).append("\n")
            append("Создана: ").append(formatTime(task.createdAtEpochMs)).append("\n")
            append("Обновлена: ").append(formatTime(task.updatedAtEpochMs)).append("\n")
            append("Прогресс: ").append(task.steps.count { it.status == SpiderStepStatus.SUCCEEDED })
                .append("/").append(task.steps.size).append(" шагов успешно выполнено\n")
            task.lastErrorCode?.let { code -> append("Код ошибки: ").append(safeCode(code)).append("\n") }
            task.resultSummary?.let { summary -> append("Результат/восстановление: ").append(safeHistoryText(summary, 400)).append("\n") }
            if (task.steps.isNotEmpty()) {
                append("\nШаги:\n")
                task.steps.take(20).forEachIndexed { index, step ->
                    append(index + 1).append(". ").append(safeHistoryText(step.title, 140))
                        .append(" — ").append(stepStatusLabel(step.status))
                    step.toolId?.let { append(" · ").append(it.name) }
                    step.resultSummary?.let { append("\n   ").append(safeHistoryText(it, 180)) }
                    append("\n")
                }
            }
            append("\nСсылки аудита:")
            if (events.isEmpty()) {
                append("\nНет доступных записей в последних событиях журнала для этой задачи. Это не доказывает отсутствие старых событий.")
            } else {
                events.forEach { event ->
                    append("\n• ").append(event.referenceId).append(" · ").append(formatTime(event.timestampEpochMs))
                        .append(" · ").append(event.toolId).append(" · ").append(event.disposition)
                        .append(" · ").append(event.resultCode)
                }
            }
            append("\n\nВ деталях не отображаются исходные запросы, токены, полные ID запросов, сырые записи аудита или внутренние файловые пути.")
        }
        AlertDialog.Builder(this)
            .setTitle("История задачи · ${shortId(task.id)}")
            .setMessage(message)
            .setPositiveButton("👍 Полезно") { _, _ -> recordTaskFeedback(task.id, helpful = true) }
            .setNegativeButton("👎 Не помогло") { _, _ -> recordTaskFeedback(task.id, helpful = false) }
            .setNeutralButton("Закрыть", null)
            .show()
    }

    private fun recordTaskFeedback(taskId: String, helpful: Boolean) {
        if (!runtime.isLearningEnabled()) {
            Toast.makeText(this, "Самообучение выключено. Включи его, чтобы сохранять новые оценки.", Toast.LENGTH_LONG).show()
            return
        }
        worker.execute {
            val task = runtime.taskCoordinator.getTask(taskId)
            val stored = task != null && runCatching { runtime.learningMemory.recordFeedback(task, helpful) }.getOrDefault(false)
            runOnUiThread {
                val message = when {
                    !stored -> "Оценить можно после того, как задача попадёт в память опыта."
                    helpful -> "Оценка сохранена. Подтверждённый успешный опыт получит больший вес при подборе подсказок."
                    else -> "Оценка сохранена. Этот случай будет помечен как нежелательный пример."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshLearningMemory()
            }
        }
    }

    private fun refreshLearningMemory() {
        if (!::learningSummary.isInitialized || isFinishing || isDestroyed) return
        updateLearningToggleButton()
        updateCloudSemanticToggleButton()
        refreshSkillLibrary()
        refreshLearningButton.isEnabled = false
        worker.execute {
            val snapshot = runCatching {
                Triple(runtime.learningMemory.count(), runtime.learningMemory.lastError, runtime.learningMemory.recoveryNotice)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshLearningButton.isEnabled = true
                val value = snapshot.getOrNull()
                learningSummary.text = if (value == null) {
                    "Не удалось прочитать память; исходные данные не очищаются автоматически."
                } else {
                    buildString {
                        append("Режим: ").append(if (runtime.isLearningEnabled()) "самообучение включено" else "самообучение приостановлено").append(".\n")
                        append("Сохранено эпизодов опыта: ").append(value.first).append(". Нет лимита по количеству записей и автоматического удаления старых эпизодов; объём ограничен доступным местом устройства.")
                        value.second?.let { append("\nПредупреждение: ").append(it) }
                        value.third?.let { append("\nВосстановление: ").append(it) }
                        runtime.taskCoordinator.terminalObserverError?.let { append("\nПоследняя ошибка сохранения опыта: ").append(safeHistoryText(it, 160)) }
                        append("\nОдна и та же память используется локальной GGUF, Яндекс AI Studio и OpenCode Zen; облачная модель получает только ограниченные метаданные релевантного опыта.")
                    }
                }
            }
        }
    }

    private fun showMemoryDiagnostics() {
        if (!::memoryDiagnosticsButton.isInitialized || isFinishing || isDestroyed) return
        memoryDiagnosticsButton.isEnabled = false
        memoryDiagnosticsButton.text = "Проверяем базы и индекс…"
        worker.execute {
            val result = runCatching { runtime.learningMemory.diagnosticsSnapshot() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                memoryDiagnosticsButton.isEnabled = true
                memoryDiagnosticsButton.text = "Диагностика памяти и поиска"
                val snapshot = result.getOrNull()
                if (snapshot == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Диагностика памяти")
                        .setMessage("Не удалось получить диагностические сведения. Данные не удалялись и автоматически не сбрасывались.")
                        .setPositiveButton("Закрыть", null)
                        .show()
                    return@runOnUiThread
                }
                val message = buildString {
                    append("СЛОЙ 1 · ЭПИЗОДИЧЕСКАЯ ПАМЯТЬ\n")
                    append("Эпизоды: ").append(snapshot.episodeCount).append("\n")
                    append("Тематические токены: ").append(snapshot.topicTokenCount).append("\n")
                    append("Основная база с WAL/SHM: ").append(formatBytes(snapshot.memoryDatabaseBytes)).append("\n")
                    append("Версия схемы: ").append(snapshot.memorySchemaVersion).append("\n")
                    append("Проверка целостности: ").append(integrityLabel(snapshot.memoryIntegrity)).append("\n\n")
                    append("СЛОЙ 2 · ПОИСКОВЫЙ ИНДЕКС\n")
                    append("Лексические записи: ").append(snapshot.lexicalPostingCount).append("\n")
                    append("Векторы: ").append(snapshot.embeddingVectorCount).append("\n")
                    append("Модели embeddings: ").append(snapshot.embeddingModelCount).append("\n")
                    append("Ожидают индексации: ").append(snapshot.pendingIndexChanges).append("\n")
                    append("Индекс с WAL/SHM: ").append(formatBytes(snapshot.searchIndexDatabaseBytes)).append("\n")
                    append("Версия схемы: ").append(snapshot.searchSchemaVersion).append("\n")
                    append("Проверка целостности: ").append(integrityLabel(snapshot.searchIndexIntegrity)).append("\n\n")
                    append("ХРАНИЛИЩЕ УСТРОЙСТВА\n")
                    append("Доступно: ").append(formatBytes(snapshot.availableStorageBytes)).append(" из ").append(formatBytes(snapshot.totalStorageBytes)).append("\n")
                    snapshot.lastStorageError?.let { append("\nПредупреждение: ").append(safeHistoryText(it, 180)) }
                    snapshot.recoveryNotice?.let { append("\nВосстановление: ").append(safeHistoryText(it, 180)) }
                    if (snapshot.pendingIndexChanges > 0) append("\n\nИндекс отстаёт от основной памяти. Изменения остаются в очереди и могут быть повторены.")
                    append("\n\nДиагностика показывает только счётчики и статусы, без текстов задач, содержимого памяти и внутренних путей.")
                }
                AlertDialog.Builder(this)
                    .setTitle("Диагностика памяти и поиска")
                    .setMessage(message)
                    .setPositiveButton("Готово", null)
                    .setNeutralButton("Проверить ещё раз") { _, _ -> showMemoryDiagnostics() }
                    .show()
            }
        }
    }

    private fun integrityLabel(value: String): String = when (value) {
        "ok" -> "OK"
        "integrity_warning" -> "ОБНАРУЖЕНО ЗАМЕЧАНИЕ"
        else -> "проверка недоступна"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 Б"
        val units = arrayOf("Б", "КиБ", "МиБ", "ГиБ", "ТиБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        return if (unit == 0) "${bytes} ${units[unit]}" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    private fun updateLearningToggleButton() {
        if (!::learningToggleButton.isInitialized) return
        learningToggleButton.text = if (runtime.isLearningEnabled()) {
            "Самообучение: ВКЛ · нажми, чтобы приостановить"
        } else {
            "Самообучение: ВЫКЛ · нажми, чтобы включить"
        }
    }

    private fun toggleLearning() {
        runCatching { runtime.setLearningEnabled(!runtime.isLearningEnabled()) }
            .onSuccess {
                updateLearningToggleButton()
                refreshLearningMemory()
                Toast.makeText(
                    this,
                    if (runtime.isLearningEnabled()) "Самообучение включено. Будущие задачи смогут использовать и сохранять опыт." else "Самообучение приостановлено. Существующая память сохранена, новые эпизоды и подсказки не используются.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .onFailure { Toast.makeText(this, "Не удалось изменить настройку самообучения.", Toast.LENGTH_LONG).show() }
    }

    private fun showLearningMemory() {
        worker.execute {
            val entries = runCatching { runtime.learningMemory.listExperiences(30) }
            runOnUiThread {
                val experiences = entries.getOrNull()
                if (experiences == null) {
                    Toast.makeText(this, "Не удалось прочитать память обучения.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (experiences.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Опыт ИИ Паука")
                        .setMessage("Память пока пуста. Она пополняется при завершении задач и обновляется твоими оценками.")
                        .setPositiveButton("Закрыть", null)
                        .show()
                    return@runOnUiThread
                }
                val labels = experiences.map { item ->
                    val outcome = when (item.outcome) {
                        SpiderLearningOutcome.VERIFIED_SUCCESS -> "✓ Успех"
                        SpiderLearningOutcome.FAILED -> "! Неудачная попытка"
                        SpiderLearningOutcome.UNKNOWN -> "? Исход неизвестен"
                        SpiderLearningOutcome.CANCELLED -> "Отменена"
                    }
                    val scopeLabel = if (item.scopeId == "global") "Общая память" else "Проект ${shortId(item.scopeId)}"
                    "$outcome · $scopeLabel · ${item.topicTokens.take(4).joinToString(", ")} · ${shortId(item.taskId)}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Опыт ИИ Паука — выбери запись")
                    .setItems(labels) { _, index -> showLearningEntry(experiences[index]) }
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
        }
    }

    private fun showLearningEntry(item: SpiderLearningExperience) {
        val outcome = when (item.outcome) {
            SpiderLearningOutcome.VERIFIED_SUCCESS -> "Успех подтверждён независимой проверкой"
            SpiderLearningOutcome.FAILED -> "Предыдущая неудачная попытка"
            SpiderLearningOutcome.UNKNOWN -> "Результат неизвестен — не следует повторять автоматически"
            SpiderLearningOutcome.CANCELLED -> "Задача отменена; этот случай не используется как подсказка"
        }
        val feedback = when (item.feedback) {
            SpiderLearningFeedback.HELPFUL -> "Полезно"
            SpiderLearningFeedback.NOT_HELPFUL -> "Не помогло"
            null -> "Без оценки"
        }
        val details = buildString {
            append(outcome).append("\n\n")
            append("Тематические слова: ").append(item.topicTokens.joinToString(", ")).append("\n")
            append("Инструменты: ").append(item.toolIds.joinToString(", ").ifBlank { "не использовались" }).append("\n")
            append("Код результата: ").append(item.errorCode ?: "нет").append("\n")
            append("Оценка пользователя: ").append(feedback).append("\n")
            append("Область памяти: ").append(if (item.scopeId == "global") "общая" else "только выбранный проект (${shortId(item.scopeId)})").append("\n")
            append("ID задачи: ").append(shortId(item.taskId)).append("\n\n")
            append("Полный текст задачи, содержимое файлов и ответы модели в этой памяти не сохраняются.")
        }
        AlertDialog.Builder(this)
            .setTitle("Запись опыта")
            .setMessage(details)
            .setNegativeButton("Назад") { _, _ -> showLearningMemory() }
            .setPositiveButton("Забыть эту запись") { _, _ ->
                worker.execute {
                    val removed = runCatching {
                        runtime.learningMemory.forget(item.taskId).also { if (it) {
                            runtime.embeddingIndex.remove(item.taskId)
                            runtime.skillLibrary.forgetEvidenceForTask(item.taskId)
                        } }
                    }.getOrDefault(false)
                    runOnUiThread {
                        Toast.makeText(this, if (removed) "Запись забыта." else "Запись уже отсутствует.", Toast.LENGTH_SHORT).show()
                        refreshLearningMemory()
                    }
                }
            }
            .setNeutralButton("Закрыть", null)
            .show()
    }

    private fun confirmClearLearningMemory() {
        AlertDialog.Builder(this)
            .setTitle("Удалить память обучения?")
            .setMessage("Все сохранённые тематические записи опыта и оценки будут удалены с этого устройства. История задач останется; при необходимости Паук сможет снова извлекать опыт из будущих задач.")
            .setNegativeButton("Оставить", null)
            .setPositiveButton("Удалить память") { _, _ ->
                worker.execute {
                    val result = runCatching {
                        runtime.learningMemory.clearAll()
                        runtime.embeddingIndex.clearAll()
                        runtime.skillLibrary.clearAllEvidence()
                    }
                    runOnUiThread {
                        Toast.makeText(this, if (result.isSuccess) "Память обучения удалена." else "Не удалось удалить память обучения.", Toast.LENGTH_LONG).show()
                        refreshLearningMemory()
                    }
                }
            }
            .show()
    }

    private fun updateCloudSemanticToggleButton() {
        if (!::cloudSemanticToggleButton.isInitialized) return
        cloudSemanticToggleButton.text = if (runtime.isCloudSemanticSearchEnabled()) {
            "Облачные embeddings: ВКЛ · отправляются только тематические слова"
        } else {
            "Облачные embeddings: ВЫКЛ · только локальный поиск"
        }
    }

    private fun toggleCloudSemanticSearch() {
        val enabling = !runtime.isCloudSemanticSearchEnabled()
        if (enabling) {
            AlertDialog.Builder(this)
                .setTitle("Включить облачный семантический поиск?")
                .setMessage("Для поиска похожего опыта на настроенный HTTPS-backend будут передаваться только очищенные тематические слова (до 12 слов), без полного текста задачи, файлов, ответа модели или секретов. Ключ провайдера остаётся на сервере. Если сервер не поддерживает embeddings, Паук продолжит локальный поиск.")
                .setNegativeButton("Оставить локальный", null)
                .setPositiveButton("Включить") { _, _ ->
                    runtime.setCloudSemanticSearchEnabled(true)
                    updateCloudSemanticToggleButton()
                    Toast.makeText(this, "Облачные embeddings включены. Проверь, что backend настроен на провайдера embeddings.", Toast.LENGTH_LONG).show()
                }.show()
        } else {
            runtime.setCloudSemanticSearchEnabled(false)
            updateCloudSemanticToggleButton()
            Toast.makeText(this, "Облачные embeddings выключены. Локальный семантический поиск продолжит работать.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshSkillLibrary() {
        if (!::skillSummary.isInitialized || isFinishing || isDestroyed) return
        worker.execute {
            val snapshot = runCatching { runtime.skillLibrary.listSkills() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val skills = snapshot.getOrNull()
                if (skills == null) {
                    skillSummary.text = "Не удалось прочитать библиотеку навыков. Исходный файл не очищался автоматически."
                    return@runOnUiThread
                }
                val verified = skills.count { it.status == SpiderSkillStatus.VERIFIED }
                val candidates = skills.count { it.status == SpiderSkillStatus.CANDIDATE }
                val revalidation = skills.count { it.status == SpiderSkillStatus.NEEDS_REVALIDATION }
                skillSummary.text = "Навыков: ${skills.size}. Проверено: $verified · кандидаты: $candidates · нужна перепроверка: $revalidation. Автоматическое исполнение навыков отключено."
            }
        }
    }

    private fun showSkillLibrary() {
        worker.execute {
            val snapshot = runCatching { runtime.skillLibrary.listSkills() }
            runOnUiThread {
                val skills = snapshot.getOrNull()
                if (skills == null) {
                    Toast.makeText(this, "Не удалось прочитать библиотеку навыков.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (skills.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("Навыки ИИ Паука")
                        .setMessage("Библиотека пока пуста.").setPositiveButton("Закрыть", null).show()
                    return@runOnUiThread
                }
                val labels = skills.map { skill ->
                    val status = when (skill.status) {
                        SpiderSkillStatus.CANDIDATE -> "КАНДИДАТ"
                        SpiderSkillStatus.VERIFIED -> "ПРОВЕРЕН"
                        SpiderSkillStatus.NEEDS_REVALIDATION -> "НУЖНА ПЕРЕПРОВЕРКА"
                        SpiderSkillStatus.DISABLED -> "ОТКЛЮЧЁН"
                    }
                    "$status · ${skill.title} · успехи ${skill.verifiedRuns}/${skill.minimumVerifiedRuns} · ошибки ${skill.failedRuns}"
                }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Библиотека навыков")
                    .setItems(labels) { _, index ->
                        val item = skills[index]
                        val details = buildString {
                            append(item.summary).append("\n\n")
                            append("Статус: ").append(item.status).append("\n")
                            append("Версия: ").append(item.toolVersion).append("\n")
                            append("Инструменты: ").append(item.toolSequence.joinToString { it.name }).append("\n")
                            append("Предусловия: ").append(item.preconditions.joinToString()).append("\n")
                            append("Независимые успехи: ").append(item.verifiedRuns).append(" из ").append(item.minimumVerifiedRuns).append("\n")
                            append("Неудачные проверки: ").append(item.failedRuns).append("\n")
                            append("Доказательства: ").append(item.evidence.size).append("\n\n")
                            append("Навыки не содержат произвольного кода, не выполняются автоматически и не снимают необходимость подтверждения пользователя.")
                        }
                        AlertDialog.Builder(this).setTitle(item.title).setMessage(details)
                            .setPositiveButton("Закрыть", null).show()
                    }.setNegativeButton("Закрыть", null).show()
            }
        }
    }

    private fun workspaceScopeLabel(scopeId: String?): String = when {
        scopeId == null -> "без выбранного пространства"
        scopeId == "global" -> "общая память"
        else -> workspaceChoices.firstOrNull { it.id == scopeId }?.name ?: "проект ${shortId(scopeId)}"
    }

    private fun safeHistoryText(raw: String, maxLength: Int): String = SpiderAuditRedactor.redact(raw)
        .replace(Regex("(?i)\\b(?:https?|file|content|smb|rtsp)://[^\\s]+"), "[URL скрыт]")
        .replace(Regex("(?i)\\b(?:eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{20,})\\b"), "[секрет скрыт]")
        .replace(Regex("(?i)(?:/data/|/storage/|/sdcard/|/home/|/Users/)[^\\s,;]+"), "[путь скрыт]")
        .replace(Regex("(?<![A-Za-z0-9])/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+"), "[путь скрыт]")
        .replace(Regex("(?i)\\b[A-Z]:\\\\[^\\s,;]+"), "[путь скрыт]")
        .take(maxLength)

    private fun safeCode(value: String): String = value.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: "unknown_error"
    private fun shortId(value: String): String = value.take(8)
    private fun formatTime(epochMs: Long): String = if (epochMs <= 0L) "неизвестно" else
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

    private fun statusLabel(status: SpiderTaskStatus): String = when (status) {
        SpiderTaskStatus.QUEUED -> "В очереди"
        SpiderTaskStatus.PLANNING -> "Планирование"
        SpiderTaskStatus.RUNNING -> "Выполняется"
        SpiderTaskStatus.WAITING_FOR_APPROVAL -> "Ожидает подтверждения"
        SpiderTaskStatus.VERIFYING -> "Проверка результата"
        SpiderTaskStatus.COMPLETED -> "Завершена"
        SpiderTaskStatus.FAILED -> "Ошибка / прервана"
        SpiderTaskStatus.PAUSED -> "Приостановлена"
        SpiderTaskStatus.CANCELLED -> "Отменена"
    }

    private fun stepStatusLabel(status: SpiderStepStatus): String = when (status) {
        SpiderStepStatus.PENDING -> "ожидает"
        SpiderStepStatus.RUNNING -> "выполняется"
        SpiderStepStatus.WAITING_FOR_APPROVAL -> "ожидает подтверждения"
        SpiderStepStatus.SUCCEEDED -> "успешно"
        SpiderStepStatus.FAILED -> "ошибка"
        SpiderStepStatus.SKIPPED -> "пропущен"
    }

    private fun statusColor(status: SpiderTaskStatus): Int = when (status) {
        SpiderTaskStatus.COMPLETED -> Color.rgb(112, 220, 158)
        SpiderTaskStatus.FAILED, SpiderTaskStatus.CANCELLED -> Color.rgb(255, 142, 142)
        SpiderTaskStatus.WAITING_FOR_APPROVAL, SpiderTaskStatus.VERIFYING -> Color.rgb(250, 210, 120)
        else -> Color.rgb(200, 176, 255)
    }

    private data class WorkspaceChoice(val id: String, val name: String) {
        override fun toString(): String = name
    }

    private data class ExecutionSummary(
        val task: SpiderAgentTask,
        val toolId: SpiderToolId,
        val toolStatus: SpiderToolCallStatus,
        val message: String,
        val auditRecorded: Boolean,
        val workspaceId: String,
        val verification: SpiderResultVerification,
        val outputPayload: String?
    )

    private fun refreshConfiguredStatus() {
        if (!::statusView.isInitialized) return
        val isConfigured = runtime.isProviderConfigured()
        val recoveryNote = when {
            runtime.taskRecoveryBlocked -> " Хранилище задач требует проверки; новые действия могут быть заблокированы ради сохранности данных."
            runtime.recoveredTasksAtStartup.isNotEmpty() -> " При запуске безопасно остановлено незавершённых задач: ${runtime.recoveredTasksAtStartup.size}; автоматический повтор не выполнялся."
            else -> ""
        }
        if (isConfigured) {
            statusView.text = "Подключение сохранено. Ключ модели остаётся на backend; можно сформировать план.$recoveryNote"
        } else {
            statusView.text = "Backend пока не подключён. Для живого планирования нужен HTTPS endpoint и настроенный на сервере провайдер модели.$recoveryNote"
        }
    }

    private fun safeMessage(error: Throwable): String = (error.message ?: "неизвестная ошибка")
        .replace(Regex("(?i)(bearer\\s+)[^\\s]+"), "\$1[СКРЫТО]")
        .replace(Regex("(?i)(api[_-]?key|token|secret)\\s*[:=]\\s*[^\\s,;]+"), "\$1=[СКРЫТО]")
        .take(240)

    private fun sectionTitle(value: String) = text(value, 17f, Color.rgb(200, 176, 255), true, top = 18)
    private fun label(value: String) = text(value, 13f, Color.rgb(205, 196, 220), false)
    private fun text(value: String, size: Float, color: Int, bold: Boolean, top: Int = 0) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, if (top == 0) 0 else dp(2), 0, dp(2))
        layoutParams = marginParams(top = top)
    }
    private fun button(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(92, 64, 140))
        minHeight = dp(46)
    }
    private fun marginParams(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
