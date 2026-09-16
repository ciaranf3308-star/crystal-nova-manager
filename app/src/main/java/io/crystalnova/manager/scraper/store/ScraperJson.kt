package io.crystalnova.manager.scraper.store

import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.MatchConfidence
import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.model.ScrapedGame
import io.crystalnova.manager.scraper.model.SourceType
import org.json.JSONObject

/**
 * JSON serialization for manifests and the global index. Pure org.json —
 * unit-testable on the JVM. All local paths are relative to
 * crystal-nova-data/.
 */
object ScraperJson {
    const val MANIFEST_VERSION = 1

    fun manifestToJson(game: ScrapedGame): String {
        val root = JSONObject()
        root.put("version", MANIFEST_VERSION)
        root.put("platform", game.platform)
        root.put("gameId", game.gameId)
        root.put("romRelativePath", game.romRelativePath)
        root.put("fileSize", game.fileSize)
        root.put("lastModified", game.lastModified)
        root.put("title", game.title)
        root.put("description", game.description)
        root.put("developer", game.developer)
        root.put("publisher", game.publisher)
        root.put("releaseYear", game.releaseYear)
        root.put("genre", game.genre)
        root.put("players", game.players)
        root.put("region", game.region.name)
        root.put("provider", game.provider)
        root.put("providerGameId", game.providerGameId)
        root.put("confidence", confidenceToString(game.confidence))
        val assets = JSONObject()
        for ((slot, prov) in game.assets) {
            val o = JSONObject()
            o.put("sourceType", prov.sourceType.name)
            o.put("provider", prov.provider)
            o.put("providerGameId", prov.providerGameId)
            o.put("region", prov.region?.name)
            o.put("sourceUrl", prov.sourceUrl)
            o.put("localPath", prov.localPath)
            o.put("sha256", prov.sha256)
            o.put("generatedBy", prov.generatedBy)
            assets.put(slot.name, o)
        }
        root.put("assets", assets)
        return root.toString(2)
    }

    fun manifestFromJson(json: String): ScrapedGame {
        val root = JSONObject(json)
        val assets = mutableMapOf<AssetSlot, AssetProvenance>()
        val ao = root.optJSONObject("assets")
        if (ao != null) {
            for (key in ao.keys()) {
                val slot = AssetSlot.valueOf(key)
                val o = ao.getJSONObject(key)
                assets[slot] = AssetProvenance(
                    sourceType = SourceType.valueOf(o.getString("sourceType")),
                    provider = o.getString("provider"),
                    providerGameId = o.optString("providerGameId").ifEmpty { null },
                    region = o.optString("region").ifEmpty { null }?.let { Region.valueOf(it) },
                    sourceUrl = o.optString("sourceUrl").ifEmpty { null },
                    localPath = o.getString("localPath"),
                    sha256 = o.optString("sha256").ifEmpty { null },
                    generatedBy = o.optString("generatedBy").ifEmpty { null },
                )
            }
        }
        return ScrapedGame(
            platform = root.getString("platform"),
            gameId = root.getString("gameId"),
            romRelativePath = root.getString("romRelativePath"),
            fileSize = root.optLong("fileSize", -1),
            lastModified = root.optLong("lastModified", -1),
            title = root.getString("title"),
            description = root.optString("description").ifEmpty { null },
            developer = root.optString("developer").ifEmpty { null },
            publisher = root.optString("publisher").ifEmpty { null },
            releaseYear = root.optString("releaseYear").ifEmpty { null },
            genre = root.optString("genre").ifEmpty { null },
            players = root.optString("players").ifEmpty { null },
            region = Region.valueOf(root.optString("region", Region.UNKNOWN.name)),
            provider = root.optString("provider").ifEmpty { null },
            providerGameId = root.optString("providerGameId").ifEmpty { null },
            confidence = confidenceFromString(root.optString("confidence", "")),
            assets = assets,
        )
    }

    private fun confidenceToString(c: MatchConfidence): String = when (c) {
        is MatchConfidence.Stored -> "STORED"
        is MatchConfidence.ExactTitle -> "EXACT_TITLE"
        is MatchConfidence.Fuzzy -> "FUZZY:${c.score}"
        is MatchConfidence.Manual -> "MANUAL"
        is MatchConfidence.None -> "NONE"
    }

    private fun confidenceFromString(s: String): MatchConfidence = when {
        s == "STORED" -> MatchConfidence.Stored
        s == "EXACT_TITLE" -> MatchConfidence.ExactTitle
        s.startsWith("FUZZY:") -> MatchConfidence.Fuzzy(s.removePrefix("FUZZY:").toDoubleOrNull() ?: 0.0)
        s == "MANUAL" -> MatchConfidence.Manual
        else -> MatchConfidence.None
    }

    /** Compact per-game summary for index.json (dashboard reads this only). */
    fun indexEntryToJson(game: ScrapedGame): JSONObject {
        val o = JSONObject()
        o.put("title", game.title)
        o.put("platform", game.platform)
        o.put("romRelativePath", game.romRelativePath)
        o.put("fileSize", game.fileSize)
        o.put("lastModified", game.lastModified)
        o.put("completeness", game.completeness.name)
        o.put("real", game.realAssetCount)
        o.put("generated", game.generatedAssetCount)
        o.put("region", game.region.name)
        o.put("provider", game.provider)
        return o
    }
}
