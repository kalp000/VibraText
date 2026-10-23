package com.example.vibratext.app.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibratext.app.ui.components.BodyText
import com.example.vibratext.app.ui.components.CardTone
import com.example.vibratext.app.ui.components.ChoiceRow
import com.example.vibratext.app.ui.components.ConfirmDialog
import com.example.vibratext.app.ui.components.ErrorText
import com.example.vibratext.app.ui.components.Format
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.InfoCard
import com.example.vibratext.app.ui.components.IntField
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.app.ui.components.SectionHeader
import com.example.vibratext.app.ui.components.SettingSlider
import com.example.vibratext.app.ui.components.SettingSwitch
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.encoding.CaseMode
import com.example.vibratext.encoding.CodeSymbols
import com.example.vibratext.encoding.ControlCode
import com.example.vibratext.encoding.EncodingTable
import com.example.vibratext.encoding.UnsupportedPolicy
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.playback.SpeedPreset
import com.example.vibratext.playback.SpeedSettings
import com.example.vibratext.playback.TimingSettings
import kotlin.math.roundToInt

private class TimingField(val label: String, val get: (TimingSettings) -> Int, val set: (TimingSettings, Int) -> TimingSettings)

private val timingFields = listOf(
    TimingField("Short pulse", { it.shortPulseMs }, { t, v -> t.copy(shortPulseMs = v) }),
    TimingField("Long pulse", { it.longPulseMs }, { t, v -> t.copy(longPulseMs = v) }),
    TimingField("Double: each pulse", { it.doublePulseMs }, { t, v -> t.copy(doublePulseMs = v) }),
    TimingField("Triple: each pulse", { it.triplePulseMs }, { t, v -> t.copy(triplePulseMs = v) }),
    TimingField("Gap between pulses", { it.pulseGapMs }, { t, v -> t.copy(pulseGapMs = v) }),
    TimingField("Gap between symbols", { it.symbolGapMs }, { t, v -> t.copy(symbolGapMs = v) }),
    TimingField("Gap between characters", { it.charGapMs }, { t, v -> t.copy(charGapMs = v) }),
    TimingField("Gap between words", { it.wordGapMs }, { t, v -> t.copy(wordGapMs = v) }),
    TimingField("Pause after START", { it.startMarkerPauseMs }, { t, v -> t.copy(startMarkerPauseMs = v) }),
    TimingField("Pause after separator", { it.separatorPauseMs }, { t, v -> t.copy(separatorPauseMs = v) }),
    TimingField("Pause after END", { it.endMarkerPauseMs }, { t, v -> t.copy(endMarkerPauseMs = v) }),
    TimingField("Completion tick", { it.completionTickMs }, { t, v -> t.copy(completionTickMs = v) }),
)

