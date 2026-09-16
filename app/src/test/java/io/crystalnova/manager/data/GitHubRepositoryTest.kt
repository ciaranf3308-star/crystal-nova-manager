package io.crystalnova.manager.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GitHubRepositoryTest {

    @Test
    fun `fetchSha parses the commit SHA`() {
        val http = FakeHttpClient(getHandler = {
            jsonResponse(200, """{"sha":"c58df4ca079cd4d8be28a45b38182a14a714dbfe"}""")
        })
        val repo = GitHubRepository(http)
        assertEquals("c58df4ca079cd4d8be28a45b38182a14a714dbfe", repo.fetchSha())
        assertTrue(http.requested.single().startsWith("https://api.github.com/repos/ciaranf3308-star/"))
    }

    @Test
    fun `fetchSha throws on non-200`() {
        val http = FakeHttpClient(getHandler = { jsonResponse(403, "rate limited") })
        assertThrows(IOException::class.java) {
            GitHubRepository(http).fetchSha()
        }
    }

    @Test
    fun `fetchSha throws on malformed body`() {
        val http = FakeHttpClient(getHandler = { jsonResponse(200, """{"nope":true}""") })
        assertThrows(IOException::class.java) {
            GitHubRepository(http).fetchSha()
        }
    }

    @Test
    fun `fetchVersionFile returns null on 404 instead of throwing`() {
        val http = FakeHttpClient(getHandler = { jsonResponse(404, "not found") })
        assertNull(GitHubRepository(http).fetchVersionFile())
    }

    @Test
    fun `fetchVersionFile returns null on malformed marker`() {
        val http = FakeHttpClient(getHandler = { jsonResponse(200, "garbage{{{") })
        assertNull(GitHubRepository(http).fetchVersionFile())
    }

    @Test
    fun `fetchLatest combines SHA and version`() {
        val http = FakeHttpClient(getHandler = { url ->
            if ("commits" in url) {
                jsonResponse(200, """{"sha":"abc1234def5678"}""")
            } else {
                jsonResponse(
                    200,
                    """{"version":"2.1.0","commit":"abc1234def5678","channel":"stable"}""",
                )
            }
        })
        val latest = GitHubRepository(http).fetchLatest()
        assertEquals("abc1234def5678", latest.sha)
        assertEquals("2.1.0", latest.version!!.version)
    }
}
