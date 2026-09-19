package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * ASSETS: the Crystal artwork library (u44 pivot placeholder). The
 * remote asset catalog and the retargeted artwork studio land in a
 * later update; this screen is the honest entry point so the HOME
 * tile never points at a dead route.
 */
@Composable
fun AssetsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "assets",
        title = "ASSETS",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "assets-back",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                StatusLine("THE CRYSTAL ARTWORK LIBRARY", Crystal.Cream)
            }
            section {
                DimLine(
                    "THE REMOTE ASSET CATALOG, THE iiSU PACK PIPELINE, " +
                        "AND THE ARTWORK STUDIO LAND IN A LATER UPDATE. " +
                        "NOTHING HERE OVERWRITES iiSU'S SCRAPED ARTWORK.",
                )
            }
            control(
                key = "assets-back",
                testTag = "assets-back",
                label = "BACK",
                onClick = onBack,
            )
        }
    }
}
