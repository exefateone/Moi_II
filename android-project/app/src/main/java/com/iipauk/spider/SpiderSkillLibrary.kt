package com.iipauk.spider

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only declarative, typed tool sequences are allowed. A skill cannot contain shell or source code. */
enum class SpiderSkillStatus { CANDIDATE, VERIFIED, NEEDS_REVALIDATION, DISABLED }
enum class SpiderSkillPrecondition { WORKSPACE_SELECTED }

data class SpiderSkillEvidence(
    val taskId: String,
    val verifiedSuccess: Boolean,
    val verifierId: String,
    val toolVersion: String,
    val recordedAtEpochMs: Long
)

data class SpiderSkillRecord(
    val id: String,
    val title: String,
    val summary: String,
    val topicTokens: List<String>,
    val toolSequence: List<SpiderToolId>,
    val preconditions: Set<SpiderSkillPrecondition>,
    val verifierId: String,
    val toolVersion: String,
    val minimumVerifiedRuns: Int,
    val status: SpiderSkillStatus,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val evidence: List<SpiderSkillEvidence>
) {
    val verifiedRuns: Int get() = evidence.count { it.verifiedSuccess && it.verifierId == verifierId && it.toolVersion == toolVersion }
    val failedRuns: Int get() = evidence.count { !it.verifiedSuccess && it.verifierId == verifierId && it.toolVersion == toolVersion }
    val verifiedTaskIds: Set<String> get() = evidence.filter { it.verifiedSuccess && it.verifierId == verifierId && it.toolVersion == toolVersion }.map { it.taskId }.toSet()
}

data class SpiderSkillRecommendation(val skill: SpiderSkillRecord, val relevanceScore: Double)

/**
 * App-private, durable library of verifiable skills. A skill is a typed recipe, never executable
 * code. It becomes VERIFIED only after a configurable number of distinct tasks produce success
 * evidence from the exact independent verifier and tool version. Recommendations never execute.
 */
