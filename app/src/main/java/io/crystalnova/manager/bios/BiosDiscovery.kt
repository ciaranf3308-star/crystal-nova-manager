package io.crystalnova.manager.bios

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure BIOS-root discovery helpers (no Android calls — JVM-testable).
 *
 * The canonical appliance layout is `<SD CARD ROOT>/roms` +
 * `<SD CARD ROOT>/bios`, no `/Emulation/` wrapper. The ROM root is a
 * persisted SAF tree URI such as
 * `content://com.android.externalstorage.documents/tree/1234-ABCD%3Aroms`;
 * the preferred BIOS root is the sibling `tree/1234-ABCD:bios` on the
 * same volume. Crystal never had a grant for that tree, so the
 * candidate URI is a *probe*: [BiosInventory] checks it for a readable
 * grant and otherwise offers it as the SAF picker's initial location.
 */
object BiosDiscovery {

    /**
     * Candidate SAF tree URI for the BIOS folder sibling of [romTreeUri],
     * e.g. `…/tree/1234-ABCD%3Aroms` →
     * `content://com.android.externalstorage.documents/tree/1234-ABCD%3Abios`.
     * Null when [romTreeUri] is not a parseable SAF tree URI.
     */
    fun candidateBiosTreeUri(romTreeUri: String): String? {
        val marker = "/tree/"
        val idx = romTreeUri.indexOf(marker)
        if (idx < 0) return null
        val authority = romTreeUri.substringBefore(marker)
        val encoded = romTreeUri.substring(idx + marker.length).substringBefore('/')
        if (encoded.isEmpty() || authority.isEmpty()) return null
        val docId = try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            return null
        }
        val volume = docId.substringBefore(':')
        if (volume.isEmpty()) return null
        val biosDocId = URLEncoder.encode("$volume:bios", "UTF-8")
        return "$authority$marker$biosDocId"
    }

    /**
     * Friendly display path for the preferred BIOS root derived from
     * [romTreeUri], e.g. `SD CARD /bios` or `INTERNAL STORAGE /bios`.
     * Never a raw content:// URI. Null when unparseable.
     */
    fun preferredBiosDisplayPath(romTreeUri: String): String? {
        val marker = "/tree/"
        val idx = romTreeUri.indexOf(marker)
        if (idx < 0) return null
        val encoded = romTreeUri.substring(idx + marker.length).substringBefore('/')
        if (encoded.isEmpty()) return null
        val docId = try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            return null
        }
        val volume = docId.substringBefore(':')
        if (volume.isEmpty()) return null
        return if (volume == "primary") "INTERNAL STORAGE /bios" else "SD CARD /bios"
    }
}
