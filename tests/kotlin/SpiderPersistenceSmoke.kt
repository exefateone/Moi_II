import com.iipauk.spider.*
import java.io.DataOutputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.content.Context

fun checkTrue(value: Boolean, message: String) { if (!value) error(message) }
fun main() {
    val base = File(System.getProperty("java.io.tmpdir"), "spider-repo-harness-${System.nanoTime()}")
    checkTrue(base.mkdirs(), "tmp dir")
    try {
        val file = File(base, "tasks.spider")
        val full = SpiderAgentTask(
            id = "task-1", goal = "Создать HTML-демо", status = SpiderTaskStatus.VERIFYING,
            steps = listOf(
                SpiderPlanStep("s1", "Список файлов", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.SUCCEEDED, "2 файла"),
                SpiderPlanStep("s2", "Создать демо", SpiderToolId.WRITE_WORKSPACE_FILE, SpiderStepStatus.SUCCEEDED, "index.html записан")
            ), createdAtEpochMs = 100, updatedAtEpochMs = 200, resultSummary = "Проверка нужна"
        )
        FileSpiderTaskRepository(file).save(full)
        checkTrue(FileSpiderTaskRepository(file).find("task-1") == full, "round-trip")
        val repo = FileSpiderTaskRepository(file)
        repo.save(SpiderAgentTask(id="task-2", goal="Другая задача", createdAtEpochMs=300, updatedAtEpochMs=300))
        checkTrue(FileSpiderTaskRepository(file).all().map { it.id } == listOf("task-1", "task-2"), "multiple tasks")
        println("PASS durable round-trip and multiple task persistence")

        val corrupt = File(base, "broken.spider")
        val raw = "not a spider task store".toByteArray()
        corrupt.writeBytes(raw)
        val recovered = FileSpiderTaskRepository(corrupt)
        checkTrue(recovered.all().isEmpty(), "corruption reset")
        checkTrue(recovered.recoveryNotice != null, "recovery notice")
        val backup = base.listFiles()!!.single { it.name.startsWith("broken.spider.corrupt-") }
        checkTrue(backup.readBytes().contentEquals(raw), "corrupt data preserved")
        recovered.save(SpiderAgentTask(id="new", goal="Новая задача"))
        checkTrue(FileSpiderTaskRepository(corrupt).find("new") != null, "write after recovery")
        println("PASS corrupt store preservation and recovery")

        val future = File(base, "future.spider")
        DataOutputStream(FileOutputStream(future)).use { out ->
            out.writeInt(FileSpiderTaskRepository.MAGIC); out.writeInt(FileSpiderTaskRepository.SCHEMA_VERSION + 1); out.writeInt(0)
        }
        val futureBytes = future.readBytes()
        val fail = runCatching { FileSpiderTaskRepository(future).all() }.exceptionOrNull()
        checkTrue(fail is IOException, "future schema rejected")
        checkTrue(future.readBytes().contentEquals(futureBytes), "future schema preserved")
        checkTrue(base.listFiles()!!.none { it.name.startsWith("future.spider.corrupt-") }, "future not quarantined")
        println("PASS future schema is preserved without downgrade overwrite")

        // Legacy v1 task stores remain readable after adding workspace-scoped learning.
        val legacy = File(base, "legacy-v1.spider")
        DataOutputStream(FileOutputStream(legacy)).use { out ->
            fun writeText(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            fun writeNullable(value: String?) {
                out.writeBoolean(value != null)
                if (value != null) writeText(value)
            }
            out.writeInt(FileSpiderTaskRepository.MAGIC)
            out.writeInt(1)
            out.writeInt(1)
            writeText("legacy-task")
            writeText("Legacy html workspace")
            writeText(SpiderTaskStatus.QUEUED.name)
            out.writeLong(100L)
            out.writeLong(200L)
            writeNullable(null) // error
            writeNullable(null) // resume status
            writeNullable(null) // result summary
            out.writeInt(0) // steps
        }
        val legacyRepo = FileSpiderTaskRepository(legacy)
        val legacyTask = legacyRepo.find("legacy-task")
        checkTrue(legacyTask != null && legacyTask.workspaceScopeId == null, "schema v1 task loads without workspace scope")
        legacyRepo.save(legacyTask!!.copy(workspaceScopeId = "legacy-workspace"))
        checkTrue(FileSpiderTaskRepository(legacy).find("legacy-task")?.workspaceScopeId == "legacy-workspace", "legacy task store migrates to schema v2 on save")
        DataInputStream(FileInputStream(legacy)).use { input -> input.readInt(); checkTrue(input.readInt() == 2, "legacy task store rewritten using schema v2") }
        println("PASS v1 task-store migration to workspace-scoped schema v2")

        // The coordinator uses the durable repository via the same public interface.
        val coordinator = SpiderTaskCoordinator(FileSpiderTaskRepository(File(base, "coordinator.spider")), idFactory={"coord-1"}, clock={123L})
        val created = coordinator.createTask("Проверить устойчивость")
        checkTrue(FileSpiderTaskRepository(File(base, "coordinator.spider")).find(created.id) == created, "coordinator persistent repository")
        println("PASS task coordinator can use durable repository")

        // Self-learning memory is driven by terminal task outcomes, user feedback, and safe retrieval.
        val learningFile = File(base, "learning-memory.spider")
        val learningMemory = FileSpiderLearningMemory(learningFile, clock = { 999L })
        val learnedCoordinator = SpiderTaskCoordinator(
            FileSpiderTaskRepository(File(base, "learning-tasks.spider")),
            idFactory = { "learning-task-1" },
            clock = { 400L },
            terminalTaskObserver = { learningMemory.observeTerminalTask(it) }
        )
        var learnedTask = learnedCoordinator.createTask(
            "Создать HTML демо страницу https://example.invalid/?token=super-secret, email private@example.com и путь /data/user/0/private/file"
        )
        learnedTask = learnedCoordinator.beginPlanning(learnedTask.id)
        learnedTask = learnedCoordinator.attachPlan(learnedTask.id, listOf(
            SpiderPlanStep("inspect", "Посмотреть HTML демо", SpiderToolId.LIST_WORKSPACE_FILES)
        ))
        learnedCoordinator.startStep(learnedTask.id, "inspect")
        learnedCoordinator.recordStepResult(learnedTask.id, "inspect", true, "Список проверен")
        learnedCoordinator.finishVerification(learnedTask.id, true, "Успешно проверено")
        checkTrue(learningMemory.count() == 1, "terminal verified task captured in learning memory")
        val memoryOnDisk = learningFile.readBytes().toString(Charsets.ISO_8859_1)
        checkTrue(!memoryOnDisk.contains("super-secret") && !memoryOnDisk.contains("private@example.com") && !memoryOnDisk.contains("/data/user/0/private"), "learning memory stores no raw secrets, emails or file paths")
        val hints = FileSpiderLearningMemory(learningFile).relevantHints("Нужно HTML демо страницу", 5)
        checkTrue(hints.any { it.kind == "verified_success" && "LIST_WORKSPACE_FILES" in it.toolIds }, "relevant verified experience is recalled as typed hint")
        checkTrue(learningMemory.recordFeedback(learnedTask.id, helpful = true), "user feedback is stored")
        checkTrue(FileSpiderLearningMemory(learningFile).relevantHints("HTML демо страницу").any { it.kind == "user_endorsed_success" && it.confidenceScore >= 90 }, "positive feedback raises success hint confidence")
        checkTrue(learningMemory.recordFeedback(learnedTask.id, helpful = false), "negative feedback can update experience")
        checkTrue(FileSpiderLearningMemory(learningFile).relevantHints("HTML демо страницу").any { it.kind == "user_disliked_success" && it.confidenceScore <= 20 }, "negative feedback downgrades prior success rather than blindly reinforcing it")
        val reopenedMemory = FileSpiderLearningMemory(learningFile)
        checkTrue(reopenedMemory.count() == 1, "learning memory persists across repository recreation")
        checkTrue(reopenedMemory.forget(learnedTask.id), "single experience can be forgotten")
        checkTrue(reopenedMemory.count() == 0, "forgetting an experience removes it")
        reopenedMemory.observeTerminalTask(learnedCoordinator.getTask(learnedTask.id)!!)
        checkTrue(reopenedMemory.count() == 1, "terminal outcome can be re-indexed when user chooses to rebuild memory")
        reopenedMemory.clearAll()
        checkTrue(reopenedMemory.count() == 0, "user-requested memory erasure clears memory")
        val ratedPlan = SpiderAgentTask(
            id = "rated-plan", goal = "Настроить browser bookmarks", status = SpiderTaskStatus.RUNNING,
            steps = listOf(SpiderPlanStep("plan-1", "Просмотреть список файлов", SpiderToolId.LIST_WORKSPACE_FILES))
        )
        checkTrue(reopenedMemory.recordFeedback(ratedPlan, helpful = true), "user can rate a plan before execution")
        val ratedPlanHint = reopenedMemory.relevantHints("browser bookmarks").single()
        checkTrue(ratedPlanHint.kind == "user_endorsed_plan" && ratedPlanHint.confidenceScore < 78, "plan rating is lower confidence than verified task success")
        checkTrue(reopenedMemory.listExperiences().single().outcome == SpiderLearningOutcome.UNKNOWN, "plan feedback alone never becomes a verified success")
        checkTrue(reopenedMemory.listExperiences().single().toolIds.isEmpty(), "brain does not learn an executable tool sequence from an unexecuted plan")
        reopenedMemory.observeTerminalTask(ratedPlan.copy(status = SpiderTaskStatus.COMPLETED, updatedAtEpochMs = 1000L, resultSummary = "verified"))
        checkTrue(reopenedMemory.relevantHints("browser bookmarks").single().kind == "user_endorsed_success", "later verified outcome upgrades the same experience and keeps feedback")
        checkTrue(reopenedMemory.relevantHints("unrelated audio transcript").isEmpty(), "irrelevant experiences are not injected into planning context")
        val failedTask = SpiderAgentTask(
            id = "failed-memory", goal = "Собрать html страницу", status = SpiderTaskStatus.FAILED,
            steps = listOf(SpiderPlanStep("s1", "Список файлов", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.FAILED)),
            lastErrorCode = "plan_failed"
        )
        reopenedMemory.observeTerminalTask(failedTask)
        checkTrue(reopenedMemory.relevantHints("html страницу").any { it.kind == "prior_failure" }, "failed episode is surfaced as caution, not as a success lesson")
        // Experience retrieval is scoped to the selected workspace; project A cannot leak to B.
        val scopedMemoryFile = File(base, "scoped-learning.spider")
        val scopedMemory = FileSpiderLearningMemory(scopedMemoryFile, clock = { 2000L })
        scopedMemory.observeTerminalTask(SpiderAgentTask(
            id = "scope-a-success", goal = "Build html css project", status = SpiderTaskStatus.COMPLETED,
            updatedAtEpochMs = 2000L, workspaceScopeId = "workspace-a"
        ))
        checkTrue(scopedMemory.relevantHints("Build html css project", scopeId = "workspace-a").isNotEmpty(), "matching project can retrieve its verified history")
        checkTrue(scopedMemory.relevantHints("Build html css project", scopeId = "workspace-b").isEmpty(), "other project cannot see scoped experience")
        checkTrue(scopedMemory.relevantHints("Build html css project").isEmpty(), "unscoped tasks do not silently import project-private experience")
        scopedMemory.observeTerminalTask(SpiderAgentTask(
            id = "global-success", goal = "Build html css project", status = SpiderTaskStatus.COMPLETED,
            updatedAtEpochMs = 2001L
        ))
        checkTrue(scopedMemory.relevantHints("Build html css project", scopeId = "workspace-b").any { it.kind == "verified_success" }, "only explicit global experiences may be reused across workspaces")
        checkTrue(scopedMemory.listExperiences().any { it.scopeId == "workspace-a" && it.taskId == "scope-a-success" }, "learning scope persists on disk")
        checkTrue(FileSpiderLearningMemory(scopedMemoryFile).relevantHints("Build html css project", scopeId = "workspace-a").any { it.kind == "verified_success" }, "schema v2 learning memory reloads scoped history")
        println("PASS workspace-scoped learning retrieval prevents cross-project memory leakage")

        // Learning schema v1 is migrated to v2 on the next terminal task observation.
        val legacyLearningFile = File(base, "legacy-learning-v1.spider")
        DataOutputStream(FileOutputStream(legacyLearningFile)).use { out ->
            fun writeText(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            fun writeNullable(value: String?) {
                out.writeBoolean(value != null)
                if (value != null) writeText(value)
            }
            out.writeInt(0x53504C4D) // SPLM memory signature
            out.writeInt(1)
            out.writeInt(1)
            writeText("legacy-memory-task")
            out.writeLong(1000L)
            out.writeInt(2); writeText("html"); writeText("css")
            writeText(SpiderLearningOutcome.VERIFIED_SUCCESS.name)
            writeNullable(null)
            out.writeInt(1); writeText(SpiderToolId.LIST_WORKSPACE_FILES.name)
            out.writeBoolean(true)
            writeNullable(null)
        }
        val legacyLearning = FileSpiderLearningMemory(legacyLearningFile, clock = { 3000L })
        checkTrue(legacyLearning.listExperiences().single().scopeId == "global", "schema v1 learning memory loads as global scope")
        legacyLearning.observeTerminalTask(SpiderAgentTask(
            id = "legacy-memory-task", goal = "Build html css project", status = SpiderTaskStatus.COMPLETED,
            updatedAtEpochMs = 3000L
        ))
        DataInputStream(FileInputStream(legacyLearningFile)).use { input -> input.readInt(); checkTrue(input.readInt() == 2, "legacy learning memory rewritten to schema v2") }
        checkTrue(FileSpiderLearningMemory(legacyLearningFile).listExperiences().single().scopeId == "global", "schema v2 keeps legacy memory global")
        println("PASS v1 learning-memory migration to workspace-scoped schema v2")

        // Time-aware ranking: fresh useful feedback outranks stale raw success, without rewriting truth.
        val recencyNow = 1_800_000_000_000L
        val recencyFile = File(base, "learning-recency.spider")
        val recencyMemory = FileSpiderLearningMemory(recencyFile, clock = { recencyNow })
        recencyMemory.observeTerminalTask(SpiderAgentTask(
            id = "stale-success", goal = "Build html css project", status = SpiderTaskStatus.COMPLETED,
            updatedAtEpochMs = recencyNow - 400L * 86_400_000L
        ))
        val freshRatedPlan = SpiderAgentTask(
            id = "fresh-rated-plan", goal = "Build html css project", status = SpiderTaskStatus.RUNNING,
            updatedAtEpochMs = recencyNow
        )
        checkTrue(recencyMemory.recordFeedback(freshRatedPlan, helpful = true), "fresh plan feedback is stored")
        val ranked = recencyMemory.relevantHints("Build html css project", 5)
        checkTrue(ranked.size == 2 && ranked.first().kind == "user_endorsed_plan" && ranked.last().kind == "verified_success", "time-aware retrieval prioritizes recent relevant feedback over stale success")
        checkTrue(recencyMemory.listExperiences().first { it.taskId == "stale-success" }.verified, "recency never downgrades a stale verified outcome")
        checkTrue(recencyMemory.listExperiences().first { it.taskId == "fresh-rated-plan" }.outcome == SpiderLearningOutcome.UNKNOWN, "recency does not turn plan feedback into verified execution")
        println("PASS time-aware retrieval ranking preserves outcome truth and prioritizes fresh evidence")

        val capFile = File(base, "learning-cap.spider")
        val capMemory = FileSpiderLearningMemory(capFile, clock = { 1234L })
        repeat(8) { index ->
            capMemory.observeTerminalTask(SpiderAgentTask(
                id = "cap-$index", goal = "HTML demo project $index", status = SpiderTaskStatus.COMPLETED,
                updatedAtEpochMs = 2000L + index
            ))
        }
        checkTrue(capMemory.relevantHints("HTML demo project", 99).size <= FileSpiderLearningMemory.MAX_HINTS, "retrieval is capped even when caller requests more")
        // Semantic retrieval links Russian/English aliases even when exact keywords differ.
        val semanticSynonymScore = SpiderSemanticSearch.similarity(
            listOf("просмотр", "каталоги", "рабочее", "пространство"),
            listOf("inspect", "directory", "workspace", "files")
        )
        val unrelatedScore = SpiderSemanticSearch.similarity(
            listOf("просмотр", "каталоги", "рабочее", "пространство"),
            listOf("audio", "music", "soundtrack")
        )
        checkTrue(semanticSynonymScore > unrelatedScore && semanticSynonymScore >= 0.13, "offline semantic vector retrieval matches cross-language aliases")
        println("PASS multilingual concept-vector semantic retrieval and unrelated-topic separation")

        // Keep a small legacy-file smoke here: rewriting the old whole-file format per insertion is
        // intentionally expensive. The SQLite instrumentation test exercises 540-row migration and
        // retention in the new production store.
        val growthFile = File(base, "learning-growth-legacy.spider")
        val growthMemory = FileSpiderLearningMemory(growthFile, clock = { 50_000L })
        repeat(40) { index ->
            growthMemory.observeTerminalTask(SpiderAgentTask(
                id = "growth-$index", goal = "inspect workspace files directory", status = SpiderTaskStatus.COMPLETED,
                updatedAtEpochMs = 60_000L + index
            ))
        }
        checkTrue(growthMemory.count() == 40, "legacy memory round-trips a sequence of episodes")
        checkTrue(FileSpiderLearningMemory(growthFile).listExperiences(100).size == 40, "legacy episodes survive a full store reopen")
        println("PASS legacy learning memory round-trip; SQLite >500 episode acceptance is an Android instrumentation test")

        // Neural embeddings are optional; the normalized vector index persists by task/model ID.
        val embeddingFile = File(base, "semantic-embeddings.spider")
        val embeddingIndex = FileSpiderEmbeddingIndex(embeddingFile)
        val embeddingA = SpiderDenseEmbedding("test-embed-v1", floatArrayOf(3f, 4f, 0f, 0f, 0f, 0f, 0f, 0f))
        val embeddingB = SpiderDenseEmbedding("test-embed-v1", floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f))
        embeddingIndex.putAll(mapOf("embedding-task-a" to embeddingA, "embedding-task-b" to embeddingB))
        val loadedEmbedding = FileSpiderEmbeddingIndex(embeddingFile).get("embedding-task-a", "test-embed-v1")
        checkTrue(loadedEmbedding != null && kotlin.math.abs(loadedEmbedding.values.sumOf { it.toDouble() * it.toDouble() } - 1.0) < 0.0001, "embedding vectors are normalized and persisted")
        checkTrue(FileSpiderEmbeddingIndex(embeddingFile).get("embedding-task-a", "different-model") == null, "embedding vectors from different model IDs are never compared")
        checkTrue(kotlin.math.abs((cosineSimilarity(embeddingA, embeddingB) ?: -1.0) - 0.8) < 0.0001, "dense-vector cosine similarity is correctly calculated")
        checkTrue(embeddingIndex.count() == 2, "multiple dense vectors are indexed")

        // Neural vector scores participate in real memory ranking, while unindexed episodes retain offline retrieval.
        val hybridMemoryFile = File(base, "learning-hybrid-search.spider")
        val hybridMemory = FileSpiderLearningMemory(hybridMemoryFile, clock = { 77_000L })
        hybridMemory.observeTerminalTask(SpiderAgentTask(
            id = "hybrid-audio", goal = "audio music soundtrack", status = SpiderTaskStatus.COMPLETED,
            updatedAtEpochMs = 77_000L
        ))
        hybridMemory.observeTerminalTask(SpiderAgentTask(
            id = "hybrid-workspace", goal = "inspect directory workspace files", status = SpiderTaskStatus.COMPLETED,
            updatedAtEpochMs = 77_001L
        ))
        val queryVector = SpiderDenseEmbedding("test-embed-v1", floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f))
        val hybridVectors = mapOf(
            "hybrid-audio" to SpiderDenseEmbedding("test-embed-v1", floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            "hybrid-workspace" to queryVector
        )
        val hybridHints = hybridMemory.relevantHints(
            "просмотр каталогов рабочего пространства", 5, queryEmbedding = queryVector, semanticIndex = hybridVectors
        )
        checkTrue(hybridHints.firstOrNull()?.topicTokens?.contains("workspace") == true, "dense embedding reranks the semantically matching episode ahead of unrelated history")
        checkTrue(hybridHints.none { "soundtrack" in it.topicTokens }, "unrelated embedding is excluded from the matched task context")
        println("PASS hybrid semantic ranking combines local multilingual features with model-specific embeddings")

        // Verified skills need independent evidence from different task IDs and cannot expand permissions.
        val skillFile = File(base, "skill-library.spider")
        val skillLibrary = FileSpiderSkillLibrary(skillFile, clock = { 70_000L })
        skillLibrary.ensureBuiltInCandidates()
        fun inventorySkill() = skillLibrary.listSkills().first { it.id == "inspect_workspace_inventory_v1" }
        checkTrue(skillLibrary.listSkills().size == 5 && skillLibrary.listSkills().all { it.status == SpiderSkillStatus.CANDIDATE }, "five real workspace tools start as non-runnable skill candidates")
        checkTrue(skillLibrary.recommend("inspect workspace directory files", workspaceSelected = true).isEmpty(), "unverified skills are not recommended")
        checkTrue(!skillLibrary.recordEvidence("inspect_workspace_inventory_v1", "evidence-wrong-verifier", true, "FakeVerifier", "LIST_WORKSPACE_FILES@1"), "skill rejects unknown verifier evidence")
        checkTrue(!skillLibrary.recordEvidence("inspect_workspace_inventory_v1", "evidence-unsupported-tool", true, "WorkspaceListingResultVerifier", "RUN_SHELL@1"), "skill rejects unsupported tool version evidence")
        checkTrue(skillLibrary.recordVerifiedBuiltInToolSuccess(SpiderToolId.SEARCH_WORKSPACE_CONTENT, "learned-search-task"), "independently verified search success teaches the internal skill library")
        checkTrue(skillLibrary.recordVerifiedBuiltInToolSuccess(SpiderToolId.READ_WORKSPACE_FILE, "learned-read-task"), "independently verified read success teaches the internal skill library")
        checkTrue(skillLibrary.recordVerifiedBuiltInToolSuccess(SpiderToolId.WRITE_WORKSPACE_FILE, "learned-write-task"), "independently verified write success teaches the internal skill library")
        checkTrue(skillLibrary.recordVerifiedBuiltInToolSuccess(SpiderToolId.CHECK_WORKSPACE_PROJECT, "learned-static-check-task"), "independently verified static-check success teaches the internal skill library")
        checkTrue(skillLibrary.listSkills().first { it.id == "search_workspace_content_v1" }.verifiedRuns == 1 &&
            skillLibrary.listSkills().first { it.id == "read_workspace_file_v1" }.verifiedRuns == 1 &&
            skillLibrary.listSkills().first { it.id == "write_workspace_file_v1" }.verifiedRuns == 1 &&
            skillLibrary.listSkills().first { it.id == "static_project_check_v1" }.verifiedRuns == 1,
            "search/read/write/static-check successes are stored under their own verifier/version contracts")
        checkTrue(!skillLibrary.recordVerifiedBuiltInToolSuccess(SpiderToolId.DELETE_WORKSPACE_FILE, "unsupported-delete-learn"), "unsupported tools cannot generate learned skills")
        repeat(3) { index ->
            checkTrue(skillLibrary.recordEvidence("inspect_workspace_inventory_v1", "verified-task-$index", true, "WorkspaceListingResultVerifier", "LIST_WORKSPACE_FILES@1"), "independent successful evidence $index is recorded")
        }
        checkTrue(inventorySkill().status == SpiderSkillStatus.VERIFIED, "three independent verifier-backed successes verify the skill")
        checkTrue(skillLibrary.recommend("посмотреть структуру каталога проекта", workspaceSelected = true).isNotEmpty(), "verified skill is recommended using semantic similarity")
        checkTrue(skillLibrary.recommend("посмотреть структуру каталога проекта", workspaceSelected = false).isEmpty(), "skill precondition is mandatory")
        checkTrue(!skillLibrary.recordEvidence("inspect_workspace_inventory_v1", "verified-task-0", true, "WorkspaceListingResultVerifier", "LIST_WORKSPACE_FILES@1"), "same task cannot be counted twice")
        checkTrue(FileSpiderSkillLibrary(skillFile).listSkills().first { it.id == "inspect_workspace_inventory_v1" }.verifiedRuns == 3, "skill status and evidence survive process restart")
        checkTrue(skillLibrary.recordEvidence("inspect_workspace_inventory_v1", "failed-task-unique", false, "WorkspaceListingResultVerifier", "LIST_WORKSPACE_FILES@1"), "independently verified failure is recorded")
        checkTrue(inventorySkill().status == SpiderSkillStatus.NEEDS_REVALIDATION, "verified skill is demoted after verified failure")
        repeat(2) { index ->
            checkTrue(skillLibrary.recordEvidence("inspect_workspace_inventory_v1", "revalidation-pass-$index", true, "WorkspaceListingResultVerifier", "LIST_WORKSPACE_FILES@1"), "fresh revalidation pass $index is recorded")
        }
        checkTrue(inventorySkill().status == SpiderSkillStatus.NEEDS_REVALIDATION, "old successes cannot instantly rehabilitate a skill after two new passes")
        checkTrue(skillLibrary.recordEvidence("inspect_workspace_inventory_v1", "revalidation-pass-2", true, "WorkspaceListingResultVerifier", "LIST_WORKSPACE_FILES@1"), "third consecutive revalidation pass is recorded")
        checkTrue(inventorySkill().status == SpiderSkillStatus.VERIFIED, "skill is rehabilitated only after three fresh consecutive verified passes")
        checkTrue(FileSpiderSkillLibrary(skillFile).listSkills().first { it.id == "inspect_workspace_inventory_v1" }.status == SpiderSkillStatus.VERIFIED, "revalidated skill and evidence survive reload")
        println("PASS persistent verified-skill library learns all five real workspace tools from verifier-backed evidence, with distinct-task/stale-failure controls")

        val corruptMemoryFile = File(base, "corrupt-learning.spider")
        val originalMemoryBytes = "not a valid learning memory store".toByteArray()
        corruptMemoryFile.writeBytes(originalMemoryBytes)
        val corruptMemory = FileSpiderLearningMemory(corruptMemoryFile, clock = { 4321L })
        checkTrue(corruptMemory.count() == 0 && corruptMemory.recoveryNotice != null, "corrupt memory is recovered with warning")
        val memoryBackup = base.listFiles()!!.single { it.name.startsWith("corrupt-learning.spider.corrupt-") }
        checkTrue(memoryBackup.readBytes().contentEquals(originalMemoryBytes), "corrupt learning memory is preserved")
        println("PASS outcome memory, scoped retrieval relevance, feedback, cautionary failures, caps, privacy and recovery")

        val executedToolMemory = FileSpiderLearningMemory(File(base, "executed-tool-memory.spider"), clock = { 1500L })
        executedToolMemory.observeTerminalTask(SpiderAgentTask(
            id = "verified-tools", goal = "Search read and write project documentation", status = SpiderTaskStatus.COMPLETED,
            steps = listOf(
                SpiderPlanStep("planned-only", "This step was never run", SpiderToolId.LIST_WORKSPACE_FILES, SpiderStepStatus.PENDING),
                SpiderPlanStep("search", "Search actual text", SpiderToolId.SEARCH_WORKSPACE_CONTENT, SpiderStepStatus.SUCCEEDED),
                SpiderPlanStep("read", "Read actual text", SpiderToolId.READ_WORKSPACE_FILE, SpiderStepStatus.SUCCEEDED),
                SpiderPlanStep("write", "Write actual text", SpiderToolId.WRITE_WORKSPACE_FILE, SpiderStepStatus.SUCCEEDED)
            )
        ))
        val learnedTools = executedToolMemory.listExperiences().single().toolIds.toSet()
        checkTrue(learnedTools == setOf(SpiderToolId.SEARCH_WORKSPACE_CONTENT, SpiderToolId.READ_WORKSPACE_FILE, SpiderToolId.WRITE_WORKSPACE_FILE), "internal brain only retains tool IDs from actually executed, verified successful steps")
        checkTrue(executedToolMemory.relevantHints("Search read and write project documentation").any { it.toolIds.toSet() == setOf("SEARCH_WORKSPACE_CONTENT", "READ_WORKSPACE_FILE", "WRITE_WORKSPACE_FILE") }, "experience memory teaches the real tool sequence for similar tasks")
        println("PASS internal brain learns verified action sequence, never planned-but-unexecuted tools")

        // First concrete low-risk tool: bounded workspace-name listing through the policy broker.
        val workspace = File(base, "workspace").apply { mkdirs() }
        File(workspace, "index.html").writeText("NEVER RETURN FILE CONTENTS")
        File(workspace, ".spider-workspace.json").writeText("INTERNAL METADATA")
        val sourceDir = File(workspace, "src").apply { mkdirs() }
        File(sourceDir, "main.kt").writeText("fun main() = Unit")
        val auditEvents = mutableListOf<String>()
        val auditSink = SpiderAuditSink { _, _, _, _, code, _ -> auditEvents.add(code); true }
        val listingHandler = WorkspaceFileListHandler { id -> if (id == "ws-1") workspace else null }
        val broker = SpiderToolBroker(listOf(listingHandler), auditSink)
        val result = broker.execute(SpiderToolRequest("request-1", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List files in selected workspace", "ws-1"))
        checkTrue(result.status == SpiderToolCallStatus.SUCCEEDED, "read-only tool completed")
        checkTrue(result.message.contains("index.html") && result.message.contains("src/"), "tool returns workspace names")
        checkTrue(!result.message.contains("NEVER RETURN FILE CONTENTS"), "tool does not return file contents")
        checkTrue(!result.message.contains(".spider-workspace.json"), "internal workspace marker is hidden")
        checkTrue(auditEvents.firstOrNull() == "execution_started" && auditEvents.lastOrNull() == "workspace_listed", "audit surrounds tool execution")
        val missing = broker.execute(SpiderToolRequest("request-2", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List files", "missing-workspace"))
        checkTrue(missing.status == SpiderToolCallStatus.FAILED, "missing workspace fails closed")
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "outside-private-name.txt").writeText("must not be enumerated")
        val link = File(workspace, "outside-link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            val symlinkResult = listingHandler.execute(SpiderToolRequest("request-3", "task-1", SpiderToolId.LIST_WORKSPACE_FILES, "List files", "ws-1"))
            checkTrue(!symlinkResult.summary.contains("outside-private-name.txt"), "symlink outside workspace is not traversed")
        } catch (_: UnsupportedOperationException) {
            // Some restricted platforms disallow symlink creation; the separate JUnit test is conditional too.
        } catch (_: java.io.IOException) {
            // Some restricted platforms disallow symlink creation; don't fail the other safety assertions.
        }
        println("PASS real read-only workspace-list handler through policy broker and audit")

        // The real app-private audit reader returns a bounded allowlist, not summaries or request IDs.
        val auditContext = object : Context() { override val filesDir: File = File(base, "app-private") }
        val safeAuditLog = SpiderAuditLog(auditContext)
        val auditDecision = SpiderPolicyDecision(SpiderPolicyDisposition.ALLOW, SpiderToolRisk.LOW, "policy_passed", "Allowed")
        checkTrue(safeAuditLog.append(
            "history-task", "private-request-id", SpiderToolId.LIST_WORKSPACE_FILES, auditDecision,
            "workspace_listed", "Done at /data/user/0/private and https://example.invalid/?token=secret"
        ), "audit event is written")
        val auditRefs = safeAuditLog.safeReferencesForTask("history-task")
        checkTrue(auditRefs.size == 1, "matching safe audit reference is returned")
        checkTrue(auditRefs.single().toolId == SpiderToolId.LIST_WORKSPACE_FILES.name, "audit reference exposes validated tool enum")
        checkTrue(auditRefs.single().resultCode == "workspace_listed", "audit reference exposes restricted result code")
        checkTrue(auditRefs.none { it.referenceId.contains("private-request") }, "request ID is not exposed as audit reference")
        println("PASS safe task-history audit references exclude raw summaries and request IDs")

        val verifier = WorkspaceListingResultVerifier { id -> if (id == "ws-1") workspace else null }
        checkTrue(verifier.verify("ws-1", result).verified, "real handler evidence matches independent filesystem walk")
        checkTrue(!verifier.verify("ws-1", result.copy(auditRecorded = false)).verified, "missing audit fails verification")
        checkTrue(!verifier.verify("missing", result).verified, "missing workspace fails verification")
        checkTrue(!verifier.verify("ws-1", result.copy(resultCode = "other")).verified, "unexpected tool result fails verification")
        checkTrue(!verifier.verify("ws-1", result.copy(verificationEvidence = null)).verified, "missing inventory evidence fails verification")
        val parsedProof = WorkspaceListingEvidence.parse(result.verificationEvidence)!!
        val forgedDigest = result.copy(verificationEvidence = parsedProof.copy(inventorySha256 = "0".repeat(64)).encode())
        checkTrue(!verifier.verify("ws-1", forgedDigest).verified, "forged inventory digest fails verification")
        val truncatedProof = WorkspaceListingEvidence.fromPaths("ws-1", listOf("index.html", "src/"), true)!!.encode()
        checkTrue(!verifier.verify("ws-1", result.copy(verificationEvidence = truncatedProof)).verified, "truncated inventory is not marked complete")
        File(workspace, "changed-after-tool.txt").writeText("workspace changed")
        val changedVerification = verifier.verify("ws-1", result)
        checkTrue(!changedVerification.verified && changedVerification.code == "verification_inventory_mismatch", "post-action inventory change prevents completion")
        println("PASS workspace-list result verifier independently checks filesystem inventory and fails closed on mismatch")

        var finalAuditEvents = 0
        val auditFailureBroker = SpiderToolBroker(
            listOf(listingHandler),
            SpiderAuditSink { _, _, _, _, code, _ ->
                finalAuditEvents++
                code == "execution_started"
            },
            requestLedger = InMemorySpiderRequestLedger()
        )
        val auditFailureResult = auditFailureBroker.execute(
            SpiderToolRequest("audit-final-failure", "task-audit", SpiderToolId.LIST_WORKSPACE_FILES, "List names", "ws-1")
        )
        checkTrue(finalAuditEvents >= 2, "preflight and final audit are attempted")
        checkTrue(auditFailureResult.status == SpiderToolCallStatus.FAILED, "failed final audit cannot be reported as success")
        checkTrue(!auditFailureResult.auditRecorded && auditFailureResult.verificationEvidence == null, "unconfirmed final audit suppresses verification evidence")
        println("PASS final audit failure cannot mark a tool call successful")

        // Request claims survive creation of a new broker/ledger instance, as after process restart.
        val ledgerFile = File(base, "request-ledger.spider")
        var dispatchCount = 0
        val countedHandler = object : SpiderToolHandler {
            override val toolId = SpiderToolId.LIST_WORKSPACE_FILES
            override val displayName = "counted read-only handler"
            override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult {
                dispatchCount++
                return SpiderToolHandlerResult(true, "workspace_listed", "Найдено элементов: 0. Рабочее пространство пусто.")
            }
        }
        val durableRequest = SpiderToolRequest("durable-request-42", "task-ledger", SpiderToolId.LIST_WORKSPACE_FILES, "List names", "ws-1")
        val firstBroker = SpiderToolBroker(listOf(countedHandler), auditSink, requestLedger = FileSpiderRequestLedger(ledgerFile))
        checkTrue(firstBroker.execute(durableRequest).status == SpiderToolCallStatus.SUCCEEDED, "first durable request runs")
        val afterRestartBroker = SpiderToolBroker(listOf(countedHandler), auditSink, requestLedger = FileSpiderRequestLedger(ledgerFile))
        val replay = afterRestartBroker.execute(durableRequest)
        checkTrue(replay.status == SpiderToolCallStatus.DENIED && replay.resultCode == "duplicate_request_id", "replay is denied after new broker instance")
        checkTrue(dispatchCount == 1, "durable replay never calls handler twice")
        println("PASS durable one-time request claim prevents replay across broker recreation")

        val corruptLedger = File(base, "corrupt-request-ledger.spider")
        val corruptLedgerBytes = "invalid-ledger".toByteArray()
        corruptLedger.writeBytes(corruptLedgerBytes)
        val failClosedClaim = FileSpiderRequestLedger(corruptLedger).claim("request-after-corruption")
        checkTrue(failClosedClaim == SpiderRequestClaimResult.STORAGE_UNAVAILABLE, "corrupt request ledger fails closed")
        checkTrue(corruptLedger.readBytes().contentEquals(corruptLedgerBytes), "corrupt request ledger is not silently overwritten")
        println("PASS corrupt request ledger is preserved and blocks new executions")

        // Simulate process death during an executing task, then recover from the durable store.
        val crashFile = File(base, "interrupted-tasks.spider")
        val beforeRestart = SpiderTaskCoordinator(FileSpiderTaskRepository(crashFile), idFactory={"crashed-task"}, clock={10L})
        val inFlight = beforeRestart.createTask("Inspect workspace")
        beforeRestart.beginPlanning(inFlight.id)
        beforeRestart.attachPlan(inFlight.id, listOf(SpiderPlanStep("list", "List names", SpiderToolId.LIST_WORKSPACE_FILES)))
        beforeRestart.startStep(inFlight.id, "list")
        val afterRestart = SpiderTaskCoordinator(FileSpiderTaskRepository(crashFile), clock={20L})
        val recoveredTask = afterRestart.recoverInterruptedTasks().single()
        checkTrue(recoveredTask.status == SpiderTaskStatus.FAILED, "interrupted task is failed closed")
        checkTrue(recoveredTask.lastErrorCode == "execution_interrupted_unknown_outcome", "recovery identifies unknown tool outcome")
        checkTrue(recoveredTask.steps.single().status == SpiderStepStatus.FAILED, "in-flight step is not replayed")
        checkTrue(afterRestart.recoverInterruptedTasks().isEmpty(), "recovery is idempotent")
        println("PASS task recovery marks interrupted execution and never auto-replays it")

        val approvalFile = File(base, "interrupted-approval.spider")
        val approvalCoordinator = SpiderTaskCoordinator(FileSpiderTaskRepository(approvalFile), idFactory={"approval-task"}, clock={30L})
        val approvalTask = approvalCoordinator.createTask("Run high risk action")
        approvalCoordinator.beginPlanning(approvalTask.id)
        approvalCoordinator.attachPlan(approvalTask.id, listOf(SpiderPlanStep("guarded", "Guarded step", SpiderToolId.DELETE_WORKSPACE_FILE)))
        approvalCoordinator.startStep(approvalTask.id, "guarded")
        approvalCoordinator.requireApproval(approvalTask.id, "guarded")
        val approvalRecovered = SpiderTaskCoordinator(FileSpiderTaskRepository(approvalFile), clock={40L}).recoverInterruptedTasks().single()
        checkTrue(approvalRecovered.lastErrorCode == "approval_expired_after_restart", "approval is expired after restart")
        checkTrue(approvalRecovered.steps.single().status == SpiderStepStatus.SKIPPED, "approval-waiting step is not auto-run")
        println("PASS pending approval expires safely after restart")

        val settingsContext = object : Context() { override val filesDir: File = File(base, "runtime-settings") }
        val learningRuntime = SpiderAgentRuntime.get(settingsContext)
        checkTrue(learningRuntime.isLearningEnabled(), "self-learning defaults to enabled for this requested feature")
        learningRuntime.setLearningEnabled(false)
        checkTrue(!learningRuntime.isLearningEnabled(), "user can pause experience capture and planner retrieval")
        learningRuntime.setLearningEnabled(true)
        checkTrue(learningRuntime.isLearningEnabled(), "user can resume self-learning without erasing stored memory")
        println("PASS user-controllable self-learning pause/resume setting")

        val capabilityStates = SpiderCapabilityCatalog.snapshot(learningRuntime.capabilityFlags()).associateBy { it.id }
        checkTrue(capabilityStates.getValue("floating_overlay").state == SpiderCapabilityState.PERMISSION_REQUIRED, "capability guide reflects missing overlay permission")
        checkTrue(capabilityStates.getValue("local_llm").state == SpiderCapabilityState.SOURCE_UNVERIFIED, "capability guide does not pretend local engine is built")
        checkTrue(capabilityStates.getValue("yandex_cloud").state == SpiderCapabilityState.CONFIGURATION_REQUIRED, "capability guide reflects missing provider configuration")
        checkTrue(capabilityStates.getValue("workspace_inventory").state == SpiderCapabilityState.READY, "real workspace listing tool is identified")
        checkTrue(capabilityStates.getValue("external_app_control").state == SpiderCapabilityState.NOT_CONNECTED, "external-app automation is not invented")
        val capabilityPrompt = learningRuntime.capabilityGuide()
        checkTrue(capabilityPrompt.contains("LIST_WORKSPACE_FILES") && !capabilityPrompt.contains("EXECUTE_SHELL"), "model receives the current allowlisted capability map")
        checkTrue(capabilityStates.getValue("agent_execution").state == SpiderCapabilityState.READY, "all registered workspace tools are reported as executable")
        println("PASS dynamic capability guide reflects runtime flags and real tool limits")

        runWorkspaceEditSmoke()
        runWorkspaceTextToolsSmoke(base)
        runWorkspaceProjectCheckSmoke(base)
        println("ALL 15 Agent Core smoke scenarios passed, including actual workspace read/search/write tools and guarded AI-edit path")
    } finally { base.deleteRecursively() }
}
