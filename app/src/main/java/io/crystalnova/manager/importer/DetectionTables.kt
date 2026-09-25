package io.crystalnova.manager.importer

/**
 * Versioned platform-detection tables.
 *
 * Bump [DETECTION_TABLES_VERSION] whenever a table changes — the
 * version is persisted alongside scan results so a stale queue can be
 * re-detected after a table update.
 */
object DetectionTables {
    const val DETECTION_TABLES_VERSION = 1

    /**
     * Entry extension (lowercase, no dot) -> platform. These are
     * deterministic, format-owned extensions: finding one inside an
     * archive CONFIRMS the platform with no further evidence needed.
     */
    val directExtension: Map<String, PlatformId> = mapOf(
        // Cartridge / ROM families.
        "gba" to PlatformId.GBA,
        "gb" to PlatformId.GB,
        "gbc" to PlatformId.GBC,
        "nds" to PlatformId.NDS,
        "3ds" to PlatformId.N3DS,
        "cia" to PlatformId.N3DS,
        "n64" to PlatformId.N64,
        "z64" to PlatformId.N64,
        "v64" to PlatformId.N64,
        "sfc" to PlatformId.SNES,
        "smc" to PlatformId.SNES,
        "nes" to PlatformId.NES,
        "md" to PlatformId.GENESIS,
        "gen" to PlatformId.GENESIS,
        "smd" to PlatformId.GENESIS,
        // Dreamcast's GDI is a Dreamcast-only descriptor.
        "gdi" to PlatformId.DREAMCAST,
        // Nintendo optical: GameCube-owned containers.
        "gcm" to PlatformId.GAMECUBE,
        "gcz" to PlatformId.GAMECUBE,
        // Wii-owned containers.
        "wbfs" to PlatformId.WII,
        // Wii U-owned containers.
        "wud" to PlatformId.WIIU,
        "wux" to PlatformId.WIIU,
        "rpx" to PlatformId.WIIU,
    )

    /**
     * Extensions that need a second signal (header magic, serial,
     * structure, or filename alias) before they confirm anything.
     * Finding ONLY these still counts as "recognized as a game".
     */
    val ambiguousDiscExtension: Set<String> = setOf(
        "iso", "bin", "cue", "img", "chd",
        // Dolphin's modern container: GameCube OR Wii — the disc
        // header decides (see [WII_MAGIC]/[GAMECUBE_MAGIC]).
        "rvz",
        // PSP's compressed ISO; occasionally a PS2 CSO.
        "cso",
        // PSP eboot; PS1-on-PSP eboots exist too.
        "pbp",
        // Original Xbox ISO tooling.
        "xiso",
        // Loose executables from disc rips.
        "elf", "dol",
        // Dreamcast bootstrap / data track markers.
        "cdi",
    )

    /**
     * Extensions eligible as loose (non-archive) game files in
     * Downloads: everything the detector can confirm directly, plus
     * the ambiguous disc containers, plus `.m3u` (a multi-disc
     * descriptor whose discs get claimed at scan time). Anything
     * else — docs, images, `.sbi`, etc. — stays ignored exactly as
     * before.
     *
     * This set does not feed [PlatformDetector.detect], so
     * [DETECTION_TABLES_VERSION] is unchanged.
     */
    val looseFileExtension: Set<String> =
        directExtension.keys + ambiguousDiscExtension + "m3u"

    /**
     * Extensions that are never game content: docs, images, audio,
     * tools, patches. An archive containing ONLY these (and no other
     * signal) is NOT_RECOGNIZED_AS_GAME rather than UNKNOWN.
     */
    val nonGameExtension: Set<String> = setOf(
        "txt", "pdf", "md", "nfo", "jpg", "jpeg", "png", "gif", "bmp",
        "webp", "mp3", "wav", "ogg", "flac", "mp4", "mkv", "avi",
        "exe", "msi", "bat", "cmd", "sh", "ps1", "apk",
        "ips", "bps", "xdelta", "ppf",
        "sav", "srm", "state",
    )

    /**
     * GameCube disc magic (bytes C2 33 9F 3D) at disc offset 0x1C —
     * the start of the disc header's magic word, not offset 0.
     */
    val gamecubeMagic: ByteArray = byteArrayOf(
        0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D,
    )

    /** Disc offset of the GameCube/Wii magic word. */
    const val DISC_MAGIC_OFFSET: Long = 0x1C

    /**
     * Wii disc magic (bytes 5D 1C 9E A3), also at disc offset 0x1C.
     */
    val wiiMagic: ByteArray = byteArrayOf(
        0x5D, 0x1C, 0x9E.toByte(), 0xA3.toByte(),
    )

