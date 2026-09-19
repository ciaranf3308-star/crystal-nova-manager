package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.scraper.esde.EsdeImport
import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.SourceType

/**
 * Game artwork studio: pick any game in the library, see exactly which
 * artwork slots are filled and by what, then per slot either pick your
 * own image, clear the slot, or match an unmatched ES-DE media group.
 *
 * User-picked images are saved with USER provenance: they always win and
 * imports never overwrite them.
 *
 * Controller-friendly wizard:
 *  1. Pick a platform (chip row)
 *  2. Pick a game (full library, not just unmatched)
 *  3. Per slot: PICK IMAGE / CLEAR; or MATCH an ES-DE media group
 */
@Composable
fun EsdeManualMatchScreen(
    importPlan: EsdeImport.ImportPlan?,
    manualMatchResult: String?,
    selectedGame: EsdeImport.RomGame?,
    artworkSlots: Map<AssetSlot, AssetProvenance?>?,
    artworkResult: String?,
    onSelectGame: (EsdeImport.RomGame?) -> Unit,
    onApplyMatch: (game: EsdeImport.RomGame, media: EsdeImport.UnmatchedMediaGroup) -> Unit,
    onPickImage: (platform: String, gameId: String, slot: AssetSlot) -> Unit,
    onClearSlot: (platform: String, gameId: String, slot: AssetSlot) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedPlatform by remember { mutableStateOf<String?>(null) }

    val allGames = importPlan?.games ?: emptyList()
    val unmatchedMedia = importPlan?.unmatchedMediaGroups ?: emptyList()

    // Every platform in the library, sorted — not just unmatched ones.
    val platforms = remember(allGames) {
        allGames.map { it.platform }.distinct().sorted()
    }

    // Auto-select the first platform if none selected.
    if (selectedPlatform == null && platforms.isNotEmpty()) {
        selectedPlatform = platforms.first()
    }

    val gamesForPlatform = remember(allGames, selectedPlatform) {
        allGames.filter { it.platform == selectedPlatform }.sortedBy { it.title.lowercase() }
    }
    val mediaForPlatform = remember(unmatchedMedia, selectedPlatform) {
        unmatchedMedia.filter { it.platform == selectedPlatform }
    }

    ScreenScaffold(
        routeKey = "settings-esde-manual-match",
        title = "GAME ARTWORK",
        onBack = {
            if (selectedGame != null) onSelectGame(null) else onBack()
        },
        modifier = modifier,
        fallbackFocusKey = "esde-manual-match-back",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            if (importPlan == null) {
                section {
                    DimLine("RUN PRE-SCAN FIRST TO LOAD YOUR LIBRARY.")
                }
                return@ControllerList
            }

            if (selectedGame == null) {
                section {
                    StatusLine("STEP 1: PICK A CONSOLE (${platforms.size})")
                }
                platforms.forEach { platform ->
                    val count = allGames.count { it.platform == platform }
                    control(
                        key = "esde-manual-platform-$platform",
                        testTag = "esde-manual-platform-$platform",
                        label = if (platform == selectedPlatform) "▶ $platform ($count)" else "$platform ($count)",
                        onClick = { selectedPlatform = platform },
                    )
                }

                section {
                    StatusLine("STEP 2: PICK A GAME (${gamesForPlatform.size})")
                }
                if (gamesForPlatform.isEmpty()) {
                    section {
                        DimLine("NO GAMES FOR THIS CONSOLE.")
                    }
                } else {
                    gamesForPlatform.forEach { game ->
                        control(
                            key = "esde-manual-game-${game.platform}-${game.gameId}",
                            testTag = "esde-manual-game-${game.gameId}",
                            label = game.title,
                            onClick = { onSelectGame(game) },
                        )
                    }
                }
            } else {
                val game = selectedGame
                val slots = artworkSlots
                val filled = slots?.values?.count { it != null } ?: 0
                section {
                    StatusLine("GAME: ${game.title}")
                    DimLine(
                        if (slots == null) "LOADING ARTWORK…"
                        else "ARTWORK: $filled / ${AssetSlot.values().size} SLOTS FILLED"
                    )
                }

                if (slots != null) {
                    AssetSlot.values().forEach { slot ->
                        val prov = slots[slot]
                        val status = when {
                            prov == null -> "EMPTY"
                            prov.sourceType == SourceType.USER -> "SET — YOURS"
                            prov.sourceType == SourceType.GENERATED -> "SET — GENERATED"
                            else -> "SET — IMPORTED"
                        }
                        control(
                            key = "esde-art-pick-${game.gameId}-${slot.name}",
                            testTag = "esde-art-pick-${game.gameId}-${slot.name}",
                            label = "▸ ${slotLabel(slot)}: $status — PICK IMAGE…",
                            onClick = { onPickImage(game.platform, game.gameId, slot) },
                        )
                        if (prov != null) {
                            control(
                                key = "esde-art-clear-${game.gameId}-${slot.name}",
                                testTag = "esde-art-clear-${game.gameId}-${slot.name}",
                                label = "  CLEAR ${slotLabel(slot)}",
                                onClick = { onClearSlot(game.platform, game.gameId, slot) },
                            )
                        }
                    }
                }

                if (mediaForPlatform.isNotEmpty()) {
                    section {
                        StatusLine("MATCH ES-DE MEDIA (${mediaForPlatform.size} UNMATCHED GROUPS)")
                        DimLine("APPLIES THE WHOLE GROUP AT ONCE")
                    }
                    mediaForPlatform.forEach { mediaGroup ->
                        control(
                            key = "esde-manual-media-${mediaGroup.key}",
                            testTag = "esde-manual-media-${mediaGroup.key}",
                            label = "${mediaGroup.displayName} (${mediaGroup.files.size} files)",
                            onClick = { onApplyMatch(game, mediaGroup) },
                        )
                    }
                }

                control(
                    key = "esde-manual-back-to-games",
                    testTag = "esde-manual-back-to-games",
                    label = "← BACK TO GAMES",
                    onClick = { onSelectGame(null) },
                )
            }

            (artworkResult ?: manualMatchResult)?.let { result ->
                section {
                    StatusLine(result)
                }
            }
        }
    }
}

private fun slotLabel(slot: AssetSlot): String = when (slot) {
    AssetSlot.BOX_FRONT -> "BOX FRONT"
    AssetSlot.BOX_SPINE -> "SPINE"
    AssetSlot.BOX_BACK -> "BOX BACK"
    AssetSlot.PHYSICAL_MEDIA -> "DISC / CART"
    AssetSlot.FULL_COVER -> "FULL COVER"
    AssetSlot.CLEAR_LOGO -> "LOGO"
    AssetSlot.SCREENSHOT -> "SCREENSHOT"
}
