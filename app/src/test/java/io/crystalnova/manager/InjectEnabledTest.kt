package io.crystalnova.manager

import io.crystalnova.manager.ui.PegasusSystemRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pegasus Setup screen used to disable INJECT whenever ANY
 * populated system lacked a launcher. The backend supports partial
 * injection (unconfigured systems are skipped and reported), so
 * INJECT must stay enabled while at least one populated system has a
 * configured launcher.
 */
class InjectEnabledTest {

    private fun row(slug: String, games: Int, status: String) =
        PegasusSystemRow(
            slug = slug,
            label = slug.uppercase(),
            gameCount = games,
            launcherStatus = status,
            launcherInstalled = true,
            isDefault = false,
        )

    private fun configured(slug: String, games: Int) =
        row(slug, games, "RetroArch · mGBA")

    private fun unconfigured(slug: String, games: Int) =
        row(slug, games, "NOT CONFIGURED")

    @Test fun `mixed configured and unconfigured leaves INJECT enabled`() {
        val rows = listOf(configured("gba", 12), unconfigured("n64", 5))
        assertTrue(isInjectEnabled(configReady = true, busy = false, rows = rows))
    }

    @Test fun `all populated systems unconfigured leaves INJECT disabled`() {
        val rows = listOf(unconfigured("gba", 12), unconfigured("n64", 5))
        assertFalse(isInjectEnabled(configReady = true, busy = false, rows = rows))
    }

    @Test fun `no populated systems leaves INJECT disabled`() {
        val rows = listOf(configured("gba", 0), unconfigured("n64", 0))
        assertFalse(isInjectEnabled(configReady = true, busy = false, rows = rows))
    }

    @Test fun `empty rows leave INJECT disabled`() {
        assertFalse(isInjectEnabled(configReady = true, busy = false, rows = emptyList()))
    }

    @Test fun `config not ready disables INJECT`() {
        val rows = listOf(configured("gba", 12))
        assertFalse(isInjectEnabled(configReady = false, busy = false, rows = rows))
    }

    @Test fun `busy disables INJECT`() {
        val rows = listOf(configured("gba", 12))
        assertFalse(isInjectEnabled(configReady = true, busy = true, rows = rows))
    }
}
