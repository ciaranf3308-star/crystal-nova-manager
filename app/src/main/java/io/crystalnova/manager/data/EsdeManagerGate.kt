package io.crystalnova.manager.data

/**
 * Gate logic for the ES-DE theme catalog's minimum-manager
 * requirement. Pure JVM — no Android APIs — so it can be
 * unit-tested in isolation.
 *
 * The catalog's `minManagerVersion` is a human-readable versionName
 * (e.g. "1.2.4-u48-esdeupdate"); `minManagerVersionCode` is the
 * machine-readable versionCode the manager really compares. The
 * name comparison is build-tag aware: manager version names carry a
 * `u<N>` build tag ("1.2.4-u50-stripped" → 50), so u50 satisfies a
 * u48 minimum even though the names differ as strings.
 */

private val BUILD_TAG_RE = Regex("""[Uu](\d+)\b""")

/**
 * Extracts the trailing `u<N>` build number from a manager
 * versionName, e.g. "1.2.4-u50-stripped" → 50. Null when the name
 * carries no such tag.
 */
fun managerBuildTag(versionName: String): Int? =
    BUILD_TAG_RE.find(versionName)?.groupValues?.get(1)?.toIntOrNull()

/**
 * True when the current manager is BELOW the catalog's stated
 * minimum — i.e. the "update the manager first" notice applies.
 *
 * - versionCode wins when present: `currentVersionCode <
 *   minVersionCode`.
 * - Otherwise build tags decide: both parse → `curTag < minTag`.
 * - Unparseable names fall back to exact-name inequality (an
 *   unknown name is not assumed to satisfy the requirement).
 */
fun managerBelowMinimum(
    minVersionName: String?,
    minVersionCode: Int?,
    currentVersionName: String,
    currentVersionCode: Int,
): Boolean {
    if (minVersionCode != null) return currentVersionCode < minVersionCode
    if (minVersionName == null) return false
    val minTag = managerBuildTag(minVersionName)
    val curTag = managerBuildTag(currentVersionName)
    return if (minTag != null && curTag != null) {
        curTag < minTag
    } else {
        minVersionName != currentVersionName
    }
}
