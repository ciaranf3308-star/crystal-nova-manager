package io.crystalnova.manager.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.data.InstalledPack
import io.crystalnova.manager.data.PackCatalogState
import io.crystalnova.manager.data.PackDownloadState
import io.crystalnova.manager.data.PackEntry
import java.io.File

/**
 * Shown when iiSU did not accept the shared ZIP (or no share target
 * resolved): the user completes the import by hand in iiSU's own UI.
 * The Manager never writes into iiSU's private storage.
 */
data class ManualImport(
    val pack: PackEntry,
    val zipName: String,
)

/**
 * THEME: the Crystal iiSU pack manager (u45). Lists packs from the
 * remote catalog, downloads ZIPs with SHA-256 verification, and hands
 * the ZIP to iiSU through Android's share intent — falling back to
 * manual-import guidance when iiSU does not resolve the share.
 *
 * Pure function of its inputs: every side effect (network, intents,
 * persistence) arrives as a callback owned by the activity. Previews
 * load lazily through [onPreviewNeeded]/[previewOf]; tests pass the
 * defaults and get honest placeholders.
 */
@Composable
fun ThemeScreen(
    catalogState: PackCatalogState,
    downloadState: PackDownloadState,
    installed: InstalledPack?,
    previewOf: (packId: String) -> ImageBitmap? = { null },
    onPreviewNeeded: (PackEntry) -> Unit = {},
    manualImport: ManualImport? = null,
    manualImportNotice: String? = null,
    onRefresh: () -> Unit = {},
    onDownload: (PackEntry) -> Unit = {},
    onInstallToIisu: (PackEntry, File) -> Unit = { _, _ -> },
    onOpenFileManager: () -> Unit = {},
    onDismissManualImport: () -> Unit = {},
    /** Pops one navigation level (B). */
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val packs = (catalogState as? PackCatalogState.Ready)?.packs.orEmpty()
    // Kick off lazy preview loads from the composable context: the
    // per-pack sections below are plain ControllerList DSL helpers, not
    // composables, so the effect cannot live inside them.
    packs.forEach { pack ->
        if (previewOf(pack.id) == null && pack.previewUrl != null) {
            LaunchedEffect(pack.id) { onPreviewNeeded(pack) }
        }
    }
    ScreenScaffold(
        routeKey = "theme",
        title = "THEME",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = if (manualImport != null) {
            "manual-import-back"
        } else if (packs.isNotEmpty()) {
            "pack-action-${packs.first().id}"
        } else {
            "theme-back"
        },
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
            if (manualImport != null) {
                manualImportSection(
                    manualImport = manualImport,
                    notice = manualImportNotice,
                    onOpenFileManager = onOpenFileManager,
                    onDismiss = onDismissManualImport,
                )
            } else {
                when (catalogState) {
                    is PackCatalogState.Checking -> section {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            StatusLine("CHECKING THE PACK LIBRARY…", Crystal.Joystick)
                        }
                    }
                    is PackCatalogState.Unavailable -> section {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatusLine("PACK LIBRARY UNAVAILABLE", Crystal.Bad)
                                DimLine(catalogState.message.uppercase())
                                DimLine(
                                    "THE PACK CATALOG IS PUBLISHED SEPARATELY " +
                                        "FROM THE MANAGER — NOTHING IS BROKEN " +
                                        "ON YOUR NOVA. CHECK YOUR CONNECTION " +
                                        "AND RETRY.",
                                )
                            }
                        }
                    }
                    is PackCatalogState.Ready -> {
                        if (packs.isEmpty()) {
                            section {
                                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        StatusLine(
                                            "NO PACKS IN THE CATALOG YET",
                                            Crystal.Joystick,
                                        )
                                        DimLine(
                                            "THE CATALOG IS LIVE BUT LISTS NO " +
                                                "PACKS. THE FIRST CRYSTAL PACK " +
                                                "ARRIVES WITH THE NEXT CATALOG " +
                                                "PUBLISH — NOTHING HERE " +
                                                "OVERWRITES iiSU'S SCRAPED " +
                                                "GAME ARTWORK.",
                                        )
                                    }
                                }
                            }
                        } else {
                            packs.forEach { pack ->
                                packSection(
                                    pack = pack,
                                    downloadState = downloadState,
                                    installed = installed?.id == pack.id,
                                    installedVersion = installed
                                        ?.takeIf { it.id == pack.id }
                                        ?.version,
                                    preview = previewOf(pack.id),
                                    onDownload = { onDownload(pack) },
                                    onInstallToIisu = onInstallToIisu,
                                )
                            }
                        }
                    }
                }
                if (catalogState is PackCatalogState.Unavailable ||
                    (catalogState is PackCatalogState.Ready && packs.isEmpty())
                ) {
                    control(
                        key = "packs-retry",
                        testTag = "packs-retry",
                        label = "CHECK AGAIN",
                        onClick = onRefresh,
                    )
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

private fun ControllerListContent.manualImportSection(
    manualImport: ManualImport,
    notice: String?,
    onOpenFileManager: () -> Unit,
    onDismiss: () -> Unit,
) {
    section {
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusLine("FINISH THE IMPORT IN iiSU", Crystal.Joystick)
                DimLine(
                    ("iiSU DID NOT TAKE THE SHARED FILE, SO IMPORT " +
                        "IT BY HAND — IT TAKES TEN SECONDS:").uppercase(),
                )
                DimLine("1 · OPEN iiSU")
                DimLine("2 · GO TO APPEARANCE > iiSU THEMES")
                DimLine("3 · IMPORT THIS ZIP: ${manualImport.zipName}".uppercase())
                if (notice != null) {
                    StatusLine(notice.uppercase(), Crystal.Bad)
                }
            }
        }
    }
    control(
        key = "manual-import-open-files",
        testTag = "manual-import-open-files",
        label = "OPEN FILE MANAGER",
        onClick = onOpenFileManager,
    )
    control(
        key = "manual-import-back",
        testTag = "manual-import-back",
        label = "BACK TO PACKS",
        onClick = onDismiss,
    )
}

