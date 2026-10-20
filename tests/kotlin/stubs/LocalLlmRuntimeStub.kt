package com.iipauk.spider

import android.content.Context
import java.io.File

/** Test-only doubles. Native llama.cpp and Android file picking are tested on Android CI/device, not by this JVM harness. */
data class SpiderLocalModelFile(val name: String, val file: File, val sizeBytes: Long, val fingerprint: String)

class SpiderLocalModelStore(context: Context) {
    fun listModels(): List<SpiderLocalModelFile> = emptyList()
}

class SpiderLocalLlmRuntime(context: Context, private val modelStore: SpiderLocalModelStore) {
    val engineAvailable: Boolean = false
    val isModelLoaded: Boolean = false
    val loadedModelName: String? = null
    fun unloadModel() = Unit
    fun generate(systemPrompt: String, roles: List<String>, contents: List<String>, maxTokens: Int = 384, temperature: Float = 0.35f): String =
        throw IllegalStateException("Native engine is outside this JVM test double")
}
