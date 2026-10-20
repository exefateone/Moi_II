package com.iipauk.spider

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** Local inference engine using llama.cpp. Availability is false until JNI is built and loaded. */
class SpiderLocalLlmRuntime(
    context: Context,
    private val modelStore: SpiderLocalModelStore
) {
    private val appContext = context.applicationContext
    private val nativeAvailable: Boolean = try {
        System.loadLibrary("spiderllm")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    @Volatile private var loadedName: String? = null
    @Volatile private var loadedPath: String? = null

    val engineAvailable: Boolean get() = nativeAvailable
    val isModelLoaded: Boolean get() = nativeAvailable && runCatching { nativeIsLoaded() }.getOrDefault(false)
    val loadedModelName: String? get() = if (isModelLoaded) loadedName else null

    @Synchronized
    fun loadModel(model: SpiderLocalModelFile, contextTokens: Int = DEFAULT_CONTEXT, threads: Int = DEFAULT_THREADS) {
        check(nativeAvailable) { "Локальный движок не собран для этой версии Android. Выполни Android CMake-сборку." }
        require(contextTokens in 512..4096) { "Размер контекста должен быть от 512 до 4096 токенов." }
        require(threads in 1..8) { "Количество потоков должно быть от 1 до 8." }
        val validated = modelStore.validate(model.file)
        check(validated.fingerprint == model.fingerprint) { "Файл модели изменился после проверки. Обнови список моделей." }
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val contextBudget = maxOf(512L * 1024 * 1024, contextTokens.toLong() * 192L * 1024)
        val conservativeNeed = (validated.sizeBytes * 1.10).toLong() + contextBudget
        check(memoryInfo.availMem >= conservativeNeed) {
            "Свободной оперативной памяти недостаточно для безопасной загрузки этой модели. Выбери меньшую квантованную GGUF-модель."
        }
        unloadModel()
        val errorCode = nativeLoadModel(validated.file.absolutePath, contextTokens, threads)
        check(errorCode.isBlank()) { friendlyLoadError(errorCode) }
        loadedName = validated.name
        loadedPath = validated.file.canonicalPath
    }

    @Synchronized
    fun unloadModel() {
        if (nativeAvailable) runCatching { nativeUnloadModel() }
        loadedName = null
        loadedPath = null
    }

    /** Roles and contents are explicit arrays, not a delimiter-encoded prompt. */
    @Synchronized
    fun generate(
        systemPrompt: String,
        roles: List<String>,
        contents: List<String>,
        maxTokens: Int = 384,
        temperature: Float = 0.35f
    ): String {
        check(nativeAvailable) { "Локальный движок не установлен в этой сборке." }
        check(isModelLoaded) { "Сначала загрузи GGUF-модель в разделе «Локальная модель»." }
        require(roles.size == contents.size && roles.size in 1..12) { "Размер истории диалога недопустим." }
        require(contents.all { it.isNotBlank() && it.length <= 2400 }) { "Сообщение слишком большое." }
        val result = nativeGenerate(systemPrompt.take(5000), roles.toTypedArray(), contents.toTypedArray(), maxTokens.coerceIn(1, 512), temperature.coerceIn(0f, 1.5f))
        check(result.isNotBlank()) { "Локальная модель вернула пустой ответ." }
        return result.take(MAX_OUTPUT_CHARS)
    }

    private fun friendlyLoadError(code: String): String = when (code) {
        "model_path_invalid" -> "Путь к файлу модели недопустим."
        "context_size_invalid" -> "Недопустимый размер контекста."
        "thread_count_invalid" -> "Недопустимое число потоков."
        "model_load_failed_check_gguf_and_available_ram" -> "Модель не загрузилась. Проверь совместимость GGUF, архитектуру и свободную оперативную память."
        "model_context_init_failed" -> "Не удалось создать контекст модели. Возможно, ей не хватает памяти."
        else -> "Не удалось загрузить локальную модель ($code)."
    }

    private external fun nativeLoadModel(path: String, contextTokens: Int, threads: Int): String
    private external fun nativeGenerate(systemPrompt: String, roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float): String
    private external fun nativeUnloadModel()
    private external fun nativeIsLoaded(): Boolean

    companion object {
        const val DEFAULT_CONTEXT = 2048
        const val DEFAULT_THREADS = 4
        private const val MAX_OUTPUT_CHARS = 16_000
    }
}

