package com.iipauk.spider

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

/**
 * First concrete, read-only Agent tool. It lists names only, never file contents, and refuses to
 * enumerate outside the selected app-private workspace. Workspace identity is an ID, not a path.
 */
class WorkspaceFileListHandler(
    private val directoryForWorkspace: (workspaceId: String) -> File?
) : SpiderToolHandler {
    override val toolId: SpiderToolId = SpiderToolId.LIST_WORKSPACE_FILES
    override val displayName: String = "List workspace files"

    override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult {
        val workspaceId = request.resourceReference?.trim().orEmpty()
        if (workspaceId.isBlank() || workspaceId.length > MAX_WORKSPACE_ID_LENGTH) {
            return failure("workspace_id_required", "Не удалось определить рабочее пространство.")
        }
        val suppliedRoot = runCatching { directoryForWorkspace(workspaceId) }.getOrNull()
            ?: return failure("workspace_not_found", "Рабочее пространство не найдено или недоступно.")
        val root = runCatching { suppliedRoot.canonicalFile }.getOrNull()
            ?: return failure("workspace_path_invalid", "Не удалось проверить путь рабочего пространства.")
        if (!root.isDirectory) return failure("workspace_not_found", "Рабочее пространство не найдено или недоступно.")

        val rootPath = root.path
        val stack = ArrayDeque<DirectoryFrame>()
        val visitedDirectories = mutableSetOf<String>()
        val listedPaths = mutableListOf<String>()
        stack.addLast(DirectoryFrame(root, "", 0))
        var truncated = false

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val canonicalDirectory = runCatching { frame.directory.canonicalFile }.getOrNull()
                ?: return failure("workspace_listing_incomplete", "Не удалось полностью проверить рабочее пространство.")
            if (!isWithinRoot(rootPath, canonicalDirectory.path)) continue
            if (!canonicalDirectory.isDirectory || !visitedDirectories.add(canonicalDirectory.path)) continue

            val children = runCatching { frame.directory.listFiles()?.sortedBy { it.name } }.getOrNull()
                ?: return failure("workspace_listing_incomplete", "Не удалось полностью прочитать список элементов рабочего пространства.")
            for (child in children) {
                // Workspace metadata and temporary metadata are internal implementation details.
                if (child.name == WORKSPACE_MARKER || child.name == "$WORKSPACE_MARKER.tmp") continue
                // Do not traverse symlinks, even when they currently point back into this workspace.
                if (runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)) continue
                if (listedPaths.size >= MAX_ENTRIES) {
                    truncated = true
                    break
                }
                val canonicalChild = runCatching { child.canonicalFile }.getOrNull()
                    ?: return failure("workspace_listing_incomplete", "Не удалось проверить один из элементов рабочего пространства.")
                if (!isWithinRoot(rootPath, canonicalChild.path)) continue
                val isDirectory = runCatching { child.isDirectory }.getOrDefault(false)
                val isFile = runCatching { child.isFile }.getOrDefault(false)
                if (!isDirectory && !isFile) continue
                val relative = if (frame.relativePath.isEmpty()) child.name else "${frame.relativePath}/${child.name}"
                listedPaths += if (isDirectory) "$relative/" else relative
                if (isDirectory) {
                    if (frame.depth < MAX_DEPTH) stack.addLast(DirectoryFrame(child, relative, frame.depth + 1))
                    else truncated = true
                }
            }
            if (truncated && listedPaths.size >= MAX_ENTRIES) break
        }

        val evidence = WorkspaceListingEvidence.fromPaths(workspaceId, listedPaths, truncated)
            ?: return failure("workspace_evidence_unavailable", "Не удалось подготовить проверяемое доказательство списка.")
        val preview = listedPaths.take(MAX_PREVIEW_ITEMS).joinToString(", ")
        val suffix = when {
            listedPaths.isEmpty() -> "Рабочее пространство пусто."
            preview.isBlank() -> "Содержимое найдено."
            else -> "Элементы: $preview"
        }
        val summary = buildString {
            append("Найдено элементов: ").append(listedPaths.size).append(". ")
            append(suffix)
            if (truncated || listedPaths.size > MAX_PREVIEW_ITEMS) append(" Список ограничен безопасным лимитом.")
        }.take(SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH)
        return SpiderToolHandlerResult(
            succeeded = true,
            resultCode = "workspace_listed",
            summary = SpiderAuditRedactor.redact(summary),
            verificationEvidence = evidence.encode()
        )
    }

    private fun isWithinRoot(rootPath: String, candidatePath: String): Boolean =
        candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)

    private fun failure(code: String, message: String) = SpiderToolHandlerResult(false, code, message)

    private data class DirectoryFrame(val directory: File, val relativePath: String, val depth: Int)

    companion object {
        private const val MAX_WORKSPACE_ID_LENGTH = 120
        private const val MAX_DEPTH = 8
        private const val MAX_ENTRIES = 2_000
        private const val MAX_PREVIEW_ITEMS = 16
        private const val WORKSPACE_MARKER = ".spider-workspace.json"
    }
}
