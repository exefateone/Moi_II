package org.json

/** Minimal deterministic JSON array double for source/JVM smoke tests only; Android uses platform org.json. */
class JSONArray {
    private val values = mutableListOf<Any?>()
    constructor()
    fun put(value: Any?): JSONArray { values += value; return this }
    fun length(): Int = values.size
    fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject
    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]") { value ->
        when (value) {
            null -> "null"
            is Number, is Boolean, is JSONObject, is JSONArray -> value.toString()
            else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}
