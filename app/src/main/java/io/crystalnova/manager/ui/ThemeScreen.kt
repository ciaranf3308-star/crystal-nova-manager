package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One Crystal iiSU pack in the library. v1 (u44) ships with an empty
 * list — the remote catalog and pack #1 land in a later update, at
 * which point this screen grows install/update/uninstall actions per
 * pack. Kept deliberately small: the pack ZIP handoff goes through
 * iiSU's own import UI (Appearance > iiSU Themes); the Manager never
 * writes into iiSU's private storage.
 */
data class CrystalPack(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    /** Human platform labels this pack covers, e.g. "PlayStation 2". */
    val systems: List<String>,
    /** True once the pack's ZIP has been imported in iiSU. */
    val installed: Boolean,
)

/**
 * THEME: the Crystal iiSU pack manager (u44 pivot). Replaces the
 * Pegasus theme updater — same screen slot, new product. The
 * updater/download/install/preview infrastructure
 * ([io.crystalnova.manager.updater]) is retained for the catalog
 * work; every Pegasus-specific concept (theme catalog URL, SAF
 * themes-folder install target, backup/rollback, OPEN PEGASUS) is
 * gone.
 *
 * Pure function of [packs]: no Android APIs, renders on the JVM for
 * screenshot tests.
 */
@Composable
fun ThemeScreen(
    packs: List<CrystalPack>,
    /** Pops one navigation level (B). */
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "theme",
        title = "THEME",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = if (packs.isEmpty()) "theme-back" else "theme-pack-0",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                BasicText(
                    text = "CRYSTAL iiSU PACKS",
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = Crystal.BodySize,
                        color = Crystal.Cream,
                    ),
                )
            }
            if (packs.isEmpty()) {
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusLine("NO CRYSTAL PACKS INSTALLED YET", Crystal.Ink)
                            DimLine(
                                "THE CRYSTAL iiSU PACK LIBRARY — PLATFORM " +
                                    "ICONS, TITLES, AND BACKGROUNDS IN THE " +
                                    "CRYSTAL AESTHETIC — ARRIVES IN A LATER " +
                                    "UPDATE. PACKS IMPORT THROUGH iiSU'S OWN " +
                                    "APPEARANCE > iiSU THEMES SCREEN; NOTHING " +
                                    "HERE OVERWRITES iiSU'S SCRAPED GAME ARTWORK.",
                            )
                        }
                    }
                }
            } else {
                packs.forEach { pack ->
                    section {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                BasicText(
                                    text = pack.name.uppercase(),
                                    style = TextStyle(
                                        fontFamily = Crystal.Mono,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = Crystal.BodySize,
                                        color = Crystal.Ink,
                                    ),
                                )
                                StatusLine(
                                    "v${pack.version} · " +
                                        if (pack.installed) "INSTALLED" else "NOT INSTALLED",
                                    if (pack.installed) Crystal.Good else Crystal.Joystick,
                                )
                                if (pack.description.isNotEmpty()) {
                                    DimLine(pack.description.uppercase())
                                }
                                if (pack.systems.isNotEmpty()) {
                                    DimLine(
                                        "COVERS: " + pack.systems.joinToString(", ").uppercase(),
                                    )
                                }
                            }
                        }
                    }
                    // Per-pack install/update/uninstall actions land with
                    // the catalog (later update); the card is display-only
                    // until then so no dead control ships.
                }
            }
            control(
                key = "theme-back",
                testTag = "theme-back",
                label = "BACK",
                onClick = onBack,
            )
        }
    }
}
