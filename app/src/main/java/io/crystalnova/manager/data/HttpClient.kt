package io.crystalnova.manager.data

import java.io.File
import java.io.IOException

/** Minimal HTTP surface the updater needs. Abstracted so tests can fake the network. */
interface HttpClient {
    @Throws(IOException::class)
    fun get(url: String): HttpResponse

    @Throws(IOException::class)
    fun download(url: String, dest: File, onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit)
}

data class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>>,
) {
    fun bodyText(): String = body.toString(Charsets.UTF_8)
}
