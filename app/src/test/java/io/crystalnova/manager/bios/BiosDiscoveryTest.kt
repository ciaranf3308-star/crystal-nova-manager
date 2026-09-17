package io.crystalnova.manager.bios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The preferred BIOS root is the `bios/` sibling of the persisted ROM
 * root on the same volume — derived purely from the ROM tree URI
 * string, no Android calls.
 */
class BiosDiscoveryTest {

    private val authority = "content://com.android.externalstorage.documents"

    @Test
    fun candidate_fromRemovableRomRoot() {
        val rom = "$authority/tree/1234-ABCD%3Aroms"
        assertEquals(
            "$authority/tree/1234-ABCD%3Abios",
            BiosDiscovery.candidateBiosTreeUri(rom),
        )
    }

    @Test
    fun candidate_fromPrimaryRomRoot() {
        val rom = "$authority/tree/primary%3Aroms"
        assertEquals(
            "$authority/tree/primary%3Abios",
            BiosDiscovery.candidateBiosTreeUri(rom),
        )
    }

    @Test
    fun candidate_ignoresNestedRomPath() {
        // ROM root nested deeper still yields the volume-level bios root.
        val rom = "$authority/tree/1234-ABCD%3AEmulation%2Froms"
        assertEquals(
            "$authority/tree/1234-ABCD%3Abios",
            BiosDiscovery.candidateBiosTreeUri(rom),
        )
    }

    @Test
    fun candidate_nullWhenNotTreeUri() {
        assertNull(BiosDiscovery.candidateBiosTreeUri("content://other/provider/doc/1"))
        assertNull(BiosDiscovery.candidateBiosTreeUri(""))
        assertNull(BiosDiscovery.candidateBiosTreeUri("$authority/tree/"))
    }

    @Test
    fun displayPath_removableVolume() {
        assertEquals(
            "SD CARD /bios",
            BiosDiscovery.preferredBiosDisplayPath("$authority/tree/1234-ABCD%3Aroms"),
        )
    }

    @Test
    fun displayPath_primaryVolume() {
        assertEquals(
            "INTERNAL STORAGE /bios",
            BiosDiscovery.preferredBiosDisplayPath("$authority/tree/primary%3Aroms"),
        )
    }

    @Test
    fun displayPath_nullWhenUnparseable() {
        assertNull(BiosDiscovery.preferredBiosDisplayPath("not-a-uri"))
    }
}
