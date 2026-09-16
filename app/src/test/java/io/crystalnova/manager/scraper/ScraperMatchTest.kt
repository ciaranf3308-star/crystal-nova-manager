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
}
