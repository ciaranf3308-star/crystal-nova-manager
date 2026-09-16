package io.crystalnova.manager.scraper.match

/**
 * Normalizes game titles for matching: strips extension, region/language
 * tags, revision markers; lowercases; collapses punctuation/whitespace.
 */
object TitleNormalizer {
    private val tagPattern = Regex("""[\(\[][^)\]]*[\)\]]""")

    fun normalize(fileName: String): String {
        var s = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = s.lastIndexOf('.')
        if (dot > 0) s = s.substring(0, dot)
        s = tagPattern.replace(s, " ")
        s = s.lowercase()
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
        return n.replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "game" }
    }
}
