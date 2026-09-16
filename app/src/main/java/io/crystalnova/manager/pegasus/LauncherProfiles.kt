package io.crystalnova.manager.pegasus

/**
 * Launcher profiles for the Pegasus metadata injection.
 *
 * Pure Kotlin — no Android imports, so this (including launch-command
 * rendering) is unit-testable on the JVM. All research behind the
 * presets below is web-verified (see the worker report); anything not
 * verifiable stays CUSTOM / NOT CONFIGURED.
 *
 * Profile types:
 * - RETROARCH: RetroArch via its external-launch activity, with a
 *   libretro core .so. Verified intent shape (libretro/RetroArch
 *   issues #13551 / #17433 / #19357):
 *     am start --user 0
 *       -n <pkg>/com.retroarch.browser.retroactivity.RetroActivityFuture
 *       -e ROM "{file.path}"
 *       -e LIBRETRO /data/data/<pkg>/cores/<core>.so   (full path required)
 *       -e CONFIGFILE /storage/emulated/0/Android/data/<pkg>/files/retroarch.cfg
 *       -e QUITFOCUS
 *       --activity-single-top
 * - STANDALONE: a preset standalone emulator, verified package +
 *   activity + path handoff only (NetherSX2, PPSSPP).
 * - VIEW_INTENT: generic `am start -a android.intent.action.VIEW`
 *   template for emulators that consume the game via intent data.
 * - CUSTOM: freeform command, stored verbatim.
 */
enum class LauncherType { RETROARCH, STANDALONE, VIEW_INTENT, CUSTOM }

/** How a STANDALONE preset hands the ROM path to the emulator. */
enum class PathHandoff {
    /** `-e <extraKey> "{file.path}"`, e.g. NetherSX2's bootPath. */
    EXTRA,
    /** `-d "<dataPrefix>{file.path}"`, e.g. PPSSPP's `file://{file.path}`. */
    DATA,
}

data class LauncherProfile(
    val type: LauncherType,
    /** RETROARCH/STANDALONE/VIEW_INTENT: emulator package. */
    val packageName: String = "",
    /** STANDALONE/VIEW_INTENT: activity (relative like `.PpssppActivity` is fine). */
    val activity: String = "",
    /** STANDALONE: intent action. VIEW_INTENT is always ACTION_VIEW. */
    val action: String = "",
    /** RETROARCH: core .so filename, e.g. `mgba_libretro_android.so`. */
    val core: String = "",
    /** STANDALONE: how the ROM path is handed over. */
    val handoff: PathHandoff = PathHandoff.EXTRA,
    /** STANDALONE + EXTRA: the string-extra key, e.g. `bootPath`. */
    val extraKey: String = "",
    /** STANDALONE + DATA: prefix before the path, e.g. `file://`. */
    val dataPrefix: String = "",
    /** CUSTOM: the verbatim launch command. */
    val command: String = "",
) {
    /** True when the profile carries everything needed to render a launch command. */
    fun isConfigured(): Boolean = when (type) {
        LauncherType.RETROARCH -> packageName.isNotBlank() && core.isNotBlank()
        LauncherType.STANDALONE ->
            packageName.isNotBlank() && activity.isNotBlank() && action.isNotBlank() &&
                (handoff != PathHandoff.EXTRA || extraKey.isNotBlank())
        LauncherType.VIEW_INTENT -> packageName.isNotBlank() && activity.isNotBlank()
        LauncherType.CUSTOM -> command.isNotBlank()
    }

    /**
     * Renders the collection-level `launch:` value as lines: the first
     * line is the command head, the rest are continuation lines (the
     * metafile writer indents them two spaces — verified working shape
     * from the Pegasus config generator output).
     */
    fun launchLines(): List<String> = when (type) {
        LauncherType.RETROARCH -> listOf(
            "am start --user 0",
            "-n $packageName/com.retroarch.browser.retroactivity.RetroActivityFuture",
            "-e ROM \"{file.path}\"",
            "-e LIBRETRO ${LauncherPresets.corePath(packageName, core)}",
            "-e CONFIGFILE /storage/emulated/0/Android/data/$packageName/files/retroarch.cfg",
            "-e QUITFOCUS",
            "--activity-single-top",
        )
        LauncherType.STANDALONE -> buildList {
            add("am start --user 0")
            add("-a $action")
            add("-n $packageName/$activity")
            when (handoff) {
                PathHandoff.EXTRA -> add("-e $extraKey \"{file.path}\"")
                PathHandoff.DATA -> add("-d \"$dataPrefix{file.path}\"")
            }
        }
        LauncherType.VIEW_INTENT -> listOf(
            "am start --user 0",
            "-a android.intent.action.VIEW",
            "-n $packageName/$activity",
            "-d \"{file.uri}\"",
        )
        LauncherType.CUSTOM -> command.lines()
    }

    /** One-line human label for the systems list, e.g. `RETROARCH + mgba_libretro_android.so`. */
    fun displayLabel(): String = when (type) {
        LauncherType.RETROARCH -> "RETROARCH + $core"
        LauncherType.STANDALONE -> LauncherPresets.standaloneName(packageName)
        LauncherType.VIEW_INTENT -> "VIEW INTENT"
        LauncherType.CUSTOM -> "CUSTOM"
    }
}

