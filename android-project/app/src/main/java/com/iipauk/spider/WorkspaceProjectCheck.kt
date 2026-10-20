package com.iipauk.spider

import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A deliberately static, read-only project check. It never runs project code, scripts, builds,
 * package managers, shell commands, or network requests. JSON and XML syntax are checked with
 * bounded parsers; other source languages are inventoried but explicitly not executed or compiled.
 */
class WorkspaceProjectCheckHandler(
    private val directoryForWorkspace: (workspaceId: String) -> File?
) : SpiderToolHandler {
    override val toolId: SpiderToolId = SpiderToolId.CHECK_WORKSPACE_PROJECT
    override val displayName: String = "Run bounded static project checks"

    override fun execute(request: SpiderToolRequest): SpiderToolHandlerResult {
        val workspaceId = request.workspaceId?.trim().orEmpty()
        if (!workspaceId.matches(Regex("[0-9a-fA-F-]{36}"))) {
            return failure("workspace_id_required", "Выбери рабочее пространство для проверки проекта.")
        }
        val supplied = runCatching { directoryForWorkspace(workspaceId) }.getOrNull()
            ?: return failure("workspace_not_found", "Рабочее пространство не найдено или недоступно.")
        val root = runCatching { supplied.canonicalFile }.getOrNull()
            ?: return failure("workspace_path_invalid", "Не удалось проверить путь рабочего пространства.")
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            return failure("workspace_not_found", "Рабочее пространство недоступно или не является обычным каталогом.")
        }
        val snapshot = runCatching { WorkspaceProjectCheckScanner.scan(root, workspaceId) }.getOrElse {
            return failure("project_check_failed", "Статическая проверка остановлена: не удалось безопасно прочитать рабочее пространство.")
        }
        return SpiderToolHandlerResult(
            succeeded = true,
            resultCode = RESULT_CODE,
            summary = snapshot.summary,
            verificationEvidence = snapshot.evidence,
            outputPayload = snapshot.renderReport()
        )
    }

    private fun failure(code: String, message: String) = SpiderToolHandlerResult(false, code, message)

    companion object { const val RESULT_CODE = "workspace_project_checked" }
}

/** Re-runs the bounded scan and compares opaque evidence; report text alone is never accepted as proof. */
class WorkspaceProjectCheckVerifier(
    private val directoryForWorkspace: (workspaceId: String) -> File?
) {
    fun verify(workspaceId: String, result: SpiderToolCallResult): SpiderResultVerification {
        if (result.status != SpiderToolCallStatus.SUCCEEDED || !result.auditRecorded) {
            return failure("project_check_not_succeeded", "Проверка результата не пройдена: инструмент или обязательный аудит завершились неуспешно.")
        }
        if (result.resultCode != WorkspaceProjectCheckHandler.RESULT_CODE) {
            return failure("project_check_result_code_mismatch", "Проверка результата не пройдена: код результата не соответствует статической проверке проекта.")
        }
        val evidence = result.verificationEvidence?.takeIf { it.matches(Regex("project-check:v1:[a-f0-9]{64}:[0-9]{1,4}:[0-9]{1,4}:[0-9]{1,4}:[01]")) }
            ?: return failure("project_check_evidence_missing", "Проверка результата не пройдена: доказательство статического анализа отсутствует или повреждено.")
        val supplied = runCatching { directoryForWorkspace(workspaceId) }.getOrNull()
            ?: return failure("project_check_workspace_unavailable", "Рабочее пространство больше недоступно для контрольного прохода.")
        val root = runCatching { supplied.canonicalFile }.getOrNull()
            ?: return failure("project_check_workspace_path_invalid", "Не удалось повторно проверить путь рабочего пространства.")
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            return failure("project_check_workspace_unavailable", "Рабочее пространство больше недоступно для контрольного прохода.")
        }
        val repeated = runCatching { WorkspaceProjectCheckScanner.scan(root, workspaceId) }.getOrNull()
            ?: return failure("project_check_rerun_failed", "Контрольный проход не смог повторить статическую проверку.")
        if (repeated.evidence != evidence) {
            return failure("project_check_changed", "Проверка результата не пройдена: состав файлов или результаты проверки изменились между проходами.")
        }
        return SpiderResultVerification(
            true,
            "workspace_project_check_verified",
            "Результат статической проверки независимо повторён: доказательство совпало. Скрипты, исходный код и сборка не запускались."
        )
    }

    private fun failure(code: String, summary: String) = SpiderResultVerification(false, code, summary)
}

