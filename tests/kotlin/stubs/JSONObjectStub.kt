package org.json

/** Minimal flat-object double for JVM smoke tests only. Android builds use platform org.json. */
class JSONObject {
    private val values = linkedMapOf<String, Any?>()

    constructor()
    constructor(raw: String) { parse(raw) }

    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun optString(key: String): String = values[key]?.toString() ?: ""
    fun opt(key: String): Any? = values[key]
    fun optString(key: String, fallback: String): String = values[key]?.toString() ?: fallback
    fun optJSONArray(key: String): JSONArray? = values[key] as? JSONArray
    fun getJSONArray(key: String): JSONArray = values[key] as? JSONArray ?: throw IllegalArgumentException("missing array: $key")
    fun optJSONObject(key: String): JSONObject? = values[key] as? JSONObject
    fun optLong(key: String, fallback: Long = 0L): Long = (values[key] as? Number)?.toLong() ?: values[key]?.toString()?.toLongOrNull() ?: fallback

    override fun toString(): String = values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${quote(key)}:${when (value) {
            null -> "null"
            is Number, is Boolean, is JSONObject, is JSONArray -> value.toString()
            else -> quote(value.toString())
        }}"
    }

    private fun parse(raw: String) {
        var i = 0
        fun ws() { while (i < raw.length && raw[i].isWhitespace()) i++ }
        fun stringValue(): String {
            require(i < raw.length && raw[i] == '"')
            i++
            val out = StringBuilder()
            while (i < raw.length) {
                val ch = raw[i++]
                if (ch == '"') return out.toString()
                if (ch == '\\' && i < raw.length) {
                    when (val escaped = raw[i++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> { require(i + 4 <= raw.length); out.append(raw.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("invalid escape")
                    }
                } else out.append(ch)
            }
            error("unterminated string")
        }
        ws(); require(raw.getOrNull(i++) == '{'); ws()
        while (i < raw.length && raw[i] != '}') {
            val key = stringValue(); ws(); require(raw.getOrNull(i++) == ':'); ws()
            val value: Any? = if (raw.getOrNull(i) == '"') stringValue() else {
                val begin = i
                while (i < raw.length && raw[i] !in ",}" && !raw[i].isWhitespace()) i++
                val token = raw.substring(begin, i)
                when (token) { "null" -> null; "true" -> true; "false" -> false; else -> token.toLongOrNull() ?: token.toDoubleOrNull() ?: token }
            }
            values[key] = value
            ws()
            if (raw.getOrNull(i) == ',') { i++; ws() } else break
        }
        require(raw.getOrNull(i) == '}')
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
