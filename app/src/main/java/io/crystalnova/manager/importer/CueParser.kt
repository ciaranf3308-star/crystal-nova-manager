package io.crystalnova.manager.importer

/**
 * Minimal CUE-sheet parser for the PS1 normalize pipeline.
 *
 * Only `FILE` directives matter: chdman does its own track parsing,
 * and Crystal only needs the referenced data filenames to validate
 * that every track exists before converting. Matching is
 * case-insensitive on the directive; filenames may be quoted or
 * bare. Everything else in the sheet is ignored.
 *
 * Pure JVM — unit-tested without Android.
 */
object CueParser {

    private val fileLine = Regex(
        """(?i)^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\S+""",
    )

    /**
     * Filenames referenced by FILE directives, in sheet order.
     * Returns an empty list when the sheet has no FILE lines.
     */
    fun referencedFiles(cueText: String): List<String> =
        cueText.lineSequence()
            .mapNotNull { line ->
                val m = fileLine.find(line) ?: return@mapNotNull null
                (m.groups[1]?.value ?: m.groups[2]?.value)?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .toList()
}