/**
 * Curated presets. RetroArch core defaults are the community-standard
 * libretro core names (`<core>_libretro_android.so` is the buildbot file
 * convention, verified via RetroArch issues naming mesen/fceumm/nestopia/
 * gpsp cores); the core field stays user-visible and editable in the
 * picker, so nothing here is a silent guess. Systems with no confident
 * core (or several equally-valid candidates, e.g. arcade) return null —
 * NOT CONFIGURED, never a fabricated command.
 */
object LauncherPresets {
    const val RETROARCH_AARCH64 = "com.retroarch.aarch64"
    const val RETROARCH_32 = "com.retroarch"

    /** RetroArch packages we detect, in preference order. */
    val retroArchPackages: List<Pair<String, String>> = listOf(
        RETROARCH_AARCH64 to "64-BIT",
        RETROARCH_32 to "32-BIT",
    )

    val NETHER_SX2 = LauncherProfile(
        type = LauncherType.STANDALONE,
        packageName = "xyz.aethersx2.android",
        activity = "xyz.aethersx2.android.EmulationActivity",
        action = "android.intent.action.MAIN",
        handoff = PathHandoff.EXTRA,
        extraKey = "bootPath",
    )
    val PPSSPP = LauncherProfile(
        type = LauncherType.STANDALONE,
        packageName = "org.ppsspp.ppsspp",
        activity = "org.ppsspp.ppsspp.PpssppActivity",
        action = "android.intent.action.VIEW",
        handoff = PathHandoff.DATA,
        dataPrefix = "file://",
    )
    val PPSSPP_GOLD = LauncherProfile(
        type = LauncherType.STANDALONE,
        packageName = "org.ppsspp.ppssppgold",
        activity = "org.ppsspp.ppsspp.PpssppActivity",
        action = "android.intent.action.VIEW",
        handoff = PathHandoff.DATA,
        dataPrefix = "file://",
    )
    /** Every package the picker detects via PackageManager (also the manifest <queries> list). */
    val knownEmulatorPackages: List<String> = listOf(
        RETROARCH_AARCH64,
        RETROARCH_32,
        "xyz.aethersx2.android",
        "org.ppsspp.ppsspp",
        "org.ppsspp.ppssppgold",
    )

    fun standaloneName(packageName: String): String = when (packageName) {
        "xyz.aethersx2.android" -> "NETHERSX2"
        "org.ppsspp.ppsspp" -> "PPSSPP"
        "org.ppsspp.ppssppgold" -> "PPSSPP GOLD"
        else -> packageName.uppercase()
    }

    /** The on-device libretro core path RetroArch's external launch
     * activity expects: the exact complete filesystem path, derived from
     * the package's standard core directory. Shown verbatim in the
     * picker so the user can verify it exists before choosing. */
    fun corePath(packageName: String, core: String): String =
        "/data/data/$packageName/cores/$core"

    fun retroArch(packageName: String = RETROARCH_AARCH64, core: String): LauncherProfile =
        LauncherProfile(type = LauncherType.RETROARCH, packageName = packageName, core = core)

    /** Community-standard libretro core .so per platform slug, or null when unknown/ambiguous. */
    fun defaultCore(slug: String): String? = when (slug) {
        "nes" -> "nestopia_libretro_android.so"
        "snes" -> "snes9x_libretro_android.so"
        "n64" -> "mupen64plus_next_libretro_android.so"
        "gb", "gbc" -> "gambatte_libretro_android.so"
        "gba" -> "mgba_libretro_android.so"
        "nds" -> "melonds_libretro_android.so"
        "genesis", "mastersystem", "gamegear", "segacd" -> "genesis_plus_gx_libretro_android.so"
        "psx" -> "pcsx_rearmed_libretro_android.so"
        "dreamcast" -> "flycast_libretro_android.so"
        "atari2600" -> "stella_libretro_android.so"
        "atari7800" -> "prosystem_libretro_android.so"
        "lynx" -> "handy_libretro_android.so"
        "wonderswan" -> "mednafen_wswan_libretro_android.so"
        "ngp" -> "mednafen_ngp_libretro_android.so"
        "virtualboy" -> "mednafen_vb_libretro_android.so"
        "pcengine" -> "mednafen_pce_fast_libretro_android.so"
        else -> null
    }

    /**
     * Standalone presets relevant to a platform slug. Only systems with
     * a verified package + path handoff are listed — everything else
     * is CUSTOM / NOT CONFIGURED.
     */
    fun standaloneFor(slug: String): List<LauncherProfile> = when (slug) {
        "ps2" -> listOf(NETHER_SX2)
        "psp" -> listOf(PPSSPP, PPSSPP_GOLD)
        else -> emptyList()
    }

    /**
     * Default launcher profile per platform slug, or null when the
     * system stays NOT CONFIGURED:
     * - n3ds / gamecube / saturn / 3do / amiga / c64: no verified core
     *   or standalone intent — CUSTOM only.
     * - arcade: several equally-valid cores (mame2003-plus, fbneo) —
     *   never silently pick one.
     */
    fun defaultProfile(slug: String): LauncherProfile? = when (slug) {
        "ps2" -> NETHER_SX2
        "psp" -> PPSSPP
        else -> defaultCore(slug)?.let { retroArch(core = it) }
    }
}
