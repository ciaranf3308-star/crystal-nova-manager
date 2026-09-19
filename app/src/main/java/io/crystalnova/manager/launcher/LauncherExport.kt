package io.crystalnova.manager.launcher

import io.crystalnova.manager.pegasus.LauncherPresets
import io.crystalnova.manager.pegasus.LauncherProfile
import io.crystalnova.manager.pegasus.LauncherProfileStore
import io.crystalnova.manager.pegasus.LauncherSource
import io.crystalnova.manager.scraper.match.PlatformTable
import io.crystalnova.manager.scraper.store.ScraperStorage
import org.json.JSONObject

/**
 * Phase 1 launcher bridge: pure builders for the Manager → Crystal Launcher
 * data contract (`crystal-launcher/docs/contract.md` §§3,7).
 *
 * The launcher is a presentation layer only: it consumes `config.json`
 * (where everything lives) and `launcher/profiles.json` (per-platform
 * emulator recipes). Both are derived, never authored, here — the recipe
 * table stays [LauncherPresets] verbatim and user choices stay in
 * [LauncherProfileStore]; this module only serializes the effective
 * profile per platform slug, preserving each profile's USER/AUTO source
 * flag.
 *
 * All functions are pure (no Android, no I/O) so the schema is covered
 * by JVM unit tests. The actual file writes live in [ScraperStorage].
 */
object LauncherExport {
    /** Contract version written into both files; the launcher parses it first. */
    const val CONTRACT_VERSION = 1
    const val CONFIG_NAME = "config.json"
    const val LAUNCHER_DIR = "launcher"
    const val PROFILES_NAME = "profiles.json"

    /** One platform's effective recipe plus how it came to be. */
    data class ExportedProfile(
        val slug: String,
        val profile: LauncherProfile,
        val source: LauncherSource,
    )

    /** Outcome of [writeBridge]; failures must never fail a BUILD. */
    sealed interface ExportResult {
        data class Ok(val files: List<String>) : ExportResult
        data class Skipped(val reason: String) : ExportResult
        data class Failed(val reason: String) : ExportResult
    }

    /**
     * Resolves the absolute data-root path from SAF tree URIs, mirroring
     * exactly how [ScraperStorage] is rooted: a dedicated media folder
     * roots the data tree at the SAF tree root; otherwise it lives in
     * `crystal-nova-data/` under the themes tree. Null when no usable
     * root exists — the caller then skips the export (the launcher
     * degrades gracefully on missing files per contract §9).
     *
     * [canonicalPath] is e.g. `StorageLocations::canonicalPath`; taking it
     * as a lambda keeps this pure-JVM testable.
     */
    fun resolveDataRoot(
        mediaTreeUri: String?,
        themesTreeUri: String?,
        canonicalPath: (String) -> String?,
    ): String? {
        if (mediaTreeUri != null) return canonicalPath(mediaTreeUri)
        val themes = themesTreeUri?.let(canonicalPath) ?: return null
        return "$themes/${ScraperStorage.DATA_DIR_NAME}"
    }

    /**
     * Builds `config.json` per contract §3. All paths absolute; the
     * launcher must not guess roots.
     */
    fun buildConfigJson(
        romRoot: String,
        dataRoot: String,
        updatedEpochSeconds: Long,
    ): String = JSONObject()
        .put("version", CONTRACT_VERSION)
        .put("romRoot", romRoot)
        .put("dataRoot", dataRoot)
        .put("indexPath", "$dataRoot/${ScraperStorage.INDEX_NAME}")
        .put("updated", updatedEpochSeconds)
        .toString()

    /**
     * Collects the effective launch recipe per known platform slug:
     * the explicit choice when one is stored (with its real USER/AUTO
     * source flag), else the curated [LauncherPresets] default (AUTO).
     * Slugs with no usable recipe (NOT CONFIGURED, or a stored profile
     * that fails [LauncherProfile.isConfigured]) are omitted — the
     * index already tells the launcher which platforms have games.
     */
    fun collectProfiles(store: LauncherProfileStore): List<ExportedProfile> {
        val slugs = (PlatformTable.all().map { it.slug } + store.all().keys).distinct()
        return slugs.mapNotNull { slug ->
            val stored = store.get(slug)
            val profile = stored ?: LauncherPresets.defaultProfile(slug) ?: return@mapNotNull null
            if (!profile.isConfigured()) return@mapNotNull null
            val source = if (stored != null) store.getSource(slug) else LauncherSource.AUTO
            ExportedProfile(slug, profile, source)
        }.sortedBy { it.slug }
    }

    /**
     * Builds `launcher/profiles.json` per contract §7. Field-for-field
     * mirror of [LauncherProfile]; the launcher ignores unknown fields
     * and tolerates absence, so every field is emitted explicitly.
     */
    fun buildProfilesJson(entries: List<ExportedProfile>): String {
        val profiles = JSONObject()
        for (e in entries) {
            val p = e.profile
            profiles.put(
                e.slug,
                JSONObject()
                    .put("type", p.type.name)
                    .put("package", p.packageName)
                    .put("activity", p.activity)
                    .put("action", p.action)
                    .put("core", p.core)
                    .put("handoff", p.handoff.name)
                    .put("extraKey", p.extraKey)
                    .put("dataPrefix", p.dataPrefix)
                    .put("grantUriPermission", p.grantUriPermission)
                    .put("command", p.command)
                    .put("source", e.source.name),
            )
        }
        return JSONObject()
            .put("version", CONTRACT_VERSION)
            .put("profiles", profiles)
            .toString()
    }
}