private data class ProjectCheckFinding(
    val severity: String,
    val code: String,
    val path: String,
    val detail: String
)

private data class ProjectCheckSnapshot(
    val workspaceId: String,
    val scannedFiles: Int,
    val checkedJson: Int,
    val checkedXml: Int,
    val skippedFiles: Int,
    val errorCount: Int,
    val warningCount: Int,
    val truncated: Boolean,
    val digest: String,
    val findings: List<ProjectCheckFinding>
) {
    val evidence: String
        get() = "project-check:v1:$digest:$scannedFiles:$errorCount:$warningCount:${if (truncated) 1 else 0}"

    val summary: String
        get() = "Статическая проверка проекта: файлов=$scannedFiles; JSON=$checkedJson; XML/SVG=$checkedXml; ошибок=$errorCount; предупреждений=$warningCount; ограничен=${if (truncated) 1 else 0}."

    fun renderReport(): String = buildString {
        append(summary).append('\n')
        append("Важно: это статическая проверка. Код, shell-команды, сборка и тестовые сценарии проекта не запускались.\n")
        append("Для JSON проверяется синтаксис; для XML/SVG — разбор без разрешения внешних сущностей; для остальных текстовых файлов проверяется только кодировка и доступность чтения.\n")
        if (checkedJson + checkedXml > 0 && errorCount == 0) {
            append("В проверенных JSON/XML файлах синтаксических ошибок не обнаружено. Это не распространяется на пропущенные файлы или другие языки.\n")
        }
        findings.take(MAX_DISPLAY_FINDINGS).forEach { finding ->
            append(finding.severity).append(" ").append(finding.code).append(" — ")
                .append(finding.path).append(": ").append(finding.detail).append('\n')
        }
        val totalFindings = errorCount + warningCount
        if (totalFindings > MAX_DISPLAY_FINDINGS) append("Показаны первые $MAX_DISPLAY_FINDINGS из $totalFindings замечаний.\n")
        if (skippedFiles > 0) append("Пропущено файлов по ограничениям размера/содержимого: $skippedFiles.\n")
        if (truncated) append("Внимание: достигнут безопасный лимит обхода; отчёт неполный.\n")
    }.take(MAX_REPORT_CHARS)

    companion object {
        private const val MAX_DISPLAY_FINDINGS = 60
        private const val MAX_REPORT_CHARS = 16 * 1024
    }
}

private object WorkspaceProjectCheckScanner {
    private const val MAX_DEPTH = 8
    private const val MAX_FILES = 500
    private const val MAX_ENTRIES = 2_000
    private const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L
    private const val MAX_FILE_BYTES = 64 * 1024L
    private const val MAX_FINDINGS = 500
    private const val WORKSPACE_MARKER = ".spider-workspace.json"
    private val jsonExtensions = setOf("json")
    private val xmlExtensions = setOf("xml", "svg")

