package io.crystalnova.manager.storage

import java.io.InputStream
import java.io.OutputStream

/** Opaque handle to a file or directory in the theme file-system. */
interface FsNode

/**
 * Minimal file-system surface the install/rollback dance needs.
 *
 * The production implementation ([SafThemeFs]) is backed by the Storage
 * Access Framework's DocumentFile; tests use an in-memory fake. All paths
 * are confined to the user-chosen themes/ tree — there is no API here
 * that can address anything outside it.
 */
interface ThemeFs {
    /** The user-chosen themes/ directory, or null when access is gone. */
    @Throws(SecurityException::class)
    fun root(): FsNode?

    /** Display name of a node (used to detect a theme-folder pick). */
    fun name(node: FsNode): String?

    fun find(dir: FsNode, name: String): FsNode?
    fun children(dir: FsNode): List<Pair<String, FsNode>>
    fun isDirectory(node: FsNode): Boolean
    fun mkdir(parent: FsNode, name: String): FsNode
    fun createFile(parent: FsNode, name: String): FsNode
    fun openInput(node: FsNode): InputStream
    fun openOutput(node: FsNode): OutputStream
    fun rename(node: FsNode, newName: String): Boolean
    fun deleteRecursively(node: FsNode): Boolean
}
