package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.match.GameMatcher
import io.crystalnova.manager.scraper.match.TitleNormalizer
import io.crystalnova.manager.scraper.model.MatchConfidence
import io.crystalnova.manager.scraper.scan.HashService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ScraperMatchTest {

    @Test fun `exact match ignores case, tags and extension`() {
        assertTrue(GameMatcher.exactMatch("Super Mario World (USA).smc", "super mario world"))
        assertTrue(GameMatcher.exactMatch("Mario Golf - Advance Tour (E).gba", "Mario Golf Advance Tour"))
    }

    @Test fun `exact match rejects different titles`() {
        assertFalse(GameMatcher.exactMatch("Metroid Prime.iso", "Super Metroid"))
    }

    @Test fun `normalizer strips region tags and file cruft`() {
        val n = TitleNormalizer.normalize("TOCA Race Driver 3 (Europe) (En,Fr,De).iso")
        assertEquals("toca race driver 3", n)
    }

    @Test fun `close titles score at or above the fuzzy threshold`() {
        val c = GameMatcher.matchConfidence("Mario Golf: Advance Tour", "Mario Golf Advance Tour")
        assertTrue(c is MatchConfidence.ExactTitle || c is MatchConfidence.Fuzzy)
        if (c is MatchConfidence.Fuzzy) assertTrue(c.score >= GameMatcher.FUZZY_THRESHOLD)
    }

    @Test fun `weak fuzzy match is never silently accepted`() {
        // Deliberately unrelated titles: must come back as None, never Fuzzy.
        val c = GameMatcher.matchConfidence("The Legend of Zelda", "Metroid Prime Hunters")
        assertTrue("expected None, got $c", c is MatchConfidence.None)
    }

    @Test fun `similarity is symmetric and bounded`() {
        val a = GameMatcher.similarity("Final Fantasy VII", "Final Fantasy 7")
        val b = GameMatcher.similarity("Final Fantasy 7", "Final Fantasy VII")
        assertEquals(a, b, 1e-9)
        assertTrue(a in 0.0..1.0)
    }

    @Test fun `sha256 matches the known test vector`() = runBlocking {
        val hex = HashService.sha256Hex(ByteArrayInputStream("abc".toByteArray()))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
        )
    }

    @Test fun `crc32 matches the known test vector`() = runBlocking {
        val hex = HashService.crc32Hex(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("352441c2", hex)
    }

    @Test fun `slugify produces filesystem-safe ids`() {
        assertEquals("mario-golf-advance-tour", TitleNormalizer.slugify("Mario Golf: Advance Tour (E).gba"))
    }

    @Test fun `slugify is deterministic and filesystem-safe across unicode inputs`() {
        val cases = mapOf(
            "Pokémon Emerald (U).gba" to "pok-mon-emerald",
            "ポケモン Pokémon Red (J).gb" to "pok-mon-red",
            // Pure-CJK: the ASCII fold erases everything, so the slug is a
            // deterministic "game-<fnv1a>" fallback, distinct per title.
            "ドラゴンクエスト.gba" to "game-" + TitleNormalizer.fnv1aHex("ドラゴンクエスト"),
            "ファイナルファンタジー.gba" to "game-" + TitleNormalizer.fnv1aHex("ファイナルファンタジー"),
            // Punctuation-only: same fallback shape, distinct per name.
            "!!!.gba" to "game-" + TitleNormalizer.fnv1aHex("!!!"),
            "???.gba" to "game-" + TitleNormalizer.fnv1aHex("???"),
            // Whitespace-only after tag-stripping: the fallback hashes the
            // extension-stripped basename (" (E)"), per the parity contract.
            " (E).gba" to "game-" + TitleNormalizer.fnv1aHex(" (E)"),
        )
        for ((fileName, expected) in cases) {
            val slug = TitleNormalizer.slugify(fileName)
            assertEquals(expected, slug)
            // Deterministic across calls.
            assertEquals(slug, TitleNormalizer.slugify(fileName))
            // Filesystem-safe charset.
            assertTrue("$slug is not url/file safe", slug.matches(Regex("[a-z0-9-]+")))
        }
        // Distinct slugs for distinct games — no silent "game" collapse.
        assertEquals(cases.size, cases.values.toSet().size)
    }

    @Test fun `fnv1a matches the known test vector`() {
        // FNV-1a 32-bit of "foobar" is the canonical check value; the
        // Pegasus theme's JS mirror must produce this too.
        assertEquals("bf9cf968", TitleNormalizer.fnv1aHex("foobar"))
        assertEquals("811c9dc5", TitleNormalizer.fnv1aHex(""))
    }

    @Test fun `slugify is independent of the default locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            // Turkish: "I".lowercase() -> "ı" with the default locale,
            // which would break byte-parity with the theme's JS resolver.
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertEquals("i", TitleNormalizer.normalize("I.gba"))
            assertEquals("pok-mon-emerald", TitleNormalizer.slugify("Pokémon Emerald (U).gba"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
