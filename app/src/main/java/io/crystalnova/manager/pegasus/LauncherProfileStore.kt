package io.crystalnova.manager.pegasus

import io.crystalnova.manager.data.KeyValueStore
import org.json.JSONObject

/**
 * How a stored launcher choice came to be. AUTO entries are written by
 * the setup assistant and may be refreshed by it; USER entries are
 * explicit manual choices and are never overwritten automatically.
 */
enum class LauncherSource { USER, AUTO }

/**
 * Persists per-platform launcher choices as one JSON object in
 * [KeyValueStore] (`pegasus_launcher_profiles`):
 * `{ "<slug>": { "type": "RETROARCH", "package": "…", "core": "…",
 *   "source": "USER" } }`.
 *
 * Only user-chosen (or auto-configured) profiles are stored;
 * [effectiveProfile] falls back to [LauncherPresets.defaultProfile] so
 * curated defaults apply without being written. Clearing a slug removes
 * the override and re-exposes the default (or NOT CONFIGURED when there
 * is none). Entries written before the source flag existed read back as
 * USER — a legacy explicit choice is never treated as auto-configured.
 *
 * Uses org.json like the rest of the codebase (ScraperJson) — the
 * round-trip is covered by JVM unit tests run in CI.
 */
class LauncherProfileStore(private val prefs: KeyValueStore) {
    companion object {
        const val KEY = "pegasus_launcher_profiles"
    }

    /** The explicit choice for [slug] (user or auto-configured), or null when none was made. */
    fun get(slug: String): LauncherProfile? {
        val root = readRoot() ?: return null
        val o = root.optJSONObject(slug) ?: return null
        return fromJson(o)
    }

    /**
     * How the stored choice for [slug] came to be. Defaults to USER:
     * entries written before the flag existed were always explicit
     * manual picks, and slates with no entry have no source at all.
     */
    fun getSource(slug: String): LauncherSource {
        val root = readRoot() ?: return LauncherSource.USER
        val o = root.optJSONObject(slug) ?: return LauncherSource.USER
        return try {
            LauncherSource.valueOf(o.optString("source", LauncherSource.USER.name))
        } catch (_: IllegalArgumentException) {
            LauncherSource.USER
        }
    }

    /** The profile in force: explicit choice, else the curated default (possibly null). */
    fun effectiveProfile(slug: String): LauncherProfile? =
        get(slug) ?: LauncherPresets.defaultProfile(slug)

    fun set(slug: String, profile: LauncherProfile) =
        set(slug, profile, LauncherSource.USER)

    /** Stores [profile] for [slug], recording how it came to be. */
    fun set(slug: String, profile: LauncherProfile, source: LauncherSource) {
        val root = readRoot() ?: JSONObject()
        root.put(slug, toJson(profile, source))
        prefs.putString(KEY, root.toString())
    }

    /** Removes the user's override; the default (if any) applies again. */
    fun clear(slug: String) {
        val root = readRoot() ?: return
        root.remove(slug)
        prefs.putString(KEY, root.toString())
    }

    /** All explicit user choices, keyed by slug. */
    fun all(): Map<String, LauncherProfile> {
        val root = readRoot() ?: return emptyMap()
        return root.keys().asSequence()
            .mapNotNull { k -> root.optJSONObject(k)?.let { k to fromJson(it) } }
            .toMap()
    }

    private fun readRoot(): JSONObject? {
        val raw = prefs.getString(KEY) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun toJson(p: LauncherProfile, source: LauncherSource): JSONObject = JSONObject()
        .put("type", p.type.name)
        .put("package", p.packageName)
        .put("activity", p.activity)
        .put("action", p.action)
        .put("core", p.core)
        .put("handoff", p.handoff.name)
        .put("extraKey", p.extraKey)
        .put("dataPrefix", p.dataPrefix)
        .put("command", p.command)
        .put("source", source.name)

    private fun fromJson(o: JSONObject): LauncherProfile {
        val type = try {
            LauncherType.valueOf(o.optString("type"))
        } catch (_: IllegalArgumentException) {
            LauncherType.CUSTOM
        }
        val handoff = try {
            PathHandoff.valueOf(o.optString("handoff"))
        } catch (_: IllegalArgumentException) {
            PathHandoff.EXTRA
        }
        return LauncherProfile(
            type = type,
            packageName = o.optString("package"),
            activity = o.optString("activity"),
            action = o.optString("action"),
            core = o.optString("core"),
            handoff = handoff,
            extraKey = o.optString("extraKey"),
            dataPrefix = o.optString("dataPrefix"),
            command = o.optString("command"),
        )
    }
}
