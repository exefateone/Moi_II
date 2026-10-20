package com.iipauk.spider

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable, versioned task history backed by SQLite.
 *
 * The old binary task store is imported in one transaction and kept as a migration archive.
 * The import marker is committed with the imported rows, so a crash either imports everything or
 * retries the import. A future database schema fails closed through SQLiteOpenHelper's downgrade
 * guard; a failed import never deletes or rewrites the binary source.
 */
class SqliteSpiderTaskRepository(
    context: Context,
    private val legacyStoreFile: File,
    databaseName: String = DATABASE_NAME
) : SpiderTaskRepository {
    private val appContext = context.applicationContext
    private val databaseFile = appContext.getDatabasePath(databaseName)
    private val helper = SpiderTaskDatabaseRegistry.get(appContext, databaseName)
    private val lock = locks.computeIfAbsent(databaseFile.canonicalPath) { Any() }

    @Volatile
    var recoveryNotice: String? = null
        private set

    @Volatile
    var lastStorageError: String? = null
        private set

    override fun find(taskId: String): SpiderAgentTask? = synchronized(lock) {
        val db = database()
        readTasksByIds(db, "SELECT $TASK_COLUMNS FROM task_history WHERE task_id = ?", arrayOf(taskId)).firstOrNull()
    }

    override fun save(task: SpiderAgentTask) = synchronized(lock) {
        validateTask(task)
        val db = database()
        db.beginTransaction()
        try {
            // Delete + insert inside one transaction keeps parent/child state atomic and explicit.
            db.delete("task_history", "task_id = ?", arrayOf(task.id))
            db.insertOrThrow("task_history", null, task.toContentValues())
            task.steps.forEachIndexed { position, step ->
                db.insertOrThrow("task_steps", null, step.toContentValues(task.id, position))
            }
            db.setTransactionSuccessful()
            lastStorageError = null
        } catch (error: Exception) {
            lastStorageError = "Не удалось сохранить историю задачи. Старые записи не удалялись."
            throw error
        } finally {
            db.endTransaction()
        }
        Unit
    }

    override fun all(): List<SpiderAgentTask> = synchronized(lock) {
        readTasksByIds(database(), "SELECT $TASK_COLUMNS FROM task_history ORDER BY created_at ASC, task_id ASC", emptyArray())
    }

    /** Efficient history screen query; does not materialize the full task table into RAM. */
    override fun recent(limit: Int): List<SpiderAgentTask> = synchronized(lock) {
        val safeLimit = limit.coerceIn(0, MAX_RECENT_LIMIT)
        if (safeLimit == 0) return@synchronized emptyList()
        readTasksByIds(
            database(),
            "SELECT $TASK_COLUMNS FROM task_history ORDER BY updated_at DESC, created_at DESC, task_id DESC LIMIT ?",
            arrayOf(safeLimit.toString())
        )
    }

    /** Startup recovery only needs in-flight tasks, not the complete historical corpus. */
    override fun recoverableForStartup(): List<SpiderAgentTask> = synchronized(lock) {
        val statuses = arrayOf(
            SpiderTaskStatus.PLANNING.name,
            SpiderTaskStatus.RUNNING.name,
            SpiderTaskStatus.WAITING_FOR_APPROVAL.name,
            SpiderTaskStatus.VERIFYING.name,
            SpiderTaskStatus.PAUSED.name,
            SpiderTaskStatus.PLANNING.name,
            SpiderTaskStatus.RUNNING.name,
            SpiderTaskStatus.WAITING_FOR_APPROVAL.name,
            SpiderTaskStatus.VERIFYING.name
        )
        readTasksByIds(
            database(),
            "SELECT $TASK_COLUMNS FROM task_history WHERE status IN (?,?,?,?) OR (status = ? AND resume_status IN (?,?,?,?)) ORDER BY updated_at ASC, created_at ASC",
            statuses
        )
    }

    override fun count(): Int = synchronized(lock) {
        database().rawQuery("SELECT COUNT(*) FROM task_history", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun database(): SQLiteDatabase {
        val db = helper.writableDatabase
        importLegacyIfNeeded(db)
        return db
    }

    private fun importLegacyIfNeeded(db: SQLiteDatabase) {
        val marker = readImportMarker(db)
        when {
            marker == null -> importLegacyStore(db)
            marker.startsWith(PENDING_IMPORT_PREFIX) -> finalizePendingImport(db)
            marker.startsWith(IMPORTED_PREFIX) -> {
                // If a process died after the verified marker but before the rename, finishing the
                // archive is safe. A file appearing after an explicit no-source marker is not moved.
                if (legacyStoreFile.exists()) {
                    recoveryNotice = null
                    archiveLegacyStore()
                }
            }
            marker == NO_LEGACY_SOURCE -> Unit
            else -> throw IOException("Task-history import state is not recognized; refusing to rewrite history.")
        }
    }

    private fun readImportMarker(db: SQLiteDatabase): String? = db.rawQuery(
        "SELECT value FROM task_history_metadata WHERE key = ?",
        arrayOf(LEGACY_IMPORT_KEY)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun importLegacyStore(db: SQLiteDatabase) {
        // Decode before writing SQLite. The existing versioned reader preserves malformed source
        // files and refuses unsupported future versions; surface an actionable notice on failure.
        val legacy = FileSpiderTaskRepository(legacyStoreFile)
        val legacyTasks = try {
            if (legacyStoreFile.exists()) legacy.all() else emptyList()
        } catch (error: Exception) {
            lastStorageError = "Не удалось прочитать прежнюю историю задач. Исходный файл сохранён; новые действия заблокированы."
            recoveryNotice = lastStorageError
            throw error
        }
        legacy.recoveryNotice?.let { recoveryNotice = it }

        val sourceStillExists = legacyStoreFile.exists()
        db.beginTransaction()
        try {
            if (legacyTasks.isNotEmpty()) {
                legacyTasks.forEach { task ->
                    validateTask(task)
                    db.insertOrThrow("task_history", null, task.toContentValues())
                    task.steps.forEachIndexed { position, step ->
                        db.insertOrThrow("task_steps", null, step.toContentValues(task.id, position))
                    }
                }
            }
            val imported = readTasksByIds(
                db,
                "SELECT $TASK_COLUMNS FROM task_history ORDER BY created_at ASC, task_id ASC",
                emptyArray()
            )
            if (imported.size != legacyTasks.size || imported.associateBy { it.id } != legacyTasks.associateBy { it.id }) {
                throw IOException("Legacy task history verification failed; source file preserved.")
            }
            val values = ContentValues().apply {
                put("key", LEGACY_IMPORT_KEY)
                // PENDING is intentionally not considered complete by future opens. It remains
                // recoverable if the process stops before a separate read-only DB integrity check.
                put("value", if (sourceStillExists) "$PENDING_IMPORT_PREFIX${legacyTasks.size}" else NO_LEGACY_SOURCE)
            }
            db.insertOrThrow("task_history_metadata", null, values)
            db.setTransactionSuccessful()
        } catch (error: Exception) {
            lastStorageError = "Не удалось перенести прежнюю историю в SQLite. Исходный файл сохранён; импорт можно повторить."
            recoveryNotice = lastStorageError
            throw error
        } finally {
            db.endTransaction()
        }

        lastStorageError = null
        if (sourceStillExists) finalizePendingImport(db, expectedTasks = legacyTasks)
    }

    /**
     * Verify a committed import from a separate read-only connection before archiving the source.
     * The pending marker keeps all repository operations fail-closed until this step succeeds.
     */
    private fun finalizePendingImport(db: SQLiteDatabase, expectedTasks: List<SpiderAgentTask>? = null) {
        if (!legacyStoreFile.exists()) {
            lastStorageError = "Перенос истории ожидает проверки, но исходный архив недоступен."
            recoveryNotice = lastStorageError
            throw IOException("Pending task-history import cannot be verified without its source file.")
        }
        val legacy = FileSpiderTaskRepository(legacyStoreFile)
        val expected = expectedTasks ?: try {
            legacy.all()
        } catch (error: Exception) {
            lastStorageError = "Не удалось повторно проверить прежнюю историю. Исходный файл сохранён."
            recoveryNotice = lastStorageError
            throw error
        }
        legacy.recoveryNotice?.let { recoveryNotice = it }

        val verificationDb = try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (error: Exception) {
            lastStorageError = "Не удалось повторно открыть базу истории для проверки. Исходный файл сохранён."
            recoveryNotice = lastStorageError
            throw error
        }
        try {
            val integrity = verificationDb.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "check_unavailable"
            }
            if (integrity != "ok") throw IOException("Task-history SQLite integrity check did not return ok.")
            val reopened = readTasksByIds(
                verificationDb,
                "SELECT $TASK_COLUMNS FROM task_history ORDER BY created_at ASC, task_id ASC",
                emptyArray()
            )
            if (reopened.size != expected.size || reopened.associateBy { it.id } != expected.associateBy { it.id }) {
                throw IOException("Reopened SQLite task history does not match its source.")
            }
        } catch (error: Exception) {
            lastStorageError = "Проверка базы истории не прошла. Исходный файл сохранён, действия остаются заблокированными."
            recoveryNotice = lastStorageError
            throw error
        } finally {
            verificationDb.close()
        }

        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("value", "$IMPORTED_PREFIX${expected.size}") }
            val updated = db.update("task_history_metadata", values, "key = ?", arrayOf(LEGACY_IMPORT_KEY))
            if (updated != 1) throw IOException("Task-history import marker could not be finalized.")
            db.setTransactionSuccessful()
        } catch (error: Exception) {
            lastStorageError = "База проверена, но не удалось зафиксировать результат миграции. Исходный файл сохранён."
            recoveryNotice = lastStorageError
            throw error
        } finally {
            db.endTransaction()
        }
        lastStorageError = null
        recoveryNotice = null
        archiveLegacyStore()
    }

    private fun archiveLegacyStore() {
        if (!legacyStoreFile.exists()) return
        val target = File(legacyStoreFile.parentFile, "${legacyStoreFile.name}.migrated-sqlite-v1")
        if (target.exists()) {
            recoveryNotice = recoveryNotice ?: "Исходная история сохранена: файл архива миграции уже существует, существующий архив не перезаписан."
            return
        }
        try {
            try {
                Files.move(legacyStoreFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: UnsupportedOperationException) {
                if (!legacyStoreFile.renameTo(target)) throw IOException("Could not archive legacy task history.")
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                if (!legacyStoreFile.renameTo(target)) throw IOException("Could not archive legacy task history.")
            }
            recoveryNotice = recoveryNotice ?: "Старая история перенесена в SQLite; исходный файл сохранён как архив миграции."
        } catch (_: Exception) {
            recoveryNotice = recoveryNotice ?: "История перенесена в SQLite, но старый файл не удалось переименовать. Исходник сохранён."
        }
    }

    private fun readTasksByIds(db: SQLiteDatabase, query: String, args: Array<String>): List<SpiderAgentTask> {
        data class TaskRow(
            val id: String, val goal: String, val status: SpiderTaskStatus,
            val createdAt: Long, val updatedAt: Long, val errorCode: String?,
            val resumeStatus: SpiderTaskStatus?, val summary: String?, val scopeId: String?
        )
        val parents = mutableListOf<TaskRow>()
        db.rawQuery(query, args).use { cursor ->
            while (cursor.moveToNext()) {
                val row = TaskRow(
                    id = cursor.getString(0), goal = cursor.getString(1),
                    status = enumValue<SpiderTaskStatus>(cursor.getString(2)),
                    createdAt = cursor.getLong(3), updatedAt = cursor.getLong(4),
                    errorCode = cursor.getNullableString(5),
                    resumeStatus = cursor.getNullableString(6)?.let { enumValue<SpiderTaskStatus>(it) },
                    summary = cursor.getNullableString(7), scopeId = cursor.getNullableString(8)
                )
                parents += row
            }
        }
        if (parents.isEmpty()) return emptyList()

        val stepsByTask = parents.map { it.id }.chunked(SQLITE_VARIABLE_LIMIT).flatMap { batch ->
            val placeholders = List(batch.size) { "?" }.joinToString(",")
            val rows = mutableListOf<Triple<String, Int, SpiderPlanStep>>()
            db.rawQuery(
                "SELECT task_id, position, step_id, title, tool_id, status, result_summary FROM task_steps WHERE task_id IN ($placeholders) ORDER BY task_id, position",
                batch.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val step = SpiderPlanStep(
                        id = cursor.getString(2),
                        title = cursor.getString(3),
                        toolId = cursor.getNullableString(4)?.let { enumValue<SpiderToolId>(it) },
                        status = enumValue<SpiderStepStatus>(cursor.getString(5)),
                        resultSummary = cursor.getNullableString(6)
                    )
                    rows += Triple(cursor.getString(0), cursor.getInt(1), step)
                }
            }
            rows
        }.groupBy({ it.first }, { it.third })

        return parents.map { row ->
            val task = SpiderAgentTask(
                id = row.id, goal = row.goal, status = row.status,
                steps = stepsByTask[row.id].orEmpty(), createdAtEpochMs = row.createdAt,
                updatedAtEpochMs = row.updatedAt, lastErrorCode = row.errorCode,
                resumeStatus = row.resumeStatus, resultSummary = row.summary,
                workspaceScopeId = row.scopeId
            )
            validateTask(task)
            task
        }
    }

    private fun SpiderAgentTask.toContentValues() = ContentValues().apply {
        put("task_id", id)
        put("goal", goal)
        put("status", status.name)
        put("created_at", createdAtEpochMs)
        put("updated_at", updatedAtEpochMs)
        putNullable("last_error_code", lastErrorCode)
        putNullable("resume_status", resumeStatus?.name)
        putNullable("result_summary", resultSummary)
        putNullable("workspace_scope_id", workspaceScopeId)
    }

    private fun SpiderPlanStep.toContentValues(taskId: String, position: Int) = ContentValues().apply {
        put("task_id", taskId)
        put("position", position)
        put("step_id", id)
        put("title", title)
        putNullable("tool_id", toolId?.name)
        put("status", status.name)
        putNullable("result_summary", resultSummary)
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.getNullableString(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun validateTask(task: SpiderAgentTask) {
        require(task.id.isNotBlank() && task.id.length <= MAX_ID_CHARS) { "Task ID is invalid" }
        require(task.goal.isNotBlank() && task.goal.length <= SpiderTaskCoordinator.MAX_GOAL_LENGTH) { "Task goal is invalid" }
        require(task.lastErrorCode == null || task.lastErrorCode.length <= MAX_ERROR_CHARS) { "Task error code is too long" }
        require(task.resultSummary == null || task.resultSummary.length <= SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH) { "Task result summary is too long" }
        require(task.workspaceScopeId == null || task.workspaceScopeId.matches(SCOPE_ID_REGEX)) { "Task workspace scope is invalid" }
        require(task.steps.size <= SpiderTaskCoordinator.MAX_PLAN_STEPS) { "Too many task steps" }
        require(task.steps.map { it.id }.toSet().size == task.steps.size) { "Task has duplicate step IDs" }
        task.steps.forEach { step ->
            require(step.id.isNotBlank() && step.id.length <= MAX_ID_CHARS) { "Step ID is invalid" }
            require(step.title.isNotBlank() && step.title.length <= SpiderTaskCoordinator.MAX_STEP_TITLE_LENGTH) { "Step title is invalid" }
            require(step.resultSummary == null || step.resultSummary.length <= SpiderTaskCoordinator.MAX_RESULT_SUMMARY_LENGTH) { "Step result summary is too long" }
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = try {
        enumValueOf<T>(value)
    } catch (error: IllegalArgumentException) {
        throw IOException("Task history contains an unknown enum value.", error)
    }

    companion object {
        const val DATABASE_NAME = "spider-task-history-v1.db"
        private const val LEGACY_IMPORT_KEY = "legacy_binary_import_v1"
        private const val PENDING_IMPORT_PREFIX = "pending:"
        private const val IMPORTED_PREFIX = "imported:"
        private const val NO_LEGACY_SOURCE = "no-legacy-file"
        private const val MAX_ID_CHARS = 120
        private const val MAX_ERROR_CHARS = 80
        private const val SQLITE_VARIABLE_LIMIT = 400
        private const val MAX_RECENT_LIMIT = 1000
        private val SCOPE_ID_REGEX = Regex("[A-Za-z0-9_.-]{1,120}")
        private const val TASK_COLUMNS = "task_id, goal, status, created_at, updated_at, last_error_code, resume_status, result_summary, workspace_scope_id"
        private val locks = ConcurrentHashMap<String, Any>()
    }
}

internal class SpiderTaskHistoryDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE task_history (task_id TEXT PRIMARY KEY NOT NULL, goal TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_error_code TEXT, resume_status TEXT, result_summary TEXT, workspace_scope_id TEXT)")
        db.execSQL("CREATE INDEX task_history_updated_idx ON task_history(updated_at DESC, created_at DESC, task_id DESC)")
        db.execSQL("CREATE INDEX task_history_status_idx ON task_history(status, updated_at DESC)")
        db.execSQL("CREATE TABLE task_steps (task_id TEXT NOT NULL, position INTEGER NOT NULL, step_id TEXT NOT NULL, title TEXT NOT NULL, tool_id TEXT, status TEXT NOT NULL, result_summary TEXT, PRIMARY KEY(task_id, position), UNIQUE(task_id, step_id), FOREIGN KEY(task_id) REFERENCES task_history(task_id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX task_steps_task_idx ON task_steps(task_id, position)")
        db.execSQL("CREATE TABLE task_history_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Add explicit migrations here; never drop user task history to make an upgrade pass.
        if (oldVersion < 1) onCreate(db)
    }

    companion object { private const val SCHEMA_VERSION = 1 }
}

private object SpiderTaskDatabaseRegistry {
    private val helpers = ConcurrentHashMap<String, SpiderTaskHistoryDatabase>()
    fun get(context: Context, name: String): SpiderTaskHistoryDatabase {
        val path = context.getDatabasePath(name).canonicalPath
        return helpers.computeIfAbsent(path) { SpiderTaskHistoryDatabase(context, name) }
    }
}
