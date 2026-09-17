package io.crystalnova.manager.bios

import io.crystalnova.manager.data.KeyValueStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Executors.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory KeyValueStore for the async BIOS tests. */
private class FakeAsyncBiosPrefs : KeyValueStore {
    private val strings = mutableMapOf<String, String>()
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }
    override fun remove(key: String) { strings.remove(key) }
}

/**
 * v24 review fix (hardware-grade): the recursive SAF scan must never
 * run on Main. [BiosInventory.requestScan] runs the walk through the
 * injected IO dispatcher and caches the result in [BiosInventory.scanState];
 * HOME readiness and the BIOS screen consume the cached result only —
 * re-reading it never re-scans.
 */
class BiosInventoryAsyncTest {

    private val romUri = "content://com.android.externalstorage.documents/tree/1234-ABCD%3Aroms"
    private val biosUri = "content://com.android.externalstorage.documents/tree/1234-ABCD%3Abios"
    private val goodBios = BiosFile("scph39001.bin", 4_194_304L, "ps2/scph39001.bin")

    private val ioThreadName = "bios-test-io"
    private val ioDispatcher = Executors
        .newSingleThreadExecutor { r -> Thread(r, ioThreadName) }
        .asCoroutineDispatcher()

    @After
    fun tearDown() {
        ioDispatcher.close()
    }

    private fun inventory(
        invocations: AtomicInteger = AtomicInteger(0),
        seenThreads: MutableList<String> = mutableListOf(),
        gate: CountDownLatch? = null,
    ): BiosInventory {
        val prefs = FakeAsyncBiosPrefs()
        prefs.putString(BiosInventory.KEY_BIOS_TREE_URI, biosUri)
        return BiosInventory(
            context = null,
            prefs = prefs,
            romTreeUriProvider = { romUri },
            lister = {
                invocations.incrementAndGet()
                seenThreads.add(Thread.currentThread().name)
                gate?.await(10, TimeUnit.SECONDS)
                listOf(goodBios)
            },
            ioDispatcher = ioDispatcher,
            accessOverride = true,
        )
    }

    @Test
    fun scan_runsThroughInjectedDispatcher_notCallerThread() = runBlocking {
        val invocations = AtomicInteger(0)
        val seenThreads = mutableListOf<String>()
        val inv = inventory(invocations, seenThreads)

        val job = inv.requestScan(this)
        job.join()

        // The blocking walk ran on the injected IO dispatcher thread —
        // never on the calling (test/Main) thread.
        assertEquals(listOf(ioThreadName), seenThreads)
        assertEquals(1, invocations)
        val state = inv.scanState.value
        assertTrue(state is BiosScanState.Ready)
        assertEquals(listOf(goodBios), (state as BiosScanState.Ready).files)
    }

    @Test
    fun scan_exposesScanningState_withoutBlockingCaller() = runBlocking {
        val gate = CountDownLatch(1)
        val inv = inventory(gate = gate)

        val job = inv.requestScan(this)
        // Set synchronously by requestScan: the caller learns a scan is
        // in flight without waiting for it.
        assertTrue(inv.scanState.value is BiosScanState.Scanning)

        gate.countDown()
        job.join()
        assertTrue(inv.scanState.value is BiosScanState.Ready)
    }

    @Test
    fun concurrentRequestScan_coalescesIntoOneScan() = runBlocking {
        val invocations = AtomicInteger(0)
        val inv = inventory(invocations)

        val job1 = inv.requestScan(this)
        val job2 = inv.requestScan(this)
        assertSame(job1, job2)
        job1.join()

        assertEquals(1, invocations)
    }

    @Test
    fun readinessReadsCache_neverRescans() = runBlocking {
        val invocations = AtomicInteger(0)
        val inv = inventory(invocations)

        inv.requestScan(this).join()
        val cached = (inv.scanState.value as BiosScanState.Ready).files

        // This is exactly what HOME readiness and the BIOS screen do:
        // pure in-memory status derivation over the cached files.
        repeat(3) {
            inv.ps2Status(5, cached)
            inv.ps2Issue(5, cached)
        }

        // No further SAF walk happened.
        assertEquals(1, invocations)
        assertTrue(inv.scanState.value is BiosScanState.Ready)
    }

    @Test
    fun clearBiosFolder_resetsScanCache() = runBlocking {
        val inv = inventory()
        inv.requestScan(this).join()
        assertTrue(inv.scanState.value is BiosScanState.Ready)

        inv.clearBiosFolder()

        assertTrue(inv.scanState.value is BiosScanState.NotScanned)
    }

    @Test
    fun scan_withNoGrant_cachesNotScanned() = runBlocking {
        val prefs = FakeAsyncBiosPrefs() // no BIOS tree URI persisted
        val inv = BiosInventory(
            context = null,
            prefs = prefs,
            romTreeUriProvider = { romUri },
            lister = { listOf(goodBios) },
            ioDispatcher = ioDispatcher,
            accessOverride = true,
        )

        inv.requestScan(this).join()

        assertTrue(inv.scanState.value is BiosScanState.NotScanned)
    }
}
