package io.crystalnova.manager.scraper.match

import io.crystalnova.manager.scraper.model.MatchConfidence

/**
 * Title matching with the 5-level priority:
 * 1. previously stored provider game ID (handled by the caller via index)
 * 2. ROM hash (on-demand only; future structured providers)
 * 3. exact normalized title + platform
 * 4. fuzzy title + platform (>= [FUZZY_THRESHOLD])
 * 5. manual match required
 *
 * Weak fuzzy matches are recorded as candidates but NEVER auto-accepted.
 */
object GameMatcher {
    /** Normalized similarity at or above this auto-accepts a fuzzy match. */
    const val FUZZY_THRESHOLD = 0.85

    fun exactMatch(a: String, b: String): Boolean =
        TitleNormalizer.normalize(a) == TitleNormalizer.normalize(b)

    /**
     * Jaro-Winkler similarity in [0,1] on normalized titles. Favors
     * shared prefixes, which suits game titles well.
     */
    fun similarity(a: String, b: String): Double {
        val s1 = TitleNormalizer.normalize(a)
        val s2 = TitleNormalizer.normalize(b)
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        return jaroWinkler(s1, s2)
    }

    fun matchConfidence(query: String, candidate: String): MatchConfidence {
        if (exactMatch(query, candidate)) return MatchConfidence.ExactTitle
        val score = similarity(query, candidate)
        return if (score >= FUZZY_THRESHOLD) MatchConfidence.Fuzzy(score)
        else MatchConfidence.None
    }

    private fun jaroWinkler(s1: String, s2: String): Double {
        val jaro = jaro(s1, s2)
        var prefix = 0
        val maxPrefix = minOf(4, minOf(s1.length, s2.length))
        while (prefix < maxPrefix && s1[prefix] == s2[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    private fun jaro(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val matchDist = maxOf(s1.length, s2.length) / 2 - 1
        val s1m = BooleanArray(s1.length)
        val s2m = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
            val start = maxOf(0, i - matchDist)
            val end = minOf(i + matchDist + 1, s2.length)
            for (j in start until end) {
                if (!s2m[j] && s1[i] == s2[j]) {
                    s1m[i] = true; s2m[j] = true; matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (s1m[i]) {
                while (!s2m[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }
        val m = matches.toDouble()
        return (m / s1.length + m / s2.length + (m - transpositions / 2.0) / m) / 3.0
    }
}
