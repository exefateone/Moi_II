package com.iipauk.spider

/** Direct OpenCode Zen language-model API configuration. */
data class SpiderOpenCodeZenConfig(
    val apiKey: String,
    val modelId: String
) {
    fun validated(): SpiderOpenCodeZenConfig {
        SpiderOpenCodeZenProtocol.validateApiKey(apiKey)
        SpiderOpenCodeZenProtocol.validateModelId(modelId)
        return copy(apiKey = apiKey.trim(), modelId = modelId.trim())
    }
}

data class SpiderOpenCodeZenModel(val id: String, val label: String = id)

/** Fixed host and explicitly curated chat/completions-compatible model IDs from OpenCode Zen docs. */
object SpiderOpenCodeZenProtocol {
    const val MODEL_LIST_URL = "https://opencode.ai/zen/v1/models"
    const val CHAT_COMPLETIONS_URL = "https://opencode.ai/zen/v1/chat/completions"

    // Zen exposes several protocols (Responses, Anthropic Messages, and Chat Completions).
    // This app intentionally uses only the documented OpenAI-compatible Chat Completions models.
    val supportedChatModelIds: Set<String> = setOf(
        "glm-5", "glm-5.1", "glm-5.2", "glm-5.3", "glm-5.3-flash",
        "kimi-k2.5", "kimi-k2.6", "kimi-k2.7-code", "kimi-k3", "mistral-large-4",
        "big-pickle", "space-bunny-free", "longcat-2.5-preview-free", "exo-free", "fledge-alpha-free",
        "mimo-v2.6-flash-free", "mimo-v2.5-free", "ling-3.1-flash-free", "ling-3.0-flash-fin-free",
        "nemotron-3-ultra-free", "nemotron-3.5-lightning-free", "nemotron-3.5-super-vl-free", "glm-5.3-flash-free"
    )

    val defaultModels: List<SpiderOpenCodeZenModel> = listOf(
        SpiderOpenCodeZenModel("big-pickle", "Big Pickle"),
        SpiderOpenCodeZenModel("glm-5.3", "GLM 5.3"),
        SpiderOpenCodeZenModel("glm-5.1", "GLM 5.1"),
        SpiderOpenCodeZenModel("kimi-k2.7-code", "Kimi K2.7 Code"),
        SpiderOpenCodeZenModel("kimi-k3", "Kimi K3"),
        SpiderOpenCodeZenModel("mistral-large-4", "Mistral Large 4"),
        SpiderOpenCodeZenModel("mimo-v2.6-flash-free", "MiMo V2.6 Flash Free"),
        SpiderOpenCodeZenModel("ling-3.1-flash-free", "Ling 3.1 Flash Free"),
        SpiderOpenCodeZenModel("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning Free"),
        SpiderOpenCodeZenModel("longcat-2.5-preview-free", "LongCat 2.5 Preview Free")
    )

    fun validateApiKey(raw: String) {
        val value = raw.trim()
        require(value.length in 8..4096) { "Введи действительный API-ключ OpenCode Zen." }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) { "API-ключ содержит управляющие символы." }
    }

    fun validateModelId(raw: String): String {
        val value = raw.trim()
        require(value.length in 1..100 && MODEL_ID.matches(value)) { "Недопустимый ID модели OpenCode Zen." }
        require(value in supportedChatModelIds) { "Эта модель не отмечена как поддерживающая Chat Completions в коннекторе ИИ Паука." }
        return value
    }

    fun visibleAvailableChatModels(allIds: Collection<String>): List<SpiderOpenCodeZenModel> {
        val available = allIds.toSet().intersect(supportedChatModelIds)
        val knownLabels = defaultModels.associate { it.id to it.label }
        return (defaultModels.filter { it.id in available } + available
            .filterNot { id -> defaultModels.any { it.id == id } }
            .sorted().map { SpiderOpenCodeZenModel(it, knownLabels[it] ?: it) })
            .distinctBy { it.id }
    }

    private val MODEL_ID = Regex("^[a-z0-9][a-z0-9._-]{0,99}$")
}
