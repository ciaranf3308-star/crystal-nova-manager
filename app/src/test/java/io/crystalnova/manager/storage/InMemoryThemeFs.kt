package io.crystalnova.manager.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory [ThemeFs] for tests. Supports failure injection to simulate
 * SAF rename failures mid-dance and permission loss.
 */
class InMemoryThemeFs : ThemeFs {

    inner class Node(
        var name: String,
        val isDir: Boolean,
        val children: MutableMap<String, Node> = mutableMapOf(),
        var bytes: ByteArray = ByteArray(0),
        var parent: Node? = null,
    ) : FsNode

    val rootNode = Node("themes", isDir = true)

    /** When false, root() returns null — simulates lost SAF permission. */
    var rootAvailable: Boolean = true

    /** Return false from this to make a rename fail (simulates SAF failure). */
    var renameGate: ((node: Node, newName: String) -> Boolean)? = null

    private fun node(n: FsNode): Node = n as Node

    override fun root(): FsNode? = if (rootAvailable) rootNode else null

    override fun find(dir: FsNode, name: String): FsNode? = node(dir).children[name]

    override fun name(node: FsNode): String = node(node).name

    override fun length(node: FsNode): Long = node(node).bytes.size.toLong()

    override fun children(dir: FsNode): List<Pair<String, FsNode>> =
        node(dir).children.entries.map { it.key to it.value as FsNode }

    override fun isDirectory(node: FsNode): Boolean = node(node).isDir

    override fun mkdir(parent: FsNode, name: String): FsNode {
        val p = node(parent)
        require(!p.children.containsKey(name)) { "exists: $name" }
        val n = Node(name, isDir = true, parent = p)
        p.children[name] = n
        return n
    }

    override fun createFile(parent: FsNode, name: String): FsNode {
        val p = node(parent)
        require(!p.children.containsKey(name)) { "exists: $name" }
        val n = Node(name, isDir = false, parent = p)
        p.children[name] = n
        return n
    }

    override fun openInput(node: FsNode): InputStream =
        ByteArrayInputStream(node(node).bytes)

    override fun openOutput(node: FsNode): OutputStream {
        val n = node(node)
        return object : ByteArrayOutputStream() {
            override fun close() {
                n.bytes = toByteArray()
                super.close()
            }
        }
    }

    override fun rename(node: FsNode, newName: String): Boolean {
        val n = node(node)
        val gate = renameGate
        if (gate != null && !gate(n, newName)) return false
        val parent = n.parent ?: return false
        if (parent.children.containsKey(newName)) return false
        parent.children.remove(n.name)
        n.name = newName
        parent.children[newName] = n
        return true
    }

    override fun deleteRecursively(node: FsNode): Boolean {
        val n = node(node)
        n.parent?.children?.remove(n.name)
        return true
    }

    /** Test helper: seed a theme directory tree with a version marker. */
    fun seedTheme(dirName: String, version: String, commit: String) {
        val dir = mkdir(rootNode, dirName)
        for (f in listOf("theme.cfg", "theme.qml", "crystal-version.json")) {
            val file = createFile(dir, f)
            val content = if (f == "crystal-version.json") {
                """{"version":"$version","commit":"$commit","channel":"stable"}"""
            } else {
                "stub $f"
            }
            openOutput(file).use { it.write(content.toByteArray()) }
        }
        for (d in listOf("components", "screens", "assets", "fonts")) {
            mkdir(dir, d)
        }
    }

    /**
     * Test helper: seed a legacy manual install — a real theme tree with
     * NO crystal-version.json, as shipped before the marker existed.
     */
    fun seedLegacyTheme(dirName: String) {
        val dir = mkdir(rootNode, dirName)
        for (f in listOf("theme.cfg", "theme.qml")) {
            val file = createFile(dir, f)
            openOutput(file).use { it.write("stub $f".toByteArray()) }
        }
        for (d in listOf("components", "screens", "assets", "fonts")) {
            mkdir(dir, d)
        }
    }

    fun treeString(): String {
        val sb = StringBuilder()
        fun walk(n: Node, depth: Int) {
            sb.append("  ".repeat(depth)).append(n.name)
                .append(if (n.isDir) "/" else " (${n.bytes.size}b)").append('\n')
            n.children.values.sortedBy { it.name }.forEach { walk(it, depth + 1) }
        }
        walk(rootNode, 0)
        return sb.toString()
    }
}
