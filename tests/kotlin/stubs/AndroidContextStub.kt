package android.content

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Compile-time stand-in for source-only JVM smoke; Android Gradle builds use android.content.Context. */
open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: File = File(System.getProperty("java.io.tmpdir"), "spider-android-context-stub")
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = PreferenceStore.stores.computeIfAbsent(name) { InMemoryPreferences() }
    companion object { const val MODE_PRIVATE: Int = 0 }
}

interface SharedPreferences {
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getString(key: String, defValue: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putBoolean(key: String, value: Boolean): Editor
        fun putString(key: String, value: String?): Editor
        fun apply()
        fun commit(): Boolean
    }
}

private class InMemoryPreferences : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun apply() { pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value } }
        override fun commit(): Boolean { apply(); return true }
    }
}

private object PreferenceStore { val stores = ConcurrentHashMap<String, SharedPreferences>() }
