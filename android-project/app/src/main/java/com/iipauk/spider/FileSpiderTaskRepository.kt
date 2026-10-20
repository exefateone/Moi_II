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
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Legacy versioned binary task store retained as the SQLite import decoder and recovery format.
 * Active runtime writes now go through SqliteSpiderTaskRepository. Pass a file under filesDir.
 * Writes use a same-directory temporary file, fsync, then atomic replace where supported.
 * A malformed store is preserved under a .corrupt-* name before starting a clean store; a future
 * schema version is never rewritten, so a downgrade cannot silently destroy newer task data.
 */
class FileSpiderTaskRepository(private val storeFile: File) : SpiderTaskRepository {
    private val canonicalPath = storeFile.canonicalFile.absolutePath
    private val lock = locks.computeIfAbsent(canonicalPath) { Any() }

    @Volatile
    var recoveryNotice: String? = null
        private set

    override fun find(taskId: String): SpiderAgentTask? = synchronized(lock) {
        refreshFromDiskLocked()[taskId]
    }

    override fun save(task: SpiderAgentTask) = synchronized(lock) {
        val latest = refreshFromDiskLocked()
        validateTask(task)
        require(latest.containsKey(task.id) || latest.size < MAX_TASKS) { "Task store limit reached" }
        latest[task.id] = task
        writeStoreLocked(latest.values.sortedBy { it.createdAtEpochMs })
        Unit
    }

    override fun all(): List<SpiderAgentTask> = synchronized(lock) {
        refreshFromDiskLocked().values.sortedBy { it.createdAtEpochMs }
    }

