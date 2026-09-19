package io.crystalnova.manager.data

/**
 * The remote Crystal iiSU pack catalog — the source of truth for the
 * THEME pack manager.
 *
 * One constant, following the same rolling-dev-latest pattern as the
 * manager's own manifest: `releases/download/dev-latest/...` on the
 * manager repo. The catalog is published by the pack pipeline (NOT by
 * the manager CI); until it exists the pack screen reports that
 * honestly instead of inventing packs.
 *
 * Catalog schema (all keys camelCase):
 * - catalogVersion: Int
 * - sourceRepo / sourceSha: provenance of the assets the catalog was built from
 * - packs: [ { id, name, version, versionCode, description?,
 *              previewUrl?, previewSha256?,
 *              systems: [String], iisuMinVersion?,
 *              zipUrl, zipSha256, zipBytes?,
 *              assets: [ { slot, platform, path, sha256, bytes,
 *                          width?, height?, required } ] } ]
 *
 * Pure JVM: no org.json (stubbed under unit tests), no Android APIs.
 */
const val PACK_CATALOG_URL =
    "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/packs/catalog.json"

/** One asset inside a pack ZIP. `slot` is icon | title | background. */
data class PackAsset(
    val slot: String,
    /** iiSU platform id (shortName / alternativeName). */
    val platform: String,
    /** Path inside the ZIP, e.g. "crystal-dawn/snes/icon.png". */
    val path: String,
    val sha256: String,
    val bytes: Long,
    val width: Int?,
    val height: Int?,
    val required: Boolean,
)

/** One downloadable/installable Crystal iiSU platform pack. */
data class PackEntry(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val description: String,
    val previewUrl: String?,
    val previewSha256: String?,
    /** Human platform labels, e.g. "PlayStation 2". */
    val systems: List<String>,
    /** Minimum iiSU version that understands the pack, e.g. "0.0.7.4". */
    val iisuMinVersion: String?,
    val zipUrl: String,
    val zipSha256: String,
    val zipBytes: Long?,
    val assets: List<PackAsset>,
)

data class PackCatalog(
    val catalogVersion: Int,
    val sourceRepo: String?,
    val sourceSha: String?,
    val packs: List<PackEntry>,
)

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

internal fun parseJson(text: String): JsonVal? {
    val s = text.trim()
    var i = 0
    val n = s.length

    fun ws() {
        while (i < n && s[i].isWhitespace()) i++
    }

    fun parseString(): String? {
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

    fun parseValue(): JsonVal? {
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

    fun parseNumber(): JsonVal? {
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

    fun parseArray(): JsonVal? {
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

    fun parseObject(): JsonVal? {
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

    val root = parseValue() ?: return null
    ws()
    return if (i == n) root else null
}

// ------------------------------------------------------------------
// Catalog model parsing — defensive: any malformed or incomplete
// input yields null (catalog) or drops the bad entry (packs/assets).
// ------------------------------------------------------------------

private val SHA256_RE = Regex("[0-9a-fA-F]{64}")

private fun JsonVal.Obj.str(key: String): String? =
    (map[key] as? JsonVal.Str)?.value?.takeIf { it.isNotBlank() }

private fun JsonVal.Obj.long(key: String): Long? =
    (map[key] as? JsonVal.Num)?.raw?.toLongOrNull()

private fun JsonVal.Obj.int(key: String): Int? =
    (map[key] as? JsonVal.Num)?.raw?.toIntOrNull()

private fun JsonVal.Obj.arr(key: String): List<JsonVal>? =
    (map[key] as? JsonVal.Arr)?.items

private fun JsonVal.Obj.obj(key: String): JsonVal.Obj? =
    map[key] as? JsonVal.Obj

internal fun parsePackAsset(o: JsonVal.Obj): PackAsset? {
    val slot = o.str("slot") ?: return null
    val platform = o.str("platform") ?: return null
    val path = o.str("path") ?: return null
    val sha256 = o.str("sha256")?.takeIf { SHA256_RE.matches(it) } ?: return null
    val bytes = o.long("bytes") ?: return null
    val width = o.int("width")
    val height = o.int("height")
    val required = (o.map["required"] as? JsonVal.Bool)?.value ?: false
    return PackAsset(
        slot = slot,
        platform = platform,
        path = path,
        sha256 = sha256,
        bytes = bytes,
        width = width,
        height = height,
        required = required,
    )
}

internal fun parsePackEntry(o: JsonVal.Obj): PackEntry? {
    val id = o.str("id") ?: return null
    val name = o.str("name") ?: return null
    val version = o.str("version") ?: return null
    val versionCode = o.int("versionCode") ?: return null
    val zipUrl = o.str("zipUrl") ?: return null
    val zipSha256 = o.str("zipSha256")?.takeIf { SHA256_RE.matches(it) } ?: return null
    val description = (o.map["description"] as? JsonVal.Str)?.value ?: ""
    val previewUrl = o.str("previewUrl")
    val previewSha256 =
        (o.map["previewSha256"] as? JsonVal.Str)?.value?.takeIf { SHA256_RE.matches(it) }
    val systems = o.arr("systems")
        ?.mapNotNull { (it as? JsonVal.Str)?.value?.takeIf { v -> v.isNotBlank() } }
        ?: emptyList()
    val iisuMinVersion = o.str("iisuMinVersion")
    val zipBytes = o.long("zipBytes")
    val assets = o.arr("assets")
        ?.mapNotNull { (it as? JsonVal.Obj)?.let(::parsePackAsset) }
        ?: emptyList()
    return PackEntry(
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        description = description,
        previewUrl = previewUrl,
        previewSha256 = previewSha256,
        systems = systems,
        iisuMinVersion = iisuMinVersion,
        zipUrl = zipUrl,
        zipSha256 = zipSha256,
        zipBytes = zipBytes,
        assets = assets,
    )
}

/**
 * Parses a pack catalog body. Returns null when the JSON is malformed
 * or the required top-level shape is missing — the caller treats that
 * as "catalog unavailable", never as an empty library. Individual bad
 * pack/asset entries are dropped; a present-but-empty packs array is
 * honest and yields an empty catalog.
 */
fun parsePackCatalog(json: String): PackCatalog? {
    val root = parseJson(json) as? JsonVal.Obj ?: return null
    val catalogVersion = root.int("catalogVersion") ?: return null
    val packsRaw = root.arr("packs") ?: return null
    val packs = packsRaw.mapNotNull { (it as? JsonVal.Obj)?.let(::parsePackEntry) }
    return PackCatalog(
        catalogVersion = catalogVersion,
        sourceRepo = root.str("sourceRepo"),
        sourceSha = root.str("sourceSha"),
        packs = packs,
    )
}

/** Lowercase hex SHA-256 of raw bytes. Pure JVM. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
}
