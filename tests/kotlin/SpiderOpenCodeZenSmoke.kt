import com.iipauk.spider.OpenCodeZenSpiderTaskPlanner
import com.iipauk.spider.SpiderAgentTask
import com.iipauk.spider.SpiderLearningContextCodec
import com.iipauk.spider.SpiderLearningHint
import com.iipauk.spider.SpiderOpenCodeZenConfig
import com.iipauk.spider.SpiderOpenCodeZenInferenceClient
import com.iipauk.spider.SpiderOpenCodeZenModel
import com.iipauk.spider.SpiderOpenCodeZenProtocol
import com.iipauk.spider.SpiderTaskStatus
import com.iipauk.spider.SpiderToolId

fun main() {
    SpiderOpenCodeZenProtocol.validateApiKey("valid-looking-api-key-123")
    check(runCatching { SpiderOpenCodeZenProtocol.validateApiKey("short") }.isFailure)
    check(runCatching { SpiderOpenCodeZenProtocol.validateApiKey("valid\nkey-12345678") }.isFailure)
    check(SpiderOpenCodeZenConfig("valid-looking-api-key-123", "big-pickle").validated().modelId == "big-pickle")
    check(runCatching { SpiderOpenCodeZenConfig("valid-looking-api-key-123", "gpt-6-astra").validated() }.isFailure) {
        "Responses-only model must not be sent to chat/completions"
    }
    check(runCatching { SpiderOpenCodeZenConfig("valid-looking-api-key-123", "https://evil.example/model").validated() }.isFailure)
    check(SpiderOpenCodeZenProtocol.MODEL_LIST_URL.startsWith("https://opencode.ai/zen/v1/"))
    check(SpiderOpenCodeZenProtocol.CHAT_COMPLETIONS_URL == "https://opencode.ai/zen/v1/chat/completions")
    val visible = SpiderOpenCodeZenProtocol.visibleAvailableChatModels(listOf("big-pickle", "gpt-6-astra", "claude-opus-5", "kimi-k2.7-code"))
    check(visible.map { it.id }.toSet() == setOf("big-pickle", "kimi-k2.7-code"))
    check(visible.first { it.id == "big-pickle" }.label == "Big Pickle")
    check(SpiderOpenCodeZenProtocol.supportedChatModelIds.none { it in setOf("gpt-6-astra", "claude-opus-5") })

    val hint = SpiderLearningHint(
        kind = "verified_success",
        topicTokens = listOf("private_topic_must_stay_local"),
        toolIds = listOf("LIST_WORKSPACE_FILES", "SEARCH_WORKSPACE_CONTENT", "READ_WORKSPACE_FILE", "WRITE_WORKSPACE_FILE", "DELETE_WORKSPACE_FILE"),
        errorCode = "safe_error_1",
        confidenceScore = 99
    )
    val safeJson = SpiderLearningContextCodec.toSafeJson(listOf(hint)).toString()
    check(safeJson.contains("verified_success"))
    check(safeJson.contains("LIST_WORKSPACE_FILES"))
    check(safeJson.contains("SEARCH_WORKSPACE_CONTENT") && safeJson.contains("READ_WORKSPACE_FILE") && safeJson.contains("WRITE_WORKSPACE_FILE"))
    check(!safeJson.contains("private_topic_must_stay_local")) { "Raw topic tokens must remain on device" }
    check(!safeJson.contains("DELETE_WORKSPACE_FILE")) { "Unimplemented/high-risk tool IDs must not be sent as learning advice" }

    var capturedSystem = ""
    var capturedUser = ""
    val fakeClient = SpiderOpenCodeZenInferenceClient { _, system, messages ->
        capturedSystem = system
        capturedUser = messages.single().second
        """{"status":"ok","task_id":"task-learning-1","steps":[{"id":"step-1","title":"List file names","tool_id":"LIST_WORKSPACE_FILES"}]}"""
    }
    val config = SpiderOpenCodeZenConfig("valid-looking-api-key-123", "big-pickle")
    val planner = OpenCodeZenSpiderTaskPlanner(config, fakeClient) { listOf(hint) }
    val proposal = planner.proposePlan(SpiderAgentTask(id = "task-learning-1", goal = "List filenames in selected workspace", status = SpiderTaskStatus.PLANNING))
    check(proposal.single().toolId == SpiderToolId.LIST_WORKSPACE_FILES)
    check(capturedSystem.contains("learning_context"))
    check(capturedUser.contains("learning_context"))
    check(capturedUser.contains("verified_success"))
    check(!capturedUser.contains("private_topic_must_stay_local"))
    check(!capturedUser.contains("DELETE_WORKSPACE_FILE"))

    val multiToolClient = SpiderOpenCodeZenInferenceClient { _, _, _ ->
        """{"status":"ok","task_id":"task-five-tools","steps":[{"id":"inventory","title":"Список файлов","tool_id":"LIST_WORKSPACE_FILES"},{"id":"search","title":"Поиск текста","tool_id":"SEARCH_WORKSPACE_CONTENT"},{"id":"read","title":"Чтение файла","tool_id":"READ_WORKSPACE_FILE"},{"id":"write","title":"Запись подтверждённого текста","tool_id":"WRITE_WORKSPACE_FILE"},{"id":"static-check","title":"Статическая проверка проекта","tool_id":"CHECK_WORKSPACE_PROJECT"}]}"""
    }
    val multiToolPlan = OpenCodeZenSpiderTaskPlanner(config, multiToolClient).proposePlan(
        SpiderAgentTask(id = "task-five-tools", goal = "Use registered workspace tools", status = SpiderTaskStatus.PLANNING)
    )
    check(multiToolPlan.mapNotNull { it.toolId } == listOf(
        SpiderToolId.LIST_WORKSPACE_FILES, SpiderToolId.SEARCH_WORKSPACE_CONTENT,
        SpiderToolId.READ_WORKSPACE_FILE, SpiderToolId.WRITE_WORKSPACE_FILE, SpiderToolId.CHECK_WORKSPACE_PROJECT
    )) { "All five actual workspace tools must pass the shared planner parser" }
    check(runCatching {
        val badClient = SpiderOpenCodeZenInferenceClient { _, _, _ ->
            """{"status":"ok","task_id":"task-unsafe","steps":[{"id":"delete","title":"Delete file","tool_id":"DELETE_WORKSPACE_FILE"}]}"""
        }
        OpenCodeZenSpiderTaskPlanner(config, badClient).proposePlan(
            SpiderAgentTask(id = "task-unsafe", goal = "Try unsupported delete", status = SpiderTaskStatus.PLANNING)
        )
    }.isFailure) { "Delete tool must remain rejected" }
    println("OpenCode Zen source smoke PASS: key/model validation, shared learning privacy, all five workspace tools and strict rejection of unsupported tools")
}
