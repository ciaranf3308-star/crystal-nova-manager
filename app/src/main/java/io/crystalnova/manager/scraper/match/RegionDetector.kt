package io.crystalnova.manager.scraper.match

import io.crystalnova.manager.scraper.model.Region

/**
 * ROM region detection, v1 scope:
 * - filename tags (modern No-Intro + legacy aliases) — baseline
 * - cheap fixed-offset header reads for small cartridge formats
 *   (SNES, N64, GB/GBC, GBA, Genesis, DS, 3DS)
 * - NO ISO/CHD parsing, no SYSTEM.CNF / PARAM.SFO — later enrichment pass
 * - UNKNOWN is a valid result; regions are never invented
 *
 * Priority: validated header field > modern filename tag > legacy alias.
 * (Sega Retro warns headers are aspirational; filename tags are the
 * pragmatic tiebreak only when no header field validates.)
 */
class RegionDetector {

    /**
     * Reads [length] bytes at [offset] from the ROM, or null when the
     * file is shorter / unreadable. Implemented by the caller (SAF stream
     * on device, synthetic bytes in tests).
     */
    fun interface HeaderReader {
        fun read(offset: Int, length: Int): ByteArray?
    }

    fun detect(platformSlug: String, fileName: String, reader: HeaderReader): Region {
        val fromHeader = fromHeader(platformSlug, reader)
        if (fromHeader != Region.UNKNOWN) return fromHeader
        return fromFileName(fileName)
    }

    // ------------------------------------------------------------------
    // Filename tags
    // ------------------------------------------------------------------

    fun fromFileName(fileName: String): Region {
        val tags = Regex("""[\(\[]([^)\]]+)[\)\]]""")
            .findAll(fileName)
            .map { it.groupValues[1].trim().lowercase() }
            .toList()
        // Modern multi-region tags first: prefer Europe > World > USA > other.
        for (tag in tags) {
            modernTag(tag)?.let { if (it != Region.UNKNOWN) return it }
        }
        for (tag in tags) {
            legacyTag(tag)?.let { if (it != Region.UNKNOWN) return it }
        }
        return Region.UNKNOWN
    }

    private fun modernTag(tag: String): Region? {
        // Language lists like "en,fr,de" are NOT regions.
        if ("," in tag && !tag.contains("usa") && !tag.contains("europe")
            && !tag.contains("japan") && !tag.contains("world")
            && !tag.contains("asia") && !tag.contains("korea")
            && !tag.contains("australia")
        ) return null
        val parts = tag.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val regions = parts.mapNotNull {
            when (it) {
                "europe" -> Region.EUROPE
                "world" -> Region.WORLD
                "usa" -> Region.USA
                "japan" -> Region.JAPAN
                "asia" -> Region.ASIA
                "korea" -> Region.KOREA
                "australia" -> Region.AUSTRALIA
                else -> null
            }
        }.toSet()
        if (regions.isEmpty()) return null
        // Preference: Europe > World > USA > Japan > rest.
        return listOf(
            Region.EUROPE, Region.WORLD, Region.USA,
            Region.JAPAN, Region.ASIA, Region.KOREA, Region.AUSTRALIA,
        ).firstOrNull { it in regions } ?: Region.UNKNOWN
    }

    private fun legacyTag(tag: String): Region? = when (tag) {
        "e" -> Region.EUROPE
        "u" -> Region.USA
        "j" -> Region.JAPAN
        "w" -> Region.WORLD
        "a" -> Region.AUSTRALIA
        "k" -> Region.KOREA
        // b (Brazil), unl/pd (unlicensed/public domain): not regions.
        else -> null
    }

    // ------------------------------------------------------------------
    // Cartridge header fields (fixed offsets, small reads)
    // ------------------------------------------------------------------

    fun fromHeader(platformSlug: String, reader: HeaderReader): Region = when (platformSlug) {
        "snes" -> snesRegion(reader)
        "n64" -> n64Region(reader)
        "gb", "gbc" -> gbRegion(reader)
        "gba", "nds" -> gameCodeRegion(reader, if (platformSlug == "nds") 0x0C else 0xAC)
        "genesis" -> genesisRegion(reader)
        "n3ds" -> n3dsRegion(reader)
        else -> Region.UNKNOWN
    }

    private fun u16le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** SNES destination code at header+0x29; header at 0x7FC0 (LoROM) or 0xFFC0 (HiROM). */
    private fun snesRegion(reader: HeaderReader): Region {
        val head = reader.read(0, 0x10040) ?: return Region.UNKNOWN
        // 512-byte copier header shifts everything.
        val shift = if (head.size % 1024 == 512) 512 else 0
        for (base in intArrayOf(0x7FC0, 0xFFC0)) {
            val h = base + shift
            if (h + 0x30 > head.size) continue
            // Validate via checksum/complement pair.
            val checksum = u16le(head, h + 0x2E)
            val complement = u16le(head, h + 0x2C)
            if ((checksum xor complement) != 0xFFFF) continue
            return when (head[h + 0x29].toInt() and 0xFF) {
                0x00 -> Region.JAPAN
                0x01, 0x0F -> Region.USA
                0x02, 0x03, 0x06, 0x07, 0x08, 0x09, 0x0A -> Region.EUROPE
                0x0B -> Region.ASIA
                0x0D -> Region.KOREA
                0x0E -> Region.WORLD
                0x11 -> Region.AUSTRALIA
                else -> Region.UNKNOWN
            }
        }
        return Region.UNKNOWN
    }

