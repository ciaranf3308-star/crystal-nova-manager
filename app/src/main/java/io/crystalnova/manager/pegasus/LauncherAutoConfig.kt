package io.crystalnova.manager.pegasus

/**
 * The setup assistant's safe-default pass. Pure Kotlin — no Android
 * imports — so every decision is unit-testable on the JVM.
 *
 * Rules:
 * - Only systems WITH games are considered; zero-game systems are
 *   never touched.
 * - A genuinely valid choice is never overwritten: a stored profile
 *   whose emulator package is currently installed (or a verbatim
 *   CUSTOM command) counts as ALREADY READY and is left completely
 *   untouched. AUTO-sourced entries additionally refresh when a
 *   strictly better safe pick appears.
 * - A BROKEN stored profile (names a package that is not installed,
 *   or is otherwise unusable) is SELF-HEALED when Crystal has a safe
 *   installed recommendation for that platform: the pick from
 *   [decide] replaces it and is saved as AUTO. This covers the
 *   pre-source-flag migration: legacy entries read back as USER, so
 *   without self-heal a stale broken choice would block repair
 *   forever. A broken profile with no safe pick stays as-is and is
 *   reported as NEEDS ATTENTION.
 * - Only SAFE installed launchers are picked: RetroArch systems get the
 *   first installed RetroArch package (aarch64 preferred) plus the
 *   curated [LauncherPresets.defaultCore]; standalone systems get
 *   their verified preset only when its known package is actually
 *   installed.
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
        if (slug == "gamecube") {
            return if (LauncherPresets.DOLPHIN.packageName in installed) {
                LauncherPresets.DOLPHIN
            } else {
                null
            }
        }
        if (slug == "n3ds") {
            // Deterministic preference: current Azahar vanilla first,
            // then the Play variant (same verified activity class).
            if (LauncherPresets.AZAHAR.packageName in installed) return LauncherPresets.AZAHAR
            if (LauncherPresets.AZAHAR_PLAY.packageName in installed) return LauncherPresets.AZAHAR_PLAY
            return null
        }
        val core = LauncherPresets.defaultCore(slug) ?: return null
        val pkg = LauncherPresets.retroArchPackages
            .firstOrNull { (pkg, _) -> pkg in installed }
            ?.first ?: return null
        return LauncherPresets.retroArch(pkg, core)
    }

    /**
     * True when [profile] is a genuinely working choice on this
     * device: fully configured, and — for anything naming an emulator
     * package — that package is currently installed. A verbatim CUSTOM
     * command is always trusted as the user's intent.
     */
    private fun isUsable(profile: LauncherProfile, installed: Set<String>): Boolean {
        if (!profile.isConfigured()) return false
        return when (profile.type) {
            LauncherType.CUSTOM -> true
            else -> profile.packageName in installed
        }
    }

    data class Outcome(
        /** Slugs whose working choice was left completely untouched. */
        val alreadyReady: List<String>,
        /** Slugs whose broken stored profile was replaced by a safe installed pick. */
        val repaired: List<String>,
        /** Slugs that received a fresh (or refreshed AUTO) profile. */
        val configured: List<String>,
        /** Slugs with games that still need attention, sorted. */
        val needAttention: List<String>,
    ) {
        /** One-line summary for the setup notice, e.g.
         *  "10 ALREADY READY · 1 REPAIRED · 2 CONFIGURED · 0 NEED ATTENTION". */
        fun summary(): String = buildString {
            append("${alreadyReady.size} ALREADY READY")
            append(" · ${repaired.size} REPAIRED")
            append(" · ${configured.size} CONFIGURED")
            append(" · ${needAttention.size} NEED ATTENTION")
            if (needAttention.isNotEmpty()) {
                append(": ")
                append(needAttention.joinToString(", ").uppercase())
            }
        }
    }

    /**
     * Applies [decide] to every slug in [slugsWithGames] (deduped).
     * Valid stored choices are preserved untouched; broken ones are
     * self-healed when a safe installed pick exists; the rest land in
     * NEEDS ATTENTION with their stored profile left as-is.
     */
    fun apply(
        store: LauncherProfileStore,
        slugsWithGames: List<String>,
        installed: Set<String>,
    ): Outcome {
        val alreadyReady = mutableListOf<String>()
        val repaired = mutableListOf<String>()
        val configured = mutableListOf<String>()
        val needAttention = mutableListOf<String>()
        for (slug in slugsWithGames.distinct()) {
            val existing = store.get(slug)
            val pick = decide(slug, installed)
            if (existing != null && isUsable(existing, installed)) {
                // Genuinely working choice. USER entries are sacred;
                // AUTO entries refresh when a strictly better safe pick
                // appears (e.g. the 64-bit RetroArch installed later).
                if (store.getSource(slug) == LauncherSource.AUTO &&
                    pick != null && pick != existing
                ) {
                    store.set(slug, pick, LauncherSource.AUTO)
                    configured += slug
                } else {
                    alreadyReady += slug
                }
                continue
            }
            if (pick != null) {
                store.set(slug, pick, LauncherSource.AUTO)
                if (existing != null) repaired += slug else configured += slug
            } else {
                // No safe pick: leave any broken profile as-is and
                // report the system as needing attention.
                needAttention += slug
            }
        }
        return Outcome(
            alreadyReady.sorted(),
            repaired.sorted(),
            configured.sorted(),
            needAttention.sorted(),
        )
    }
}