    private fun refreshFromDiskLocked(): LinkedHashMap<String, SpiderAgentTask> {
        if (!storeFile.exists()) return linkedMapOf()
        val length = storeFile.length()
        if (length <= 0L || length > MAX_STORE_BYTES) {
            return recoverCorruptStoreLocked("Stored task file has an invalid size.")
        }

        try {
            DataInputStream(BufferedInputStream(FileInputStream(storeFile))).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC) throw InvalidTaskStoreException("Task store signature is invalid.")
                val schema = input.readInt()
                if (schema !in MIN_SUPPORTED_SCHEMA..SCHEMA_VERSION) {
                    throw UnsupportedTaskStoreSchemaException(
                        "Task store schema $schema is not supported by this app (supported $MIN_SUPPORTED_SCHEMA..$SCHEMA_VERSION)."
                    )
                }
                val count = input.readInt()
                if (count !in 0..MAX_TASKS) throw InvalidTaskStoreException("Task count is outside the allowed range.")
                val tasks = linkedMapOf<String, SpiderAgentTask>()
                repeat(count) {
                    val task = readTask(input, schema)
                    if (tasks.put(task.id, task) != null) {
                        throw InvalidTaskStoreException("Duplicate task ID in stored data.")
                    }
                }
                if (input.read() != -1) throw InvalidTaskStoreException("Unexpected trailing bytes in task store.")
                return tasks
            }
        } catch (unsupported: UnsupportedTaskStoreSchemaException) {
            throw unsupported
        } catch (invalid: InvalidTaskStoreException) {
            return recoverCorruptStoreLocked(invalid.message ?: "Stored task data is invalid.")
        } catch (eof: EOFException) {
            return recoverCorruptStoreLocked("Stored task data is truncated.")
        } catch (invalid: IllegalArgumentException) {
            return recoverCorruptStoreLocked("Stored task data contains an invalid field.")
        }
    }

    private fun recoverCorruptStoreLocked(reason: String): LinkedHashMap<String, SpiderAgentTask> {
        val backup = File(storeFile.parentFile, "${storeFile.name}.corrupt-${System.currentTimeMillis()}")
        try {
            Files.move(storeFile.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!storeFile.renameTo(backup)) {
                throw IOException("$reason Could not preserve the original file; refusing to reset the task store.")
            }
        } catch (e: IOException) {
            throw IOException("$reason Could not preserve the original file; refusing to reset the task store.", e)
        }
        recoveryNotice = "$reason The previous file was preserved as '${backup.name}'. A new task store will be created when the next task is saved."
        return linkedMapOf()
    }

    private fun writeStoreLocked(tasks: List<SpiderAgentTask>) {
        val parent = storeFile.absoluteFile.parentFile
            ?: throw IOException("Task store must be inside an app-private directory.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create task store directory.")
        if (!parent.isDirectory) throw IOException("Task store parent is not a directory.")

        val temp = File(parent, "${storeFile.name}.tmp")
        try {
            val fileOutput = FileOutputStream(temp, false)
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(SCHEMA_VERSION)
                output.writeInt(tasks.size)
                tasks.forEach { writeTask(output, it) }
                output.flush()
                fileOutput.fd.sync()
            }
            try {
                Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }
    }

    private fun writeTask(out: DataOutputStream, task: SpiderAgentTask) {
        writeString(out, task.id, MAX_ID_CHARS)
        writeString(out, task.goal, MAX_GOAL_CHARS)
        writeString(out, task.status.name, 40)
        out.writeLong(task.createdAtEpochMs)
        out.writeLong(task.updatedAtEpochMs)
        writeNullableString(out, task.lastErrorCode, MAX_ERROR_CHARS)
        writeNullableString(out, task.resumeStatus?.name, 40)
        writeNullableString(out, task.resultSummary, SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH)
        writeNullableString(out, task.workspaceScopeId, MAX_SCOPE_CHARS)
        require(task.steps.size <= SpiderTaskCoordinator.MAX_PLAN_STEPS) { "Too many task steps" }
        out.writeInt(task.steps.size)
        task.steps.forEach { step ->
            writeString(out, step.id, MAX_ID_CHARS)
            writeString(out, step.title, SpiderTaskCoordinator.MAX_STEP_TITLE_LENGTH)
            writeNullableString(out, step.toolId?.name, 60)
            writeString(out, step.status.name, 40)
            writeNullableString(out, step.resultSummary, SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH)
        }
    }

    private fun readTask(input: DataInputStream, schema: Int): SpiderAgentTask {
        val id = readString(input, MAX_ID_CHARS)
        val goal = readString(input, MAX_GOAL_CHARS)
        val status = enumValue<SpiderTaskStatus>(readString(input, 40))
        val createdAt = input.readLong()
        val updatedAt = input.readLong()
        val error = readNullableString(input, MAX_ERROR_CHARS)
        val resume = readNullableString(input, 40)?.let { enumValue<SpiderTaskStatus>(it) }
        val summary = readNullableString(input, SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH)
        // Schema 1 did not persist the optional workspace scope. It loads as global memory scope;
        // the next normal save rewrites the repository using schema 2.
        val workspaceScopeId = if (schema >= 2) readNullableString(input, MAX_SCOPE_CHARS) else null
        val stepCount = input.readInt()
        if (stepCount !in 0..SpiderTaskCoordinator.MAX_PLAN_STEPS) {
            throw InvalidTaskStoreException("Stored task step count is invalid.")
        }
        val steps = ArrayList<SpiderPlanStep>(stepCount)
        repeat(stepCount) {
            val stepId = readString(input, MAX_ID_CHARS)
            val title = readString(input, SpiderTaskCoordinator.MAX_STEP_TITLE_LENGTH)
            val toolId = readNullableString(input, 60)?.let { enumValue<SpiderToolId>(it) }
            val stepStatus = enumValue<SpiderStepStatus>(readString(input, 40))
            val result = readNullableString(input, SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH)
            steps += SpiderPlanStep(stepId, title, toolId, stepStatus, result)
        }
        if (id.isBlank() || goal.isBlank()) throw InvalidTaskStoreException("Stored task ID or goal is blank.")
        if (steps.map { it.id }.toSet().size != steps.size) throw InvalidTaskStoreException("Stored task has duplicate step IDs.")
        val task = SpiderAgentTask(id, goal, status, steps, createdAt, updatedAt, error, resume, summary, workspaceScopeId)
        try {
            validateTask(task)
        } catch (_: IllegalArgumentException) {
            throw InvalidTaskStoreException("Stored task violates the current task contract.")
        }
        return task
    }

    private fun validateTask(task: SpiderAgentTask) {
        require(task.id.isNotBlank() && task.id.length <= MAX_ID_CHARS) { "Task ID is invalid" }
        require(task.goal.isNotBlank() && task.goal.length <= MAX_GOAL_CHARS) { "Task goal is invalid" }
        require(task.lastErrorCode == null || task.lastErrorCode.length <= MAX_ERROR_CHARS) { "Task error code is too long" }
        require(task.resultSummary == null || task.resultSummary.length <= SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH) { "Task result summary is too long" }
        require(task.workspaceScopeId == null || task.workspaceScopeId.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "Task workspace scope is invalid" }
        require(task.steps.size <= SpiderTaskCoordinator.MAX_PLAN_STEPS) { "Too many task steps" }
        require(task.steps.map { it.id }.toSet().size == task.steps.size) { "Task has duplicate step IDs" }
        task.steps.forEach { step ->
            require(step.id.isNotBlank() && step.id.length <= MAX_ID_CHARS) { "Step ID is invalid" }
            require(step.title.isNotBlank() && step.title.length <= SpiderTaskCoordinator.MAX_STEP_TITLE_LENGTH) { "Step title is invalid" }
            require(step.resultSummary == null || step.resultSummary.length <= SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH) { "Step result summary is too long" }
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf<T>(value)

    private fun writeNullableString(out: DataOutputStream, value: String?, maxChars: Int) {
        out.writeBoolean(value != null)
        if (value != null) writeString(out, value, maxChars)
    }

    private fun readNullableString(input: DataInputStream, maxChars: Int): String? =
        if (input.readBoolean()) readString(input, maxChars) else null

    private fun writeString(out: DataOutputStream, value: String, maxChars: Int) {
        require(value.length <= maxChars) { "Stored string exceeds field limit" }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maxChars * MAX_UTF8_BYTES_PER_CHAR) throw IllegalArgumentException("Stored string is too large")
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream, maxChars: Int): String {
        val length = input.readInt()
        if (length !in 0..(maxChars * MAX_UTF8_BYTES_PER_CHAR)) {
            throw InvalidTaskStoreException("Stored string length is invalid.")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val value = try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw InvalidTaskStoreException("Stored string is not valid UTF-8.")
        }
        if (value.length > maxChars) throw InvalidTaskStoreException("Stored string exceeds field limit.")
        return value
    }

    private class InvalidTaskStoreException(message: String) : IOException(message)
    private class UnsupportedTaskStoreSchemaException(message: String) : IOException(message)

    companion object {
        internal const val MAGIC = 0x53505452 // SPTR
        internal const val SCHEMA_VERSION = 2
        private const val MIN_SUPPORTED_SCHEMA = 1
        private const val MAX_SCOPE_CHARS = 120
        internal const val MAX_TASKS = 2_000
        private const val MAX_STORE_BYTES = 8L * 1024L * 1024L
        private const val MAX_ID_CHARS = 120
        private const val MAX_GOAL_CHARS = SpiderTaskCoordinator.MAX_GOAL_LENGTH
        private const val MAX_ERROR_CHARS = 80
        private const val MAX_UTF8_BYTES_PER_CHAR = 4
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