    fun scan(rootInput: File, workspaceId: String): ProjectCheckSnapshot {
        require(workspaceId.matches(Regex("[0-9a-fA-F-]{36}")))
        val root = rootInput.canonicalFile
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        val rootPath = root.path
        val queue = ArrayDeque<Frame>()
        queue.addLast(Frame(root, "", 0))
        val visited = mutableSetOf<String>()
        val fingerprints = mutableListOf<String>()
        val findings = mutableListOf<ProjectCheckFinding>()
        var scannedFiles = 0
        var visitedEntries = 0
        var checkedJson = 0
        var checkedXml = 0
        var skipped = 0
        var bytesRead = 0L
        var truncated = false
        var totalErrors = 0
        var totalWarnings = 0

        fun finding(severity: String, code: String, path: String, detail: String) {
            when (severity) { "ERROR" -> totalErrors++; else -> totalWarnings++ }
            if (findings.size < MAX_FINDINGS) {
                findings += ProjectCheckFinding(severity, code, path.take(240), detail.take(160))
            }
        }

        while (queue.isNotEmpty()) {
            val frame = queue.removeFirst()
            val canonicalDirectory = runCatching { frame.directory.canonicalFile }.getOrNull()
            if (canonicalDirectory == null) {
                finding("WARNING", "directory_unavailable", frame.relativePath.ifBlank { "." }, "Каталог не удалось проверить и он пропущен.")
                continue
            }
            if (!isWithin(rootPath, canonicalDirectory.path) || !canonicalDirectory.isDirectory ||
                Files.isSymbolicLink(canonicalDirectory.toPath()) || !visited.add(canonicalDirectory.path)) continue
            val children = runCatching { frame.directory.listFiles()?.sortedBy { it.name } }.getOrNull()
            if (children == null) {
                finding("WARNING", "directory_unreadable", frame.relativePath.ifBlank { "." }, "Не удалось прочитать каталог.")
                continue
            }
            for (child in children) {
                visitedEntries++
                if (visitedEntries > MAX_ENTRIES) {
                    truncated = true
                    finding("WARNING", "entry_limit", frame.relativePath.ifBlank { "." }, "Достигнут предел 2000 элементов; часть проекта не проверена.")
                    queue.clear()
                    break
                }
                val relative = if (frame.relativePath.isBlank()) child.name else "${frame.relativePath}/${child.name}"
                if (child.name == WORKSPACE_MARKER || child.name == "$WORKSPACE_MARKER.tmp") continue
                if (relative.split('/').any { WorkspaceToolSafety.isSensitiveSegment(it) }) continue
                if (runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)) {
                    finding("WARNING", "symlink_skipped", relative, "Символическая ссылка не обходилась.")
                    continue
                }
                val canonicalChild = runCatching { child.canonicalFile }.getOrNull()
                if (canonicalChild == null) {
                    finding("WARNING", "path_unavailable", relative, "Путь не удалось проверить.")
                    continue
                }
                if (!isWithin(rootPath, canonicalChild.path)) {
                    finding("WARNING", "path_escape_skipped", relative, "Элемент за пределами проекта пропущен.")
                    continue
                }
                if (child.isDirectory) {
                    if (frame.depth < MAX_DEPTH) queue.addLast(Frame(child, relative, frame.depth + 1))
                    else {
                        truncated = true
                        finding("WARNING", "depth_limit", relative, "Достигнута максимальная глубина обхода.")
                    }
                    continue
                }
                if (!child.isFile || !WorkspaceToolSafety.isSupportedTextFile(child)) continue
                if (scannedFiles >= MAX_FILES) {
                    truncated = true
                    break
                }
                val length = runCatching { child.length() }.getOrDefault(Long.MAX_VALUE)
                if (length < 0 || bytesRead + length > MAX_TOTAL_BYTES) {
                    truncated = true
                    finding("WARNING", "total_size_limit", relative, "Достигнут общий лимит чтения 8 МиБ; часть проекта не проверена.")
                    break
                }
                scannedFiles++
                val lastModified = runCatching { child.lastModified() }.getOrDefault(-1L)
                if (length > MAX_FILE_BYTES) {
                    skipped++
                    finding("WARNING", "file_size_skipped", relative, "Размер превышает лимит чтения 64 КиБ; файл не проверен по содержимому.")
                    fingerprints += "$relative|$length|$lastModified|LARGE"
                    continue
                }
                val text = try {
                    WorkspaceToolSafety.readUtf8(child, MAX_FILE_BYTES.toInt())
                } catch (_: Exception) {
                    skipped++
                    finding("WARNING", "text_read_skipped", relative, "Файл не прочитан как безопасный UTF-8 текст.")
                    fingerprints += "$relative|$length|$lastModified|UNREADABLE"
                    continue
                }
                bytesRead += length
                // Do not inspect credential-looking bodies. File path and bounded metadata still enter ephemeral evidence.
                if (WorkspaceToolSafety.sanitizeOutput(text) != text) {
                    skipped++
                    finding("WARNING", "credential_like_content_skipped", relative, "Файл похож на содержащий учётные данные и не анализировался.")
                    fingerprints += "$relative|$length|$lastModified|REDACTED"
                    continue
                }
                val extension = child.name.substringAfterLast('.', "").lowercase()
                val contentDigest = sha256(text.toByteArray(Charsets.UTF_8))
                fingerprints += "$relative|$length|$lastModified|$contentDigest"
                try {
                    when {
                        extension in jsonExtensions -> {
                            checkedJson++
                            StrictJsonSyntaxValidator.validate(text)
                        }
                        extension in xmlExtensions -> {
                            checkedXml++
                            SecureXmlSyntaxValidator.validate(text)
                        }
                    }
                } catch (error: StaticSyntaxException) {
                    finding("ERROR", "json_syntax_invalid", relative,
                        "JSON синтаксис не прошёл проверку в позиции ${error.position.coerceAtLeast(0)}.")
                } catch (error: SAXParseException) {
                    val position = error.lineNumber.coerceAtLeast(0) * 100_000 + error.columnNumber.coerceAtLeast(0)
                    finding("ERROR", "xml_syntax_invalid", relative,
                        "XML/SVG синтаксис не прошёл проверку в строке ${error.lineNumber.coerceAtLeast(0)}, столбце ${error.columnNumber.coerceAtLeast(0)} (код позиции $position).")
                } catch (_: Exception) {
                    // Do not expose parser implementation details or a source fragment in reports.
                    finding("ERROR", "secure_parser_unavailable", relative, "Не удалось безопасно выполнить статическую проверку формата.")
                }
            }
            if (scannedFiles >= MAX_FILES) {
                if (queue.isNotEmpty() || children.isNotEmpty()) truncated = true
                break
            }
        }

