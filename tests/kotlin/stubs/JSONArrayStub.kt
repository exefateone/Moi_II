package org.json

/** Minimal serializer double for outbound Agent requests in JVM smoke tests only. */
class JSONArray {
    private val values = mutableListOf<Any?>()
    constructor()
    constructor(items: Collection<*>) { values.addAll(items) }
    fun put(value: Any?): JSONArray { values += value; return this }
    fun length(): Int = values.size
    fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject
    fun getJSONArray(index: Int): JSONArray = values.getOrNull(index) as? JSONArray ?: throw IllegalArgumentException("missing array: $index")
    fun getDouble(index: Int): Double = (values.getOrNull(index) as? Number)?.toDouble() ?: throw IllegalArgumentException("missing number: $index")
    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]") { value ->
        when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is JSONObject, is JSONArray, is Number, is Boolean -> value.toString()
            else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}
