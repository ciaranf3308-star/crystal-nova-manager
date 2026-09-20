package io.crystalnova.manager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsdeManagerGateTest {

    // --- managerBuildTag ---

    @Test
    fun buildTagExtractsFromVersionName() {
        assertEquals(50, managerBuildTag("1.2.4-u50-stripped"))
        assertEquals(48, managerBuildTag("1.2.4-u48-esdeupdate"))
        assertEquals(49, managerBuildTag("1.2.4-u49-esdefolderfix"))
    }

    @Test
    fun buildTagIsCaseInsensitive() {
        assertEquals(7, managerBuildTag("2.0-U7"))
    }

    @Test
    fun buildTagNullWhenAbsent() {
        assertNull(managerBuildTag("1.2.4"))
        assertNull(managerBuildTag(""))
        // "u" inside a word is not a build tag boundary for the spec
        // regex — just document plain behaviour here.
        assertNull(managerBuildTag("1.2.4-stripped"))
    }

    // --- managerBelowMinimum: build-tag comparison (the reported bug) ---

    @Test
    fun u50AboveMinU48DoesNotWarn() {
        // The exact bug: catalog wants u48, manager is u50 — no warning.
        assertFalse(
            managerBelowMinimum(
                minVersionName = "1.2.4-u48-esdeupdate",
                minVersionCode = null,
                currentVersionName = "1.2.4-u50-stripped",
                currentVersionCode = 65,
            ),
        )
    }

    @Test
    fun u48MeetsMinU48WithoutWarning() {
        assertFalse(
            managerBelowMinimum(
                minVersionName = "1.2.4-u48-esdeupdate",
                minVersionCode = null,
                currentVersionName = "1.2.4-u48-esdeupdate",
                currentVersionCode = 63,
            ),
        )
    }

    @Test
    fun u47BelowMinU48Warns() {
        assertTrue(
            managerBelowMinimum(
                minVersionName = "1.2.4-u48-esdeupdate",
                minVersionCode = null,
                currentVersionName = "1.2.4-u47-packcatalog",
                currentVersionCode = 62,
            ),
        )
    }

    // --- managerBelowMinimum: unparseable names fall back to inequality ---

    @Test
    fun unparseableNamesEqualIsNotBelow() {
        assertFalse(
            managerBelowMinimum(
                minVersionName = "mystery",
                minVersionCode = null,
                currentVersionName = "mystery",
                currentVersionCode = 1,
            ),
        )
    }

    @Test
    fun unparseableNamesDifferentWarns() {
        assertTrue(
            managerBelowMinimum(
                minVersionName = "mystery",
                minVersionCode = null,
                currentVersionName = "1.2.4-u50-stripped",
                currentVersionCode = 65,
            ),
        )
    }

    // --- managerBelowMinimum: versionCode gate ---

    @Test
    fun versionCodeGateSatisfied() {
        // 65 >= 63: no warning even when names differ.
        assertFalse(
            managerBelowMinimum(
                minVersionName = "1.2.4-u48-esdeupdate",
                minVersionCode = 63,
                currentVersionName = "1.2.4-u50-stripped",
                currentVersionCode = 65,
            ),
        )
    }

    @Test
    fun versionCodeGateViolated() {
        // 62 < 63: warning.
        assertTrue(
            managerBelowMinimum(
                minVersionName = "1.2.4-u48-esdeupdate",
                minVersionCode = 63,
                currentVersionName = "1.2.4-u47-packcatalog",
                currentVersionCode = 62,
            ),
        )
    }

    @Test
    fun versionCodePreferredOverNameWhenBothPresent() {
        // The name minimum (u99) looks far in the future, but the
        // versionCode (60) is what counts — 65 >= 60, no warning.
        assertFalse(
            managerBelowMinimum(
                minVersionName = "1.2.4-u99-future",
                minVersionCode = 60,
                currentVersionName = "1.2.4-u50-stripped",
                currentVersionCode = 65,
            ),
        )
    }

    // --- managerBelowMinimum: no minimum at all ---

    @Test
    fun noMinimumNeverWarns() {
        assertFalse(
            managerBelowMinimum(
                minVersionName = null,
                minVersionCode = null,
                currentVersionName = "1.0.0",
                currentVersionCode = 1,
            ),
        )
    }
}