        val digestBuilder = StringBuilder().append("workspace=").append(workspaceId).append('\n')
        fingerprints.sorted().forEach { digestBuilder.append(it).append('\n') }
        digestBuilder.append("counts=").append(scannedFiles).append(',').append(checkedJson).append(',').append(checkedXml)
            .append(',').append(skipped).append(',').append(totalErrors).append(',').append(totalWarnings).append(',').append(truncated)
        findings.forEach { digestBuilder.append('\n').append(it.severity).append('|').append(it.code).append('|').append(it.path).append('|').append(it.detail) }
        val digest = sha256(digestBuilder.toString().toByteArray(Charsets.UTF_8))
        return ProjectCheckSnapshot(workspaceId, scannedFiles, checkedJson, checkedXml, skipped, totalErrors, totalWarnings, truncated, digest, findings)
    }

    private fun isWithin(root: String, candidate: String): Boolean = candidate == root || candidate.startsWith(root + File.separator)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private data class Frame(val directory: File, val relativePath: String, val depth: Int)
}

private class StaticSyntaxException(val position: Int) : IllegalArgumentException()

/** Strict RFC-8259-shaped syntax checker; no object is executed and no external JSON dependency is needed. */
private class StrictJsonSyntaxValidator(private val text: String) {
    private var index = 0
    private var depth = 0

    fun validate() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        if (index != text.length) fail()
    }

    private fun parseValue() {
        if (++depth > 64) fail()
        try {
            skipWhitespace()
            when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> parseNumber()
                else -> fail()
            }
        } finally { depth-- }
    }

    private fun parseObject() {
        expect('{'); skipWhitespace()
        if (consume('}')) return
        while (true) {
            skipWhitespace(); if (peek() != '"') fail()
            parseString(); skipWhitespace(); expect(':'); parseValue(); skipWhitespace()
            if (consume('}')) return
            expect(','); skipWhitespace()
            if (peek() == '}') fail()
        }
    }

    private fun parseArray() {
        expect('['); skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue(); skipWhitespace()
            if (consume(']')) return
            expect(','); skipWhitespace()
            if (peek() == ']') fail()
        }
    }

    private fun parseString() {
        expect('"')
        while (index < text.length) {
            val ch = text[index++]
            when {
                ch == '"' -> return
                ch.code < 0x20 -> fail()
                ch == '\\' -> {
                    if (index >= text.length) fail()
                    when (text[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) { if (index >= text.length || text[index++].digitToIntOrNull(16) == null) fail() }
                        else -> fail()
                    }
                }
            }
        }
        fail()
    }

    private fun parseNumber() {
        consume('-')
        if (consume('0')) {
            if (peek() in '0'..'9') fail()
        } else {
            if (peek() !in '1'..'9') fail()
            while (peek() in '0'..'9') index++
        }
        if (consume('.')) {
            if (peek() !in '0'..'9') fail()
            while (peek() in '0'..'9') index++
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            if (peek() !in '0'..'9') fail()
            while (peek() in '0'..'9') index++
        }
    }

    private fun literal(expected: String) {
        if (!text.regionMatches(index, expected, 0, expected.length)) fail()
        index += expected.length
    }
    private fun skipWhitespace() { while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++ }
    private fun expect(ch: Char) { if (!consume(ch)) fail() }
    private fun consume(ch: Char): Boolean { if (peek() != ch) return false; index++; return true }
    private fun peek(): Char = text.getOrNull(index) ?: '\u0000'
    private fun fail(): Nothing = throw StaticSyntaxException(index)

    companion object { fun validate(text: String) = StrictJsonSyntaxValidator(text).validate() }
}

/** Secure XML parser rejects DTDs and external entities before parsing the document. */
private object SecureXmlSyntaxValidator {
    fun validate(text: String) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.setExpandEntityReferences(false)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException) { throw exception }
            override fun fatalError(exception: SAXParseException) { throw exception }
        })
        builder.parse(InputSource(StringReader(text)))
    }
}
