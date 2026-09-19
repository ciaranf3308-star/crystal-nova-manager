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

/**
 * Manual media matching: pairs unmatched ROM games with unmatched ES-DE
 * media groups, console by console.
 *
 * Controller-friendly wizard:
 *  1. Pick a platform (chip row)
 *  2. Pick an unmatched game (list)
 *  3. Pick an unmatched media group (list)
 *  4. Confirm -> files are copied, index updated, lists refresh
 */
@Composable
fun EsdeManualMatchScreen(
    importPlan: EsdeImport.ImportPlan?,
    manualMatchResult: String?,
    onApplyMatch: (game: EsdeImport.RomGame, media: EsdeImport.UnmatchedMediaGroup) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedPlatform by remember { mutableStateOf<String?>(null) }
    var selectedGame by remember { mutableStateOf<EsdeImport.RomGame?>(null) }

    val unmatchedGames = importPlan?.unmatchedGames ?: emptyList()
    val unmatchedMedia = importPlan?.unmatchedMediaGroups ?: emptyList()

    // Platforms that have unmatched games, sorted.
    val platforms = remember(unmatchedGames) {
        unmatchedGames.map { it.platform }.distinct().sorted()
    }

    // Auto-select the first platform if none selected.
    if (selectedPlatform == null && platforms.isNotEmpty()) {
        selectedPlatform = platforms.first()
    }

    val gamesForPlatform = remember(unmatchedGames, selectedPlatform) {
        unmatchedGames.filter { it.platform == selectedPlatform }.sortedBy { it.title.lowercase() }
    }
    val mediaForPlatform = remember(unmatchedMedia, selectedPlatform) {
        unmatchedMedia.filter { it.platform == selectedPlatform }
    }

    ScreenScaffold(
        routeKey = "settings-esde-manual-match",
        title = "MANUAL MEDIA MATCH",
        onBack = {
            if (selectedGame != null) selectedGame = null else onBack()
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
                    DimLine("RUN PRE-SCAN FIRST TO FIND UNMATCHED GAMES AND MEDIA.")
                }
                return@ControllerList
            }

            if (unmatchedGames.isEmpty()) {
                section {
                    StatusLine("ALL GAMES MATCHED — NOTHING TO DO MANUALLY.")
                }
                return@ControllerList
            }

            // ---- Step 1: platform chips ----
            if (selectedGame == null) {
                section {
                    StatusLine("STEP 1: PICK A CONSOLE (${platforms.size})")
                }
                // Platform chips as a wrapped row of controls.
                platforms.forEach { platform ->
                    val count = unmatchedGames.count { it.platform == platform }
                    control(
                        key = "esde-manual-platform-$platform",
                        testTag = "esde-manual-platform-$platform",
                        label = if (platform == selectedPlatform) "▶ $platform ($count)" else "$platform ($count)",
                        onClick = { selectedPlatform = platform },
                    )
                }

                section {
                    StatusLine("STEP 2: PICK A GAME WITHOUT MEDIA (${gamesForPlatform.size})")
                }
                if (gamesForPlatform.isEmpty()) {
                    section {
                        DimLine("NO UNMATCHED GAMES FOR THIS CONSOLE.")
                    }
                } else {
                    gamesForPlatform.forEach { game ->
                        control(
                            key = "esde-manual-game-${game.platform}-${game.gameId}",
                            testTag = "esde-manual-game-${game.gameId}",
                            label = game.title,
                            onClick = { selectedGame = game },
                        )
                    }
                }
            } else {
                // ---- Step 2: pick media for the selected game ----
                val game = selectedGame!!
                section {
                    StatusLine("MATCHING: ${game.title}")
                    DimLine("PICK THE MEDIA GROUP FOR THIS GAME (${mediaForPlatform.size} AVAILABLE)")
                }
                if (mediaForPlatform.isEmpty()) {
                    section {
                        DimLine("NO UNMATCHED MEDIA FOR ${game.platform.uppercase()}.")
                    }
                } else {
                    mediaForPlatform.forEach { mediaGroup ->
                        val slotCount = mediaGroup.files.size
                        control(
                            key = "esde-manual-media-${mediaGroup.key}",
                            testTag = "esde-manual-media-${mediaGroup.key}",
                            label = "${mediaGroup.displayName} ($slotCount files)",
                            onClick = { onApplyMatch(game, mediaGroup) },
                        )
                    }
                }
                control(
                    key = "esde-manual-back-to-games",
                    testTag = "esde-manual-back-to-games",
                    label = "← BACK TO GAMES",
                    onClick = { selectedGame = null },
                )
            }

            manualMatchResult?.let { result ->
                section {
                    StatusLine(result)
                }
            }
        }
    }
}
