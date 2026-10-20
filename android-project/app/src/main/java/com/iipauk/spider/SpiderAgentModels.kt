package com.iipauk.spider

/**
 * Shared contracts for the future Spider Agent tool broker.
 * This model is deliberately data-only: no model output is executed as a shell command.
 */
enum class SpiderTaskStatus {
    QUEUED, PLANNING, RUNNING, WAITING_FOR_APPROVAL, VERIFYING,
    COMPLETED, FAILED, PAUSED, CANCELLED
}

enum class SpiderToolId {
    SEARCH_PUBLIC_WEB,
    OPEN_BROWSER,
    LIST_WORKSPACE_FILES,
    SEARCH_WORKSPACE_CONTENT,
    READ_WORKSPACE_FILE,
    WRITE_WORKSPACE_FILE,
    RUN_PROJECT_TESTS,
    CHECK_WORKSPACE_PROJECT,
    READ_MEDIA_METADATA,
    PLAY_MEDIA,
    DOWNLOAD_REMOTE_FILE,
    INSTALL_PACKAGE,
    DELETE_WORKSPACE_FILE,
    SEND_EXTERNAL_CONTENT
}

enum class SpiderToolRisk { LOW, GUARDED, HIGH, CRITICAL }

enum class SpiderPolicyDisposition { ALLOW, REQUIRE_APPROVAL, DENY }

enum class SpiderStepStatus { PENDING, RUNNING, WAITING_FOR_APPROVAL, SUCCEEDED, FAILED, SKIPPED }

data class SpiderPlanStep(
    val id: String,
    val title: String,
    val toolId: SpiderToolId?,
    val status: SpiderStepStatus = SpiderStepStatus.PENDING,
    val resultSummary: String? = null
)

data class SpiderAgentTask(
    val id: String,
    val goal: String,
    val status: SpiderTaskStatus = SpiderTaskStatus.QUEUED,
    val steps: List<SpiderPlanStep> = emptyList(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = createdAtEpochMs,
    val lastErrorCode: String? = null,
    /** Restores the prior active state when a task is resumed from PAUSED. */
    val resumeStatus: SpiderTaskStatus? = null,
    /** Redacted, user-visible summary of the last known result. */
    val resultSummary: String? = null,
    /** Optional app-private workspace ID used to isolate project learning; never a filesystem path. */
    val workspaceScopeId: String? = null
)

/** A typed tool request. It intentionally has no `command` field or arbitrary shell payload. */
data class SpiderToolRequest(
    val requestId: String,
    val taskId: String,
    val toolId: SpiderToolId,
    val summary: String,
    /** For file tools, a workspace-relative path. For LIST_WORKSPACE_FILES legacy requests this is the workspace ID. */
    val resourceReference: String? = null,
    /** Selected workspace ID for text read/search/write tools. Never a raw path. */
    val workspaceId: String? = null,
    /** Ephemeral input for a search query or proposed file content; never copied to audit or learning memory. */
    val inputText: String? = null,
    /** True only when the UI explicitly asked the user to approve replacement of an existing file. */
    val overwriteExisting: Boolean = false,
    /** Optional stale-write guard: SHA-256 of the source shown in the preview, or MISSING for a new file. */
    val expectedPreimageSha256: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class SpiderPolicyContext(
    /** Persistent user setting for routine network access. Sensitive actions still need per-request approval. */
    val networkAccessEnabled: Boolean = false,
    /** IDs approved by the user in a visible confirmation UI. Approval is request-scoped, not reusable. */
    val approvedRequestIds: Set<String> = emptySet()
)

data class SpiderPolicyDecision(
    val disposition: SpiderPolicyDisposition,
    val risk: SpiderToolRisk,
    val reasonCode: String,
    val userMessage: String
)
