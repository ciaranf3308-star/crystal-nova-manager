package io.crystalnova.manager.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * u46: guards the INJECT TEST PACK utility.
 *
 * - [sha256Hex] is verified against the standard "abc" vector (pure JVM).
 * - The APK-bundled asset's hash must match [TestPackInjector.EXPECTED_SHA256],
 *   so a stale or corrupted asset can never ship silently.
 *
 * The MediaStore copy itself is validated on real Nova hardware — the
 * whole point of this utility is the on-device iiSU import test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TestPackInjectorTest {

    @Test
    fun `sha256Hex matches the abc test vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TestPackInjector.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `bundled test pack asset hash matches expected`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = context.assets.open(TestPackInjector.ASSET_NAME).use { it.readBytes() }
        assertEquals(
            TestPackInjector.EXPECTED_SHA256,
            TestPackInjector.sha256Hex(bytes),
        )
    }
}
