package com.iipauk.spider

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Setup for the hosted OpenCode Zen language-model API. */
class SpiderOpenCodeZenActivity : Activity() {
    private val store by lazy { SpiderOpenCodeZenConfigStore(applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var apiKeyInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var statusView: TextView
    private lateinit var saveTestButton: Button
    private lateinit var refreshButton: Button
    private var visibleModels: List<SpiderOpenCodeZenModel> = SpiderOpenCodeZenProtocol.defaultModels

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ИИ Паук — OpenCode Zen API"
        window.statusBarColor = Color.rgb(22, 19, 31)
        window.navigationBarColor = Color.rgb(22, 19, 31)
        setContentView(buildLayout())
        store.load()?.let { saved ->
            apiKeyInput.setText(saved.apiKey)
            setSelectedModel(saved.modelId)
            statusView.text = "Настройки сохранены на устройстве. Доступность ключа и модели не подтверждается, пока ты не проверишь API."
        }
    }

    private fun buildLayout(): android.view.View {
        val background = Color.rgb(18, 16, 25)
        val surface = Color.rgb(35, 30, 48)
        val text = Color.rgb(247, 243, 255)
        val muted = Color.rgb(190, 181, 207)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
            setBackgroundColor(background)
        }
        root.addView(label("OpenCode Zen API", 23f, text, true))
        root.addView(note(
            "Подключение без сервера и компьютера",
            "Это прямое подключение к API языковой модели OpenCode Zen. Нужны API-ключ OpenCode Zen и интернет. ИИ Паук отправляет запросы с этого устройства на https://opencode.ai/zen/v1. Доступ к файлам проекта и выполнение действий регулируются отдельным локальным Agent Broker."
        ), matchWrap())
        root.addView(note(
            "Важно: подключается языковая модель",
            "В этом режиме Паук получает ответы от облачной чат-модели. Сам API не видит файлы твоего проекта и не запускает инструменты. Для работы с файлами используй локальный Agent Broker: он ограничивает действия рабочим пространством и требует подтверждения для операций, которые могут менять данные."
        ), matchWrap())
        root.addView(label("API-ключ OpenCode Zen", 15f, text, true), matchWrap())
        apiKeyInput = EditText(this).apply {
            hint = "Вставь API-ключ"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(text); setHintTextColor(muted); textSize = 15f
            setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(surface)
            filters = arrayOf(android.text.InputFilter.LengthFilter(4096))
            setSingleLine(true)
        }
        root.addView(apiKeyInput, matchWrap())
        root.addView(button("Получить ключ OpenCode Zen", surface, text) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://opencode.ai/auth")))
        }, matchWrap())

        root.addView(label("Модель Chat Completions", 15f, text, true), matchWrap())
        modelSpinner = Spinner(this)
        root.addView(modelSpinner, matchWrap())
        setModelChoices(visibleModels, preferredId = "big-pickle")
        root.addView(note(
            "Стоимость и конфиденциальность",
            "Использование API регулируется тарифами OpenCode Zen; проверь баланс и лимиты аккаунта. Проверка ключа отправляет короткий реальный запрос с лимитом в 1 выходной токен, поэтому провайдер может учесть его в использовании. После выбора этого режима текст диалога будет передаваться OpenCode Zen. API-ключ хранится в зашифрованном виде с ключом Android Keystore на этом устройстве."
        ), matchWrap())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshButton = button("Обновить каталог моделей", surface, text) { refreshModels() }
        saveTestButton = button("Тестовый запрос и сохранить", Color.rgb(105, 77, 175), Color.WHITE) { verifyAndSave() }
        actions.addView(refreshButton, matchWrap())
        actions.addView(saveTestButton, matchWrap())
        actions.addView(button("Отключить и удалить ключ", surface, text) { clearConfig() }, matchWrap())
        root.addView(actions, matchWrap())

