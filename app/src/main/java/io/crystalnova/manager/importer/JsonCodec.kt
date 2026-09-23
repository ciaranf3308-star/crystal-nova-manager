package io.crystalnova.manager.importer

import io.crystalnova.manager.data.KeyValueStore

/**
 * Minimal JSON writer to pair with the existing [parseJson] reader in
 * `data/Json.kt` (internal to this module, so visible here). Used for
 * the persisted import queue, import history, and platform mapping —
 * all app-private files the user never edits by hand.
 *
 * Writer output is always well-formed; the reader side treats ANY
 * malformed input as "no data" and falls back to defaults, never
 * throwing.
 */
object JsonCodec {

    // ---- writing ----

    fun writeString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun writeObject(vararg pairs: Pair<String, String>): String = buildString {
        append('{')
        pairs.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append(writeString(k)).append(':').append(v)
        }
        append('}')
    }

    fun writeArray(items: List<String>): String =
        items.joinToString(",", "[", "]")

    fun writeLong(value: Long): String = value.toString()
    fun writeInt(value: Int): String = value.toString()
    fun writeBoolean(value: Boolean): String = value.toString()
    fun writeNull(): String = "null"
    fun writeNullableString(value: String?): String =
        value?.let(::writeString) ?: writeNull()

    // ---- nested structures (queue/history persistence) ----

    /** Renders any nested value (object/list/scalar) as JSON text. */
    fun stringifyValue(value: Any?): String = when (value) {
        null -> writeNull()
        is String -> writeString(value)
        is Boolean -> writeBoolean(value)
        is Number -> value.toString()
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            stringifyObject(value as Map<String, Any?>)
        }
        is List<*> -> writeArray(value.map { stringifyValue(it) })
        else -> writeString(value.toString())
    }

    fun stringifyObject(map: Map<String, Any?>): String =
        writeObject(*map.entries.map { (k, v) -> k to stringifyValue(v) }.toTypedArray())

    fun stringifyArray(rows: List<Map<String, Any?>>): String =
        writeArray(rows.map { stringifyObject(it) })

    /** Parses a top-level JSON array into plain Kotlin values, or null when malformed. */
    fun parseArray(text: String): List<Any?>? = JsonRead.arr(text)
}

/**
 * Typed accessors over the parsed [JsonVal] tree. Every accessor is
 * null-safe: wrong shapes yield null and callers fall back to
 * defaults.
 */
object JsonRead {
    fun obj(text: String): Map<String, Any?>? {
        val root = try {
            io.crystalnova.manager.data.parseJson(text)
        } catch (_: Exception) {
            null
        } ?: return null
        return (root as? io.crystalnova.manager.data.JsonVal.Obj)?.map?.mapValues { toKotlin(it.value) }
    }

    fun arr(text: String): List<Any?>? {
        val root = try {
            io.crystalnova.manager.data.parseJson(text)
        } catch (_: Exception) {
            null
        } ?: return null
        return (root as? io.crystalnova.manager.data.JsonVal.Arr)?.items?.map(::toKotlin)
    }

    @Suppress("UNCHECKED_CAST")
    fun toKotlin(v: io.crystalnova.manager.data.JsonVal): Any? = when (v) {
        is io.crystalnova.manager.data.JsonVal.Str -> v.value
        is io.crystalnova.manager.data.JsonVal.Num ->
            v.raw.toLongOrNull() ?: v.raw.toDoubleOrNull()
        is io.crystalnova.manager.data.JsonVal.Bool -> v.value
        is io.crystalnova.manager.data.JsonVal.Null -> null
        is io.crystalnova.manager.data.JsonVal.Obj ->
            v.map.mapValues { toKotlin(it.value) }
        is io.crystalnova.manager.data.JsonVal.Arr -> v.items.map(::toKotlin)
    }

    fun str(map: Map<String, Any?>, key: String): String? =
        map[key] as? String

    fun long(map: Map<String, Any?>, key: String): Long? =
        (map[key] as? Number)?.toLong()

    fun int(map: Map<String, Any?>, key: String): Int? =
        (map[key] as? Number)?.toInt()

    fun bool(map: Map<String, Any?>, key: String): Boolean? =
        map[key] as? Boolean

    @Suppress("UNCHECKED_CAST")
    fun obj(map: Map<String, Any?>, key: String): Map<String, Any?>? =
        map[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(map: Map<String, Any?>, key: String): List<Any?>? =
        map[key] as? List<Any?>
}

/** Map-backed [KeyValueStore] for JVM tests. */
class MapKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}
