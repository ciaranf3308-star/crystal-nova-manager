package io.crystalnova.manager.importer

import io.crystalnova.manager.data.KeyValueStore

/**
 * Persistent console -> ROM-folder mapping.
 *
 * The base is the user-granted ROMs tree (e.g. the SD card's ROMs/
 * root); each platform maps to one folder name under it. Defaults
 * follow the ES-DE folder conventions. Genesis and Mega Drive are
 * distinct platform identities sharing one default folder, per the
 * user's setup.
 *
 * Persisted as JSON under a single [KeyValueStore] key so the mapping
 * survives app restarts; every folder name is validated before it is
 * stored (no separators, no traversal).
 */
class PlatformMapping(private val prefs: KeyValueStore) {

    companion object {
        const val KEY = "importer.platform_folders"

        /**
         * ES-DE-conventional defaults. The button order below is also
         * the classification grid order — the Nova's configured
         * systems, most-used first.
         */
        val DEFAULT_FOLDERS: Map<PlatformId, String> = mapOf(
            PlatformId.GBA to "gba",
            PlatformId.GBC to "gbc",
            PlatformId.GB to "gb",
            PlatformId.NDS to "nds",
            PlatformId.N3DS to "3ds",
            PlatformId.SNES to "snes",
            PlatformId.NES to "nes",
            PlatformId.N64 to "n64",
            PlatformId.GENESIS to "genesis",
            PlatformId.MEGADRIVE to "genesis",
            PlatformId.PSX to "psx",
            PlatformId.PS2 to "ps2",
            PlatformId.PSP to "psp",
            PlatformId.GAMECUBE to "gc",
            PlatformId.WII to "wii",
            PlatformId.WIIU to "wiiu",
            PlatformId.DREAMCAST to "dreamcast",
            PlatformId.XBOX to "xbox",
        )

        /** Classification-grid order (the user's configured systems). */
        val ORDERED: List<PlatformId> = listOf(
            PlatformId.GBA,
            PlatformId.GBC,
            PlatformId.GB,
            PlatformId.NDS,
            PlatformId.N3DS,
            PlatformId.SNES,
            PlatformId.NES,
            PlatformId.N64,
            PlatformId.GENESIS,
            PlatformId.MEGADRIVE,
            PlatformId.PSX,
            PlatformId.PS2,
            PlatformId.PSP,
            PlatformId.GAMECUBE,
            PlatformId.WII,
            PlatformId.WIIU,
            PlatformId.DREAMCAST,
            PlatformId.XBOX,
        )
    }

    private var cache: MutableMap<PlatformId, String>? = null

    private fun all(): MutableMap<PlatformId, String> {
        cache?.let { return it }
        val loaded = mutableMapOf<PlatformId, String>()
        val raw = prefs.getString(KEY)
        val map = raw?.let(JsonRead::obj)
        if (map != null) {
            for ((k, v) in map) {
                val platform = runCatching { PlatformId.valueOf(k) }.getOrNull()
                val folder = v as? String
                if (platform != null && folder != null && isValidFolder(folder)) {
                    loaded[platform] = folder
                }
            }
        }
        // Any platform missing from storage falls back to its default.
        for ((platform, folder) in DEFAULT_FOLDERS) {
            loaded.putIfAbsent(platform, folder)
        }
        cache = loaded
        return loaded
    }

    /** Folder name for [platform] under the ROMs root. */
    fun folderFor(platform: PlatformId): String = all()[platform]
        ?: DEFAULT_FOLDERS[platform]
        ?: platform.name.lowercase()

    /**
     * Sets a custom folder. Returns false (and stores nothing) when
     * the name is not a safe single path segment.
     */
    fun setFolder(platform: PlatformId, folder: String): Boolean {
        val cleaned = folder.trim()
        if (!isValidFolder(cleaned)) return false
        all()[platform] = cleaned
        persist()
        return true
    }

    /** Restores every platform to its default folder. */
    fun resetDefaults() {
        cache = DEFAULT_FOLDERS.toMutableMap()
        persist()
    }

    /** Snapshot of the current mapping in grid order. */
    fun snapshot(): List<Pair<PlatformId, String>> =
        ORDERED.map { it to folderFor(it) }

    private fun persist() {
        val body = all().entries.joinToString(",") { (platform, folder) ->
            "${JsonCodec.writeString(platform.name)}:${JsonCodec.writeString(folder)}"
        }
        prefs.putString(KEY, "{$body}")
    }

    /** A folder name is one safe path segment: no separators, no traversal. */
    fun isValidFolder(folder: String): Boolean {
        if (folder.isEmpty() || folder.length > 64) return false
        if (folder == "." || folder == "..") return false
        return folder.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } &&
            !folder.startsWith('.')
    }
}
