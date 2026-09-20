package io.crystalnova.manager.data

// ------------------------------------------------------------------
// Minimal JSON parser: objects, arrays, strings, numbers, booleans,
// null. Returns null on ANY malformed input — never throws, never
// guesses. The catalog shape is small; a full JSON library is not
// worth the dependency.
// ------------------------------------------------------------------

internal sealed interface JsonVal {
    data class Obj(val map: Map<String, JsonVal>) : JsonVal
    data class Arr(val items: List<JsonVal>) : JsonVal
    data class Str(val value: String) : JsonVal
    data class Num(val raw: String) : JsonVal
    data class Bool(val value: Boolean) : JsonVal
    data object Null : JsonVal
}

// parseValue and parseObject/parseArray are mutually recursive, which
// Kotlin local functions cannot express (no forward references), so the
// parser is a small private class instead of nested local functions.
private class JsonParser(text: String) {
    private val s = text.trim()
    private var i = 0
    private val n = s.length

    fun parse(): JsonVal? {
        val root = parseValue() ?: return null
        ws()
        return if (i == n) root else null
    }

    private fun ws() {
        while (i < n && s[i].isWhitespace()) i++
    }

    private fun parseString(): String? {
        if (i >= n || s[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> {
                    i++
                    return sb.toString()
                }
                c == '\\' -> {
                    i++
                    if (i >= n) return null
                    when (val e = s[i]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            if (i + 4 >= n) return null
                            val code = s.substring(i + 1, i + 5).toIntOrNull(16)
                                ?: return null
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                    i++
                }
                c == '\n' || c == '\r' -> return null
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun parseValue(): JsonVal? {
        ws()
        if (i >= n) return null
        return when (val c = s[i]) {
            '"' -> parseString()?.let(JsonVal::Str)
            '{' -> parseObject()
            '[' -> parseArray()
            't' -> if (s.startsWith("true", i)) {
                i += 4
                JsonVal.Bool(true)
            } else null
            'f' -> if (s.startsWith("false", i)) {
                i += 5
                JsonVal.Bool(false)
            } else null
            'n' -> if (s.startsWith("null", i)) {
                i += 4
                JsonVal.Null
            } else null
            '-', in '0'..'9' -> parseNumber()
            else -> null
        }
    }

    private fun parseNumber(): JsonVal? {
        val start = i
        if (i < n && s[i] == '-') i++
        if (i >= n) return null
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < n && s[i].isDigit()) i++
        } else return null
        if (i < n && s[i] == '.') {
            i++
            if (i >= n || !s[i].isDigit()) return null
            while (i < n && s[i].isDigit()) i++
        }
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            if (i >= n || !s[i].isDigit()) return null
            while (i < n && s[i].isDigit()) i++
        }
        return JsonVal.Num(s.substring(start, i))
    }

    private fun parseArray(): JsonVal? {
        i++ // [
        val items = mutableListOf<JsonVal>()
        ws()
        if (i < n && s[i] == ']') {
            i++
            return JsonVal.Arr(items)
        }
        while (true) {
            val v = parseValue() ?: return null
            items += v
            ws()
            if (i >= n) return null
            when (s[i]) {
                ',' -> {
                    i++
                    continue
                }
                ']' -> {
                    i++
                    return JsonVal.Arr(items)
                }
                else -> return null
            }
        }
    }

    private fun parseObject(): JsonVal? {
        i++ // {
        val map = LinkedHashMap<String, JsonVal>()
        ws()
        if (i < n && s[i] == '}') {
            i++
            return JsonVal.Obj(map)
        }
        while (true) {
            ws()
            val key = parseString() ?: return null
            ws()
            if (i >= n || s[i] != ':') return null
            i++
            val v = parseValue() ?: return null
            map[key] = v
            ws()
            if (i >= n) return null
            when (s[i]) {
                ',' -> {
                    i++
                    continue
                }
                '}' -> {
                    i++
                    return JsonVal.Obj(map)
                }
                else -> return null
            }
        }
    }
}

internal fun parseJson(text: String): JsonVal? = JsonParser(text).parse()
