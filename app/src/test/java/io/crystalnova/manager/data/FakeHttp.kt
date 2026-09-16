package io.crystalnova.manager.data

import java.io.File
import java.io.IOException

/** Shared fake for repository/manager tests. */
class FakeHttpClient(
    val getHandler: (String) -> HttpResponse = { throw IOException("not stubbed") },
    val downloadHandler: (String, File) -> Unit = { _, _ -> throw IOException("not stubbed") },
) : HttpClient {
    val requested = mutableListOf<String>()

    override fun get(url: String): HttpResponse {
        requested += url
        return getHandler(url)
    }

    override fun download(url: String, dest: File, onProgress: (Long, Long?) -> Unit) {
        requested += url
        downloadHandler(url, dest)
        onProgress(dest.length(), dest.length())
    }
}

fun jsonResponse(code: Int, body: String) =
    HttpResponse(code, body.toByteArray(), emptyMap())
