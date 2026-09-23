package io.crystalnova.manager.importer

/**
 * Pure platform detection: archive signals in, [Detection] out.
 *
 * Signal tiers, strongest first:
 *   1. Direct ROM extensions (`.gba` -> GBA) — CONFIRMED.
 *   2. Disc-header magic (GameCube/Wii magic word at disc offset
 *      0x1C) — CONFIRMED.
 *   3. Disc structures (1ST_READ.BIN, PSP_GAME/) and PlayStation
 *      serial ranges (SLUS-20554 -> PS2) — CONFIRMED, or LIKELY for
 *      the weak structural hints (EBOOT.PBP).
 *   4. Filename aliases ("(PS2)", "[GC]") — LIKELY, fallback only.
 *
 * No I/O happens here: the caller supplies the entry list and a
 * [readHeader] function for the targeted header reads, which keeps
 * this unit-testable on the JVM. Deterministic: the same signals
 * always produce the same detection.
 */
class PlatformDetector {

    /**
     * Reads up to [length] bytes starting at [offset] from the entry
     * at [entryIndex] (indices into [entries]). Returns null when the
     * bytes cannot be read — a failed header read simply yields no
     * header signal, never an error.
     */
    fun interface HeaderReader {
        fun read(entryIndex: Int, offset: Long, length: Int): ByteArray?
    }

    fun detect(
        archiveName: String,
        entries: List<ArchiveEntryInfo>,
        readHeader: HeaderReader,
    ): Detection {
        val files = entries.filter { !it.isDirectory }
        if (files.isEmpty()) {
            return Detection(
                platform = null,
                confidence = Confidence.UNKNOWN,
                recognizedAsGame = false,
            )
        }

        // ---- Tier 1: direct ROM extensions (strongest). ----
        val extSignals = files.mapNotNull { entry ->
            val ext = entry.path.substringAfterLast('.', "").lowercase()
            val platform = DetectionTables.directExtension[ext] ?: return@mapNotNull null
            Signal(platform, Confidence.CONFIRMED, entryName(entry.path), entry.size)
        }

        // ---- Tier 2: targeted header reads for disc images. ----
        // Only entries that could plausibly be raw disc images pay for
        // a header read; bounded to the first few candidates so a huge
        // archive does not do hundreds of seeks. The GameCube/Wii
        // magic word sits at disc offset 0x1C, so the read targets
        // exactly those 4 bytes.
        val headerSignals = mutableListOf<Signal>()
        files
            .mapIndexed { index, entry -> index to entry }
            .filter { (_, entry) ->
                val ext = entry.path.substringAfterLast('.', "").lowercase()
                ext in setOf("iso", "rvz", "gcm", "bin", "img")
            }
            .take(4)
            .forEach { (index, entry) ->
                val head = readHeader.read(index, DetectionTables.DISC_MAGIC_OFFSET, 4)
                    ?: return@forEach
                when {
                    head.startsWith(DetectionTables.gamecubeMagic) ->
                        headerSignals += Signal(
                            PlatformId.GAMECUBE, Confidence.CONFIRMED,
                            "${entryName(entry.path)} (disc header)", entry.size,
                        )
                    head.startsWith(DetectionTables.wiiMagic) ->
                        headerSignals += Signal(
                            PlatformId.WII, Confidence.CONFIRMED,
                            "${entryName(entry.path)} (disc header)", entry.size,
                        )
                }
            }

        // ---- Tier 3: disc structures + PlayStation serials. ----
        val structureSignals = mutableListOf<Signal>()
        val serialSignals = mutableListOf<Signal>()
        val haystacks = files.map { it.path } + archiveName
        for (entry in files) {
            val lower = entry.path.lowercase()
            for (s in DetectionTables.structureSignals) {
                if (lower.contains(s.marker)) {
                    structureSignals += Signal(
                        s.platform, s.confidence,
                        entryName(entry.path), entry.size,
                    )
                    break
                }
            }
        }
        for (hay in haystacks) {
            val serial = findSerial(hay) ?: continue
            serialSignals += Signal(
                serial.first, Confidence.CONFIRMED, serial.second, 0L,
            )
            break
        }

        // ---- Tier 4: filename aliases (weakest, fallback only). ----
        val aliasSignals = mutableListOf<Signal>()
        if (extSignals.isEmpty() && headerSignals.isEmpty() &&
            structureSignals.isEmpty() && serialSignals.isEmpty()
        ) {
            val aliasHay = (listOf(archiveName) + files.map { it.path })
                .joinToString(" ")
            for (alias in DetectionTables.filenameAliases) {
                if (alias.pattern.containsMatchIn(aliasHay)) {
                    aliasSignals += Signal(
                        alias.platform, Confidence.LIKELY,
                        "name: ${alias.pattern.pattern.take(24)}…", 0L,
                    )
                    break
                }
            }
        }

        val confirmed = extSignals + headerSignals +
            structureSignals.filter { it.confidence == Confidence.CONFIRMED } +
            serialSignals
        val likely = structureSignals.filter { it.confidence == Confidence.LIKELY } +
            aliasSignals

        val winner = pickWinner(confirmed) ?: pickWinner(likely)
        if (winner != null) {
            return Detection(
                platform = winner.platform,
                confidence = winner.confidence,
                signals = (confirmed + likely)
                    .filter { it.platform == winner.platform }
                    .map { it.evidence }
                    .distinct(),
                recognizedAsGame = true,
            )
        }

        // No platform signal: is it at least game-shaped (ambiguous
        // disc containers), or plainly not a game (docs/tools)?
        val ambiguous = files.any { entry ->
            entry.path.substringAfterLast('.', "").lowercase() in
                DetectionTables.ambiguousDiscExtension
        }
        val allNonGame = files.isNotEmpty() && files.all { entry ->
            entry.path.substringAfterLast('.', "").lowercase() in
                DetectionTables.nonGameExtension
        }
        return Detection(
            platform = null,
            confidence = Confidence.UNKNOWN,
            recognizedAsGame = ambiguous || !allNonGame,
        )
    }

    private data class Signal(
        val platform: PlatformId,
        val confidence: Confidence,
        val evidence: String,
        /** Entry size; used to break multi-platform ties. */
        val size: Long,
    )

    /**
     * One winner per tier: a single distinct platform wins outright;
     * conflicting signals (e.g. `.gba` and `.nes` in one archive) go
     * to the platform owning the largest file — deterministic.
     */
    private fun pickWinner(signals: List<Signal>): Signal? {
        if (signals.isEmpty()) return null
        val byPlatform = signals.groupBy { it.platform }
        if (byPlatform.size == 1) return signals.first()
        return byPlatform.maxByOrNull { (_, list) -> list.sumOf { it.size } }
            ?.value?.first()
    }

    private fun findSerial(hay: String): Pair<PlatformId, String>? {
        DetectionTables.ps2Serial.find(hay)?.let {
            return PlatformId.PS2 to it.value.uppercase()
        }
        DetectionTables.ps1Serial.find(hay)?.let {
            return PlatformId.PSX to it.value.uppercase()
        }
        DetectionTables.pspSerial.find(hay)?.let {
            return PlatformId.PSP to it.value.uppercase()
        }
        return null
    }

    private fun entryName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
