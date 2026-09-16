package io.crystalnova.manager.updater

import io.crystalnova.manager.data.VersionInfo
import org.junit.Assert.*
import org.junit.Test

class UpdateDeciderTest {

    private fun v(version: String, commit: String) =
        VersionInfo(version = version, commit = commit, channel = "stable")

    @Test
    fun `local equals remote is up to date`() {
        val d = UpdateDecider.decide(
            installed = v("2.0.0", "8a86a06bdd5f1845f8b02bf66fa65e769e733fac"),
            installedSha = "8a86a06bdd5f1845f8b02bf66fa65e769e733fac",
            remoteSha = "8a86a06bdd5f1845f8b02bf66fa65e769e733fac",
            remoteVersion = v("2.0.0", "8a86a06bdd5f1845f8b02bf66fa65e769e733fac"),
        )
        assertEquals(UpdateDecider.Decision.UpToDate, d)
    }

    @Test
    fun `remote newer version is update available`() {
        val d = UpdateDecider.decide(
            installed = v("2.0.0", "8a86a06"),
            installedSha = "8a86a06",
            remoteSha = "abc1234def",
            remoteVersion = v("2.1.0", "abc1234def"),
        )
        assertEquals(UpdateDecider.Decision.UpdateAvailable, d)
    }

    @Test
    fun `remote newer commit with same version is update available`() {
        val d = UpdateDecider.decide(
            installed = v("2.0.0", "8a86a06"),
            installedSha = "8a86a06",
            remoteSha = "c58df4c",
            remoteVersion = v("2.0.0", "c58df4c"),
        )
        assertEquals(UpdateDecider.Decision.UpdateAvailable, d)
    }

    @Test
    fun `unreachable remote is unknown, never destructive`() {
        val d = UpdateDecider.decide(
            installed = v("2.0.0", "8a86a06"),
            installedSha = "8a86a06",
            remoteSha = null,
            remoteVersion = null,
        )
        assertTrue(d is UpdateDecider.Decision.Unknown)
    }

    @Test
    fun `malformed remote version falls back to SHA comparison`() {
        // Remote marker missing/malformed: SHAs equal → up to date.
        val upToDate = UpdateDecider.decide(
            installed = v("2.0.0", "8a86a06"),
            installedSha = null,
            remoteSha = "8a86a06",
            remoteVersion = null,
        )
        assertEquals(UpdateDecider.Decision.UpToDate, upToDate)
        // …SHAs differ → update available.
        val available = UpdateDecider.decide(
            installed = v("2.0.0", "8a86a06"),
            installedSha = null,
            remoteSha = "deadbee",
            remoteVersion = null,
        )
        assertEquals(UpdateDecider.Decision.UpdateAvailable, available)
    }

    @Test
    fun `unknown installed theme offers an update`() {
        val d = UpdateDecider.decide(
            installed = null,
            installedSha = null,
            remoteSha = "abc1234",
            remoteVersion = v("2.0.0", "abc1234"),
        )
        assertEquals(UpdateDecider.Decision.UpdateAvailable, d)
    }

    @Test
    fun `short SHA prefixes match full SHAs`() {
        val d = UpdateDecider.decide(
            installed = v("2.0.0", "8a86a06"),
            installedSha = "8a86a06",
            remoteSha = "8a86a06bdd5f1845f8b02bf66fa65e769e733fac",
            remoteVersion = v("2.0.0", "8a86a06"),
        )
        assertEquals(UpdateDecider.Decision.UpToDate, d)
    }
}
