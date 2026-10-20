package com.iipauk.spider

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-private workspace directories. UI/PTy integration is a future milestone. */
class WorkspaceRepository(context: Context) {
    private val root: File = File(context.filesDir, "workspaces").canonicalFile

    init {
        check(root.mkdirs() || root.isDirectory) { "Cannot create workspace root" }
    }

    data class Workspace(val id: String, val name: String, val directory: File, val createdAt: Long)

    @Synchronized
    fun list(): List<Workspace> = root.listFiles()?.asSequence()
        ?.filter { it.isDirectory && it.canonicalFile.parentFile == root }
        ?.mapNotNull { readWorkspace(it) }
        ?.sortedBy { it.createdAt }
        ?.toList() ?: emptyList()

    @Synchronized
    fun create(rawName: String): Workspace {
        val name = normaliseName(rawName)
        require(name.isNotBlank()) { "Workspace name is empty" }
        require(list().none { it.name.equals(name, ignoreCase = true) }) { "Workspace name already exists" }
        val id = UUID.randomUUID().toString()
        val dir = childDirectory(id)
        check(dir.mkdirs()) { "Cannot create workspace directory" }
        val createdAt = System.currentTimeMillis()
        val metadata = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("id", id)
            .put("name", name)
            .put("createdAt", createdAt)
        val temp = File(dir, "$MARKER.tmp")
        val marker = File(dir, MARKER)
        try {
            temp.writeText(metadata.toString(2), Charsets.UTF_8)
            Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
        return Workspace(id, name, dir.canonicalFile, createdAt)
    }

    @Synchronized
    fun rename(id: String, rawName: String): Workspace {
        val name = normaliseName(rawName)
        require(name.isNotBlank()) { "Workspace name is empty" }
        val existing = list().firstOrNull { it.id == id } ?: error("Workspace not found")
        require(list().none { it.id != id && it.name.equals(name, ignoreCase = true) }) { "Workspace name already exists" }
        val marker = File(existing.directory, MARKER)
        val json = JSONObject(marker.readText(Charsets.UTF_8)).put("name", name)
        val temp = File(existing.directory, "$MARKER.tmp")
        temp.writeText(json.toString(2), Charsets.UTF_8)
        Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return existing.copy(name = name)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid workspace id" }
        val dir = directoryFor(id) ?: return false
        require(dir.isDirectory && dir.canonicalFile.parentFile == root) { "Workspace path escaped root" }
        return dir.deleteRecursively()
    }

    fun directoryFor(id: String): File? {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
        val dir = childDirectory(id)
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != root || !canonical.isDirectory) return null
        return canonical.takeIf { readWorkspace(it) != null }
    }

    private fun childDirectory(id: String): File {
        val child = File(root, id).canonicalFile
        require(child.parentFile == root) { "Workspace path escaped root" }
        return child
    }

    private fun readWorkspace(dir: File): Workspace? = runCatching {
        val canonical = dir.canonicalFile
        require(canonical.parentFile == root)
        val json = JSONObject(File(canonical, MARKER).readText(Charsets.UTF_8))
        val id = json.getString("id")
        require(id == canonical.name && id.matches(Regex("[0-9a-fA-F-]{36}")))
        require(json.getInt("schemaVersion") == SCHEMA_VERSION)
        Workspace(id, normaliseName(json.getString("name")), canonical, json.getLong("createdAt"))
    }.getOrNull()

    private fun normaliseName(raw: String): String = raw
        .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NAME_LENGTH)

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MARKER = ".spider-workspace.json"
        private const val MAX_NAME_LENGTH = 40
    }
}
