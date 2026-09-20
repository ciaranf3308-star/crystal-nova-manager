package io.crystalnova.manager.data

/**
 * The remote ES-DE "crystal" theme catalog — the source of truth for
 * the u48 ES-DE theme updater.
 *
 * The catalog is published by the theme pipeline to the
 * `crystal-esde-theme` repo's rolling `stable` release — not by the
 * manager CI. Until it is reachable the theme screen reports that
 * honestly instead of inventing a theme. The URL is hardcoded: the
 * catalog may not exist yet while the app is in development, and the
 * app only needs it at runtime.
 *
 * Catalog schema (all keys camelCase):
 * - id: String ("crystal")
 * - version: String, e.g. "1.0.0"
 * - versionCode: Int
 * - zipUrl: String
 * - zipSha256: String (64 hex chars)
 * - zipBytes: Int?
 * - minManagerVersion: String?, e.g. "1.2.4-u48-esdeupdate"
 * - history: [ { version, versionCode, zipUrl, zipSha256, zipBytes } ]
 *   (latest previous first — history[0] is the rollback target)
 *
 * Pure JVM: reuses the minimal parser from PackCatalog.kt (no org.json,
 * no Android APIs).
 */
const val ESDE_THEME_CATALOG_URL =
    "https://github.com/ciaranf3308-star/crystal-esde-theme/releases/download/stable/catalog.json"

/**
 * One theme release: the catalog's current release or one history
 * (rollback) entry. Same shape for both — the install flow treats
 * them identically.
 */
data class EsdeThemeEntry(
    val version: String,
    val versionCode: Int,
    val zipUrl: String,
    val zipSha256: String,
    val zipBytes: Long?,
)

data class EsdeThemeCatalog(
    val id: String,
    val version: String,
    val versionCode: Int,
    val zipUrl: String,
    val zipSha256: String,
    val zipBytes: Long?,
    val minManagerVersion: String?,
    val history: List<EsdeThemeEntry>,
) {
    /** The current release as an installable entry. */
    fun current(): EsdeThemeEntry =
        EsdeThemeEntry(
            version = version,
            versionCode = versionCode,
            zipUrl = zipUrl,
            zipSha256 = zipSha256,
            zipBytes = zipBytes,
        )

    /**
     * The rollback target: history[0], the latest previous release.
     * Null when the catalog ships no history.
     */
    fun rollbackTarget(): EsdeThemeEntry? = history.firstOrNull()
}

// ------------------------------------------------------------------
// Parsing — defensive, mirroring PackCatalog.kt: malformed JSON or a
// missing top-level shape yields null; individual bad history entries
// are dropped.
// ------------------------------------------------------------------

private val ESDE_SHA256_RE = Regex("[0-9a-fA-F]{64}")

private fun JsonVal.Obj.esdeStr(key: String): String? =
    (map[key] as? JsonVal.Str)?.value?.takeIf { it.isNotBlank() }

private fun JsonVal.Obj.esdeLong(key: String): Long? =
    (map[key] as? JsonVal.Num)?.raw?.toLongOrNull()

private fun JsonVal.Obj.esdeInt(key: String): Int? =
    (map[key] as? JsonVal.Num)?.raw?.toIntOrNull()

private fun JsonVal.Obj.esdeArr(key: String): List<JsonVal>? =
    (map[key] as? JsonVal.Arr)?.items

internal fun parseEsdeThemeEntry(o: JsonVal.Obj): EsdeThemeEntry? {
    val version = o.esdeStr("version") ?: return null
    val versionCode = o.esdeInt("versionCode") ?: return null
    val zipUrl = o.esdeStr("zipUrl") ?: return null
    val zipSha256 = o.esdeStr("zipSha256")
        ?.takeIf { ESDE_SHA256_RE.matches(it) } ?: return null
    val zipBytes = o.esdeLong("zipBytes")
    return EsdeThemeEntry(
        version = version,
        versionCode = versionCode,
        zipUrl = zipUrl,
        zipSha256 = zipSha256,
        zipBytes = zipBytes,
    )
}

/**
 * Parses an ES-DE theme catalog body. Returns null when the JSON is
 * malformed or the required top-level shape is missing — the caller
 * treats that as "catalog unavailable", never as an empty theme.
 */
fun parseEsdeThemeCatalog(json: String): EsdeThemeCatalog? {
    val root = parseJson(json) as? JsonVal.Obj ?: return null
    val id = root.esdeStr("id") ?: return null
    val version = root.esdeStr("version") ?: return null
    val versionCode = root.esdeInt("versionCode") ?: return null
    val zipUrl = root.esdeStr("zipUrl") ?: return null
    val zipSha256 = root.esdeStr("zipSha256")
        ?.takeIf { ESDE_SHA256_RE.matches(it) } ?: return null
    val history = root.esdeArr("history")
        ?.mapNotNull { (it as? JsonVal.Obj)?.let(::parseEsdeThemeEntry) }
        ?: emptyList()
    return EsdeThemeCatalog(
        id = id,
        version = version,
        versionCode = versionCode,
        zipUrl = zipUrl,
        zipSha256 = zipSha256,
        zipBytes = root.esdeLong("zipBytes"),
        minManagerVersion = root.esdeStr("minManagerVersion"),
        history = history,
    )
}
