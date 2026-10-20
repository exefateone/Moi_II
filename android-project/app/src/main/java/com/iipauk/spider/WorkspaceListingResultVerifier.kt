package com.iipauk.spider

import java.io.File
import java.nio.file.Files

/** Result for a verification pass independent from the handler's own listing operation. */
data class SpiderResultVerification(
    val verified: Boolean,
    val code: String,
    val summary: String
)

/**
 * Re-reads the bounded filesystem inventory after the tool returns and compares a compact digest.
 * A truncated inventory is not accepted as complete evidence: callers must split/retry a larger
 * project in a future paginated tool rather than mark a partial listing complete.
 */
class WorkspaceListingResultVerifier(
    private val directoryForWorkspace: (workspaceId: String) -> File?
) {
    fun verify(workspaceId: String, result: SpiderToolCallResult): SpiderResultVerification {
        if (workspaceId.isBlank() || workspaceId.length > MAX_WORKSPACE_ID_LENGTH) {
            return failure("verification_workspace_id_invalid", "Проверка результата не пройдена: выбранное рабочее пространство некорректно.")
        }
        if (result.status != SpiderToolCallStatus.SUCCEEDED) {
            return failure("verification_tool_not_succeeded", "Проверка результата не пройдена: инструмент не сообщил об успешном выполнении.")
        }
        if (!result.auditRecorded) {
            return failure("verification_audit_missing", "Проверка результата не пройдена: нет подтверждённой записи аудита.")
        }
        if (result.resultCode != EXPECTED_RESULT_CODE) {
            return failure("verification_result_code_mismatch", "Проверка результата не пройдена: код результата не соответствует списку рабочего пространства.")
        }
        val summaryMatch = SUMMARY_PATTERN.find(result.message)
            ?: return failure("verification_summary_invalid", "Проверка результата не пройдена: сводка инструмента не соответствует ожидаемому формату.")
        if (result.message.isBlank() || result.message.length > SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH) {
            return failure("verification_summary_invalid", "Проверка результата не пройдена: сводка инструмента не соответствует ожидаемому формату.")
        }
        val summaryCount = summaryMatch.groupValues[1].toIntOrNull()
            ?: return failure("verification_summary_invalid", "Проверка результата не пройдена: невозможно проверить количество элементов.")
        val evidence = WorkspaceListingEvidence.parse(result.verificationEvidence)
            ?: return failure("verification_evidence_missing", "Проверка результата не пройдена: проверяемое доказательство инвентаризации отсутствует или повреждено.")
        if (evidence.workspaceId != workspaceId) {
            return failure("verification_evidence_workspace_mismatch", "Проверка результата не пройдена: доказательство относится к другому рабочему пространству.")
        }
        if (evidence.entryCount != summaryCount) {
            return failure("verification_evidence_count_mismatch", "Проверка результата не пройдена: число элементов в сводке не совпадает с доказательством.")
        }
        if (evidence.truncated) {
            return failure("verification_inventory_truncated", "Проверка результата не пройдена: список ограничен и не может считаться полным.")
        }

        val suppliedRoot = runCatching { directoryForWorkspace(workspaceId) }.getOrNull()
            ?: return failure("verification_workspace_unavailable", "Проверка результата не пройдена: рабочее пространство больше недоступно.")
        val root = runCatching { suppliedRoot.canonicalFile }.getOrNull()
            ?: return failure("verification_workspace_path_invalid", "Проверка результата не пройдена: путь рабочего пространства недоступен.")
        if (!root.isDirectory) {
            return failure("verification_workspace_unavailable", "Проверка результата не пройдена: рабочее пространство больше недоступно.")
        }

        val inventory = runCatching { independentlyEnumerate(root) }.getOrNull()
            ?: return failure("verification_inventory_unavailable", "Проверка результата не пройдена: не удалось повторно проверить список элементов.")
        if (inventory.truncated) {
            return failure("verification_inventory_truncated", "Проверка результата не пройдена: текущее рабочее пространство превышает безопасный лимит полной проверки.")
        }
        if (inventory.paths.size != evidence.entryCount || WorkspaceListingEvidence.digestPaths(inventory.paths) != evidence.inventorySha256) {
            return failure("verification_inventory_mismatch", "Проверка результата не пройдена: состав рабочего пространства изменился или не совпадает с результатом инструмента.")
        }
        return SpiderResultVerification(
            verified = true,
            code = "workspace_listing_verified",
            summary = "Проверка пройдена: состав рабочего пространства независимо перечитан и совпадает с доказательством инструмента; запись аудита подтверждена. Содержимое файлов не читалось."
        )
    }

    /** Fresh NIO walk does not follow symbolic links; it is separate from the handler's queue walk. */
    private fun independentlyEnumerate(root: File): InventorySnapshot {
        val paths = mutableListOf<String>()
        var truncated = false
        val rootPath = root.toPath()
        Files.walk(rootPath, MAX_DEPTH + 1).use { entries ->
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == rootPath) continue
                val fileName = path.fileName?.toString() ?: continue
                if (fileName == WORKSPACE_MARKER || fileName == "$WORKSPACE_MARKER.tmp") continue
                if (Files.isSymbolicLink(path)) continue
                val canonical = path.toFile().canonicalFile
                if (!isWithinRoot(root.path, canonical.path)) continue
                val isDirectory = Files.isDirectory(path)
                val isFile = Files.isRegularFile(path)
                if (!isDirectory && !isFile) continue
                val relative = rootPath.relativize(path).toString().replace(File.separatorChar, '/')
                paths += if (isDirectory) "$relative/" else relative
                val depth = relative.count { it == '/' } + 1
                if (isDirectory && depth >= MAX_DEPTH + 1) truncated = true
                if (paths.size > MAX_ENTRIES) {
                    truncated = true
                    break
                }
            }
        }
        return InventorySnapshot(paths, truncated)
    }

    private fun isWithinRoot(rootPath: String, candidatePath: String): Boolean =
        candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)

    private fun failure(code: String, summary: String) = SpiderResultVerification(false, code, summary)

    private data class InventorySnapshot(val paths: List<String>, val truncated: Boolean)

    companion object {
        private const val MAX_WORKSPACE_ID_LENGTH = 120
        private const val MAX_DEPTH = 8
        private const val MAX_ENTRIES = 2_000
        private const val EXPECTED_RESULT_CODE = "workspace_listed"
        private const val WORKSPACE_MARKER = ".spider-workspace.json"
        private val SUMMARY_PATTERN = Regex("^Найдено элементов: ([0-9]+)\\.")
    }
}
