package io.crystalnova.manager.updater

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun themeFiles(): Map<String, String> = mapOf(
        "theme.cfg" to "[theme]",
        "theme.qml" to "import QtQuick 2.0",
        "crystal-version.json" to """{"version":"2.1.0","commit":"abc1234","channel":"stable"}""",
        "components/A.qml" to "x",
        "screens/B.qml" to "x",
        "assets/c.png" to "png",
        "fonts/f.otf" to "font",
    )

    /** Builds a zip; [prefix] nests entries (like GitHub) or leaves them flat. */
    private fun buildZip(entries: Map<String, String>, prefix: String = ""): File {
        val zip = tmp.newFile("theme.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(prefix + name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `valid nested GitHub-style zip extracts and locates the repo root`() {
        val zip = buildZip(themeFiles(), prefix = "crystal-nova-pegasus-theme-main/")
        val root = ZipValidator.extract(zip, tmp.newFolder("out"))
        assertTrue(File(root, "theme.qml").isFile)
        assertTrue(File(root, "components").isDirectory)
        assertEquals("2.1.0", File(root, "crystal-version.json").readText().let {
            io.crystalnova.manager.data.VersionInfo.parse(it)!!.version
        })
    }

    @Test
    fun `flat zip is accepted too`() {
        val root = ZipValidator.extract(buildZip(themeFiles()), tmp.newFolder("out2"))
        assertTrue(ZipValidator.isValidRoot(root))
    }

    @Test
    fun `path traversal entry rejects the whole package`() {
        val zip = buildZip(
            themeFiles() + ("../evil.sh" to "pwn"),
            prefix = "crystal-nova-pegasus-theme-main/",
        )
        assertThrows(InvalidPackageException::class.java) {
            ZipValidator.extract(zip, tmp.newFolder("out3"))
        }
    }

    @Test
    fun `absolute path entry is rejected`() {
        val zip = buildZip(mapOf("/abs/evil.sh" to "pwn"))
        assertThrows(InvalidPackageException::class.java) {
            ZipValidator.extract(zip, tmp.newFolder("out4"))
        }
    }

    @Test
    fun `missing theme_qml is rejected`() {
        val files = themeFiles().filterKeys { it != "theme.qml" }
        val zip = buildZip(files, prefix = "crystal-nova-pegasus-theme-main/")
        assertThrows(InvalidPackageException::class.java) {
            ZipValidator.extract(zip, tmp.newFolder("out5"))
        }
    }

    @Test
    fun `missing theme_cfg is rejected`() {
        val files = themeFiles().filterKeys { it != "theme.cfg" }
        val zip = buildZip(files, prefix = "crystal-nova-pegasus-theme-main/")
        assertThrows(InvalidPackageException::class.java) {
            ZipValidator.extract(zip, tmp.newFolder("out6"))
        }
    }

    @Test
    fun `missing required directory is rejected`() {
        val files = themeFiles().filterKeys { !it.startsWith("assets/") }
        val zip = buildZip(files, prefix = "crystal-nova-pegasus-theme-main/")
        assertThrows(InvalidPackageException::class.java) {
            ZipValidator.extract(zip, tmp.newFolder("out7"))
        }
    }

    @Test
    fun `empty archive is rejected`() {
        val zip = tmp.newFile("empty.zip")
        ZipOutputStream(zip.outputStream()).close()
        assertThrows(InvalidPackageException::class.java) {
            ZipValidator.extract(zip, tmp.newFolder("out8"))
        }
    }

    @Test
    fun `non-zip file is rejected`() {
        val notZip = tmp.newFile("nope.zip")
        notZip.writeText("this is not a zip")
        assertThrows(InvalidPackageException::class.java) {
            ZipValidator.extract(notZip, tmp.newFolder("out9"))
        }
    }

    @Test
    fun `nothing is extracted outside the destination dir`() {
        val zip = buildZip(
            themeFiles() + ("sub/../../escape.txt" to "pwn"),
            prefix = "crystal-nova-pegasus-theme-main/",
        )
        val dest = tmp.newFolder("out10")
        assertThrows(InvalidPackageException::class.java) {
            ZipValidator.extract(zip, dest)
        }
        assertFalse(File(tmp.root, "escape.txt").exists())
    }
}
