package android.content

import java.io.File

/** Signature-only stubs for a fast Kotlin type-check; not an Android runtime or SQLite simulator. */
open class Context {
    open val applicationContext: Context get() = this
    open fun getDatabasePath(name: String): File = File(System.getProperty("java.io.tmpdir"), name)
}

class ContentValues {
    fun put(key: String, value: String?) = Unit
    fun put(key: String, value: Long?) = Unit
    fun put(key: String, value: Int?) = Unit
    fun putNull(key: String) = Unit
}