    /** N64 country byte at 0x3E after normalizing v64/n64 byte order to z64. */
    private fun n64Region(reader: HeaderReader): Region {
        val raw = reader.read(0, 0x40) ?: return Region.UNKNOWN
        if (raw.size < 0x40) return Region.UNKNOWN
        val magic = (raw[0].toLong() and 0xFF shl 24) or
            (raw[1].toLong() and 0xFF shl 16) or
            (raw[2].toLong() and 0xFF shl 8) or
            (raw[3].toLong() and 0xFF)
        val be = ByteArray(0x40)
        when (magic) {
            0x80371240L -> raw.copyInto(be) // z64 already big-endian
            0x37804012L -> for (i in 0 until 0x40 step 2) { // v64 byteswapped
                be[i] = raw[i + 1]; be[i + 1] = raw[i]
            }
            0x40123780L -> for (i in 0 until 0x40 step 4) { // n64 little-endian
                be[i] = raw[i + 3]; be[i + 1] = raw[i + 2]
                be[i + 2] = raw[i + 1]; be[i + 3] = raw[i]
            }
            else -> return Region.UNKNOWN
        }
        return when (be[0x3E].toInt().toChar()) {
            'J' -> Region.JAPAN
            'E', 'N' -> Region.USA
            'P', 'D', 'F', 'I', 'S', 'W', 'X', 'Y' -> Region.EUROPE
            'U' -> Region.AUSTRALIA
            'C' -> Region.ASIA
            'K' -> Region.KOREA
            else -> Region.UNKNOWN
        }
    }

    /** GB/GBC destination at 0x14A: Japan vs rest-of-world only. */
    private fun gbRegion(reader: HeaderReader): Region {
        val head = reader.read(0, 0x200) ?: return Region.UNKNOWN
        if (head.size < 0x14B) return Region.UNKNOWN
        return when (head[0x14A].toInt() and 0xFF) {
            0x00 -> Region.JAPAN
            else -> Region.UNKNOWN // 0x01 = non-Japanese; can't split USA/Europe
        }
    }

    /** GBA/DS 4th game-code character = territory suffix. */
    private fun gameCodeRegion(reader: HeaderReader, codeOffset: Int): Region {
        val head = reader.read(0, codeOffset + 4) ?: return Region.UNKNOWN
        if (head.size < codeOffset + 4) return Region.UNKNOWN
        // GBA fixed byte 0x96 at 0xB2 validates the header.
        if (codeOffset == 0xAC) {
            val fixed = reader.read(0xB2, 1) ?: return Region.UNKNOWN
            if (fixed[0] != 0x96.toByte()) return Region.UNKNOWN
        }
        return when (head[codeOffset + 3].toInt().toChar().uppercaseChar()) {
            'J' -> Region.JAPAN
            'E' -> Region.USA
            'P', 'D', 'F', 'I', 'S', 'H', 'R' -> Region.EUROPE
            'U' -> Region.AUSTRALIA
            'K' -> Region.KOREA
            'C', 'T' -> Region.ASIA
            else -> Region.UNKNOWN
        }
    }

    /** Genesis region at 0x1F0: pre-1994 text or 1994 hex bitmask. */
    private fun genesisRegion(reader: HeaderReader): Region {
        val head = reader.read(0, 0x200) ?: return Region.UNKNOWN
        if (head.size < 0x200) return Region.UNKNOWN
        val id = head.slice(0x100 until 0x104).map { it.toInt().toChar() }.joinToString("")
        if (!id.startsWith("SEGA")) return Region.UNKNOWN
        val r = head[0x1F0].toInt() and 0xFF
        if (r in 0x00..0x0F) {
            // 1994 bitmask scheme.
            val regions = mutableSetOf<Region>()
            if (r and 0x01 != 0) regions += Region.JAPAN
            if (r and 0x02 != 0) regions += Region.ASIA
            if (r and 0x04 != 0) regions += Region.USA
            if (r and 0x08 != 0) regions += Region.EUROPE
            return if (regions.size == 1) regions.first() else Region.WORLD
        }
        val c = r.toChar().uppercaseChar()
        return when (c) {
            'J' -> Region.JAPAN
            'U' -> Region.USA
            'E' -> Region.EUROPE
            else -> Region.UNKNOWN
        }
    }

    /** 3DS product code via NCSD partition table; last char = region. */
    private fun n3dsRegion(reader: HeaderReader): Region {
        val ncsd = reader.read(0, 0x200) ?: return Region.UNKNOWN
        if (ncsd.size < 0x200) return Region.UNKNOWN
        val magic = ncsd.slice(0x100 until 0x104).map { it.toInt().toChar() }.joinToString("")
        var ncchOffset = -1
        if (magic == "NCSD") {
            val mediaUnits = u32le(ncsd, 0x120)
            if (mediaUnits > 0) ncchOffset = mediaUnits * 0x200
        }
        val codeOffset = if (ncchOffset > 0) ncchOffset + 0x150 else 0x1150
        val code = reader.read(codeOffset, 16) ?: return Region.UNKNOWN
        if (code.size < 16) return Region.UNKNOWN
        val last = code[15].toInt().toChar().uppercaseChar()
        if (!last.isLetter()) return Region.UNKNOWN
        return when (last) {
            'J' -> Region.JAPAN
            'E' -> Region.USA
            'P' -> Region.EUROPE
            'K' -> Region.KOREA
            'C', 'T' -> Region.ASIA
            else -> Region.UNKNOWN
        }
    }

    private fun u32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
