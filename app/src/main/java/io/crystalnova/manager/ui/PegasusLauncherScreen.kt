package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.pegasus.LauncherProfile

/** One installed-or-known RetroArch build offered for a system. */
data class RetroArchOption(
    val packageName: String,
    /** "64-BIT" / "32-BIT" */
    val tag: String,
    /** Default libretro core .so, or null when the system has no known core. */
    val core: String?,
    val installed: Boolean,
)

/** One verified standalone emulator preset offered for a system. */
data class StandaloneOption(
    val profile: LauncherProfile,
    val installed: Boolean,
)

/**
 * LAUNCHER picker for one system: the current status, the curated
 * default, installed RetroArch builds with the known core, verified
 * standalone presets, and a free-form CUSTOM command field.
 *
 * Installed state is shown, never hidden — but the choice stays the
 * user's: picking an emulator that isn't installed yet is allowed and
 * clearly marked, never silently substituted.
 */
@Composable
fun PegasusLauncherScreen(
    slug: String,
    label: String,
    currentStatus: String,
    isDefault: Boolean,
    defaultProfile: LauncherProfile?,
    retroArchOptions: List<RetroArchOption>,
    standaloneOptions: List<StandaloneOption>,
    customCommand: String,
    notice: String?,
    onUseDefault: () -> Unit,
    onSelectRetroArch: (packageName: String, core: String) -> Unit,
    onSelectStandalone: (LauncherProfile) -> Unit,
    onSaveCustom: (String) -> Unit,
    onClear: () -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dispatcher = remember { FocusDispatcher() }
    var customText by remember(slug, customCommand) { mutableStateOf(customCommand) }
    var editingCustom by remember { mutableStateOf(false) }
    // A no-op registration so gamepad A while the field is focused does
    // not trigger the last-registered button action.
    LaunchedEffect(Unit) { dispatcher.register("custom-field") {} }

    // Controller focus: exactly one control takes initial focus — the
    // default button when usable, else the first RetroArch option with a
    // known core, else the always-enabled CLEAR CHOICE fallback.
    val defaultUsable = defaultProfile != null &&
        !(isDefault && currentStatus == defaultProfile.displayLabel())
    val firstRaUsable = retroArchOptions.indexOfFirst { it.core != null }

    ScreenRoot(
        onBack = onBack,
        dispatcher = dispatcher,
        modifier = modifier,
        passThroughAWhen = { editingCustom },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CrystalHeader()
            SectionLabel("LAUNCHER · ${label.uppercase()}")
            StatusLine(
                "CURRENT: $currentStatus" +
                    if (currentStatus == "NOT CONFIGURED") "" else if (isDefault) " · DEFAULT" else " · YOUR CHOICE",
                if (currentStatus == "NOT CONFIGURED") Crystal.Bad else Crystal.Good,
            )

            defaultProfile?.let { def ->
                CrystalButton(
                    key = "launcher-default",
                    label = "USE DEFAULT: ${def.displayLabel()}",
                    onClick = onUseDefault,
                    dispatcher = dispatcher,
                    enabled = defaultUsable,
                    requestInitialFocus = defaultUsable,
                )
            }

            SectionLabel("RETROARCH")
            if (retroArchOptions.isEmpty()) {
                DimLine("RETROARCH NOT INSTALLED — INSTALL IT OR USE CUSTOM BELOW.")
            } else {
                retroArchOptions.forEachIndexed { index, opt ->
                    val coreLabel = opt.core ?: "NO KNOWN CORE"
                    CrystalButton(
                        key = "launcher-ra-${opt.packageName}",
                        label = "RETROARCH ${opt.tag} + $coreLabel" +
                            if (opt.installed) "" else " · NOT INSTALLED",
                        onClick = { opt.core?.let { onSelectRetroArch(opt.packageName, it) } },
                        dispatcher = dispatcher,
                        enabled = opt.core != null,
                        requestInitialFocus = !defaultUsable && index == firstRaUsable,
                    )
                }
                DimLine(
                    "THE CORE IS THE COMMUNITY-STANDARD LIBRETRO CORE FOR THIS " +
                        "SYSTEM. THE LAUNCH COMMAND USES ITS FULL ON-DEVICE PATH " +
                        "(/data/data/<PACKAGE>/cores/<CORE>). {file.path} IS FILLED " +
                        "IN WHEN PEGASUS LAUNCHES THE GAME.",
                )
            }

            if (standaloneOptions.isNotEmpty()) {
                SectionLabel("STANDALONE")
                standaloneOptions.forEachIndexed { index, opt ->
                    CrystalButton(
                        key = "launcher-sa-${opt.profile.packageName}",
                        label = opt.profile.displayLabel() +
                            if (opt.installed) "" else " · NOT INSTALLED",
                        onClick = { onSelectStandalone(opt.profile) },
                        dispatcher = dispatcher,
                        requestInitialFocus = !defaultUsable && firstRaUsable < 0 && index == 0,
                    )
                }
            }

            SectionLabel("CUSTOM")
            DimLine("YOUR OWN COMMAND, USED VERBATIM. {file.path} = GAME PATH.")
            var fieldFocused by remember { mutableStateOf(false) }
            BasicTextField(
                value = customText,
                onValueChange = { customText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        fieldFocused = it.isFocused
                        editingCustom = it.isFocused
                        if (it.isFocused) dispatcher.onFocused("custom-field")
                    }
                    .focusable()
                    .border(
                        2.dp,
                        if (fieldFocused) Crystal.Cream else Crystal.Frame,
                        RoundedCornerShape(2.dp),
                    )
                    .background(if (fieldFocused) Crystal.Cream else Crystal.Tile)
                    .padding(16.dp),
                textStyle = TextStyle(
                    fontFamily = Crystal.Mono,
                    fontSize = Crystal.BodySize,
                    color = if (fieldFocused) Crystal.CreamInk else Crystal.Ink,
                ),
                maxLines = 6,
            )
            CrystalButton(
                key = "launcher-save-custom",
                label = "SAVE CUSTOM COMMAND",
                onClick = { onSaveCustom(customText) },
                dispatcher = dispatcher,
                enabled = customText.isNotBlank(),
            )

            CrystalButton(
                key = "launcher-clear",
                label = "CLEAR CHOICE",
                onClick = onClear,
                dispatcher = dispatcher,
                danger = true,
                requestInitialFocus = !defaultUsable && firstRaUsable < 0 && standaloneOptions.isEmpty(),
            )
            DimLine("CLEARING REMOVES YOUR CHOICE — THE CURATED DEFAULT APPLIES AGAIN, OR NOT CONFIGURED WHEN THERE IS NONE.")

            notice?.let { NoticeBlock(it, onDismissNotice, dispatcher) }

            Spacer(Modifier.padding(8.dp))
            BackFooter()
        }
    }
}
