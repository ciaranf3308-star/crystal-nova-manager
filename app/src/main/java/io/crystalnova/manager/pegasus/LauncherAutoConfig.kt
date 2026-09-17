package io.crystalnova.manager.pegasus

/**
 * The setup assistant's safe-default pass. Pure Kotlin — no Android
 * imports — so every decision is unit-testable on the JVM.
 *
 * Rules:
 * - Only systems WITH games are considered; zero-game systems are
 *   never touched.
 * - An explicit USER choice is never overwritten. AUTO-sourced entries
 *   may be refreshed (the emulator set can change between runs).
 * - Only SAFE installed launchers are picked: RetroArch systems get the
 *   first installed RetroArch package (aarch64 preferred) plus the
 *   curated [LauncherPresets.defaultCore]; PS2 gets NetherSX2 and PSP
 *   gets PPSSPP/PPSSPP Gold (standard preferred), each only when its
 *   known package is actually installed.
 * - Anything else is NEEDS ATTENTION — never a fabricated launcher.
 */
object LauncherAutoConfig {

    /** The safe pick for [slug], or null when it needs attention. */
    fun decide(slug: String, installed: Set<String>): LauncherProfile? {
        if (slug == "ps2") {
            return if (LauncherPresets.NETHER_SX2.packageName in installed) {
                LauncherPresets.NETHER_SX2
            } else {
                null
            }
        }
        if (slug == "psp") {
            if (LauncherPresets.PPSSPP.packageName in installed) return LauncherPresets.PPSSPP
            if (LauncherPresets.PPSSPP_GOLD.packageName in installed) return LauncherPresets.PPSSPP_GOLD
            return null
        }
        val core = LauncherPresets.defaultCore(slug) ?: return null
        val pkg = LauncherPresets.retroArchPackages
            .firstOrNull { (pkg, _) -> pkg in installed }
            ?.first ?: return null
        return LauncherPresets.retroArch(pkg, core)
    }

    data class Outcome(
        /** Slugs that received (or refreshed) an AUTO profile. */
        val configured: List<String>,
        /** Slugs with games that still need attention, sorted. */
        val needAttention: List<String>,
    ) {
        /** One-line summary for the setup notice, e.g.
         *  "AUTO-CONFIGURED 12 · 3 NEED ATTENTION: N64, PS2, WII". */
        fun summary(): String = buildString {
            append("AUTO-CONFIGURED ${configured.size}")
            if (needAttention.isNotEmpty()) {
                append(" · ${needAttention.size} NEED ATTENTION: ")
                append(needAttention.joinToString(", ").uppercase())
            }
        }
    }

    /**
     * Applies [decide] to every slug in [slugsWithGames] (deduped).
     * Writes AUTO-sourced profiles for safe picks; leaves USER choices
     * and NEEDS ATTENTION slugs untouched.
     */
    fun apply(
        store: LauncherProfileStore,
        slugsWithGames: List<String>,
        installed: Set<String>,
    ): Outcome {
        val configured = mutableListOf<String>()
        val needAttention = mutableListOf<String>()
        for (slug in slugsWithGames.distinct()) {
            if (store.getSource(slug) == LauncherSource.USER && store.get(slug) != null) {
                continue
            }
            val pick = decide(slug, installed)
            if (pick != null) {
                store.set(slug, pick, LauncherSource.AUTO)
                configured += slug
            } else {
                needAttention += slug
            }
        }
        return Outcome(configured, needAttention.sorted())
    }
}
