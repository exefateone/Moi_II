package com.iipauk.spider

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** Model files remain user-supplied and outside the APK to avoid multi-gigabyte app packages. */
data class SpiderLocalModelFile(
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val fingerprint: String
)

class SpiderLocalModelStore(context: Context) {
    private val appContext = context.applicationContext

    fun directory(): File {
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(root, "llm").apply {
            if (!exists() && !mkdirs()) throw IOException("Не удалось создать папку локальных моделей.")
            if (!isDirectory) throw IOException("Путь локальных моделей недоступен.")
        }
    }

    fun listModels(): List<SpiderLocalModelFile> {
        val dir = directory().canonicalFile
        return (dir.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            .mapNotNull { file -> runCatching { validate(file, dir) }.getOrNull() }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toList()
    }

    /** Copies an explicitly selected SAF document into the managed model directory atomically. */
    @Synchronized
    fun importModel(uri: Uri, resolver: ContentResolver): SpiderLocalModelFile {
        val dir = directory().canonicalFile
        val requestedName = queryName(uri, resolver) ?: uri.lastPathSegment ?: "local-model.gguf"
        val sourceSize = querySize(uri, resolver)?.takeIf { it > 0L }
        val safeName = sanitizeName(requestedName)
        require(safeName.endsWith(".gguf", ignoreCase = true)) { "Выбери файл модели с расширением .gguf." }
        require(sourceSize == null || sourceSize in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            "Размер GGUF-файла недопустим или превышает 16 ГиБ."
        }
        val target = File(dir, safeName).canonicalFile
        require(target.parentFile == dir) { "Недопустимое имя файла модели." }
        val temp = File(dir, ".${safeName.take(80)}.${System.nanoTime()}.partial")
        try {
            // Preflight before writing a multi-gigabyte file; reserve space for metadata and cleanup.
            val initialSpace = StatFs(dir.absolutePath).availableBytes
            val initialReserve = maxOf(MIN_FREE_RESERVE_BYTES, (sourceSize ?: 0L) / 20L)
            require(initialSpace >= (sourceSize ?: 0L) + initialReserve) {
                "Недостаточно свободного места для безопасного импорта модели. Освободи место и повтори попытку."
            }
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    var lastSpaceCheck = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        total += read
                        require(total <= MAX_MODEL_BYTES) { "Файл модели превышает поддерживаемый размер 16 ГиБ." }
                        if (sourceSize != null) require(total <= sourceSize) { "Источник модели превысил заявленный размер. Импорт остановлен." }
                        output.write(buffer, 0, read)
                        if (total - lastSpaceCheck >= SPACE_CHECK_INTERVAL_BYTES) {
                            val remaining = (sourceSize ?: total) - total
                            val reserve = maxOf(MIN_FREE_RESERVE_BYTES, (sourceSize ?: total) / 20L)
                            require(StatFs(dir.absolutePath).availableBytes >= remaining.coerceAtLeast(0L) + reserve) {
                                "Во время импорта закончился запас свободного места. Частичный файл будет удалён."
                            }
                            lastSpaceCheck = total
                        }
                    }
                    output.flush()
                    require(sourceSize == null || total == sourceSize) { "Размер импортированного файла не совпал с размером источника." }
                }
            } ?: throw IOException("Не удалось открыть выбранный файл.")
            validateHeaderAndSize(temp)
            val stat = StatFs(dir.absolutePath)
            require(stat.availableBytes >= MIN_FREE_RESERVE_BYTES) { "На устройстве почти не осталось свободного места." }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Same-directory fallback with rollback: keep the previous model recoverable on failure.
                val backup = File(dir, ".${safeName.take(80)}.previous")
                if (backup.exists()) backup.delete()
                val hadTarget = target.exists()
                if (hadTarget && !target.renameTo(backup)) throw IOException("Не удалось безопасно подготовить замену модели.")
                if (!temp.renameTo(target)) {
                    if (hadTarget) backup.renameTo(target)
                    throw IOException("Не удалось атомарно сохранить модель в папке llm.")
                }
                if (backup.exists()) backup.delete()
            }
            return validate(target, dir)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun validate(file: File): SpiderLocalModelFile = validate(file.canonicalFile, directory().canonicalFile)

    private fun validate(file: File, expectedDirectory: File): SpiderLocalModelFile {
        val canonical = file.canonicalFile
        require(canonical.parentFile == expectedDirectory) { "Модель должна находиться непосредственно в папке llm." }
        require(canonical.isFile && canonical.name.endsWith(".gguf", ignoreCase = true)) { "Файл модели должен иметь расширение .gguf." }
        validateHeaderAndSize(canonical)
        return SpiderLocalModelFile(canonical.name, canonical, canonical.length(), fingerprint(canonical))
    }

    private fun validateHeaderAndSize(file: File) {
        val size = file.length()
        require(size in MIN_MODEL_BYTES..MAX_MODEL_BYTES) { "Размер GGUF-файла недопустим или файл пустой." }
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            require(input.read(magic) == 4 && magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                "Файл не имеет заголовка GGUF. Выбери совместимую локальную модель."
            }
        }
    }

    private fun queryName(uri: Uri, resolver: ContentResolver): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri, resolver: ContentResolver): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0) null else cursor.getLong(index).takeIf { it > 0L }
        }
    }.getOrNull()

    private fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim().trim('.')
            .take(120)
        return base.ifBlank { "local-model.gguf" }
    }

    /** Fast tamper/change hint only: metadata plus a bounded header/trailer sample, not a full-file digest. */
    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${file.length()}:${file.lastModified()}".toByteArray(Charsets.UTF_8))
        FileInputStream(file).use { input ->
            val sample = ByteArray(4096)
            val read = input.read(sample)
            if (read > 0) digest.update(sample, 0, read)
        }
        if (file.length() > 4096) {
            java.io.RandomAccessFile(file, "r").use { random ->
                val sample = ByteArray(4096)
                random.seek((file.length() - sample.size).coerceAtLeast(0))
                val read = random.read(sample)
                if (read > 0) digest.update(sample, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_MODEL_BYTES = 16L * 1024 * 1024 * 1024
        private const val MIN_MODEL_BYTES = 1024L * 1024
        private const val MIN_FREE_RESERVE_BYTES = 128L * 1024 * 1024
        private const val SPACE_CHECK_INTERVAL_BYTES = 64L * 1024 * 1024
    }
}
