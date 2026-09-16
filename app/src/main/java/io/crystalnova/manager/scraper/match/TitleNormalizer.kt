package io.crystalnova.manager.scraper.match

import java.util.Locale

/**
 * Normalizes game titles for matching: strips extension, region/language
 * tags, revision markers; lowercases; collapses punctuation/whitespace.
 *
 * Lowercasing is pinned to [Locale.ROOT]: the default locale would give
 * different slugs on e.g. Turkish-locale devices ("I" -> "ı"), breaking
 * the byte-for-byte identity parity with the theme's JS resolver.
 *
 * ## Slug parity contract (Pegasus theme JS must mirror this exactly)
 *
 * ```
 * slugify(fileName):
 *   base     = fileName after the last '/' or '\\'
 *   base     = base minus its extension (text after the last '.', only if
 *              the '.' is not the first character)
 *   noTags   = base with every "(...)" / "[...]" group replaced by a space
 *   lower    = noTags.lowercase()            // Unicode default case map;
 *                                            // JS String.toLowerCase() matches
 *   ascii    = lower with '_' -> ' ', every char outside [a-z0-9 ] -> ' ',
 *              whitespace runs collapsed, trimmed
 *   slug     = ascii with every run of non-[a-z0-9] -> '-', trimmed of '-'
 *              (the trailing-article move in normalize() is inert —
 *              commas are folded to spaces before its regex runs — so the
 *              JS mirror must NOT implement it)
 *   if slug is empty:                        // pure-CJK / punctuation-only
 *     uni    = lower with '_' -> ' ', whitespace runs collapsed, trimmed
 *              (non-ASCII kept)
 *     if uni is empty: uni = base            // whitespace-only names,
 *                                         // e.g. " (E).gba" -> " (E)"
 *     slug   = "game-" + fnv1aHex(uni)
 *
 * fnv1aHex(s):
 *   h = 0x811c9dc5
 *   for each UTF-8 byte b of s:
 *     h = h xor b
 *     h = (h * 0x01000193) mod 2^32          // 32-bit wraparound
 *   return h as unsigned, 8 lowercase hex digits, zero-padded
 * ```
 *
 * JS mirror: `Math.imul(h, 0x01000193)` for the multiply step and
 * `(h >>> 0).toString(16).padStart(8, '0')` for the hex step give
 * bit-identical results to the Kotlin implementation below.
 */
object TitleNormalizer {
    private val tagPattern = Regex("""[\(\[][^)\]]*[\)\]]""")

    fun normalize(fileName: String): String {
        var s = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = s.lastIndexOf('.')
        if (dot > 0) s = s.substring(0, dot)
        s = tagPattern.replace(s, " ")
        s = s.lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Move trailing articles: "zelda, the" -> "the zelda"
        val article = Regex("^(.*),\\s*(the|a|an)$").find(s)
        if (article != null) s = "${article.groupValues[2]} ${article.groupValues[1]}"
        return s
    }

    /** URL/file-safe slug, e.g. "Mario Golf - Advance Tour (E).gba" -> "mario-golf-advance-tour". */
    fun slugify(fileName: String): String {
        val n = normalize(fileName)
        val slug = n.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isNotEmpty()) return slug
        // The ASCII fold erased everything (pure-CJK or punctuation-only
        // names). Fall back to a deterministic FNV-1a hash of the
        // Unicode-tolerant normalization so distinct games still get
        // distinct, stable, filesystem-safe ids instead of all
        // collapsing to "game". See the parity contract above.
        val unicode = unicodeNormalize(fileName).ifEmpty {
            // Whitespace-only after tag-stripping (e.g. " (E).gba"):
            // fall back to the extension-stripped basename, per contract.
            val base = fileName.substringAfterLast('/').substringAfterLast('\\')
            val dot = base.lastIndexOf('.')
            if (dot > 0) base.substring(0, dot) else base
        }
        return "game-" + fnv1aHex(unicode)
    }

    /**
     * Like [normalize] but keeps non-ASCII letters/digits: strip
     * extension, strip tags, ROOT-lowercase, collapse whitespace, trim.
     */
    fun unicodeNormalize(fileName: String): String {
        var s = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = s.lastIndexOf('.')
        if (dot > 0) s = s.substring(0, dot)
        s = tagPattern.replace(s, " ")
        return s.lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * FNV-1a 32-bit over the UTF-8 bytes of [s], as 8 lowercase hex
     * chars. Chosen over SHA-256 so the Pegasus theme's QML/JS can mirror
     * it bit-identically with Math.imul (see the parity contract above).
     */
    fun fnv1aHex(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193.toInt()
        }
        return h.toUInt().toString(16).padStart(8, '0')
    }
}
