package com.iipauk.spider

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * Common path and content boundary for actual workspace tools. All paths are relative to a
 * user-selected app-private workspace; no handler accepts an arbitrary filesystem path.
 */
internal object WorkspaceToolSafety {
    const val MAX_READ_BYTES = 64 * 1024
    const val MAX_WRITE_BYTES = 128 * 1024
    const val MAX_SEARCH_QUERY_CHARS = 120
    const val MAX_SEARCH_OUTPUT_CHARS = 18 * 1024

    private val supportedExtensions = setOf(
        "md", "txt", "rst", "adoc", "json", "jsonc", "yaml", "yml", "xml", "html", "htm",
        "css", "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "sh",
        "toml", "properties", "gradle", "sql", "csv", "svg", "gitignore", "editorconfig", "ini"
    )
    private val supportedNames = setOf("readme", "license", "notice", "dockerfile", "makefile", "justfile")
    private val excludedSegments = setOf(
        ".git", ".ssh", ".spider-trash", "node_modules", "vendor", "build", "dist", "target", "out", ".gradle"
    )
    private val sensitiveSuffixes = setOf("pem", "key", "p12", "pfx", "keystore", "jks", "der")

    data class Target(val root: File, val file: File, val relativePath: String)
    data class SearchSnapshot(
        val text: String,
        val matchCount: Int,
        val scannedFiles: Int,
        val truncated: Boolean
    )