class FileSpiderSkillLibrary(
    private val storeFile: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val canonicalPath = storeFile.canonicalFile.absolutePath
    private val lock = locks.computeIfAbsent(canonicalPath) { Any() }

    @Volatile var lastError: String? = null
        private set
    @Volatile var recoveryNotice: String? = null
        private set

    /**
     * Installs missing built-in candidates without overwriting existing evidence or status. Every
     * candidate remains recommendation-ineligible until three distinct tasks pass its verifier.
     */
    fun ensureBuiltInCandidates() = synchronized(lock) {
        val all = readLocked()
        val now = clock()
        var changed = false
        BUILT_IN_CANDIDATES.forEach { definition ->
            if (definition.id !in all) {
                all[definition.id] = definition.toRecord(now)
                changed = true
            }
        }
        if (changed) writeLocked(all.values.toList())
        lastError = null
    }

    /** Adds a candidate recipe after strict validation; existing definitions cannot be silently replaced. */
    fun registerCandidate(candidate: SpiderSkillRecord): Boolean = synchronized(lock) {
        validate(candidate)
        val all = readLocked()
        val existing = all[candidate.id]
        if (existing != null) {
            require(existing == candidate) { "Skill ID already exists; create a new versioned ID instead of overwriting it." }
            return@synchronized false
        }
        all[candidate.id] = candidate.copy(status = SpiderSkillStatus.CANDIDATE, evidence = emptyList(), createdAtEpochMs = clock(), updatedAtEpochMs = clock())
        writeLocked(all.values.toList())
        lastError = null
        true
    }

    /**
     * Records evidence only from the verifier/version declared by the skill. One task can count once.
     * A valid failed verification revokes VERIFIED status pending new passing evidence.
     */
    fun recordEvidence(
        skillId: String,
        taskId: String,
        verifiedSuccess: Boolean,
        verifierId: String,
        toolVersion: String
    ): Boolean = synchronized(lock) {
        if (!taskId.matches(TASK_ID_REGEX) || verifierId.length !in 1..80 || toolVersion.length !in 1..80) return@synchronized false
        val all = readLocked()
        val item = all[skillId] ?: return@synchronized false
        if (item.status == SpiderSkillStatus.DISABLED || verifierId != item.verifierId || toolVersion != item.toolVersion) return@synchronized false
        if (item.evidence.any { it.taskId == taskId }) return@synchronized false
        val updatedEvidence = item.evidence + SpiderSkillEvidence(taskId, verifiedSuccess, verifierId, toolVersion, clock())
        val validPasses = updatedEvidence.count { it.verifiedSuccess && it.verifierId == item.verifierId && it.toolVersion == item.toolVersion }
        // After a verified regression, old passes cannot immediately rehabilitate the skill.
        // Require a fresh consecutive streak of independently verified passes after the failure.
        val passingStreak = updatedEvidence.asReversed().takeWhile {
            it.verifiedSuccess && it.verifierId == item.verifierId && it.toolVersion == item.toolVersion
        }.size
        val newStatus = when {
            !verifiedSuccess && item.status == SpiderSkillStatus.VERIFIED -> SpiderSkillStatus.NEEDS_REVALIDATION
            item.status == SpiderSkillStatus.NEEDS_REVALIDATION ->
                if (passingStreak >= item.minimumVerifiedRuns) SpiderSkillStatus.VERIFIED else SpiderSkillStatus.NEEDS_REVALIDATION
            validPasses >= item.minimumVerifiedRuns -> SpiderSkillStatus.VERIFIED
            else -> SpiderSkillStatus.CANDIDATE
        }
        all[skillId] = item.copy(status = newStatus, updatedAtEpochMs = clock(), evidence = updatedEvidence.takeLast(MAX_EVIDENCE_PER_SKILL))
        writeLocked(all.values.toList())
        lastError = null
        true
    }

    /**
     * Records one success only after the caller has independently verified a real tool result.
     * The recipe is a typed suggestion, not executable code; unknown tools have no learning route.
     */
    fun recordVerifiedBuiltInToolSuccess(toolId: SpiderToolId, taskId: String): Boolean {
        val contract = BUILT_IN_EVIDENCE_CONTRACTS[toolId] ?: return false
        return recordEvidence(
            skillId = contract.skillId,
            taskId = taskId,
            verifiedSuccess = true,
            verifierId = contract.verifierId,
            toolVersion = contract.toolVersion
        )
    }

    /** Only VERIFIED skills with satisfied preconditions can be recommended; no automatic execution. */
    fun recommend(goal: String, workspaceSelected: Boolean, limit: Int = 3): List<SpiderSkillRecommendation> = synchronized(lock) {
        if (goal.isBlank() || limit <= 0) return@synchronized emptyList()
        val queryTokens = safeTokens(goal)
        if (queryTokens.isEmpty()) return@synchronized emptyList()
        readLocked().values.asSequence()
            .filter { it.status == SpiderSkillStatus.VERIFIED }
            .filter { skill -> skill.preconditions.all { it != SpiderSkillPrecondition.WORKSPACE_SELECTED || workspaceSelected } }
            .map { it to SpiderSemanticSearch.similarity(queryTokens, it.topicTokens) }
            .filter { it.second >= MIN_RECOMMENDATION_SCORE }
            .sortedByDescending { it.second }
            .take(limit.coerceAtMost(MAX_RECOMMENDATIONS))
            .map { SpiderSkillRecommendation(it.first, it.second) }
            .toList()
    }

    fun listSkills(): List<SpiderSkillRecord> = synchronized(lock) {
        readLocked().values.sortedWith(compareBy<SpiderSkillRecord> { statusOrder(it.status) }.thenByDescending { it.updatedAtEpochMs })
    }

    fun disable(skillId: String): Boolean = synchronized(lock) {
        val all = readLocked()
        val skill = all[skillId] ?: return@synchronized false
        all[skillId] = skill.copy(status = SpiderSkillStatus.DISABLED, updatedAtEpochMs = clock())
        writeLocked(all.values.toList())
        true
    }

    fun count(): Int = synchronized(lock) { readLocked().size }

    /** Forget evidence tied to one task when the corresponding learning episode is deleted. */
    fun forgetEvidenceForTask(taskId: String): Boolean = synchronized(lock) {
        val all = readLocked()
        var changed = false
        all.toMap().forEach { (id, skill) ->
            val kept = skill.evidence.filterNot { it.taskId == taskId }
            if (kept.size != skill.evidence.size) {
                changed = true
                val validPasses = kept.count { it.verifiedSuccess && it.verifierId == skill.verifierId && it.toolVersion == skill.toolVersion }
                val hasFailure = kept.any { !it.verifiedSuccess && it.verifierId == skill.verifierId && it.toolVersion == skill.toolVersion }
                val passingStreak = kept.asReversed().takeWhile {
                    it.verifiedSuccess && it.verifierId == skill.verifierId && it.toolVersion == skill.toolVersion
                }.size
                val status = when {
                    skill.status == SpiderSkillStatus.DISABLED -> SpiderSkillStatus.DISABLED
                    hasFailure && passingStreak < skill.minimumVerifiedRuns -> SpiderSkillStatus.NEEDS_REVALIDATION
                    validPasses >= skill.minimumVerifiedRuns && (!hasFailure || passingStreak >= skill.minimumVerifiedRuns) -> SpiderSkillStatus.VERIFIED
                    else -> SpiderSkillStatus.CANDIDATE
                }
                all[id] = skill.copy(evidence = kept, status = status, updatedAtEpochMs = clock())
            }
        }
        if (changed) writeLocked(all.values.toList())
        changed
    }

    /** User's full memory-clear action also removes derived skill evidence, but retains generic recipes. */
    fun clearAllEvidence() = synchronized(lock) {
        val all = readLocked()
        val reset = all.mapValues { (_, skill) ->
            skill.copy(
                status = if (skill.status == SpiderSkillStatus.DISABLED) SpiderSkillStatus.DISABLED else SpiderSkillStatus.CANDIDATE,
                evidence = emptyList(), updatedAtEpochMs = clock()
            )
        }
        writeLocked(reset.values.toList())
    }

    fun clearEvidence(skillId: String): Boolean = synchronized(lock) {
        val all = readLocked()
        val skill = all[skillId] ?: return@synchronized false
        all[skillId] = skill.copy(status = SpiderSkillStatus.CANDIDATE, evidence = emptyList(), updatedAtEpochMs = clock())
        writeLocked(all.values.toList())
        true
    }

    private fun validate(item: SpiderSkillRecord) {
        require(item.id.matches(SKILL_ID_REGEX)) { "Skill ID is invalid." }
        require(item.title.isNotBlank() && item.title.length <= MAX_TITLE_CHARS) { "Skill title is invalid." }
        require(item.summary.isNotBlank() && item.summary.length <= MAX_SUMMARY_CHARS) { "Skill summary is invalid." }
        require(item.topicTokens.isNotEmpty() && item.topicTokens.size <= MAX_TOPIC_TOKENS && item.topicTokens.all(::isSafeToken)) { "Skill topic tags are invalid." }
        require(item.toolSequence.isNotEmpty() && item.toolSequence.size <= MAX_TOOL_STEPS && item.toolSequence.all { it in IMPLEMENTED_SKILL_TOOLS }) { "Skill uses unavailable tools." }
        require(item.preconditions.isNotEmpty()) { "Skill preconditions are required." }
        require(item.verifierId in ALLOWED_VERIFIERS) { "Skill verifier is not allowlisted." }
        require(item.toolVersion.matches(TOOL_VERSION_REGEX)) { "Skill tool version is invalid." }
        require(item.minimumVerifiedRuns in 2..10) { "Skill needs 2–10 independent successful runs." }
        require(item.evidence.isEmpty()) { "A candidate cannot bring external evidence with it." }
        require(item.status == SpiderSkillStatus.CANDIDATE) { "New skill must start as candidate." }
    }

    private fun safeTokens(raw: String): List<String> {
        val scrubbed = raw
            .replace(Regex("(?i)\\b(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9_]{12,}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b"), " ")
            .replace(Regex("(?i)\\b(?:https?|file|content|smb|rtsp)://[^\\s]+"), " ")
            .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?i)\\b(?:api[_-]?key|token|secret|password|пароль|токен|ключ)\\s*[:=]?\\s*[^\\s,;]+"), " ")
        return TOKEN_REGEX.findAll(scrubbed.lowercase()).map { it.value }.filter(::isSafeToken).distinct().take(MAX_TOPIC_TOKENS).toList()
    }

    private fun isSafeToken(token: String): Boolean = token.length in 3..MAX_TOKEN_CHARS &&
        token.matches(Regex("[a-zа-яё_-]+")) && token !in SENSITIVE_TOKENS && token !in INSTRUCTION_TOKENS

    private fun statusOrder(status: SpiderSkillStatus): Int = when (status) {
        SpiderSkillStatus.VERIFIED -> 0
        SpiderSkillStatus.CANDIDATE -> 1
        SpiderSkillStatus.NEEDS_REVALIDATION -> 2
        SpiderSkillStatus.DISABLED -> 3
    }

    private fun readLocked(): LinkedHashMap<String, SpiderSkillRecord> {
        if (!storeFile.exists()) return linkedMapOf()
        if (storeFile.length() <= 0L || storeFile.length() > MAX_STORE_BYTES) {
            throw IOException("Хранилище навыков имеет некорректный или слишком большой размер; данные оставлены без изменений.")
        }
        try {
            DataInputStream(BufferedInputStream(FileInputStream(storeFile))).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Skill library signature is invalid.")
                val version = input.readInt()
                if (version != SCHEMA_VERSION) throw IOException("Unsupported skill-library schema; existing data is preserved.")
                val count = input.readInt()
                if (count !in 0..MAX_SKILL_COUNT || count.toLong() > storeFile.length() / 32L + 1L) throw IOException("Skill library count is inconsistent with file size.")
                val all = linkedMapOf<String, SpiderSkillRecord>()
                repeat(count) {
                    val id = readString(input, MAX_ID_CHARS)
                    val title = readString(input, MAX_TITLE_CHARS)
                    val summary = readString(input, MAX_SUMMARY_CHARS)
                    val tokens = readStringList(input, MAX_TOPIC_TOKENS, MAX_TOKEN_CHARS)
                    val tools = readStringList(input, MAX_TOOL_STEPS, 60).map { enumValue<SpiderToolId>(it) }
                    val preconditions = readStringList(input, MAX_PRECONDITIONS, 80).map { enumValue<SpiderSkillPrecondition>(it) }.toSet()
                    val verifier = readString(input, 80)
                    val toolVersion = readString(input, 80)
                    val minimumRuns = input.readInt()
                    val status = enumValue<SpiderSkillStatus>(readString(input, 40))
                    val createdAt = input.readLong()
                    val updatedAt = input.readLong()
                    val evidenceCount = input.readInt()
                    if (evidenceCount !in 0..MAX_EVIDENCE_PER_SKILL) throw IOException("Skill evidence count is invalid.")
                    val evidence = List(evidenceCount) {
                        SpiderSkillEvidence(
                            taskId = readString(input, MAX_ID_CHARS),
                            verifiedSuccess = input.readBoolean(),
                            verifierId = readString(input, 80),
                            toolVersion = readString(input, 80),
                            recordedAtEpochMs = input.readLong()
                        )
                    }
                    val skill = SpiderSkillRecord(id, title, summary, tokens, tools, preconditions, verifier, toolVersion, minimumRuns, status, createdAt, updatedAt, evidence)
                    validateLoaded(skill)
                    if (all.put(id, skill) != null) throw IOException("Duplicate skill ID.")
                }
                if (input.read() != -1) throw IOException("Unexpected trailing bytes in skill library.")
                return all
            }
        } catch (eof: EOFException) {
            throw IOException("Skill library is truncated; original file is preserved.", eof)
        }
    }

    private fun validateLoaded(skill: SpiderSkillRecord) {
        require(skill.id.matches(SKILL_ID_REGEX) && skill.title.isNotBlank() && skill.title.length <= MAX_TITLE_CHARS)
        require(skill.summary.isNotBlank() && skill.summary.length <= MAX_SUMMARY_CHARS)
        require(skill.topicTokens.isNotEmpty() && skill.topicTokens.size <= MAX_TOPIC_TOKENS && skill.topicTokens.all(::isSafeToken))
        require(skill.toolSequence.isNotEmpty() && skill.toolSequence.size <= MAX_TOOL_STEPS && skill.toolSequence.all { it in IMPLEMENTED_SKILL_TOOLS })
        require(skill.verifierId in ALLOWED_VERIFIERS && skill.toolVersion.matches(TOOL_VERSION_REGEX))
        require(skill.minimumVerifiedRuns in 2..10 && skill.evidence.size <= MAX_EVIDENCE_PER_SKILL)
        require(skill.evidence.map { it.taskId }.distinct().size == skill.evidence.size)
        val verifiedStreak = skill.evidence.asReversed().takeWhile {
            it.verifiedSuccess && it.verifierId == skill.verifierId && it.toolVersion == skill.toolVersion
        }.size
        require(skill.status != SpiderSkillStatus.VERIFIED || verifiedStreak >= skill.minimumVerifiedRuns)
    }

    private fun writeLocked(items: List<SpiderSkillRecord>) {
        val parent = storeFile.absoluteFile.parentFile ?: throw IOException("Skill library must be app-private.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create skill library directory.")
        if (!parent.isDirectory) throw IOException("Skill library parent is not a directory.")
        val temp = File(parent, storeFile.name + ".tmp")
        try {
            val fileStream = FileOutputStream(temp, false)
            DataOutputStream(BufferedOutputStream(fileStream)).use { out ->
                out.writeInt(MAGIC); out.writeInt(SCHEMA_VERSION); out.writeInt(items.size)
                items.forEach { item ->
                    writeString(out, item.id, MAX_ID_CHARS)
                    writeString(out, item.title, MAX_TITLE_CHARS)
                    writeString(out, item.summary, MAX_SUMMARY_CHARS)
                    writeStringList(out, item.topicTokens.take(MAX_TOPIC_TOKENS), MAX_TOPIC_TOKENS, MAX_TOKEN_CHARS)
                    writeStringList(out, item.toolSequence.map { it.name }, MAX_TOOL_STEPS, 60)
                    writeStringList(out, item.preconditions.map { it.name }.sorted(), MAX_PRECONDITIONS, 80)
                    writeString(out, item.verifierId, 80)
                    writeString(out, item.toolVersion, 80)
                    out.writeInt(item.minimumVerifiedRuns)
                    writeString(out, item.status.name, 40)
                    out.writeLong(item.createdAtEpochMs); out.writeLong(item.updatedAtEpochMs)
                    val evidence = item.evidence.takeLast(MAX_EVIDENCE_PER_SKILL)
                    out.writeInt(evidence.size)
                    evidence.forEach { e ->
                        writeString(out, e.taskId, MAX_ID_CHARS); out.writeBoolean(e.verifiedSuccess)
                        writeString(out, e.verifierId, 80); writeString(out, e.toolVersion, 80); out.writeLong(e.recordedAtEpochMs)
                    }
                }
                out.flush(); fileStream.fd.sync()
            }
            if (temp.length() > MAX_STORE_BYTES) throw IOException("Библиотека навыков достигла безопасного лимита размера; старые данные сохранены.")
            try {
                Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }
    }

    private fun writeString(out: DataOutputStream, value: String, max: Int) {
        require(value.length <= max)
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= max * 4)
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun readString(input: DataInputStream, max: Int): String {
        val length = input.readInt()
        if (length !in 0..max * 4) throw IOException("Skill field length is invalid.")
        val bytes = ByteArray(length); input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8).also { if (it.length > max) throw IOException("Skill field exceeds limit.") }
    }
    private fun writeStringList(out: DataOutputStream, values: List<String>, maxCount: Int, maxLength: Int) {
        require(values.size <= maxCount); out.writeInt(values.size); values.forEach { writeString(out, it, maxLength) }
    }
    private fun readStringList(input: DataInputStream, maxCount: Int, maxLength: Int): List<String> {
        val count = input.readInt(); if (count !in 0..maxCount) throw IOException("Skill list count is invalid.")
        return List(count) { readString(input, maxLength) }
    }
    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf<T>(value)

    private data class BuiltInCandidateDefinition(
        val id: String,
        val title: String,
        val summary: String,
        val topicTokens: List<String>,
        val toolId: SpiderToolId,
        val verifierId: String,
        val toolVersion: String
    ) {
        fun toRecord(now: Long) = SpiderSkillRecord(
            id = id,
            title = title,
            summary = summary,
            topicTokens = topicTokens,
            toolSequence = listOf(toolId),
            preconditions = setOf(SpiderSkillPrecondition.WORKSPACE_SELECTED),
            verifierId = verifierId,
            toolVersion = toolVersion,
            minimumVerifiedRuns = 3,
            status = SpiderSkillStatus.CANDIDATE,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            evidence = emptyList()
        )
    }

    private data class BuiltInEvidenceContract(
        val skillId: String,
        val verifierId: String,
        val toolVersion: String
    )

    companion object {
        private const val MAGIC = 0x53504B4C // SPKL
        private const val SCHEMA_VERSION = 1
        private const val WORKSPACE_VERIFIER_ID = "WorkspaceListingResultVerifier"
        private const val WORKSPACE_TEXT_VERIFIER_ID = "WorkspaceTextToolVerifier"
        private const val WORKSPACE_TOOL_VERSION = "LIST_WORKSPACE_FILES@1"
        private const val SEARCH_TOOL_VERSION = "SEARCH_WORKSPACE_CONTENT@1"
        private const val READ_TOOL_VERSION = "READ_WORKSPACE_FILE@1"
        private const val WRITE_TOOL_VERSION = "WRITE_WORKSPACE_FILE@1"
        private const val PROJECT_CHECK_VERIFIER_ID = "WorkspaceProjectCheckVerifier"
        private const val PROJECT_CHECK_TOOL_VERSION = "CHECK_WORKSPACE_PROJECT@1"
        private const val MAX_STORE_BYTES = 16L * 1024L * 1024L
        private const val MAX_EVIDENCE_PER_SKILL = 10_000
        private const val MAX_SKILL_COUNT = 100_000
        private const val MAX_ID_CHARS = 120
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_SUMMARY_CHARS = 240
        private const val MAX_TOKEN_CHARS = 32
        private const val MAX_TOPIC_TOKENS = 12
        private const val MAX_TOOL_STEPS = 5
        private const val MAX_PRECONDITIONS = 6
        private const val MAX_RECOMMENDATIONS = 5
        private const val MIN_RECOMMENDATION_SCORE = 0.13
        private val TASK_ID_REGEX = Regex("[A-Za-z0-9._:-]{1,120}")
        private val SKILL_ID_REGEX = Regex("[A-Za-z0-9_.-]{1,120}")
        private val TOOL_VERSION_REGEX = Regex("[A-Za-z0-9_.@-]{1,80}")
        private val TOKEN_REGEX = Regex("[a-z0-9а-яё_-]{3,32}")
        private val SENSITIVE_TOKENS = setOf("token", "secret", "password", "пароль", "токен", "ключ", "apikey", "bearer")
        private val INSTRUCTION_TOKENS = setOf("ignore", "instructions", "system", "override", "execute", "shell", "jailbreak", "bypass", "игнорируй", "инструкции", "команда", "обойди")
        private val IMPLEMENTED_SKILL_TOOLS = setOf(
            SpiderToolId.LIST_WORKSPACE_FILES,
            SpiderToolId.SEARCH_WORKSPACE_CONTENT,
            SpiderToolId.READ_WORKSPACE_FILE,
            SpiderToolId.WRITE_WORKSPACE_FILE,
            SpiderToolId.CHECK_WORKSPACE_PROJECT
        )
        private val ALLOWED_VERIFIERS = setOf(WORKSPACE_VERIFIER_ID, WORKSPACE_TEXT_VERIFIER_ID, PROJECT_CHECK_VERIFIER_ID)
        private val BUILT_IN_CANDIDATES = listOf(
            BuiltInCandidateDefinition(
                "inspect_workspace_inventory_v1",
                "Проверенная инвентаризация рабочего пространства",
                "Получить список имён файлов и каталогов в выбранном пространстве и проверить полноту результата.",
                listOf("inspect", "workspace", "files", "directory", "list", "проверка", "проект"),
                SpiderToolId.LIST_WORKSPACE_FILES, WORKSPACE_VERIFIER_ID, WORKSPACE_TOOL_VERSION
            ),
            BuiltInCandidateDefinition(
                "search_workspace_content_v1",
                "Проверенный поиск по тексту проекта",
                "Найти заданную фразу в разрешённых текстовых файлах и независимо повторить ограниченный поиск.",
                listOf("search", "workspace", "content", "text", "files", "поиск", "текст", "проект"),
                SpiderToolId.SEARCH_WORKSPACE_CONTENT, WORKSPACE_TEXT_VERIFIER_ID, SEARCH_TOOL_VERSION
            ),
            BuiltInCandidateDefinition(
                "read_workspace_file_v1",
                "Проверенное чтение текстового файла",
                "Прочитать один разрешённый UTF-8 файл, замаскировать распространённые секреты и проверить повторное чтение.",
                listOf("read", "workspace", "text", "file", "content", "чтение", "текст", "проект"),
                SpiderToolId.READ_WORKSPACE_FILE, WORKSPACE_TEXT_VERIFIER_ID, READ_TOOL_VERSION
            ),
            BuiltInCandidateDefinition(
                "write_workspace_file_v1",
                "Проверенная запись текстового файла",
                "Записать только текст, введённый пользователем и подтверждённый в UI, затем проверить содержимое и SHA-256.",
                listOf("write", "workspace", "text", "file", "create", "запись", "файл", "проект"),
                SpiderToolId.WRITE_WORKSPACE_FILE, WORKSPACE_TEXT_VERIFIER_ID, WRITE_TOOL_VERSION
            ),
            BuiltInCandidateDefinition(
                "static_project_check_v1",
                "Проверенная статическая проверка проекта",
                "Проверить JSON/XML и UTF-8 в ограниченном наборе файлов без запуска исходного кода, shell или сборки; повторно сверить доказательство.",
                listOf("static", "check", "project", "json", "xml", "проверка", "проект", "синтаксис"),
                SpiderToolId.CHECK_WORKSPACE_PROJECT, PROJECT_CHECK_VERIFIER_ID, PROJECT_CHECK_TOOL_VERSION
            )
        )
        private val BUILT_IN_EVIDENCE_CONTRACTS = BUILT_IN_CANDIDATES.associate { item ->
            item.toolId to BuiltInEvidenceContract(item.id, item.verifierId, item.toolVersion)
        }
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
}
