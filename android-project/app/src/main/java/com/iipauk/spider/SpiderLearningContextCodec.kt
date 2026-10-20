package com.iipauk.spider

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared privacy/safety boundary for learning hints sent to any hosted model.
 * Topic tokens stay on device for episode retrieval and are never included in the hosted LLM
 * learning-context payload. A separate neural-embeddings feature has its own explicit opt-in and
 * may send sanitized topic tokens to the configured backend. Hosted LLMs receive bounded outcome/tool
 * metadata, never the raw local memory topics, transcripts, files or tool output.
 */
object SpiderLearningContextCodec {
    const val MAX_HINTS = 5
    private val allowedKinds = setOf(
        "verified_success", "user_endorsed_success", "user_disliked_success",
        "user_endorsed_plan", "user_disliked_plan", "prior_failure", "uncertain_outcome"
    )
    private val safeErrorCode = Regex("[A-Za-z0-9_.-]{1,80}")
    private val allowedTools = setOf(
        SpiderToolId.LIST_WORKSPACE_FILES.name,
        SpiderToolId.SEARCH_WORKSPACE_CONTENT.name,
        SpiderToolId.READ_WORKSPACE_FILE.name,
        SpiderToolId.WRITE_WORKSPACE_FILE.name,
        SpiderToolId.CHECK_WORKSPACE_PROJECT.name
    )

    fun toSafeJson(hints: List<SpiderLearningHint>): JSONArray = JSONArray().apply {
        hints.take(MAX_HINTS).forEach { hint ->
            val tools = JSONArray()
            hint.toolIds.filter { it in allowedTools }
                .distinct().take(5).forEach(tools::put)
            put(JSONObject()
                .put("kind", hint.kind.takeIf { it in allowedKinds } ?: "uncertain_outcome")
                .put("tool_ids", tools)
                .put("error_code", hint.errorCode?.takeIf { safeErrorCode.matches(it) })
                .put("confidence_score", hint.confidenceScore.coerceIn(0, 100)))
        }
    }
}
