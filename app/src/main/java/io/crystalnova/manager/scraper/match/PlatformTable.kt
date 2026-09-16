package io.crystalnova.manager.scraper.match

import io.crystalnova.manager.scraper.model.MediaKind

/**
 * Known platform. libretroName is the RetroArch playlist name used for
 * thumbnail URLs (null when Libretro has no matching repo).
 */
data class PlatformInfo(
    val slug: String,
    val displayName: String,
    val libretroName: String?,
    val mediaKind: MediaKind,
    val extensions: Set<String>,
)

/**
 * Platform registry. Folder-name aliases map user ROM folders onto slugs.
 * Unknown folders/platforms resolve to null — callers must treat those
 * games as UNKNOWN media, never as cartridges.
 */
object PlatformTable {
    private val platforms: List<PlatformInfo> = listOf(
        PlatformInfo("nes", "Nintendo Entertainment System", "Nintendo - Nintendo Entertainment System", MediaKind.CARTRIDGE, setOf("nes", "fds", "unf", "unif")),
        PlatformInfo("snes", "Super Nintendo", "Nintendo - Super Nintendo Entertainment System", MediaKind.CARTRIDGE, setOf("sfc", "smc", "swc", "fig")),
        PlatformInfo("n64", "Nintendo 64", "Nintendo - Nintendo 64", MediaKind.CARTRIDGE, setOf("n64", "z64", "v64")),
        PlatformInfo("gamecube", "Nintendo GameCube", "Nintendo - GameCube", MediaKind.DISC, setOf("iso", "gcm", "ciso", "gcz", "rvz")),
        PlatformInfo("gb", "Game Boy", "Nintendo - Game Boy", MediaKind.CARTRIDGE, setOf("gb")),
        PlatformInfo("gbc", "Game Boy Color", "Nintendo - Game Boy Color", MediaKind.CARTRIDGE, setOf("gbc")),
        PlatformInfo("gba", "Game Boy Advance", "Nintendo - Game Boy Advance", MediaKind.CARTRIDGE, setOf("gba")),
        PlatformInfo("nds", "Nintendo DS", "Nintendo - Nintendo DS", MediaKind.CARTRIDGE, setOf("nds")),
        PlatformInfo("n3ds", "Nintendo 3DS", "Nintendo - Nintendo 3DS", MediaKind.CARTRIDGE, setOf("3ds", "3dsx", "cci")),
        PlatformInfo("genesis", "Sega Genesis / Mega Drive", "Sega - Mega Drive - Genesis", MediaKind.CARTRIDGE, setOf("md", "smd", "gen", "bin")),
        PlatformInfo("mastersystem", "Sega Master System", "Sega - Master System - Mark III", MediaKind.CARTRIDGE, setOf("sms")),
        PlatformInfo("gamegear", "Sega Game Gear", "Sega - Game Gear", MediaKind.CARTRIDGE, setOf("gg")),
        PlatformInfo("segacd", "Sega CD", "Sega - Mega-CD - Sega CD", MediaKind.DISC, setOf("iso", "cue", "chd", "m3u")),
        PlatformInfo("saturn", "Sega Saturn", "Sega - Saturn", MediaKind.DISC, setOf("iso", "cue", "chd", "m3u")),
        PlatformInfo("dreamcast", "Sega Dreamcast", "Sega - Dreamcast", MediaKind.DISC, setOf("gdi", "cdi", "chd", "cue", "m3u")),
        PlatformInfo("psx", "PlayStation", "Sony - PlayStation", MediaKind.DISC, setOf("iso", "cue", "chd", "pbp", "m3u", "img")),
        PlatformInfo("ps2", "PlayStation 2", "Sony - PlayStation 2", MediaKind.DISC, setOf("iso", "chd", "cso", "bin")),
        PlatformInfo("psp", "PlayStation Portable", "Sony - PlayStation Portable", MediaKind.UMD, setOf("iso", "cso", "pbp")),
        PlatformInfo("atari2600", "Atari 2600", "Atari - 2600", MediaKind.CARTRIDGE, setOf("a26", "bin")),
        PlatformInfo("atari7800", "Atari 7800", "Atari - 7800", MediaKind.CARTRIDGE, setOf("a78")),
        PlatformInfo("lynx", "Atari Lynx", "Atari - Lynx", MediaKind.CARTRIDGE, setOf("lnx")),
        PlatformInfo("wonderswan", "WonderSwan", "Bandai - WonderSwan", MediaKind.CARTRIDGE, setOf("ws", "wsc")),
        PlatformInfo("ngp", "Neo Geo Pocket", "SNK - Neo Geo Pocket", MediaKind.CARTRIDGE, setOf("ngp", "ngc")),
        PlatformInfo("virtualboy", "Virtual Boy", "Nintendo - Virtual Boy", MediaKind.CARTRIDGE, setOf("vb")),
        PlatformInfo("pcengine", "PC Engine / TurboGrafx", "NEC - PC Engine - TurboGrafx 16", MediaKind.CARTRIDGE, setOf("pce", "sgx")),
        PlatformInfo("3do", "3DO", "The 3DO Company - 3DO", MediaKind.DISC, setOf("iso", "cue", "chd")),
        PlatformInfo("amiga", "Commodore Amiga", "Commodore - Amiga", MediaKind.DISC, setOf("adf", "ipf", "hdf")),
        PlatformInfo("c64", "Commodore 64", "Commodore - 64", MediaKind.GENERIC, setOf("d64", "t64", "prg", "crt")),
        PlatformInfo("arcade", "Arcade", "MAME", MediaKind.GENERIC, setOf("zip")),
    )

    private val bySlug: Map<String, PlatformInfo> = platforms.associateBy { it.slug }

    /** Common folder-name aliases seen in ROM collections. */
    private val aliases: Map<String, String> = mapOf(
        "megadrive" to "genesis", "md" to "genesis", "sega genesis" to "genesis",
        "smd" to "genesis", "gen" to "genesis",
        "super nintendo" to "snes", "super-nintendo" to "snes",
        "nintendo entertainment system" to "nes",
        "game boy advance" to "gba", "gameboy advance" to "gba",
        "game boy color" to "gbc", "gameboy color" to "gbc",
        "game boy" to "gb", "gameboy" to "gb",
        "nintendo ds" to "nds", "nintendo 3ds" to "n3ds", "3ds" to "n3ds",
        "nintendo 64" to "n64", "gamecube" to "gamecube", "gc" to "gamecube",
        "master system" to "mastersystem", "sms" to "mastersystem",
        "game gear" to "gamegear",
        "sega cd" to "segacd", "mega cd" to "segacd", "mega-cd" to "segacd",
        "playstation" to "psx", "playstation 1" to "psx",
        "playstation 2" to "ps2", "playstation portable" to "psp",
        "sega saturn" to "saturn", "sega dreamcast" to "dreamcast",
        "neogeo pocket" to "ngp", "neo geo pocket" to "ngp",
        "virtual boy" to "virtualboy", "turbografx" to "pcengine",
        "turbografx-16" to "pcengine", "pc engine" to "pcengine",
        "atari 2600" to "atari2600", "atari 7800" to "atari7800",
        "wonderswan color" to "wonderswan",
    )

    fun bySlug(slug: String): PlatformInfo? = bySlug[slug]

    /** Resolve a ROM folder name to a known platform, or null when unknown. */
    fun byFolderName(name: String): PlatformInfo? {
        val key = name.trim().lowercase()
        return bySlug[key] ?: aliases[key]?.let { bySlug[it] }
    }

    fun all(): List<PlatformInfo> = platforms
}
