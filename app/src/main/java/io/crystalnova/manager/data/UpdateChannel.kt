package io.crystalnova.manager.data

/**
 * Update channels. Phase U1 exposes STABLE only — BETA exists so the
 * channel switch can be added later without restructuring.
 */
sealed interface UpdateChannel {
    val branch: String
    val label: String

    data object Stable : UpdateChannel {
        override val branch = "main"
        override val label = VersionInfo.CHANNEL_STABLE
    }

    /** Not exposed in Phase U1 UI. Reserved for future Nova-tested beta builds. */
    data object Beta : UpdateChannel {
        override val branch = "beta"
        override val label = VersionInfo.CHANNEL_BETA
    }
}