    /**
     * PlayStation serial ranges. The letter prefix alone does NOT
     * decide the generation — the numeric range does:
     *   PS1: SLUS-0xxxx/1xxxx, SCUS-94xxx, SLES/SCES-0..4xxxx,
     *        SLPS-0/1xxxxx, SLPM-85xxx+
     *   PS2: SLUS-2xxxx, SCUS-97xxx, SLES/SCES-5xxxx, SLPS-2xxxxx,
     *        SLPM-6xxxx
     * PSP serials use their own prefixes (ULUS/ULES/...) and never
     * collide with the S-prefix ranges.
     */
    val ps1Serial: Regex = Regex(
        "(?i)\\b(?:SLUS-0\\d{4}|SLUS-1\\d{4}|SCUS-94\\d{3}|" +
            "SLES-[0-4]\\d{4}|SCES-[0-4]\\d{4}|" +
            "SLPS-[01]\\d{4}|SLPM-8[5-9]\\d{3})\\b",
    )
    val ps2Serial: Regex = Regex(
        "(?i)\\b(?:SLUS-2\\d{4}|SCUS-97\\d{3}|" +
            "SLES-5\\d{4}|SCES-5\\d{4}|" +
            "SLPS-2\\d{4}|SLPM-6\\d{4})\\b",
    )
    val pspSerial: Regex = Regex(
        "(?i)\\b(?:ULUS|ULES|ULJM|ULJS|UCUS|UCKS|NPJH|NPHG|ULKS)-\\d{5}\\b",
    )

    /**
     * Disc-structure markers (matched case-insensitively against entry
     * paths). Ordered strongest-first.
     */
    val structureSignals: List<StructureSignal> = listOf(
        StructureSignal("1st_read.bin", PlatformId.DREAMCAST, Confidence.CONFIRMED),
        StructureSignal("ip.bin", PlatformId.DREAMCAST, Confidence.CONFIRMED),
        StructureSignal("umd_data.bin", PlatformId.PSP, Confidence.CONFIRMED),
        StructureSignal("psp_game/", PlatformId.PSP, Confidence.CONFIRMED),
        StructureSignal("eboot.pbp", PlatformId.PSP, Confidence.LIKELY),
    )

    data class StructureSignal(
        val marker: String,
        val platform: PlatformId,
        val confidence: Confidence,
    )

    /**
     * Filename aliases — the WEAKEST tier, consulted only when no
     * extension, header, structure, or serial signal fired. Each is a
     * (regex, platform) pair; the first match in list order wins, so
     * overlapping names (GBA/GBC before GB, PS2 before PSX, Wii U
     * before Wii) are ordered deliberately.
     */
    val filenameAliases: List<AliasSignal> = listOf(
        AliasSignal(Regex("(?i)\\bgba\\b|game[ _-]?boy[ _-]?advance"), PlatformId.GBA),
        AliasSignal(Regex("(?i)\\bgbc\\b|game[ _-]?boy[ _-]?color"), PlatformId.GBC),
        AliasSignal(Regex("(?i)\\bgb\\b|game[ _-]?boy\\b"), PlatformId.GB),
        AliasSignal(Regex("(?i)\\b3ds\\b"), PlatformId.N3DS),
        AliasSignal(Regex("(?i)\\bnds\\b|\\bds\\b|nintendo[ _-]?ds"), PlatformId.NDS),
        AliasSignal(Regex("(?i)\\bn64\\b|nintendo[ _-]?64"), PlatformId.N64),
        AliasSignal(Regex("(?i)\\bsnes\\b|super[ _-]?nintendo"), PlatformId.SNES),
        AliasSignal(Regex("(?i)\\bnes\\b"), PlatformId.NES),
        AliasSignal(Regex("(?i)mega[ _-]?drive|\\bgenesis\\b|\\bgen\\b|\\bmd\\b"), PlatformId.GENESIS),
        AliasSignal(Regex("(?i)\\bgcn?\\b|gamecube"), PlatformId.GAMECUBE),
        AliasSignal(Regex("(?i)wii[ _-]?u|\\bwiiu\\b"), PlatformId.WIIU),
        AliasSignal(Regex("(?i)\\bwii\\b"), PlatformId.WII),
        AliasSignal(Regex("(?i)\\bdc\\b|dreamcast"), PlatformId.DREAMCAST),
        AliasSignal(Regex("(?i)\\bps2\\b|playstation[ _-]?2"), PlatformId.PS2),
        AliasSignal(Regex("(?i)\\bpsp\\b"), PlatformId.PSP),
        AliasSignal(Regex("(?i)\\bpsx\\b|\\bps1\\b|playstation\\b"), PlatformId.PSX),
        AliasSignal(Regex("(?i)\\bxbox\\b"), PlatformId.XBOX),
    )

    data class AliasSignal(
        val pattern: Regex,
        val platform: PlatformId,
    )
}
