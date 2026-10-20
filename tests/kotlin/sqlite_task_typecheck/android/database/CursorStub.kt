package android.database

/** Signature-only Cursor API surface used by the task-store source check. */
interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getString(index: Int): String
    fun getInt(index: Int): Int
    fun getLong(index: Int): Long
    fun isNull(index: Int): Boolean
    override fun close()
}