@Composable
fun ProfileEditorScreen(onBack: () -> Unit, vm: ProfileEditorViewModel = viewModel()) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    var editChar by remember { mutableStateOf<Char?>(null) }
    var addChar by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var previewSender by remember { mutableStateOf("Alex") }
    var previewMessage by remember { mutableStateOf("Hello, are you free?") }
    var previewError by remember { mutableStateOf<String?>(null) }

    val requestBack: () -> Unit = {
        if (dirty) confirmDiscard = true else onBack()
    }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    val profile = draft
    val problems = profile?.validate().orEmpty()

    ScreenScaffold(
        title = "Edit profile",
        onBack = requestBack,
        actions = {
            TextButton(onClick = { vm.save(onBack) }, enabled = profile != null && dirty && problems.isEmpty()) { Text("Save") }
        },
    ) { padding ->
        if (profile == null) {
            PageList(padding) {
                item { BodyText(if (loaded) "This profile no longer exists." else "Loading…") }
            }
            return@ScreenScaffold
        }
        PageList(padding) {
            if (problems.isNotEmpty()) {
                item {
                    InfoCard(title = "Fix these before saving", tone = CardTone.WARNING) {
                        problems.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (!vm.hasVibrator) {
                item { InfoCard(tone = CardTone.WARNING) { BodyText("This device reports no vibrator, so previews cannot be felt.") } }
            }

            item {
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = { v -> vm.update { it.copy(name = v) } },
                    label = { Text("Profile name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- symbols ----------------------------------------------------------------------
            item { SectionHeader("Symbol mapping") }
            item {
                HintText(
                    "Every character is a code of ${CodeSymbols.CODE_LENGTH} digits (0–3). Each digit is one vibration symbol. " +
                        "Changing the mapping changes how every character feels, and the decoder uses the same mapping. " +
                        "Each symbol is used exactly once; choosing one that another digit has swaps them.",
                )
            }
            items(listOf(0, 1, 2, 3), key = { it }) { digit ->
                val symbol = profile.symbolMapping.symbolFor(digit)
                var open by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Digit $digit →", Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { open = true }) { Text("${symbol.label} (${symbol.letter}) ▾") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            VibSymbol.entries.forEach { s ->
                                DropdownMenuItem(text = { Text("${s.label} (${s.letter})") }, onClick = {
                                    open = false
                                    vm.setSymbolForDigit(digit, s)
                                })
                            }
                        }
                    }
                    FilledTonalButton(onClick = { vm.playSymbol(symbol) }) { Text("Feel") }
                }
            }

            // ---- text handling ----------------------------------------------------------------
            item { SectionHeader("Text handling") }
            item {
                ChoiceRow(
                    title = "Capital letters",
                    description = "Normalised: everything is sent in lower case (capitals cannot be restored when decoding). Preserved: a capital is sent as a CASE_SHIFT marker plus the letter.",
                    options = listOf("Normalise to lower case" to CaseMode.NORMALIZE, "Preserve with a case marker" to CaseMode.PRESERVE),
                    selected = profile.caseMode,
                    onSelect = { v -> vm.update { it.copy(caseMode = v) } },
                )
            }
            item {
                SettingSwitch(
                    title = "Remove accents",
                    description = "é becomes e, ü becomes u, so accented text still matches the table.",
                    checked = profile.stripAccents,
                    onCheckedChange = { v -> vm.update { it.copy(stripAccents = v) } },
                )
            }
            item {
                ChoiceRow(
                    title = "Characters not in the table",
                    description = "Either replace them with the replacement character, or skip the whole notification and show why in History.",
                    options = listOf("Replace with a character" to UnsupportedPolicy.REPLACE, "Skip the notification" to UnsupportedPolicy.ERROR),
                    selected = profile.unsupportedPolicy,
                    onSelect = { v -> vm.update { it.copy(unsupportedPolicy = v) } },
                )
            }
            if (profile.unsupportedPolicy == UnsupportedPolicy.REPLACE) {
                item {
                    OutlinedTextField(
                        value = profile.replacementChar.toString(),
                        onValueChange = { v -> v.lastOrNull { !it.isISOControl() }?.let { c -> vm.update { it.copy(replacementChar = c) } } },
                        label = { Text("Replacement character") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- timing -----------------------------------------------------------------------
            item { SectionHeader("Timing") }
            item {
                HintText("All values in milliseconds. A short gap between pulses, a longer one between symbols and a longer one still between characters is what makes the code readable by touch.")
            }
            items(timingFields.chunked(2), key = { it.first().label }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { f ->
                        IntField(
                            label = f.label,
                            value = f.get(profile.timing),
                            onValueChange = { v -> vm.update { it.copy(timing = f.set(it.timing, v)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                OutlinedButton(onClick = { vm.update { it.copy(timing = TimingSettings.DEFAULT) } }) { Text("Reset timing to defaults") }
            }

            // ---- speed, repeat, intensity ------------------------------------------------------
            item { SectionHeader("Speed, repetition and intensity") }
            item {
                ChoiceRow(
                    title = "Playback speed",
                    description = "Scales every duration. Slow = 1.5× as long, Fast = 0.75×. Gaps keep their order so the code stays readable.",
                    options = SpeedPreset.entries.map { it.label to it },
                    selected = profile.speed.preset,
                    onSelect = { v -> vm.update { it.copy(speed = it.speed.copy(preset = v)) } },
                )
            }
            if (profile.speed.preset == SpeedPreset.CUSTOM) {
                item {
                    SettingSlider(
                        title = "Custom duration multiplier",
                        description = "Below 1× is faster, above 1× is slower.",
                        value = (profile.speed.customMultiplier * 100).roundToInt(),
                        range = 25..400,
                        step = 5,
                        format = { Format.multiplier(it / 100.0) },
                        onCommit = { v -> vm.update { it.copy(speed = SpeedSettings(SpeedPreset.CUSTOM, v / 100.0)) } },
                    )
                }
            }
            item {
                SettingSlider(
                    title = "Repeat count",
                    description = "How many times the whole message is played. The completion tick plays once, after the last repetition.",
                    value = profile.repeat.count,
                    range = 1..10,
                    step = 1,
                    format = { "$it×" },
                    onCommit = { v -> vm.update { it.copy(repeat = it.repeat.copy(count = v)) } },
                )
            }
            if (profile.repeat.count > 1) {
                item {
                    IntField(
                        label = "Pause between repetitions",
                        value = profile.repeat.pauseMs,
                        onValueChange = { v -> vm.update { it.copy(repeat = it.repeat.copy(pauseMs = v)) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    SettingSwitch(
                        title = "Repetition marker",
                        description = "Two quick ticks between repetitions, so you can tell a repeat from a new message.",
                        checked = profile.repeat.repeatMarker,
                        onCheckedChange = { v -> vm.update { it.copy(repeat = it.repeat.copy(repeatMarker = v)) } },
                    )
                }
            }
            item {
                SettingSlider(
                    title = "Intensity",
                    description = if (vm.supportsAmplitude) "How strong each pulse is." else "This phone's vibrator has no strength control, so this setting has no effect here.",
                    value = profile.intensityPercent,
                    range = 1..100,
                    step = 1,
                    format = { "$it %" },
                    onCommit = { v -> vm.update { it.copy(intensityPercent = v) } },
                )
            }
            item {
                SettingSwitch(
                    title = "Completion tick",
                    description = "One very short pulse after the message so you know it has ended.",
                    checked = profile.completionTick,
                    onCheckedChange = { v -> vm.update { it.copy(completionTick = v) } },
                )
            }

            // ---- preview ----------------------------------------------------------------------
            item { SectionHeader("Try this profile") }
            item { HintText("Plays with the settings above, even before you save.") }
            item {
                OutlinedTextField(value = previewSender, onValueChange = { previewSender = it }, label = { Text("Sender (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(value = previewMessage, onValueChange = { previewMessage = it; previewError = null }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                val plan = vm.planFor(previewSender, previewMessage)
                plan.getOrNull()?.let { HintText("Takes about ${Format.duration(it.estimatedDurationMs)}.") }
                plan.exceptionOrNull()?.let { ErrorText(it.message ?: "This text cannot be encoded.") }
                previewError?.let { ErrorText(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { previewError = vm.playMessage(previewSender, previewMessage) }, enabled = problems.isEmpty()) { Text("Play") }
                    OutlinedButton(onClick = vm::stop) { Text("Stop") }
                }
            }

            // ---- character table ---------------------------------------------------------------
            item { SectionHeader("Character table") }
            item {
                HintText(
                    "Each character has a unique 3-symbol code (letters S L D T). Reserved codes for START, SEPARATOR, END, CASE_SHIFT and TRUNCATED cannot be used. " +
                        "${profile.charCodes.size} of at most ${CodeSymbols.CODE_SPACE - ControlCode.entries.size} characters (3 symbols give ${CodeSymbols.CODE_SPACE} codes, ${ControlCode.entries.size} are reserved).",
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { addChar = true }) { Text("Add character") }
                    OutlinedButton(onClick = { confirmReset = true }) { Text("Reset table") }
                }
            }
            items(profile.charCodes.entries.sortedBy { it.value }, key = { it.key }) { (c, code) ->
                val letters = CodeSymbols.toSymbols(code, profile.symbolMapping).joinToString(" ") { it.letter.toString() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(EncodingTable.display(c), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.8f))
                    Text(letters, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1.4f))
                    TextButton(onClick = { vm.playCharacter(c) }) { Text("Feel") }
                    TextButton(onClick = { editChar = c }) { Text("Edit") }
                }
            }
        }
    }

    editChar?.let { c ->
        val code = profile?.charCodes?.get(c)
        if (profile == null || code == null) {
            editChar = null
        } else {
            CodeDialog(
                title = "Code for “${EncodingTable.display(c)}”",
                fixedChar = c,
                initialSymbols = CodeSymbols.toSymbols(code, profile.symbolMapping).joinToString("") { it.letter.toString() },
                vm = vm,
                onSave = { _, newCode ->
                    vm.setCharCode(c, newCode)
                    editChar = null
                },
                onRemove = {
                    vm.removeChar(c)
                    editChar = null
                },
                onDismiss = { editChar = null },
            )
        }
    }
    if (addChar) {
        CodeDialog(
            title = "Add a character",
            fixedChar = null,
            initialSymbols = "",
            vm = vm,
            onSave = { ch, newCode ->
                vm.setCharCode(ch, newCode)
                addChar = false
            },
            onRemove = null,
            onDismiss = { addChar = false },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset the character table?",
            message = "All characters get their default codes again.",
            confirmLabel = "Reset",
            onConfirm = {
                vm.resetTable()
                confirmReset = false
            },
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "This profile has changes that are not saved.",
            confirmLabel = "Discard",
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun CodeDialog(
    title: String,
    fixedChar: Char?,
    initialSymbols: String,
    vm: ProfileEditorViewModel,
    onSave: (Char, Int) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var charText by remember { mutableStateOf(fixedChar?.toString() ?: "") }
    var symbols by remember { mutableStateOf(initialSymbols) }
    val draft by vm.draft.collectAsStateWithLifecycle()

    val ch: Char? = fixedChar ?: charText.singleOrNull()
    val charError: String? = when {
        fixedChar != null -> null
        charText.isEmpty() -> "Type the character to add."
        ch == null -> "Enter exactly one character."
        ch.isISOControl() -> "Control characters cannot be added."
        draft?.charCodes?.containsKey(ch) == true -> "“${EncodingTable.display(ch)}” is already in the table; edit it instead."
        else -> null
    }
    val parsed = vm.parseCode(symbols, fixedChar)
    val codeError = (parsed as? CodeInput.Invalid)?.message
    val canSave = charError == null && parsed is CodeInput.Valid && ch != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fixedChar == null) {
                    OutlinedTextField(value = charText, onValueChange = { charText = it }, label = { Text("Character") }, singleLine = true)
                    charError?.let { ErrorText(it) }
                }
                OutlinedTextField(
                    value = symbols,
                    onValueChange = { symbols = it.uppercase() },
                    label = { Text("Symbols, e.g. LSSDT") },
                    singleLine = true,
                )
                HintText("S = short, L = long, D = double, T = triple, exactly ${CodeSymbols.CODE_LENGTH} symbols.")
                codeError?.let { ErrorText(it) }
                OutlinedButton(
                    onClick = { if (ch != null && parsed is CodeInput.Valid) vm.playCode(ch, parsed.code) },
                    enabled = canSave,
                ) { Text("Feel this code") }
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text("Remove this character from the table") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ch != null && parsed is CodeInput.Valid) onSave(ch, parsed.code) }, enabled = canSave) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
