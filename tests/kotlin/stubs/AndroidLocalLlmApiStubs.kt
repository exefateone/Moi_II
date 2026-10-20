package android.content

import android.database.Cursor
import android.net.Uri
import java.io.File
import java.io.InputStream

/** Minimal API signatures to type-check the local model-store/runtime Kotlin sources without Android SDK. */
open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: File = File(System.getProperty("java.io.tmpdir"), "spider-local-llm-stub")
    open fun getExternalFilesDir(type: String?): File? = null
    open fun getSystemService(name: String): Any? = null
    open val contentResolver: ContentResolver = ContentResolver()
    companion object {
        const val ACTIVITY_SERVICE = "activity"
    }
}

open class ContentResolver {
    open fun openInputStream(uri: Uri): InputStream? = null
    open fun query(uri: Uri, projection: Array<String>, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
}
