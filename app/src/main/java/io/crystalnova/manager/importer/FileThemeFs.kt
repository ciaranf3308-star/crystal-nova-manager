package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A [ThemeFs] backed by plain [java.io.File], rooted at [rootDir].
 *
 * Used ONLY for the PS1 pipeline's private temp directory
 * (`context.cacheDir/psx-<itemId>/`): chdman needs real POSIX paths,
 * and partial conversion debris must never sit inside the live ROM
 * folder. It is never pointed at user data — the live `psx/` install
 * always goes through the SAF `roms` ThemeFs.
 *
 * Pure JVM — unit-tested without Android.
 */
class FileThemeFs(rootDir: File) : ThemeFs {

    private val rootFile: File = rootDir.absoluteFile

    private data class FileNode(val file: File) : FsNode

    /** True when [file] is the root or lives under it. */
    private fun isInsideRoot(file: File): Boolean {
        val path = file.absoluteFile.path
        val root = rootFile.path
        return path == root || path.startsWith(root + File.separatorChar)
    }

    private fun nodeOf(file: File): FsNode =
        FileNode(if (isInsideRoot(file)) file.absoluteFile else file)

    override fun root(): FsNode = nodeOf(rootFile)

    override fun name(node: FsNode): String? =
        (node as? FileNode)?.file?.name

    override fun find(dir: FsNode, name: String): FsNode? {
        val parent = (dir as? FileNode)?.file ?: return null
        val child = File(parent, name)
        return if (child.exists()) nodeOf(child) else null
    }

    override fun children(dir: FsNode): List<Pair<String, FsNode>> {
        val parent = (dir as? FileNode)?.file ?: return emptyList()
        return parent.listFiles()
            ?.sortedBy { it.name }
            ?.map { it.name to nodeOf(it) }
            ?: emptyList()
    }

    override fun length(node: FsNode): Long =
        (node as? FileNode)?.file?.takeIf { it.isFile }?.length() ?: 0L

    override fun isDirectory(node: FsNode): Boolean =
        (node as? FileNode)?.file?.isDirectory == true

    override fun mkdir(parent: FsNode, name: String): FsNode {
        val dir = (parent as? FileNode)?.file ?: throw IllegalArgumentException("bad node")
        val child = File(dir, name)
        if (!child.mkdirs() && !child.isDirectory) {
            throw IllegalStateException("Could not create directory $name")
        }
        return nodeOf(child)
    }

    override fun createFile(parent: FsNode, name: String): FsNode {
        val dir = (parent as? FileNode)?.file ?: throw IllegalArgumentException("bad node")
        val child = File(dir, name)
        child.parentFile?.mkdirs()
        if (!child.exists() && !child.createNewFile()) {
            throw IllegalStateException("Could not create file $name")
        }
        return nodeOf(child)
    }

    override fun openInput(node: FsNode): InputStream {
        val file = (node as? FileNode)?.file ?: throw IllegalArgumentException("bad node")
        return file.inputStream()
    }

    override fun openOutput(node: FsNode): OutputStream {
        val file = (node as? FileNode)?.file ?: throw IllegalArgumentException("bad node")
        file.parentFile?.mkdirs()
        return file.outputStream()
    }

    override fun rename(node: FsNode, newName: String): Boolean {
        val file = (node as? FileNode)?.file ?: return false
        return file.renameTo(File(file.parentFile, newName))
    }

    override fun deleteRecursively(node: FsNode): Boolean {
        val file = (node as? FileNode)?.file ?: return false
        // Never let a bug nuke outside the temp root.
        if (!isInsideRoot(file)) return false
        return file.deleteRecursively()
    }
}
