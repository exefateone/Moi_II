package com.iipauk.spider

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** User-facing hub for local GGUF inference, explicit Yandex API routing and bounded chat. */
class SpiderLlmActivity : Activity() {
    private lateinit var runtime: SpiderAgentRuntime
    private lateinit var modeSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var engineStatus: TextView
    private lateinit var folderStatus: TextView
    private lateinit var conversationView: TextView
    private lateinit var promptInput: EditText
    private lateinit var sendButton: Button
    private lateinit var loadButton: Button
    private lateinit var importButton: Button
    private var models: List<SpiderLocalModelFile> = emptyList()
    private val conversation = mutableListOf<Pair<String, String>>()
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = SpiderAgentRuntime.get(applicationContext)
        title = "ИИ Паук — локальное ядро и LLM"
        window.statusBarColor = Color.rgb(22, 19, 31)
        window.navigationBarColor = Color.rgb(22, 19, 31)
        setContentView(buildLayout())
        refreshModels()
        refreshStatus()
    }

    private fun buildLayout(): View {
        val background = Color.rgb(18, 16, 25)
        val surface = Color.rgb(35, 30, 48)
        val text = Color.rgb(247, 243, 255)
        val muted = Color.rgb(186, 177, 202)
        val scroll = ScrollView(this).apply { setBackgroundColor(background) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            this.text = "ИИ Паук: локальное ядро + LLM"
            textSize = 23f; setTextColor(text); typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            this.text = "Планирование и память работают в приложении. Можно использовать локальную GGUF-модель, Яндекс AI Studio через свой backend или напрямую hosted OpenCode Zen API без собственного сервера."
            textSize = 14f; setTextColor(muted); setPadding(0, dp(8), 0, dp(12))
        })

        root.addView(label("Режим языковой модели", text))
        modeSpinner = Spinner(this)
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, SpiderLlmMode.entries.map { it.title })
        modeSpinner.setSelection(SpiderLlmMode.entries.indexOf(runtime.llmMode()).coerceAtLeast(0))
        root.addView(modeSpinner, matchWrap())
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = SpiderLlmMode.entries.getOrNull(position) ?: SpiderLlmMode.LOCAL_ONLY
                runCatching { runtime.setLlmMode(mode) }
                refreshStatus()
            }
        }
        root.addView(TextView(this).apply {
            this.text = "По умолчанию выбран локальный режим. Облачная передача включается только после явного выбора соответствующего режима. Для fallback отправка недавних сообщений на backend разрешена настройкой «Сначала локально, облако по разрешённому fallback»."
            textSize = 12f; setTextColor(muted); setPadding(0, dp(6), 0, dp(10))
        })

        root.addView(label("GGUF-модель в папке llm", text))
        folderStatus = TextView(this).apply { setTextColor(muted); textSize = 12f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(folderStatus)
        modelSpinner = Spinner(this)
        root.addView(modelSpinner, matchWrap())
        val modelButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importButton = button("Импортировать GGUF", surface, text) { launchImporter() }
        modelButtons.addView(importButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        modelButtons.addView(button("Обновить", surface, text) { refreshModels() }, LinearLayout.LayoutParams(0, dp(48), 0.72f))
        root.addView(modelButtons, matchWrap())
        val loadRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        loadButton = button("Загрузить модель", Color.rgb(105, 77, 175), Color.WHITE) { loadSelectedModel() }
        loadRow.addView(loadButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        loadRow.addView(button("Выгрузить", surface, text) {
            worker.execute { runtime.localLlmRuntime.unloadModel(); runOnUiThread { refreshStatus(); say("Локальная модель выгружена.") } }
        }, LinearLayout.LayoutParams(0, dp(48), 0.72f))
        root.addView(loadRow, matchWrap())

        engineStatus = TextView(this).apply { setTextColor(text); textSize = 13f; setPadding(0, dp(8), 0, dp(14)) }
        root.addView(engineStatus)
        root.addView(button("Настроить Яндекс API / backend", surface, text) {
            startActivity(Intent(this@SpiderLlmActivity, SpiderAgentActivity::class.java))
        }, matchWrap())
        root.addView(button("Настроить OpenCode Zen API — без своего сервера", Color.rgb(62, 48, 88), text) {
            startActivity(Intent(this@SpiderLlmActivity, SpiderOpenCodeZenActivity::class.java))
        }, matchWrap())
        root.addView(TextView(this).apply {
            this.text = "OpenCode Zen — прямой API языковой модели; после выбора этого режима текст диалога будет передан сервису. Ключ зашифрован на устройстве. Это не OpenCode coding-agent и не доступ к файлам проекта."
            textSize = 12f; setTextColor(muted); setPadding(0, dp(3), 0, dp(8))
        })

        root.addView(label("Диалог с выбранной моделью", text).apply { setPadding(0, dp(14), 0, dp(6)) })
        conversationView = TextView(this).apply {
            setTextColor(text); textSize = 15f; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(surface)
            this.text = "Диалог пока пуст. Отправь сообщение после выбора режима."
        }
        root.addView(conversationView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
        promptInput = EditText(this).apply {
            hint = "Напиши сообщение ИИ Пауку…"; setTextColor(text); setHintTextColor(muted); textSize = 16f
            minLines = 2; maxLines = 4; setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(surface); filters = arrayOf(android.text.InputFilter.LengthFilter(2200))
        }
        root.addView(promptInput, matchWrap())
        sendButton = button("Отправить", Color.rgb(105, 77, 175), Color.WHITE) { sendMessage() }
        root.addView(sendButton, matchWrap())
        root.addView(button("Очистить текущий диалог", surface, text) {
            conversation.clear(); renderConversation();
        }, matchWrap())
        root.addView(TextView(this).apply {
            this.text = "Общая память опыта работает во всех режимах: локальная GGUF, Яндекс AI Studio и OpenCode Zen. Паук сохраняет проверенные исходы задач и твои оценки, а затем передаёт выбранной модели релевантные подсказки. В контексте памяти для облачной модели находятся только ограниченные метаданные — не тематические слова памяти, содержимое файлов или полный архив. Отдельная опция облачных embeddings в экране агента выключена по умолчанию и только после согласия может отправлять очищенные тематические слова на настроенный backend. Это адаптация поведения Паука через память, а не онлайн-переобучение весов модели. Ключ Яндекса хранится только на backend-сервере, никогда не в приложении."
            setTextColor(muted); textSize = 12f; setPadding(0, dp(12), 0, dp(6))
        })
        root.addView(button("Готово", surface, text) { finish() }, matchWrap())
        return scroll
    }

    private fun refreshModels() {
        try {
            models = runtime.localModelStore.listModels()
            val labels = if (models.isEmpty()) listOf("Нет валидных GGUF-моделей") else models.map { "${it.name} · ${formatSize(it.sizeBytes)}" }
            modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            folderStatus.text = "Папка моделей: ${runtime.localModelStore.directory().absolutePath}\nДля больших моделей рекомендуется квантованный GGUF-файл, подходящий по RAM и лицензии."
            if (models.isEmpty()) engineStatus?.text = if (runtime.localLlmRuntime.engineAvailable) "Движок доступен, модель не импортирована." else "Native llama.cpp пока не собран; импорт можно подготовить, но загрузка будет доступна после Android CMake-сборки."
        } catch (error: Exception) {
            models = emptyList()
            modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Папка llm недоступна"))
            folderStatus.text = "Не удалось открыть каталог локальных моделей."
            say(error.message ?: "Ошибка папки моделей.")
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::engineStatus.isInitialized) return
        val local = when {
            !runtime.localLlmRuntime.engineAvailable -> "Локальный движок: НЕ ГОТОВ (native-библиотека не загружена)"
            runtime.localLlmRuntime.isModelLoaded -> "Локальный движок: активен · ${runtime.localLlmRuntime.loadedModelName}"
            else -> "Локальный движок собран, модель не загружена"
        }
        val cloud = if (runtime.isProviderConfigured()) "Яндекс/backend: настроен клиентский endpoint" else "Яндекс/backend: не настроен"
        val zen = if (runtime.isOpenCodeZenConfigured()) "OpenCode Zen API: ключ/модель сохранены" else "OpenCode Zen API: не настроен"
        engineStatus.text = "$local\n$cloud\n$zen\nРежим: ${runtime.llmMode().title}"
        loadButton.isEnabled = runtime.localLlmRuntime.engineAvailable && models.isNotEmpty() && !runtime.localLlmRuntime.isModelLoaded
        loadButton.text = if (runtime.localLlmRuntime.isModelLoaded) "Модель загружена" else "Загрузить модель"
    }

    private fun loadSelectedModel() {
        if (!runtime.localLlmRuntime.engineAvailable) {
            say("Сначала собери Android native-библиотеку llama.cpp. Модель не имитируется и не будет объявлена работающей без движка.")
            return
        }
        val model = models.getOrNull(modelSpinner.selectedItemPosition)
        if (model == null) { say("Сначала импортируй валидный GGUF-файл."); return }
        loadButton.isEnabled = false
        engineStatus.text = "Загружаю локальную модель. Это может занять некоторое время…"
        worker.execute {
            val result = runCatching { runtime.localLlmRuntime.loadModel(model) }
            runOnUiThread {
                refreshStatus()
                if (result.isSuccess) say("Модель ${model.name} загружена и готова к локальной генерации.")
                else say(result.exceptionOrNull()?.message ?: "Модель не удалось загрузить.")
            }
        }
    }

    private fun launchImporter() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try { startActivityForResult(intent, REQUEST_IMPORT_MODEL) }
        catch (_: Exception) { say("Не удалось открыть системный выбор файлов.") }
    }

    @Deprecated("Uses Android's document picker result callback for broad API 26+ compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_MODEL || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        importButton.isEnabled = false
        worker.execute {
            val priorLoadedName = runtime.localLlmRuntime.loadedModelName
            // Unmap native weights before the file can be atomically replaced under the same name.
            if (priorLoadedName != null) runtime.localLlmRuntime.unloadModel()
            val result = runCatching { runtime.localModelStore.importModel(uri, contentResolver) }
            if (result.isFailure && priorLoadedName != null) {
                // Import failure should not unnecessarily leave the previous, still-valid model unloaded.
                runCatching {
                    runtime.localModelStore.listModels().firstOrNull { it.name == priorLoadedName }?.let {
                        runtime.localLlmRuntime.loadModel(it)
                    }
                }
            }
            runOnUiThread {
                importButton.isEnabled = true
                refreshModels()
                if (result.isSuccess) say("Модель сохранена в папку llm: ${result.getOrThrow().name}. Нажми «Загрузить модель», чтобы запустить её.")
                else say(result.exceptionOrNull()?.message ?: "Не удалось импортировать GGUF.")
            }
        }
    }

    private fun sendMessage() {
        val prompt = promptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) { say("Напиши сообщение."); return }
        if (prompt.length > 2200) { say("Сообщение слишком большое."); return }
        // Capture the selected route at submission time. Changing the spinner while inference is
        // running must not redirect an already-submitted local-only prompt to the cloud.
        val selectedMode = runtime.llmMode()
        conversation += "user" to prompt
        while (conversation.size > 12) conversation.removeAt(0)
        renderConversation()
        promptInput.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(promptInput.windowToken, 0)
        sendButton.isEnabled = false
        engineStatus.text = "Обрабатываю сообщение выбранным режимом…"
        worker.execute {
            val result = runCatching { respond(prompt, selectedMode) }
            runOnUiThread {
                sendButton.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isSuccess) {
                    conversation += "assistant" to result.getOrThrow()
                    while (conversation.size > 12) conversation.removeAt(0)
                    renderConversation()
                } else {
                    // A transport/model error is UI state, not an assistant utterance. Do not
                    // insert it into chat history where it could be sent to Yandex on the next turn.
                    if (conversation.lastOrNull() == ("user" to prompt)) {
                        conversation.removeAt(conversation.lastIndex)
                    }
                    renderConversation()
                    if (promptInput.text.isNullOrBlank()) {
                        promptInput.setText(prompt)
                        promptInput.setSelection(promptInput.text.length)
                    }
                    say(result.exceptionOrNull()?.message ?: "Не удалось получить ответ от модели.")
                }
                refreshStatus()
            }
        }
    }

    private fun respond(prompt: String, selectedMode: SpiderLlmMode): String {
        return when (selectedMode) {
            SpiderLlmMode.LOCAL_ONLY -> localRespond(prompt)
            SpiderLlmMode.YANDEX_CLOUD -> cloudRespond()
            SpiderLlmMode.OPENCODE_ZEN -> openCodeZenRespond()
            SpiderLlmMode.LOCAL_THEN_YANDEX -> {
                try { localRespond(prompt) }
                catch (localFailure: Exception) {
                    try { cloudRespond() }
                    catch (cloudFailure: Exception) {
                        throw IllegalStateException("Локальный движок: ${localFailure.javaClass.simpleName}; облачный резерв: ${cloudFailure.message ?: cloudFailure.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun localRespond(query: String): String {
        check(runtime.localLlmRuntime.isModelLoaded) { "Локальная модель не загружена. Импортируй GGUF и нажми «Загрузить»." }
        val bounded = conversation.takeLast(8).map { (role, content) -> role to content.takeLast(2200) }
        return runtime.localLlmRuntime.generate(
            runtime.localConversationSystemPrompt(query),
            bounded.map { it.first },
            bounded.map { it.second },
            maxTokens = 384,
            temperature = 0.35f
        )
    }

    private fun openCodeZenRespond(): String {
        val config = runtime.openCodeZenConfig()
            ?: throw IllegalStateException("Сначала открой «Настроить OpenCode Zen API — без своего сервера», введи API-ключ и проверь подключение.")
        val bounded = conversation.takeLast(10).map { (role, content) -> role to content.takeLast(2200) }
        val latestUserGoal = bounded.lastOrNull { it.first == "user" }?.second.orEmpty()
        val learningHints = runtime.conversationLearningHints(latestUserGoal)
        return SpiderOpenCodeZenClient().send(config, bounded, learningHints)
    }

    private fun cloudRespond(): String {
        val connection = runtime.providerConnection()
            ?: throw IllegalStateException("Сначала открой «Настроить Яндекс API / backend» и сохрани HTTPS endpoint и клиентский токен. API-ключ Яндекса вводится только на backend.")
        val bounded = conversation.takeLast(10).map { (role, content) -> role to content.takeLast(2200) }
        val hints = runtime.conversationLearningHints(bounded.lastOrNull { it.first == "user" }?.second.orEmpty())
        return HttpSpiderChatClient(connection).send(bounded, hints)
    }

    private fun renderConversation() {
        if (!::conversationView.isInitialized) return
        conversationView.text = if (conversation.isEmpty()) "Диалог пока пуст. Отправь сообщение после выбора режима." else conversation.joinToString("\n\n") { (role, content) ->
            val label = if (role == "user") "Ты" else "ИИ Паук"
            "$label:\n${content.take(4000)}"
        }.takeLast(12_000)
    }

    private fun label(value: String, color: Int) = TextView(this).apply {
        text = value; setTextColor(color); textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(value: String, bg: Int, fg: Int, action: () -> Unit) = Button(this).apply {
        text = value; setTextColor(fg); setBackgroundColor(bg); textSize = 13f; setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun formatSize(size: Long): String = when {
        size >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GiB", size / (1024.0 * 1024 * 1024))
        else -> String.format(java.util.Locale.US, "%.0f MiB", size / (1024.0 * 1024))
    }
    private fun say(message: String) = Toast.makeText(this, message.take(260), Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val REQUEST_IMPORT_MODEL = 4701 }
}
