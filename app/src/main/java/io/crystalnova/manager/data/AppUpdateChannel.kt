package io.crystalnova.manager.data

/**
 * The manager app's own update channel (self-updater), independent of
 * the theme [UpdateChannel].
 *
 * - DEV: follows the rolling `dev-latest` prerelease via its
 *   manifest.json. This is the default — the Nova is the dev device.
 * - STABLE: follows published GitHub releases only
 *   (`releases/latest`, which never returns prereleases or drafts).
 *
 * Persisted in the app's [KeyValueStore]; an unset or unrecognized
 * stored value reads back as DEV.
 */
enum class AppUpdateChannel {
    DEV,
    STABLE;

    companion object {
        const val KEY = "manager.update_channel"

        fun load(store: KeyValueStore): AppUpdateChannel =
            when (store.getString(KEY)?.trim()?.uppercase()) {
                STABLE.name -> STABLE
                else -> DEV
            }

        fun save(store: KeyValueStore, channel: AppUpdateChannel) {
            store.putString(KEY, channel.name)
        }
    }
}