        statusView = TextView(this).apply {
            setTextColor(muted); textSize = 13f; setPadding(0, dp(10), 0, dp(8)); textIsSelectable = true
            text = "Не проверено. Нажми «Тестовый запрос и сохранить» после ввода ключа."
        }
        root.addView(statusView, matchWrap())
        root.addView(button("Готово", surface, text) { finish() }, matchWrap())
        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshModels() {
        val key = apiKeyInput.text.toString().trim()
        if (runCatching { SpiderOpenCodeZenProtocol.validateApiKey(key) }.isFailure) {
            show("Сначала введи API-ключ OpenCode Zen.")
            return
        }
        runApiAction("Загружаю публичный каталог моделей…") {
            SpiderOpenCodeZenClient().listAvailableChatModels(key).also { models ->
                runOnUiThreadIfAlive {
                    val preferred = selectedModelId()
                    visibleModels = models
                    setModelChoices(models, preferred)
                    statusView.text = "Каталог обновлён: найдено ${models.size} совместимых моделей Chat Completions. Это публичный каталог и сам по себе не подтверждает API-ключ."
                }
            }
            "Каталог моделей обновлён. Выбери нужную модель."
        }
    }

    private fun verifyAndSave() {
        val key = apiKeyInput.text.toString().trim()
        val modelId = selectedModelId()
        val valid = runCatching { SpiderOpenCodeZenConfig(key, modelId).validated() }
        if (valid.isFailure) {
            show(valid.exceptionOrNull()?.message ?: "Проверь API-ключ и модель.")
            return
        }
        runApiAction("Проверяю каталог и отправляю минимальный тестовый запрос…") {
            val client = SpiderOpenCodeZenClient()
            val available = client.listAvailableChatModels(key)
            require(available.any { it.id == modelId }) {
                "Выбранная модель не найдена в доступном каталоге OpenCode Zen. Обнови каталог и выбери доступную модель."
            }
            client.testConnection(valid.getOrThrow())
            store.save(SpiderOpenCodeZenConfig(key, modelId))
            runOnUiThreadIfAlive {
                setModelChoices(available, modelId)
            }
            "API подтверждён: короткий тестовый запрос успешен, модель $modelId доступна. Ключ и модель сохранены зашифрованно на устройстве. Провайдер может учитывать тестовый токен в использовании."
        }
    }

    private fun clearConfig() {
        AlertDialog.Builder(this)
            .setTitle("Отключить OpenCode Zen?")
            .setMessage("Сохранённый API-ключ и модель будут удалены с этого устройства. Сам ключ в аккаунте OpenCode Zen не отзывается.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                runCatching { store.clear() }
                apiKeyInput.setText("")
                statusView.text = "Настройки удалены с устройства. При необходимости отзови ключ в аккаунте OpenCode Zen отдельно."
                Toast.makeText(this, "OpenCode Zen отключён", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun runApiAction(busyText: String, action: () -> String) {
        saveTestButton.isEnabled = false
        refreshButton.isEnabled = false
        statusView.text = busyText
        worker.execute {
            val result = runCatching(action)
            runOnUiThreadIfAlive {
                saveTestButton.isEnabled = true
                refreshButton.isEnabled = true
                statusView.text = result.getOrElse { it.message ?: "Не удалось проверить OpenCode Zen API." }
            }
        }
    }

    private fun runOnUiThreadIfAlive(action: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) action() }
    }

    private fun setModelChoices(models: List<SpiderOpenCodeZenModel>, preferredId: String?) {
        if (models.isEmpty()) return
        visibleModels = models
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            models.map { "${it.label}  ·  ${it.id}" })
        val index = models.indexOfFirst { it.id == preferredId }.let { if (it >= 0) it else 0 }
        modelSpinner.setSelection(index)
    }

    private fun setSelectedModel(modelId: String) {
        val known = visibleModels.any { it.id == modelId }
        if (!known && modelId in SpiderOpenCodeZenProtocol.supportedChatModelIds) {
            visibleModels = visibleModels + SpiderOpenCodeZenModel(modelId, modelId)
            setModelChoices(visibleModels, modelId)
        } else setModelChoices(visibleModels, modelId)
    }

    private fun selectedModelId(): String = visibleModels.getOrNull(modelSpinner.selectedItemPosition)?.id.orEmpty()

    private fun note(heading: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(Color.rgb(31, 27, 41))
        addView(label(heading, 14f, Color.rgb(233, 219, 255), true))
        addView(TextView(this@SpiderOpenCodeZenActivity).apply {
            text = body; textSize = 12f; setTextColor(Color.rgb(190, 181, 207)); setPadding(0, dp(5), 0, 0)
        })
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun button(value: String, bg: Int, fg: Int, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(fg); setBackgroundColor(bg); setOnClickListener { click() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(8)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun show(message: String) = Toast.makeText(this, message.take(260), Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
