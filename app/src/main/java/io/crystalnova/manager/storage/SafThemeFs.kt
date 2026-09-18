package io.crystalnova.manager.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

private class DocNode(val doc: DocumentFile) : FsNode

/**
 * [ThemeFs] backed by the Storage Access Framework.
 *
 * The user picks the themes/ folder once with the system picker; the
 * persisted tree URI permission is the only storage access the app holds.
 * No MANAGE_EXTERNAL_STORAGE, no broad media permissions.
 *
 * Revocation contract: EVERY method may throw [SecurityException] when the
 * persisted grant is revoked mid-operation. Nothing here degrades to an
 * empty value — a silent empty listing would masquerade revocation as an
 * empty library (and, worse, let callers prune real data). Callers
 * translate the exception into the "folder access lost" reselection
 * state. The single null case is [root] when no folder was ever picked.
 */
class SafThemeFs(
    private val context: Context,
    private val treeUriString: () -> String?,
) : ThemeFs {

    override fun root(): FsNode? {
        // No folder picked yet: null, not an exception.
        val uriString = treeUriString() ?: return null
        // A revoked grant surfaces here as a null DocumentFile, an
        // unreadable root, or a SecurityException — all three mean
        // "access lost" and all three throw, so callers can tell "not
        // configured" (null) apart from "revoked" (throws).
        val doc = try {
            DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: throw SecurityException("storage not accessible: $uriString")
        try {
            if (!doc.canRead()) throw SecurityException("storage not readable: $uriString")
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            throw SecurityException("storage not readable: $uriString")
        }
        return DocNode(doc)
    }

    private fun doc(node: FsNode): DocumentFile = (node as DocNode).doc

    override fun find(dir: FsNode, name: String): FsNode? =
        doc(dir).findFile(name)?.let(::DocNode)

    override fun name(node: FsNode): String? = doc(node).name

    override fun length(node: FsNode): Long = doc(node).length()

    override fun children(dir: FsNode): List<Pair<String, FsNode>> =
        doc(dir).listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            name to DocNode(f)
        }

    override fun isDirectory(node: FsNode): Boolean = doc(node).isDirectory

    override fun mkdir(parent: FsNode, name: String): FsNode =
        DocNode(doc(parent).createDirectory(name) ?: error("mkdir failed: $name"))

    override fun createFile(parent: FsNode, name: String): FsNode =
        DocNode(
            doc(parent).createFile("application/octet-stream", name)
                ?: error("createFile failed: $name"),
        )

    override fun openInput(node: FsNode): InputStream =
        context.contentResolver.openInputStream(doc(node).uri)
            ?: error("openInput failed")

    override fun openOutput(node: FsNode): OutputStream =
        context.contentResolver.openOutputStream(doc(node).uri)
            ?: error("openOutput failed")

    override fun rename(node: FsNode, newName: String): Boolean =
        doc(node).renameTo(newName)

    override fun deleteRecursively(node: FsNode): Boolean =
        doc(node).delete()
}
