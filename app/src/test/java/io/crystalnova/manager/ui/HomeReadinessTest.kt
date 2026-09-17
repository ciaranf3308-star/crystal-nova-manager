package io.crystalnova.manager.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v23: HOME's READY state is derived from real persisted production
 * state only (scanned library, stored launcher profiles, Pegasus
 * install check) — it must never be invented. Artwork completeness
 * never blocks READY: this gate deliberately does not consult any
 * artwork state.
 */
class HomeReadinessTest {

    private fun readiness(
        systemCount: Int = 13,
        totalGames: Int = 147,
        configuredCount: Int = 13,
        issueCount: Int = 0,
        pegasusInstalled: Boolean = true,
        romReady: Boolean = true,
    ) = HomeReadiness(
        systemCount = systemCount,
        totalGames = totalGames,
        configuredCount = configuredCount,
        issueCount = issueCount,
        pegasusInstalled = pegasusInstalled,
        romReady = romReady,
    )

    @Test
    fun ready_whenLibraryScannedPegasusInstalledAndAllLaunchersConfigured() {
        assertTrue(readiness().ready)
    }

    @Test
    fun notReady_whenOneLauncherNeedsAttention() {
        assertFalse(readiness(issueCount = 1, configuredCount = 12).ready)
    }

    @Test
    fun notReady_whenPegasusNotInstalled() {
        assertFalse(readiness(pegasusInstalled = false).ready)
    }

    @Test
    fun notReady_whenNoRomFolder() {
        assertFalse(readiness(romReady = false).ready)
    }

    @Test
    fun notReady_whenNoGamesScanned() {
        assertFalse(readiness(systemCount = 0, totalGames = 0, configuredCount = 0).ready)
    }
}