private fun ControllerListContent.packSection(
    pack: PackEntry,
    downloadState: PackDownloadState,
    installed: Boolean,
    installedVersion: String?,
    preview: ImageBitmap?,
    onDownload: () -> Unit,
    onInstallToIisu: (PackEntry, File) -> Unit,
) {
    section {
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "${pack.name} preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
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
                    "v${pack.version}" +
                        if (installed) " · INSTALLED" else " · NOT INSTALLED",
                    if (installed) Crystal.Good else Crystal.Joystick,
                )
                if (pack.description.isNotEmpty()) {
                    DimLine(pack.description.uppercase())
                }
                val coverage = buildList {
                    if (pack.systems.isNotEmpty()) {
                        add("COVERS: " + pack.systems.joinToString(", ").uppercase())
                    }
                    pack.zipBytes?.let { add("SIZE: " + formatPackBytes(it)) }
                    if (pack.assets.isNotEmpty()) {
                        add("${pack.assets.size} ASSETS")
                    }
                    pack.iisuMinVersion?.let { add("NEEDS iiSU $it+") }
                }
                if (coverage.isNotEmpty()) {
                    DimLine(coverage.joinToString(" · "))
                }
                if (installed && installedVersion != null && installedVersion != pack.version) {
                    DimLine("INSTALLED v$installedVersion — v${pack.version} AVAILABLE")
                }
            }
        }
    }
    val thisPack = when (downloadState) {
        is PackDownloadState.Downloading -> downloadState.packId == pack.id
        is PackDownloadState.Verifying -> downloadState.packId == pack.id
        is PackDownloadState.ReadyToInstall -> downloadState.packId == pack.id
        is PackDownloadState.Failed -> downloadState.packId == pack.id
        else -> false
    }
    // One download at a time: while another pack is mid-download this
    // pack's DOWNLOAD disables instead of silently no-opping.
    val busyElsewhere = (downloadState is PackDownloadState.Downloading ||
        downloadState is PackDownloadState.Verifying) && !thisPack
    when {
        downloadState is PackDownloadState.Downloading && thisPack -> section {
            val pct = downloadState.total
                ?.takeIf { it > 0 }
                ?.let { (downloadState.done * 100 / it).toInt().coerceIn(0, 100) }
            StatusLine(
                if (pct != null) "DOWNLOADING… $pct%" else "DOWNLOADING…",
                Crystal.Joystick,
            )
        }
        downloadState is PackDownloadState.Verifying && thisPack -> section {
            StatusLine("VERIFYING CHECKSUM…", Crystal.Joystick)
        }
        downloadState is PackDownloadState.ReadyToInstall && thisPack -> control(
            key = "pack-action-${pack.id}",
            testTag = "pack-action-${pack.id}",
            label = if (installed) "SEND TO iiSU AGAIN" else "INSTALL TO iiSU",
            onClick = { onInstallToIisu(pack, downloadState.zip) },
        )
        downloadState is PackDownloadState.Failed && thisPack -> {
            section {
                StatusLine(
                    "DOWNLOAD FAILED: ${downloadState.message}".uppercase(),
                    Crystal.Bad,
                )
            }
            control(
                key = "pack-action-${pack.id}",
                testTag = "pack-action-${pack.id}",
                label = "RETRY DOWNLOAD",
                onClick = onDownload,
            )
        }
        else -> control(
            key = "pack-action-${pack.id}",
            testTag = "pack-action-${pack.id}",
            label = if (installed) "DOWNLOAD AGAIN" else "DOWNLOAD",
            enabled = !busyElsewhere,
            onClick = onDownload,
        )
    }
}

/** Decodes preview bytes off the UI thread; null when undecodable. */
fun decodePreviewImage(bytes: ByteArray): ImageBitmap? = runCatching {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** Human size for a pack ZIP, e.g. "3.5 MB". Never throws. */
internal fun formatPackBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}
