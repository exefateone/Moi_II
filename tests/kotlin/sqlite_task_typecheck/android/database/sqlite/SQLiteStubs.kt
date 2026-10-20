package android.database.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor

/** Signature-only Android SQLite APIs. Functional behavior is tested on an Android emulator. */
open class SQLiteDatabase {
    interface CursorFactory
    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor = error("type-check stub only")
    fun beginTransaction() = Unit
    fun endTransaction() = Unit
    fun setTransactionSuccessful() = Unit
    fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long = 0L
    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int = 0
    fun update(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?): Int = 0
    fun execSQL(sql: String) = Unit
    fun setForeignKeyConstraintsEnabled(enabled: Boolean) = Unit
    fun close() = Unit
    companion object {
        const val OPEN_READONLY = 1
        fun openDatabase(path: String, factory: CursorFactory?, flags: Int): SQLiteDatabase = SQLiteDatabase()
    }
}

abstract class SQLiteOpenHelper(
    context: Context,
    name: String,
    factory: SQLiteDatabase.CursorFactory?,
    version: Int
) {
    fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit
    val writableDatabase: SQLiteDatabase get() = error("type-check stub only")
    val readableDatabase: SQLiteDatabase get() = error("type-check stub only")
    open fun onConfigure(db: SQLiteDatabase) = Unit
    abstract fun onCreate(db: SQLiteDatabase)
    abstract fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)
}
