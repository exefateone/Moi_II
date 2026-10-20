package android.database

interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean
    fun getString(columnIndex: Int): String?
    fun getColumnIndex(columnName: String): Int
    fun getLong(columnIndex: Int): Long
    override fun close()
}