    fun resolveTarget(rootInput: File, relativePath: String, allowMissingLeaf: Boolean): Target {
        if (relativePath.isBlank() || relativePath.length > 240 || relativePath.any { it.code < 0x20 || it.code == 0x7f }) {
            throw WorkspaceToolException("file_path_invalid", "Укажи относительный путь к текстовому файлу внутри рабочего пространства.")
        }
        if (relativePath.startsWith('/') || relativePath.startsWith('\\') || '\\' in relativePath ||
            Regex("^[A-Za-z]:").containsMatchIn(relativePath) || relativePath.contains("://")) {
            throw WorkspaceToolException("file_path_not_relative", "Абсолютные пути и внешние адреса запрещены; используй путь относительно выбранного проекта.")
        }
        val segments = relativePath.split('/')
        if (segments.size !in 1..8 || segments.any { it.isBlank() || it == "." || it == ".." || it.length > 120 }) {
            throw WorkspaceToolException("file_path_invalid", "Путь содержит недопустимые сегменты или слишком глубокую вложенность.")
        }
        if (segments.any(::isSensitiveSegment)) {
            throw WorkspaceToolException("sensitive_path_blocked", "Доступ к каталогам и файлам с признаками секретов или внутренними метаданными заблокирован.")
        }
        val root = rootInput.canonicalFile
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            throw WorkspaceToolException("workspace_unavailable", "Выбранное рабочее пространство недоступно.")
        }
        var current = root
        segments.forEachIndexed { index, segment ->
            current = File(current, segment)
            val isLast = index == segments.lastIndex
            if (Files.isSymbolicLink(current.toPath())) {
                throw WorkspaceToolException("symlink_blocked", "Символические ссылки не используются инструментами ИИ Паука.")
            }
            val canonical = current.canonicalFile
            if (!isWithin(root, canonical)) {
                throw WorkspaceToolException("path_escape_blocked", "Путь выходит за пределы выбранного рабочего пространства.")
            }
            if (current.exists()) {
                if (!isLast && !current.isDirectory) {
                    throw WorkspaceToolException("parent_not_directory", "Один из промежуточных элементов пути не является каталогом.")
                }
                if (isLast && !current.isFile && !current.isDirectory) {
                    throw WorkspaceToolException("file_type_unsupported", "Поддерживаются только обычные текстовые файлы.")
                }
            } else if (!isLast || !allowMissingLeaf) {
                throw WorkspaceToolException("file_not_found", "Файл или каталог не найден в выбранном рабочем пространстве.")
            }
        }
        val target = current.canonicalFile
        if (!isWithin(root, target)) throw WorkspaceToolException("path_escape_blocked", "Путь выходит за пределы выбранного рабочего пространства.")
        if (!isSupportedTextFile(target)) throw WorkspaceToolException("file_type_unsupported", "Поддерживается безопасная работа только с известными текстовыми форматами исходников и документов.")
        return Target(root, target, segments.joinToString("/"))
    }

    /** Create missing parent directories one component at a time after validating the relative path. */
    fun validateWritablePath(relativePath: String) {
        val segments = validateSegments(relativePath)
        if (segments.any(::isSensitiveSegment) || !isSupportedTextFile(File(segments.last()))) {
            throw WorkspaceToolException("file_type_unsupported", "Путь не относится к разрешённым текстовым файлам или выглядит как файл с секретами.")
        }
    }

    fun createSafeParents(rootInput: File, relativePath: String) {
        val segments = validateSegments(relativePath)
        if (segments.any(::isSensitiveSegment)) throw WorkspaceToolException("sensitive_path_blocked", "Каталог или файл заблокирован политикой безопасного доступа.")
        val root = rootInput.canonicalFile
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) throw WorkspaceToolException("workspace_unavailable", "Рабочее пространство недоступно.")
        var parent = root
        segments.dropLast(1).forEach { segment ->
            if (isSensitiveSegment(segment)) throw WorkspaceToolException("sensitive_path_blocked", "Каталог заблокирован политикой безопасного доступа.")
            val next = File(parent, segment)
            if (Files.isSymbolicLink(next.toPath())) throw WorkspaceToolException("symlink_blocked", "Символические ссылки запрещены.")
            if (next.exists()) {
                if (!next.isDirectory) throw WorkspaceToolException("parent_not_directory", "Промежуточный элемент пути не является каталогом.")
            } else if (!next.mkdir()) {
                throw WorkspaceToolException("parent_create_failed", "Не удалось создать каталог внутри рабочего пространства.")
            }
            val canonical = next.canonicalFile
            if (!isWithin(root, canonical)) throw WorkspaceToolException("path_escape_blocked", "Путь выходит за пределы выбранного рабочего пространства.")
            parent = canonical
        }
    }

    fun readBytesBounded(file: File, maxBytes: Int): ByteArray {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) throw WorkspaceToolException("file_not_found", "Обычный файл не найден.")
        if (maxBytes !in 1..MAX_WRITE_BYTES) throw WorkspaceToolException("read_limit_invalid", "Неверный безопасный лимит чтения.")
        if (file.length() > maxBytes) throw WorkspaceToolException("file_too_large", "Файл больше безопасного лимита чтения ${maxBytes / 1024} КиБ.")
        return readBounded(file, maxBytes)
    }

    fun readUtf8(file: File, maxBytes: Int = MAX_READ_BYTES): String {
        val bytes = readBytesBounded(file, maxBytes)
        if (bytes.any { it == 0.toByte() }) throw WorkspaceToolException("binary_file_blocked", "Похоже, это бинарный файл; чтение содержимого заблокировано.")
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw WorkspaceToolException("invalid_utf8", "Файл не является корректным UTF-8 текстом; содержимое не показано.") }
    }

    fun writeAtomic(file: File, text: String, overwriteExisting: Boolean, expectedPreimageSha256: String? = null) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) throw WorkspaceToolException("write_too_large", "Новый текст превышает безопасный лимит 128 КиБ.")
        if (bytes.any { it == 0.toByte() }) throw WorkspaceToolException("binary_content_blocked", "Нельзя записывать бинарные данные этим текстовым инструментом.")
        if (Files.isSymbolicLink(file.toPath())) throw WorkspaceToolException("symlink_blocked", "Перезапись символической ссылки запрещена.")
        if (file.exists() && !file.isFile) throw WorkspaceToolException("file_type_unsupported", "Нельзя перезаписывать каталог или специальный файл.")
        if (file.exists() && !overwriteExisting) throw WorkspaceToolException("overwrite_not_confirmed", "Файл уже существует. Подтверди отдельную перезапись.")
        val parent = file.parentFile ?: throw WorkspaceToolException("parent_unavailable", "Не удалось проверить каталог назначения.")
        if (!parent.isDirectory || Files.isSymbolicLink(parent.toPath())) throw WorkspaceToolException("parent_unavailable", "Каталог назначения недоступен.")
        val temp = File(parent, ".spider-write-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp, false).use { stream -> stream.write(bytes); stream.fd.sync() }
            if (Files.isSymbolicLink(file.toPath())) throw WorkspaceToolException("symlink_blocked", "Путь назначения изменился на символическую ссылку.")
            expectedPreimageSha256?.let { expected ->
                val actual = if (!file.exists()) "MISSING" else {
                    val currentBytes = readBytesBounded(file, MAX_WRITE_BYTES)
                    sha256(currentBytes)
                }
                if (actual != expected) throw WorkspaceToolException("preimage_changed", "Файл изменился во время записи. Кандидат отклонён; повторно проверь исходник.")
            }
            if (file.exists() && !overwriteExisting) throw WorkspaceToolException("overwrite_not_confirmed", "Файл появился до записи. Подтверди перезапись повторно.")
            if (overwriteExisting) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (error: WorkspaceToolException) {
            throw error
        } catch (_: Exception) {
            throw WorkspaceToolException("atomic_write_failed", "Не удалось атомарно сохранить файл; исходник не считается изменённым.")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun sanitizeOutput(text: String): String {
        val credentialAssignments = Regex(
            """(?i)(["']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|client[_-]?secret|password|passwd|authorization|private[_-]?key|secret)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s"',;]+)"""
        )
        return text
            .replace(Regex("""(?i)Bearer\s+(?:"[^"]*"|'[^']*'|[^\s"',;]+)"""), "Bearer [REDACTED]")
            .replace(credentialAssignments) { "${it.groupValues[1]}[REDACTED]" }
            .replace(Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b"), "[REDACTED_TOKEN]")
            .replace(Regex("AIza[0-9A-Za-z_-]{30,}"), "[REDACTED_TOKEN]")
            .replace(Regex("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b"), "[REDACTED_TOKEN]")
            .replace(Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"), "[REDACTED_TOKEN]")
            .replace(Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"), "[REDACTED_TOKEN]")
            .replace(Regex("\\bAKIA[0-9A-Z]{16}\\b"), "[REDACTED_TOKEN]")
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun evidence(kind: String, quantity: Int, digest: String): String = "wsv1|$kind|$quantity|$digest"

    fun verifyEvidence(raw: String?, kind: String): Pair<Int, String>? {
        if (raw.isNullOrBlank() || raw.length > 160) return null
        val parts = raw.split('|')
        if (parts.size != 4 || parts[0] != "wsv1" || parts[1] != kind) return null
        val quantity = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val digest = parts[3].takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        return quantity to digest
    }

    fun search(rootInput: File, rawQuery: String): SearchSnapshot {
        val query = rawQuery.trim()
        if (query.isEmpty() || query.length > MAX_SEARCH_QUERY_CHARS || query.any { it.code < 0x20 || it.code == 0x7f }) {
            throw WorkspaceToolException("search_query_invalid", "Укажи поисковую фразу длиной от 1 до 120 символов.")
        }
        val root = rootInput.canonicalFile
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) throw WorkspaceToolException("workspace_unavailable", "Рабочее пространство недоступно.")
        val queue = ArrayDeque<DirectoryFrame>()
        val visited = mutableSetOf<String>()
        queue.add(DirectoryFrame(root, "", 0))
        val matches = mutableListOf<String>()
        var scanned = 0
        var bytesScanned = 0L
        var truncated = false
        val needle = query.lowercase(Locale.ROOT)
        while (queue.isNotEmpty()) {
            val frame = queue.removeFirst()
            if (!visited.add(frame.directory.canonicalPath)) continue
            val children = frame.directory.listFiles()?.sortedBy { it.name }
                ?: throw WorkspaceToolException("workspace_search_incomplete", "Не удалось полностью просмотреть каталог; результат поиска не считается полным.")
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (isSensitiveSegment(child.name) || child.name == WorkspaceRepositoryMarker.NAME) continue
                val relative = if (frame.relative.isBlank()) child.name else "${frame.relative}/${child.name}"
                if (!isWithin(root, child.canonicalFile)) continue
                if (child.isDirectory) {
                    if (frame.depth >= MAX_SEARCH_DEPTH || scanned >= MAX_SEARCH_FILES || bytesScanned >= MAX_SEARCH_BYTES) { truncated = true; continue }
                    queue.add(DirectoryFrame(child, relative, frame.depth + 1))
                    continue
                }
                if (!child.isFile || !isSupportedTextFile(child) || child.length() > MAX_SEARCH_FILE_BYTES) continue
                if (scanned >= MAX_SEARCH_FILES || bytesScanned + child.length() > MAX_SEARCH_BYTES) { truncated = true; break }
                scanned++
                bytesScanned += child.length()
                val content = runCatching { readUtf8(child, MAX_SEARCH_FILE_BYTES) }.getOrNull() ?: continue
                content.lineSequence().forEachIndexed { lineIndex, line ->
                    if (matches.size >= MAX_MATCHES) { truncated = true; return@forEachIndexed }
                    if (line.lowercase(Locale.ROOT).contains(needle)) {
                        val snippet = sanitizeOutput(line.trim()).take(MAX_SNIPPET_CHARS)
                        matches += "$relative:${lineIndex + 1}: $snippet"
                    }
                }
                if (matches.size >= MAX_MATCHES) { truncated = true; break }
            }
            if (truncated) break
        }
        val rendered = if (matches.isEmpty()) "Совпадений не найдено." else matches.joinToString("\n")
        return SearchSnapshot(rendered.take(MAX_SEARCH_OUTPUT_CHARS), matches.size, scanned, truncated)
    }

    fun isSensitiveSegment(segment: String): Boolean {
        val name = segment.lowercase(Locale.ROOT)
        val base = name.substringAfterLast('.')
        return name in excludedSegments || name == ".env" || name.startsWith(".env.") || name.endsWith(".env") ||
            name.contains("credential") || name.contains("secret") || name in setOf("id_rsa", "id_ed25519") ||
            base in sensitiveSuffixes || name.endsWith(".pem") || name.endsWith(".key")
    }

    fun isSupportedTextFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        if (isSensitiveSegment(name) || name == WorkspaceRepositoryMarker.NAME) return false
        val extension = name.substringAfterLast('.', "")
        val base = name.substringBeforeLast('.', name)
        return extension in supportedExtensions || name in supportedNames || base in supportedNames || name == ".gitignore" || name == ".editorconfig"
    }

    private fun validateSegments(relativePath: String): List<String> {
        if (relativePath.length !in 1..240 || relativePath.startsWith('/') || relativePath.startsWith('\\') || '\\' in relativePath ||
            Regex("^[A-Za-z]:").containsMatchIn(relativePath) || relativePath.contains("://") || relativePath.any { it.code < 0x20 || it.code == 0x7f }) {
            throw WorkspaceToolException("file_path_invalid", "Укажи относительный путь внутри рабочего пространства.")
        }
        val segments = relativePath.split('/')
        if (segments.size !in 1..8 || segments.any { it.isBlank() || it == "." || it == ".." || it.length > 120 }) {
            throw WorkspaceToolException("file_path_invalid", "Путь содержит недопустимые сегменты.")
        }
        return segments
    }

    private fun readBounded(file: File, maxBytes: Int): ByteArray {
        ByteArrayOutputStream().use { output ->
            FileInputStream(file).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw WorkspaceToolException("file_too_large", "Файл превысил безопасный лимит чтения.")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }

    private fun isWithin(root: File, candidate: File): Boolean = candidate == root || candidate.path.startsWith(root.path + File.separator)

    private data class DirectoryFrame(val directory: File, val relative: String, val depth: Int)
    private object WorkspaceRepositoryMarker { const val NAME = ".spider-workspace.json" }
    private const val MAX_SEARCH_DEPTH = 6
    private const val MAX_SEARCH_FILES = 500
    private const val MAX_SEARCH_BYTES = 8L * 1024L * 1024L
    private const val MAX_SEARCH_FILE_BYTES = 64 * 1024
    private const val MAX_MATCHES = 20
    private const val MAX_SNIPPET_CHARS = 180
}

internal class WorkspaceToolException(val safeCode: String, message: String) : IllegalArgumentException(message)

class WorkspaceFileReadHandler(private val directoryForWorkspace: (String) -> File?) : SpiderToolHandler {
    override val toolId = SpiderToolId.READ_WORKSPACE_FILE
    override val displayName = "Read a text file in workspace"

    override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult = try {
        val root = requiredRoot(directoryForWorkspace, request.workspaceId)
        val target = WorkspaceToolSafety.resolveTarget(root, request.resourceReference.orEmpty(), allowMissingLeaf = false)
        if (!target.file.isFile) throw WorkspaceToolException("file_not_found", "Указанный путь не является обычным файлом.")
        val safeContent = WorkspaceToolSafety.sanitizeOutput(WorkspaceToolSafety.readUtf8(target.file))
        val bytes = safeContent.toByteArray(StandardCharsets.UTF_8)
        SpiderToolHandlerResult(
            succeeded = true,
            resultCode = "workspace_file_read",
            summary = "Текстовый файл прочитан (${bytes.size} байт после защиты от раскрытия секретов).",
            verificationEvidence = WorkspaceToolSafety.evidence("read", bytes.size, WorkspaceToolSafety.sha256(bytes)),
            outputPayload = safeContent
        )
    } catch (error: WorkspaceToolException) {
        SpiderToolHandlerResult(false, error.safeCode, error.message ?: "Не удалось прочитать файл.")
    } catch (_: Exception) {
        SpiderToolHandlerResult(false, "workspace_file_read_failed", "Не удалось прочитать файл рабочего пространства.")
    }
}

class WorkspaceFileWriteHandler(private val directoryForWorkspace: (String) -> File?) : SpiderToolHandler {
    override val toolId = SpiderToolId.WRITE_WORKSPACE_FILE
    override val displayName = "Write a text file in workspace"

    @Synchronized
    override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult = try {
        val root = requiredRoot(directoryForWorkspace, request.workspaceId)
        val path = request.resourceReference.orEmpty()
        val content = request.inputText ?: throw WorkspaceToolException("write_content_required", "Для записи нужен текст содержимого.")
        val byteCount = content.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount > WorkspaceToolSafety.MAX_WRITE_BYTES) throw WorkspaceToolException("write_too_large", "Новый текст превышает безопасный лимит 128 КиБ.")
        // AI-reviewed edits must keep the already reviewed path tree stable. Manual user-entered writes
        // may create checked parent segments; an AI edit never creates directories during execution.
        WorkspaceToolSafety.validateWritablePath(path)
        if (request.expectedPreimageSha256 == null) {
            WorkspaceToolSafety.createSafeParents(root, path)
        }
        val target = WorkspaceToolSafety.resolveTarget(root, path, allowMissingLeaf = true)
        val existed = target.file.exists()
        request.expectedPreimageSha256?.let { expected ->
            if (expected != "MISSING" && !expected.matches(Regex("[a-f0-9]{64}"))) {
                throw WorkspaceToolException("preimage_hash_invalid", "Контрольная сумма исходника не прошла проверку.")
            }
            val actual = if (!existed) "MISSING" else {
                val bytesBefore = WorkspaceToolSafety.readBytesBounded(target.file, WorkspaceToolSafety.MAX_WRITE_BYTES)
                WorkspaceToolSafety.sha256(bytesBefore)
            }
            if (actual != expected) {
                throw WorkspaceToolException("preimage_changed", "Файл изменился после предпросмотра. Ничего не записано; заново сформируй предложение изменений.")
            }
        }
        if (existed && !request.overwriteExisting) throw WorkspaceToolException("overwrite_not_confirmed", "Файл уже существует. Подтверди отдельную перезапись.")
        WorkspaceToolSafety.writeAtomic(
            target.file, content, overwriteExisting = request.overwriteExisting,
            expectedPreimageSha256 = request.expectedPreimageSha256
        )
        val saved = WorkspaceToolSafety.readBytesBounded(target.file, WorkspaceToolSafety.MAX_WRITE_BYTES)
        val digest = WorkspaceToolSafety.sha256(saved)
        SpiderToolHandlerResult(
            succeeded = true,
            resultCode = if (existed) "workspace_file_updated" else "workspace_file_created",
            summary = "Файл записан и сохранён атомарно (${saved.size} байт). Содержимое в журнал не записывается.",
            verificationEvidence = WorkspaceToolSafety.evidence("write", saved.size, digest)
        )
    } catch (error: WorkspaceToolException) {
        SpiderToolHandlerResult(false, error.safeCode, error.message ?: "Не удалось записать файл.")
    } catch (_: Exception) {
        SpiderToolHandlerResult(false, "workspace_file_write_failed", "Не удалось безопасно записать файл рабочего пространства.")
    }
}

class WorkspaceContentSearchHandler(private val directoryForWorkspace: (String) -> File?) : SpiderToolHandler {
    override val toolId = SpiderToolId.SEARCH_WORKSPACE_CONTENT
    override val displayName = "Search text inside workspace files"

    override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult = try {
        val root = requiredRoot(directoryForWorkspace, request.workspaceId)
        val query = request.inputText ?: throw WorkspaceToolException("search_query_invalid", "Поисковая фраза отсутствует.")
        val snapshot = WorkspaceToolSafety.search(root, query)
        if (snapshot.truncated) {
            SpiderToolHandlerResult(
                succeeded = false,
                resultCode = "workspace_search_limit_reached",
                summary = "Поиск достиг безопасного лимита по файлам или совпадениям; результат неполный и не отмечается как подтверждённый."
            )
        } else {
            val output = WorkspaceToolSafety.sanitizeOutput(snapshot.text)
            val bytes = output.toByteArray(StandardCharsets.UTF_8)
            SpiderToolHandlerResult(
                succeeded = true,
                resultCode = "workspace_search_completed",
                summary = "Поиск проверен: совпадений ${snapshot.matchCount}; просмотрено текстовых файлов ${snapshot.scannedFiles}.",
                verificationEvidence = WorkspaceToolSafety.evidence("search", snapshot.matchCount, WorkspaceToolSafety.sha256(bytes)),
                outputPayload = output
            )
        }
    } catch (error: WorkspaceToolException) {
        SpiderToolHandlerResult(false, error.safeCode, error.message ?: "Не удалось выполнить поиск.")
    } catch (_: Exception) {
        SpiderToolHandlerResult(false, "workspace_search_failed", "Не удалось выполнить поиск по текстовым файлам рабочего пространства.")
    }
}

/** Rechecks file state after the handler returns; only this verifier's result can complete a step. */
class WorkspaceTextToolVerifier(private val directoryForWorkspace: (String) -> File?) {
    fun verify(request: SpiderToolRequest, result: SpiderToolCallResult): SpiderResultVerification {
        if (result.status != SpiderToolCallStatus.SUCCEEDED || !result.auditRecorded) {
            return fail("workspace_tool_not_succeeded", "Проверка не пройдена: инструмент не завершился с подтверждённым событием аудита.")
        }
        return try {
            val root = requiredRoot(directoryForWorkspace, request.workspaceId)
            when (request.toolId) {
                SpiderToolId.READ_WORKSPACE_FILE -> verifyRead(root, request, result)
                SpiderToolId.WRITE_WORKSPACE_FILE -> verifyWrite(root, request, result)
                SpiderToolId.SEARCH_WORKSPACE_CONTENT -> verifySearch(root, request, result)
                else -> fail("workspace_tool_verifier_unknown", "Для этого инструмента нет независимого проверяющего контракта.")
            }
        } catch (error: WorkspaceToolException) {
            fail(error.safeCode, "Проверка не пройдена: ${error.message ?: "рабочее пространство недоступно"}")
        } catch (_: Exception) {
            fail("workspace_tool_verification_failed", "Не удалось независимо проверить текущее состояние рабочего пространства.")
        }
    }

    private fun verifyRead(root: File, request: SpiderToolRequest, result: SpiderToolCallResult): SpiderResultVerification {
        if (result.resultCode != "workspace_file_read") return fail("workspace_read_code_mismatch", "Код результата не соответствует чтению файла.")
        val target = WorkspaceToolSafety.resolveTarget(root, request.resourceReference.orEmpty(), allowMissingLeaf = false)
        val current = WorkspaceToolSafety.sanitizeOutput(WorkspaceToolSafety.readUtf8(target.file))
        val supplied = result.outputPayload ?: return fail("workspace_read_payload_missing", "Содержимое прочитанного файла отсутствует в результате.")
        if (current != supplied) return fail("workspace_read_changed", "Файл изменился во время проверки; содержимое не считается подтверждённым.")
        val bytes = supplied.toByteArray(StandardCharsets.UTF_8)
        val evidence = WorkspaceToolSafety.verifyEvidence(result.verificationEvidence, "read")
            ?: return fail("workspace_read_evidence_invalid", "Доказательство чтения повреждено.")
        if (evidence.first != bytes.size || evidence.second != WorkspaceToolSafety.sha256(bytes)) {
            return fail("workspace_read_evidence_mismatch", "Размер или контрольная сумма прочитанного текста не совпали.")
        }
        return ok("Чтение подтверждено: файл повторно прочитан, а текст и контрольная сумма совпали.")
    }

    private fun verifyWrite(root: File, request: SpiderToolRequest, result: SpiderToolCallResult): SpiderResultVerification {
        if (result.resultCode !in setOf("workspace_file_created", "workspace_file_updated")) return fail("workspace_write_code_mismatch", "Код результата не соответствует записи файла.")
        val target = WorkspaceToolSafety.resolveTarget(root, request.resourceReference.orEmpty(), allowMissingLeaf = false)
        val bytes = WorkspaceToolSafety.readBytesBounded(target.file, WorkspaceToolSafety.MAX_WRITE_BYTES)
        val evidence = WorkspaceToolSafety.verifyEvidence(result.verificationEvidence, "write")
            ?: return fail("workspace_write_evidence_invalid", "Доказательство записи повреждено.")
        if (bytes.size > WorkspaceToolSafety.MAX_WRITE_BYTES || evidence.first != bytes.size || evidence.second != WorkspaceToolSafety.sha256(bytes)) {
            return fail("workspace_write_evidence_mismatch", "Контрольная сумма сохранённого файла не совпала с результатом инструмента.")
        }
        val desired = request.inputText?.toByteArray(StandardCharsets.UTF_8)
            ?: return fail("workspace_write_input_missing", "Не найден исходный текст подтверждённой записи.")
        if (!bytes.contentEquals(desired)) return fail("workspace_write_content_mismatch", "Содержимое файла отличается от подтверждённого текста.")
        return ok("Запись подтверждена: файл повторно прочитан с диска, размер и SHA-256 совпали.")
    }

    private fun verifySearch(root: File, request: SpiderToolRequest, result: SpiderToolCallResult): SpiderResultVerification {
        if (result.resultCode != "workspace_search_completed") return fail("workspace_search_code_mismatch", "Код результата не соответствует поиску.")
        val query = request.inputText ?: return fail("workspace_search_input_missing", "Поисковая фраза отсутствует.")
        val snapshot = WorkspaceToolSafety.search(root, query)
        if (snapshot.truncated) return fail("workspace_search_incomplete", "Повторный поиск достиг безопасного лимита и не подтверждает полноту результата.")
        val current = WorkspaceToolSafety.sanitizeOutput(snapshot.text)
        val supplied = result.outputPayload ?: return fail("workspace_search_payload_missing", "Вывод поиска отсутствует.")
        if (current != supplied) return fail("workspace_search_changed", "Результаты поиска изменились между выполнением и проверкой.")
        val bytes = supplied.toByteArray(StandardCharsets.UTF_8)
        val evidence = WorkspaceToolSafety.verifyEvidence(result.verificationEvidence, "search")
            ?: return fail("workspace_search_evidence_invalid", "Доказательство поиска повреждено.")
        if (evidence.first != snapshot.matchCount || evidence.second != WorkspaceToolSafety.sha256(bytes)) {
            return fail("workspace_search_evidence_mismatch", "Количество совпадений или контрольная сумма поиска не совпали.")
        }
        return ok("Поиск подтверждён повторным проходом по рабочему пространству.")
    }

    private fun ok(message: String) = SpiderResultVerification(true, "workspace_tool_verified", message)
    private fun fail(code: String, message: String) = SpiderResultVerification(false, code, message)
}

private fun requiredRoot(directoryForWorkspace: (String) -> File?, rawWorkspaceId: String?): File {
    val id = rawWorkspaceId?.trim().orEmpty()
    if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) throw WorkspaceToolException("workspace_id_invalid", "Выбери допустимое рабочее пространство.")
    val root = runCatching { directoryForWorkspace(id) }.getOrNull()
        ?: throw WorkspaceToolException("workspace_not_found", "Рабочее пространство не найдено.")
    val canonical = root.canonicalFile
    if (!canonical.isDirectory || Files.isSymbolicLink(canonical.toPath())) throw WorkspaceToolException("workspace_unavailable", "Рабочее пространство недоступно.")
    return canonical
}
