package com.example.vibratext.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibratext.app.ui.GraphViewModel
import com.example.vibratext.app.ui.components.BodyText
import com.example.vibratext.app.ui.components.ChoiceRow
import com.example.vibratext.app.ui.components.ConfirmDialog
import com.example.vibratext.app.ui.components.ErrorText
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.PrivacyDialog
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.app.ui.components.SectionHeader
import com.example.vibratext.app.ui.components.SettingSlider
import com.example.vibratext.app.ui.components.SettingSwitch
import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.data.LongMessagePolicy
import com.example.vibratext.data.PlaybackMode
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.data.UnknownSenderBehavior
import com.example.vibratext.data.VibrationUsage
import com.example.vibratext.notification.TemplateRenderer
import com.example.vibratext.playback.QueuePolicy
import com.example.vibratext.playback.SpeedPreset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : GraphViewModel(app) {
    val settings: StateFlow<AppSettings> = graph.store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val profiles: StateFlow<ProfileBook> = graph.store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.store.updateSettings(transform) }
    }

    fun setMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            graph.store.updateSettings { it.copy(monitoringEnabled = enabled, privacyAcknowledged = it.privacyAcknowledged || enabled) }
            if (!enabled) graph.controller.stop()
        }
    }

    fun setDefaultProfile(id: String) {
        viewModelScope.launch { graph.store.updateProfiles { it.setDefault(id) } }
    }

    /** Quick controls for the default profile; the full editor is on the Profiles screen. */
    fun updateDefaultProfile(transform: (EncodingProfile) -> EncodingProfile) {
        viewModelScope.launch {
            graph.store.updateProfiles { book ->
                val updated = transform(book.default)
                if (updated.validate().isEmpty()) book.upsert(updated) else book
            }
        }
    }

    fun setHistoryMax(max: Int) {
        viewModelScope.launch {
            graph.store.updateSettings { it.copy(historyMaxEntries = max) }
            graph.store.trimHistory(max)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { graph.store.clearHistory() }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            graph.controller.stop()
            graph.store.clearAll()
            graph.setLastNotification(null)
            graph.diagnostics.reset()
        }
    }
}

