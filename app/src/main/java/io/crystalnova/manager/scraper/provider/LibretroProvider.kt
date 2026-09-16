package io.crystalnova.manager.scraper.provider

import io.crystalnova.manager.scraper.match.PlatformTable
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Region
import java.net.URLEncoder

/**
 * Libretro thumbnails — the U2 keyless artwork provider.
 *
 * Serves BOX_FRONT (Named_Boxarts), SCREENSHOT (Named_Snaps) and
 * CLEAR_LOGO (Named_Logos). It has no back/spine/disc scans, so those
 * slots always fall through to Crystal's generated fallbacks.
 *
 * Fetch order per candidate: the thumbnail CDN first
 * (thumbnails.libretro.com), then raw.githubusercontent.com.
 * Never touches api.github.com (rate-limited).
 */
class LibretroProvider : ArtworkProvider {
    override val id: String = "libretro"
    override val displayName: String = "Libretro Thumbnails"
    override val requiresApiKey: Boolean = false

    override suspend fun artworkFor(query: ScrapeQuery): Map<AssetSlot, List<ArtworkCandidate>> {
        val system = PlatformTable.bySlug(query.platformSlug)?.libretroName
            ?: return emptyMap()
        val names = candidateNames(query.fileName)
        val result = mutableMapOf<AssetSlot, List<ArtworkCandidate>>()
        result[AssetSlot.BOX_FRONT] = names.flatMap { urls(system, "Named_Boxarts", it) }
        result[AssetSlot.SCREENSHOT] = names.flatMap { urls(system, "Named_Snaps", it) }
        result[AssetSlot.CLEAR_LOGO] = names.flatMap { urls(system, "Named_Logos", it) }
        return result
    }

    /**
     * Candidate-name ladder, mirroring RetroArch's flexible matching:
     * modernized region tag, as-is, sanitized, article-moved, short name.
     */
    fun candidateNames(fileName: String): List<String> {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.')
        val ladder = linkedSetOf<String>()
        ladder += modernizeRegionTag(base)
        ladder += base
        ladder += sanitize(base)
        ladder += sanitize(modernizeRegionTag(base))
        moveArticle(base)?.let { ladder += it }
        // Short name: drop everything from the first " (" — region-agnostic.
        val short = base.substringBefore(" (").trim()
        if (short.isNotEmpty() && short != base) {
            ladder += short
            ladder += sanitize(short)
        }
        return ladder.toList()
    }

    /** "(E)" -> "(Europe)", "(U)" -> "(USA)", "(J)" -> "(Japan)" for DAT-style names. */
    private fun modernizeRegionTag(name: String): String =
        name.replace(Regex("""\(E\)"""), "(Europe)")
            .replace(Regex("""\(U\)"""), "(USA)")
            .replace(Regex("""\(J\)"""), "(Japan)")
            .replace(Regex("""\(W\)"""), "(World)")

    /** Libretro filename rule: & * / : < > ? \ | " become _. */
    private fun sanitize(name: String): String =
        name.replace(Regex("""[&*/:<>?\\|"]"""), "_")

    private fun moveArticle(name: String): String? {
        val m = Regex("^(.*),\\s*(The|A|An)(\\s*\\(.*\\))?$").find(name) ?: return null
        return "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}"
    }

    private fun urls(system: String, kind: String, name: String): List<ArtworkCandidate> {
        val sys = URLEncoder.encode(system, "UTF-8").replace("+", "%20")
        val file = URLEncoder.encode("$name.png", "UTF-8").replace("+", "%20")
        // Repo name: playlist name with " - " -> "_-_" and spaces -> "_".
        val repo = system.replace(" - ", "_-_").replace(' ', '_')
        return listOf(
            ArtworkCandidate("https://thumbnails.libretro.com/$sys/$kind/$file"),
            ArtworkCandidate("https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/$kind/$file"),
        )
    }

    /** Region preference for artwork: detected > Europe > World > USA > other. */
    fun preferredRegions(detected: Region): List<Region> {
        val ordered = listOf(Region.EUROPE, Region.WORLD, Region.USA, Region.JAPAN, Region.ASIA)
        return (listOf(detected) + ordered).distinct().filter { it != Region.UNKNOWN }
    }
}
