package io.crystalnova.manager.pegasus

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure config-root validator. No Android framework:
 * the validator takes the document-id string, not a Uri.
 */
class PegasusConfigRootsTest {

    @Test
    fun legacyRoot_internalStorage_isValid() {
        assertEquals(
            ConfigValidity.VALID,
            PegasusConfigRoots.validateDocumentId("primary:pegasus-frontend"),
        )
    }

    @Test
    fun legacyRoot_sdCard_isValid() {
        assertEquals(
            ConfigValidity.VALID,
            PegasusConfigRoots.validateDocumentId("1234-ABCD:pegasus-frontend"),
        )
    }

    @Test
    fun appSpecificRoot_isValid() {
        assertEquals(
            ConfigValidity.VALID,
            PegasusConfigRoots.validateDocumentId(
                "primary:Android/data/org.pegasus_frontend.android/files/pegasus-frontend",
            ),
        )
    }

    @Test
    fun nestedUnderEmulation_isWrongFolder() {
        // The real Nova bug: the last segment matches, but this is a
        // folder Pegasus never reads.
        assertEquals(
            ConfigValidity.WRONG_FOLDER,
            PegasusConfigRoots.validateDocumentId("primary:Emulation/pegasus-frontend"),
        )
    }

    @Test
    fun randomFolder_isWrongFolder() {
        assertEquals(
            ConfigValidity.WRONG_FOLDER,
            PegasusConfigRoots.validateDocumentId("primary:Download"),
        )
    }

    @Test
    fun noColon_isWrongFolder() {
        assertEquals(
            ConfigValidity.WRONG_FOLDER,
            PegasusConfigRoots.validateDocumentId("pegasus-frontend"),
        )
    }

    @Test
    fun emptyPath_isWrongFolder() {
        assertEquals(
            ConfigValidity.WRONG_FOLDER,
            PegasusConfigRoots.validateDocumentId("primary:"),
        )
    }

    @Test
    fun appRoot_typoInPackage_isWrongFolder() {
        assertEquals(
            ConfigValidity.WRONG_FOLDER,
            PegasusConfigRoots.validateDocumentId(
                "primary:Android/data/org.pegasus_frontend.android.evil/files/pegasus-frontend",
            ),
        )
    }

    @Test
    fun trailingSlash_isValid() {
        assertEquals(
            ConfigValidity.VALID,
            PegasusConfigRoots.validateDocumentId("primary:pegasus-frontend/"),
        )
    }

    @Test
    fun deepNestingUnderAppRoot_isWrongFolder() {
        assertEquals(
            ConfigValidity.WRONG_FOLDER,
            PegasusConfigRoots.validateDocumentId(
                "primary:Android/data/org.pegasus_frontend.android/files/pegasus-frontend/metafiles",
            ),
        )
    }
}