@Composable
fun SettingsScreen(onOpenApps: () -> Unit, onOpenProfiles: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val book by vm.profiles.collectAsStateWithLifecycle()
    var askPrivacy by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var templateText by remember(s.template) { mutableStateOf(s.template) }
    val templateErrors = TemplateRenderer.validate(templateText)
    val defaultProfile = book.default

    ScreenScaffold(title = "Settings") { padding ->
        PageList(padding) {
            item { SectionHeader("Monitoring") }
            item {
                SettingSwitch(
                    title = "Notification monitoring",
                    description = "Master switch. Nothing vibrates while this is off. Notification access itself is granted in Android's settings (see Home).",
                    checked = s.monitoringEnabled,
                    onCheckedChange = { on -> if (on && !s.privacyAcknowledged) askPrivacy = true else vm.setMonitoring(on) },
                )
            }
            item {
                BodyText("${s.selectedPackages.size} app(s) selected.")
                OutlinedButton(onClick = onOpenApps) { Text("Choose monitored apps") }
            }

            item { SectionHeader("When to play") }
            item {
                SettingSwitch("Play while the screen is locked", "Vibrate for notifications that arrive while the phone is locked or the screen is off.", s.playWhileLocked) { v -> vm.update { it.copy(playWhileLocked = v) } }
            }
            item {
                SettingSwitch("Play while the phone is unlocked", "Vibrate for notifications that arrive while you are using the phone.", s.playWhileUnlocked) { v -> vm.update { it.copy(playWhileUnlocked = v) } }
            }
            item {
                SettingSwitch("Play silent notifications", "Include notifications the app posted as “silent” (no sound or vibration of their own).", s.playSilent) { v -> vm.update { it.copy(playSilent = v) } }
            }
            item {
                SettingSwitch("Play grouped notifications", "Also play the summary entry Android creates for a stack of notifications. Usually leave off, because each message already plays on its own.", s.playGroupSummaries) { v -> vm.update { it.copy(playGroupSummaries = v) } }
            }
            item {
                SettingSwitch("Play ongoing notifications", "Include permanent notifications such as music players or downloads. Usually leave off.", s.playOngoing) { v -> vm.update { it.copy(playOngoing = v) } }
            }
            item {
                SettingSwitch("Skip notifications with nothing readable", "Ignore notifications that expose no title or text (for example when the app hides previews).", s.requireReadableContent) { v -> vm.update { it.copy(requireReadableContent = v) } }
            }

            item { SectionHeader("What to play") }
            item {
                ChoiceRow(
                    title = "Playback mode",
                    description = s.playbackMode.description,
                    options = PlaybackMode.entries.map { it.label to it },
                    selected = s.playbackMode,
                    onSelect = { v -> vm.update { it.copy(playbackMode = v) } },
                )
            }
            if (s.playbackMode == PlaybackMode.TEMPLATE) {
                item {
                    OutlinedTextField(
                        value = templateText,
                        onValueChange = {
                            templateText = it
                            if (TemplateRenderer.validate(it).isEmpty()) vm.update { s2 -> s2.copy(template = it) }
                        },
                        label = { Text("Template") },
                        singleLine = true,
                        isError = templateErrors.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HintText("Placeholders: {app} {sender} {message} {time}. If one is missing from a notification, a fallback is used instead.")
                    templateErrors.forEach { ErrorText(it) }
                }
            }
            item {
                SettingSwitch("Only important notifications", "Play only notifications that match a rule marked “important” (set up on the Rules screen).", s.onlyImportant) { v -> vm.update { it.copy(onlyImportant = v) } }
            }

            item { SectionHeader("Long messages") }
            item {
                SettingSlider(
                    title = "Maximum message length",
                    description = "Longer messages are shortened according to the choice below. A 100-character message is hundreds of pulses, so keep this practical.",
                    value = s.maxMessageChars, range = 5..500, step = 5, format = { "$it chars" },
                    onCommit = { v -> vm.update { it.copy(maxMessageChars = v) } },
                )
            }
            item {
                ChoiceRow(
                    title = "When a message is too long",
                    options = LongMessagePolicy.entries.map { it.label to it },
                    selected = s.longMessagePolicy,
                    onSelect = { v -> vm.update { it.copy(longMessagePolicy = v) } },
                )
            }
            item {
                SettingSwitch("Mark shortened messages", "Play a TRUNCATED marker after anything that was cut, so you always know the message was not complete.", s.playTruncationMarker) { v -> vm.update { it.copy(playTruncationMarker = v) } }
            }
            item {
                SettingSlider(
                    title = "Maximum sender name length",
                    description = "Longer names are shortened (and marked).",
                    value = s.maxSenderChars, range = 5..50, step = 1, format = { "$it chars" },
                    onCommit = { v -> vm.update { it.copy(maxSenderChars = v) } },
                )
            }
            item {
                ChoiceRow(
                    title = "When the sender is unknown",
                    description = "Many apps (and notifications with hidden previews) do not expose who wrote the message.",
                    options = UnknownSenderBehavior.entries.map { it.label to it },
                    selected = s.unknownSenderBehavior,
                    onSelect = { v -> vm.update { it.copy(unknownSenderBehavior = v) } },
                )
            }

            item { SectionHeader("Filtering") }
            item {
                SettingSwitch("Ignore duplicate events", "Apps often re-post the same notification. Repeats within the window below are ignored; a genuinely new message is never treated as a duplicate.", s.ignoreDuplicates) { v -> vm.update { it.copy(ignoreDuplicates = v) } }
            }
            if (s.ignoreDuplicates) {
                item {
                    SettingSlider(
                        title = "Duplicate window",
                        description = "How long an identical notification counts as a repeat.",
                        value = (s.duplicateWindowMs / 1000).toInt(), range = 5..300, step = 5, format = { "$it s" },
                        onCommit = { v -> vm.update { it.copy(duplicateWindowMs = v * 1000L) } },
                    )
                }
            }
            item {
                SettingSwitch("Skip while Do Not Disturb is on", "Do not vibrate while any Do Not Disturb mode is active.", s.ignoreWhenDnd) { v -> vm.update { it.copy(ignoreWhenDnd = v) } }
            }
            item {
                SettingSwitch("Pause during phone calls", "Skip notifications while a phone or VoIP call is active or the phone is ringing.", s.pauseDuringCalls) { v -> vm.update { it.copy(pauseDuringCalls = v) } }
            }

            item { SectionHeader("Timing between notifications") }
            item {
                SettingSlider(
                    title = "Delay before playback",
                    description = "Wait this long after a notification arrives before vibrating.",
                    value = s.startDelayMs.toInt(), range = 0..10_000, step = 250, format = { "${it / 1000.0} s" },
                    onCommit = { v -> vm.update { it.copy(startDelayMs = v.toLong()) } },
                )
            }
            item {
                SettingSlider(
                    title = "Minimum time between notifications",
                    description = "Quiet time between the end of one playback and the start of the next.",
                    value = s.minIntervalMs.toInt(), range = 0..30_000, step = 500, format = { "${it / 1000.0} s" },
                    onCommit = { v -> vm.update { it.copy(minIntervalMs = v.toLong()) } },
                )
            }
            item {
                ChoiceRow(
                    title = "When another notification arrives during playback",
                    description = s.queuePolicy.description,
                    options = QueuePolicy.entries.map { it.label to it },
                    selected = s.queuePolicy,
                    onSelect = { v -> vm.update { it.copy(queuePolicy = v) } },
                )
            }
            item {
                SettingSlider(
                    title = "Queue length",
                    description = "How many notifications may wait. When it is full the oldest is dropped.",
                    value = s.maxQueueSize, range = 1..20, step = 1, format = { "$it" },
                    onCommit = { v -> vm.update { it.copy(maxQueueSize = v) } },
                )
            }
            item {
                SettingSwitch("New notification interrupts the current one", "Stop what is playing and start the new notification immediately, instead of using the queue.", s.interruptCurrent) { v -> vm.update { it.copy(interruptCurrent = v) } }
            }

            item { SectionHeader("Vibration output") }
            item {
                ChoiceRow(
                    title = "Vibration type",
                    description = s.vibrationUsage.description,
                    options = VibrationUsage.entries.map { it.label to it },
                    selected = s.vibrationUsage,
                    onSelect = { v -> vm.update { it.copy(vibrationUsage = v) } },
                )
            }

            item { SectionHeader("Default profile") }
            item {
                ChoiceRow(
                    title = "Default profile",
                    description = "Used for every notification that no rule assigns to another profile.",
                    options = book.profiles.map { it.name to it.id },
                    selected = book.defaultProfileId,
                    onSelect = vm::setDefaultProfile,
                )
            }
            item {
                HintText("Quick settings for “${defaultProfile.name}”:")
                ChoiceRow(
                    title = "Playback speed",
                    options = SpeedPreset.entries.map { it.label to it },
                    selected = defaultProfile.speed.preset,
                    onSelect = { v -> vm.updateDefaultProfile { it.copy(speed = it.speed.copy(preset = v)) } },
                )
            }
            item {
                SettingSlider(
                    title = "Repeat notifications",
                    description = "How many times each notification is played.",
                    value = defaultProfile.repeat.count, range = 1..10, step = 1, format = { "$it×" },
                    onCommit = { v -> vm.updateDefaultProfile { it.copy(repeat = it.repeat.copy(count = v)) } },
                )
            }
            item {
                SettingSwitch("Completion tick", "A very short pulse after the message so you know it has finished.", defaultProfile.completionTick) { v ->
                    vm.updateDefaultProfile { it.copy(completionTick = v) }
                }
            }
            item { OutlinedButton(onClick = onOpenProfiles) { Text("All profiles and full editor") } }

            item { SectionHeader("History") }
            item {
                SettingSwitch("Keep a history", "Record processed notifications on this phone only. Never uploaded.", s.historyEnabled) { v -> vm.update { it.copy(historyEnabled = v) } }
            }
            item {
                SettingSwitch("Store sender and message preview", "When off, history keeps only app, time, profile and status.", s.historyStoreContent, enabled = s.historyEnabled) { v ->
                    vm.update { it.copy(historyStoreContent = v) }
                }
            }
            item {
                SettingSlider(
                    title = "History size",
                    description = "Older entries are removed automatically.",
                    value = s.historyMaxEntries, range = 10..1000, step = 10, format = { "$it entries" },
                    onCommit = vm::setHistoryMax,
                )
            }
            item { OutlinedButton(onClick = { confirmClearHistory = true }) { Text("Clear notification history") } }

            item { SectionHeader("Your data") }
            item {
                HintText("Settings, profiles, rules and history are stored only on this phone and are not included in backups.")
                OutlinedButton(onClick = { confirmDeleteAll = true }) { Text("Delete all VibraText data") }
            }
        }
    }

    if (askPrivacy) {
        PrivacyDialog(
            onConfirm = {
                askPrivacy = false
                vm.setMonitoring(true)
            },
            onDismiss = { askPrivacy = false },
        )
    }
    if (confirmClearHistory) {
        ConfirmDialog("Clear the history?", "All recorded notifications are deleted from this phone.", "Clear", {
            vm.clearHistory()
            confirmClearHistory = false
        }, { confirmClearHistory = false })
    }
    if (confirmDeleteAll) {
        ConfirmDialog(
            "Delete all data?",
            "This removes your settings, profiles, rules and history and switches monitoring off. Notification access stays granted in Android's settings.",
            "Delete everything",
            {
                vm.deleteAllData()
                confirmDeleteAll = false
            },
            { confirmDeleteAll = false },
        )
    }
}
