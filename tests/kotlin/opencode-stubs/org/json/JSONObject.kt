package org.json

/** Minimal deterministic JSON double for source/JVM smoke tests only; Android uses platform org.json. */
class JSONObject {
    private val values = linkedMapOf<String, Any?>()
    constructor()
    constructor(raw: String) { parseKnownFields(raw) }
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = values[key] as? Boolean ?: fallback
    fun optString(key: String, fallback: String = ""): String = values[key]?.toString() ?: fallback
    fun optJSONObject(key: String): JSONObject? = values[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = values[key] as? JSONArray
    override fun toString(): String = values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${quote(key)}:${when (value) {
            null -> "null"
            is Number, is Boolean, is JSONObject, is JSONArray -> value.toString()
            else -> quote(value.toString())
        }}"
    }
    private fun parseKnownFields(raw: String) {
        fun field(name: String): String? = Regex("\\\"" + Regex.escape(name) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
            .find(raw)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        listOf("status", "task_id", "message", "id", "title", "tool_id").forEach { name -> field(name)?.let { values[name] = it } }
        if (raw.contains("\"steps\"")) {
            val arrayBody = raw.substringAfter("\"steps\"").substringAfter('[', "").substringBeforeLast(']', "")
            val items = JSONArray()
            Regex("\\{([^{}]*)\\}").findAll(arrayBody).forEach { match ->
                val stepRaw = match.value
                val step = JSONObject()
                listOf("id", "title", "tool_id").forEach { name ->
                    val value = Regex("\\\"" + Regex.escape(name) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
                        .find(stepRaw)?.groupValues?.get(1)
                    if (value != null) step.put(name, value)
                }
                items.put(step)
            }
            values["steps"] = items
        }
    }
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch -> when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        } }
        append('"')
    }
}
