package com.example.vibratext.json

/**
 * Tiny, strict JSON reader/writer used for profile import/export and local storage.
 *
 * Why not a library? Imported profiles are untrusted input. A ~200-line parser that only
 * produces plain data (Map / List / String / Long / Double / Boolean / null) can never
 * instantiate classes or execute anything, and its limits (depth, size) are explicit.
 */
class JsonException(message: String) : Exception(message)

object MiniJson {
    const val MAX_DEPTH = 32
    const val MAX_INPUT_CHARS = 2_000_000

    /** Parses [text] into Map<String, Any?>, List<Any?>, String, Long, Double, Boolean or null. */
    fun parse(text: String): Any? {
        if (text.length > MAX_INPUT_CHARS) throw JsonException("JSON input is too large")
        val parser = Parser(text)
        parser.skipWs()
        val value = parser.readValue(0)
        parser.skipWs()
        if (!parser.atEnd()) parser.fail("Unexpected trailing characters")
        return value
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Any?, pretty: Boolean, depth: Int) {
        if (depth > MAX_DEPTH) throw JsonException("Nesting too deep")
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value.toString())
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> {
                if (value.isNaN() || value.isInfinite()) throw JsonException("Cannot encode NaN/Infinity")
                sb.append(value.toString())
            }
            is Float -> write(sb, value.toDouble(), pretty, depth)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (k !is String) throw JsonException("JSON object keys must be strings")
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, depth + 1)
                    writeString(sb, k)
                    sb.append(if (pretty) ": " else ":")
                    write(sb, v, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, depth + 1)
                    write(sb, v, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append(']')
            }
            else -> throw JsonException("Unsupported JSON value type: ${value::class.simpleName}")
        }
    }

    private fun newline(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun fail(msg: String): Nothing = throw JsonException("$msg at position $pos")

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Nesting too deep")
            if (atEnd()) fail("Unexpected end of input")
            val c = s[pos]
            return when {
                c == '{' -> readObject(depth)
                c == '[' -> readArray(depth)
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> fail("Unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Invalid literal")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("Expected string key")
                val key = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("Expected ':'")
                pos++
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                if (atEnd()) fail("Unterminated object")
                val c = s[pos]
                if (c == ',') {
                    pos++
                } else if (c == '}') {
                    pos++
                    return map
                } else {
                    fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                list.add(readValue(depth + 1))
                skipWs()
                if (atEnd()) fail("Unterminated array")
                val c = s[pos]
                if (c == ',') {
                    pos++
                } else if (c == ']') {
                    pos++
                    return list
                } else {
                    fail("Expected ',' or ']'")
                }
            }
        }

        fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Truncated \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("Invalid \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Invalid escape '\\$e'")
                        }
                    }
                    c.code < 0x20 -> fail("Unescaped control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '-' || s[pos] == '+' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) pos++
            val token = s.substring(start, pos)
            val unsigned = token.removePrefix("-")
            if (unsigned.isEmpty() || (unsigned.length > 1 && unsigned[0] == '0' && unsigned[1] in '0'..'9')) {
                pos = start
                fail("Invalid number")
            }
            val isInteger = token.none { it == '.' || it == 'e' || it == 'E' }
            if (isInteger) {
                token.toLongOrNull()?.let { return it }
            }
            val d = token.toDoubleOrNull()
            if (d == null || d.isNaN() || d.isInfinite()) {
                pos = start
                fail("Invalid number")
            }
            return d
        }
    }
}

/** Typed, path-aware accessor over a parsed JSON object. Throws [JsonException] on type mismatch. */
class JsonObj private constructor(private val map: Map<String, Any?>, private val path: String) {
    companion object {
        fun of(value: Any?, path: String = "$"): JsonObj {
            if (value !is Map<*, *>) throw JsonException("$path must be an object")
            @Suppress("UNCHECKED_CAST")
            return JsonObj(value as Map<String, Any?>, path)
        }
    }

    val keys: Set<String> get() = map.keys

    fun has(key: String): Boolean = map[key] != null

    fun string(key: String, default: String? = null): String {
        val v = map[key] ?: return default ?: throw JsonException("$path.$key is required")
        return v as? String ?: throw JsonException("$path.$key must be a string")
    }

    fun optString(key: String): String? {
        val v = map[key] ?: return null
        return v as? String ?: throw JsonException("$path.$key must be a string")
    }

    fun int(key: String, default: Int? = null): Int {
        val v = map[key] ?: return default ?: throw JsonException("$path.$key is required")
        return when (v) {
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else throw JsonException("$path.$key is out of range")
            is Double -> if (v == Math.rint(v) && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else throw JsonException("$path.$key must be an integer")
            else -> throw JsonException("$path.$key must be a number")
        }
    }

    fun long(key: String, default: Long? = null): Long {
        val v = map[key] ?: return default ?: throw JsonException("$path.$key is required")
        return when (v) {
            is Long -> v
            is Double -> if (v == Math.rint(v)) v.toLong() else throw JsonException("$path.$key must be an integer")
            else -> throw JsonException("$path.$key must be a number")
        }
    }

    fun double(key: String, default: Double? = null): Double {
        val v = map[key] ?: return default ?: throw JsonException("$path.$key is required")
        return when (v) {
            is Long -> v.toDouble()
            is Double -> v
            else -> throw JsonException("$path.$key must be a number")
        }
    }

    fun bool(key: String, default: Boolean? = null): Boolean {
        val v = map[key] ?: return default ?: throw JsonException("$path.$key is required")
        return v as? Boolean ?: throw JsonException("$path.$key must be true or false")
    }

    fun obj(key: String): JsonObj? {
        val v = map[key] ?: return null
        return of(v, "$path.$key")
    }

    fun list(key: String): List<Any?>? {
        val v = map[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        return v as? List<Any?> ?: throw JsonException("$path.$key must be an array")
    }

    fun raw(key: String): Any? = map[key]
}
