package io.crystalnova.manager.pegasus

import org.junit.Assert.*
import org.junit.Test

/** In-memory [MetafileSwapFs]: records every op, can fail on demand. */
private class FakeSwapFs(
    initial: Map<String, ByteArray> = emptyMap(),
    var failRenameIf: (from: String, to: String) -> Boolean = { _, _ -> false },
    var failWrite: Boolean = false,
    var failCreate: Boolean = false,
) : MetafileSwapFs {
    val files = initial.toMutableMap()
    val log = mutableListOf<String>()

    override fun find(name: String): Boolean {
        log += "find:$name"
        return name in files
    }

    override fun create(name: String): Boolean {
        log += "create:$name"
        if (failCreate) return false
        files[name] = ByteArray(0)
        return true
    }

    override fun write(name: String, bytes: ByteArray): Boolean {
        log += "write:$name"
        if (failWrite || name !in files) return false
        files[name] = bytes
        return true
    }

    override fun rename(from: String, to: String): Boolean {
        log += "rename:$from->$to"
        if (failRenameIf(from, to)) return false
        val bytes = files.remove(from) ?: return false
        files[to] = bytes
        return true
    }

    override fun delete(name: String) {
        log += "delete:$name"
        files.remove(name)
    }
}

class PegasusLibrarySwapTest {

    private val final = MetafileGenerator.FILE_NAME
    private val tmp = PegasusLibrary.TMP_NAME
    private val backup = PegasusLibrary.BACKUP_NAME
    private val newBytes = "new metafile".toByteArray()
    private val oldBytes = "previous metafile".toByteArray()

    @Test
    fun swap_freshInstall_writesFinal() {
        val fs = FakeSwapFs()
        assertTrue(swapMetafile(fs, newBytes))
        assertArrayEquals(newBytes, fs.files[final])
        assertFalse("tmp cleaned up", tmp in fs.files)
        assertFalse("backup cleaned up", backup in fs.files)
        assertEquals(
            listOf(
                "delete:$tmp",
                "create:$tmp",
                "write:$tmp",
                "delete:$backup",
                "find:$final",
                "rename:$tmp->$final",
                "delete:$backup",
            ),
            fs.log,
        )
    }

    @Test
    fun swap_replacesPreviousViaBackup() {
        val fs = FakeSwapFs(mapOf(final to oldBytes))
        assertTrue(swapMetafile(fs, newBytes))
        assertArrayEquals(newBytes, fs.files[final])
        assertFalse("tmp cleaned up", tmp in fs.files)
        assertFalse("backup cleaned up", backup in fs.files)
        assertTrue("previous moved aside first", fs.log.indexOf("rename:$final->$backup") < fs.log.indexOf("rename:$tmp->$final"))
    }

    @Test
    fun swap_failedFinalRename_restoresPrevious() {
        // Only the tmp->final swap fails; the backup->final restore succeeds.
        val fs = FakeSwapFs(mapOf(final to oldBytes), failRenameIf = { from, to -> from == tmp && to == final })
        assertFalse(swapMetafile(fs, newBytes))
        assertArrayEquals("previous metafile restored, never lost", oldBytes, fs.files[final])
        assertFalse("tmp cleaned up", tmp in fs.files)
        assertFalse("backup cleaned up", backup in fs.files)
    }

    @Test
    fun swap_failedBackupRename_abortsAndKeepsPrevious() {
        val fs = FakeSwapFs(mapOf(final to oldBytes), failRenameIf = { from, to -> from == final && to == backup })
        assertFalse(swapMetafile(fs, newBytes))
        assertArrayEquals("previous untouched when it cannot be preserved", oldBytes, fs.files[final])
        assertFalse("tmp cleaned up", tmp in fs.files)
    }

    @Test
    fun swap_writeFailure_deletesTmpAndKeepsPrevious() {
        val fs = FakeSwapFs(mapOf(final to oldBytes), failWrite = true)
        assertFalse(swapMetafile(fs, newBytes))
        assertArrayEquals(oldBytes, fs.files[final])
        assertFalse("tmp cleaned up", tmp in fs.files)
        assertFalse(fs.log.any { it.startsWith("rename:") })
    }

    @Test
    fun swap_createFailure_returnsFalse() {
        val fs = FakeSwapFs(failCreate = true)
        assertFalse(swapMetafile(fs, newBytes))
        assertTrue(fs.files.isEmpty())
    }

    @Test
    fun swap_neverTouchesOtherMetadataFiles() {
        val other = "other.metadata.pegasus.txt"
        val fs = FakeSwapFs(mapOf(final to oldBytes, other to "untouched".toByteArray()))
        assertTrue(swapMetafile(fs, newBytes))
        assertArrayEquals("other metadata files are never modified", "untouched".toByteArray(), fs.files[other])
        assertTrue("no op names any other file", fs.log.none { it.contains(other) })
    }
}
