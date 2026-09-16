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
 */
class SafThemeFs(
    private val context: Context,
    private val treeUriString: () -> String?,
) : ThemeFs {

    override fun root(): FsNode? {
        val uriString = treeUriString() ?: return null
        return try {
            val doc = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
            // fromTreeUri returns null when the persisted permission is gone.
            if (doc == null || !doc.canRead()) null else DocNode(doc)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun doc(node: FsNode): DocumentFile = (node as DocNode).doc

    override fun find(dir: FsNode, name: String): FsNode? =
        doc(dir).findFile(name)?.let(::DocNode)

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
