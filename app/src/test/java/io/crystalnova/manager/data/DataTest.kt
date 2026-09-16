package io.crystalnova.manager.data

import org.junit.Assert.*
import org.junit.Test

class VersionInfoTest {

    @Test
    fun `parses a valid marker`() {
        val v = VersionInfo.parse(
            """{"version":"2.0.0","commit":"8a86a06bdd5f1845f8b02bf66fa65e769e733fac","channel":"stable"}""",
        )
        assertNotNull(v)
        assertEquals("2.0.0", v!!.version)
        assertEquals("8a86a06bdd5f1845f8b02bf66fa65e769e733fac", v.commit)
        assertEquals("stable", v.channel)
        assertEquals("8a86a06", v.shortCommit)
    }

    @Test
    fun `malformed JSON returns null`() {
        assertNull(VersionInfo.parse("{not json"))
        assertNull(VersionInfo.parse(""))
        assertNull(VersionInfo.parse("null"))
    }

    @Test
    fun `missing required fields return null`() {
        assertNull(VersionInfo.parse("""{"version":"2.0.0"}"""))
        assertNull(VersionInfo.parse("""{"commit":"abc1234"}"""))
        assertNull(VersionInfo.parse("""{"version":"","commit":"abc1234"}"""))
    }

    @Test
    fun `missing channel defaults to stable`() {
        val v = VersionInfo.parse("""{"version":"2.0.0","commit":"abc1234"}""")
        assertEquals("stable", v!!.channel)
    }

    @Test
    fun `compareVersions orders semantically`() {
        assertTrue(compareVersions("2.1.0", "2.0.0") > 0)
        assertTrue(compareVersions("2.0.0", "2.1.0") < 0)
        assertEquals(0, compareVersions("2.0.0", "2.0.0"))
        assertTrue(compareVersions("2.0.10", "2.0.9") > 0)
        assertTrue(compareVersions("10.0.0", "9.9.9") > 0)
    }
}

class GitHubEndpointsTest {

    @Test
    fun `official endpoints are allowed`() {
        GitHubEndpoints.checkAllowed(GitHubEndpoints.commitApi("main"))
        GitHubEndpoints.checkAllowed(GitHubEndpoints.versionFile("main"))
        GitHubEndpoints.checkAllowed(GitHubEndpoints.zipball("main"))
    }

    @Test
    fun `github cdn host is rejected - codeload serves the zip directly`() {
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed(
                "https://objects.githubusercontent.com/github-production-release-asset/abc123?token=xyz",
            )
        }
    }

    @Test
    fun `evil hosts are rejected`() {
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed("https://evil.com/ciaranf3308-star/crystal-nova-pegasus-theme/zip")
        }
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed("https://api.github.com.evil.com/repos/x/y")
        }
    }

    @Test
    fun `non-https is rejected`() {
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed("http://api.github.com/repos/ciaranf3308-star/crystal-nova-pegasus-theme/commits/main")
        }
    }

    @Test
    fun `paths escaping the repo are rejected`() {
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed("https://api.github.com/repos/someone-else/other-repo/commits/main")
        }
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed("https://raw.githubusercontent.com/ciaranf3308-star/evil-repo/main/payload")
        }
    }

    @Test
    fun `manager release endpoints are allowed`() {
        GitHubEndpoints.checkAllowed(GitHubEndpoints.managerLatestReleaseApi())
        GitHubEndpoints.checkAllowed(
            "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/v1.0.2-u1/crystal-nova-manager-u1.2.apk",
        )
    }

    @Test
    fun `other owners manager repos are rejected`() {
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed(
                "https://api.github.com/repos/someone-else/crystal-nova-manager/releases/latest",
            )
        }
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed(
                "https://raw.githubusercontent.com/ciaranf3308-star/crystal-nova-manager/main/payload",
            )
        }
    }

    @Test
    fun `redirect Location headers are re-checked, not trusted`() {
        // A redirect target is validated by the client on every hop; a
        // codeload Location pointing off-allowlist must throw.
        assertThrows(SecurityException::class.java) {
            GitHubEndpoints.checkAllowed("https://malicious-cdn.example/file.zip")
        }
    }
}
