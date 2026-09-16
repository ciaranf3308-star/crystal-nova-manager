package io.crystalnova.manager.scraper.generate

/** Pure text-wrapping; measurement is injected so this stays JVM-testable. */
object TextWrap {
    /**
     * Wraps [text] into lines fitting [maxWidth] using [measure]. Long
     * words are hard-broken. Returns at most [maxLines] lines.
     */
    fun wrap(
        text: String,
        maxWidth: Float,
        maxLines: Int = Int.MAX_VALUE,
        measure: (String) -> Float,
    ): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    if (lines.size >= maxLines) return lines
                    current = StringBuilder()
                }
                // Hard-break overlong words.
                var rest = word
                while (measure(rest) > maxWidth && rest.length > 1) {
                    var cut = rest.length - 1
                    while (cut > 1 && measure(rest.substring(0, cut)) > maxWidth) cut--
                    lines += rest.substring(0, cut)
                    if (lines.size >= maxLines) return lines
                    rest = rest.substring(cut)
                }
                current = StringBuilder(rest)
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines += current.toString()
        return lines
    }
}
