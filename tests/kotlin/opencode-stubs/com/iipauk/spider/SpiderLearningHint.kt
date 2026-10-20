package com.iipauk.spider

/** Test-only DTO matching the production, metadata-only learning hint contract. */
data class SpiderLearningHint(
    val kind: String,
    val topicTokens: List<String>,
    val toolIds: List<String>,
    val errorCode: String?,
    val confidenceScore: Int
)
