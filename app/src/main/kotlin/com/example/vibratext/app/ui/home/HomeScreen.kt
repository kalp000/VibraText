@file:OptIn(ExperimentalLayoutApi::class)

package com.example.vibratext.app.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibratext.app.ui.GraphViewModel
import com.example.vibratext.app.ui.Routes
import com.example.vibratext.app.ui.components.BodyText
import com.example.vibratext.app.ui.components.CardTone
import com.example.vibratext.app.ui.components.Format
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.InfoCard
import com.example.vibratext.app.ui.components.OnResume
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.PlaybackCard
import com.example.vibratext.app.ui.components.PrivacyDialog
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.app.ui.components.SectionHeader
import com.example.vibratext.app.util.NotificationAccessHelper
import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.ProfileBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : GraphViewModel(app) {
    val settings: StateFlow<AppSettings> = graph.store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val profiles: StateFlow<ProfileBook> = graph.store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileBook.initial())
    val listenerConnected = graph.listenerConnected
    val playback = graph.controller.state
    val lastNotification = graph.lastNotification
    val diagnostics = graph.diagnostics.snapshot

    private val _accessGranted = MutableStateFlow(false)
    val accessGranted: StateFlow<Boolean> = _accessGranted.asStateFlow()

    /** Called whenever the screen is shown: the user may have changed notification access in system settings. */
    fun refreshAccess() {
        val granted = NotificationAccessHelper.isGranted(getApplication())
        _accessGranted.value = granted
        if (granted && !graph.listenerConnected.value) NotificationAccessHelper.requestRebind(getApplication())
    }

    fun setMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            graph.store.updateSettings { it.copy(monitoringEnabled = enabled, privacyAcknowledged = it.privacyAcknowledged || enabled) }
            if (!enabled) graph.controller.stop()
        }
    }

    fun stopPlayback() = graph.controller.stop()

    fun replay() {
        graph.controller.replay()
    }

    fun labelFor(packageName: String): String = graph.installedApps.labelFor(packageName)

    fun reconnect() = NotificationAccessHelper.requestRebind(getApplication())
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, vm: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val granted by vm.accessGranted.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val last by vm.lastNotification.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    var askPrivacy by remember { mutableStateOf(false) }

    OnResume { vm.refreshAccess() }

    val status: Pair<String, CardTone> = when {
        !granted -> "Not active: notification access has not been granted" to CardTone.WARNING
        !connected -> "Not active: waiting for Android to connect VibraText to notifications" to CardTone.WARNING
        !settings.monitoringEnabled -> "Paused: monitoring is switched off" to CardTone.NORMAL
        settings.selectedPackages.isEmpty() -> "On, but no apps are selected, so nothing will vibrate" to CardTone.WARNING
        else -> "Active" to CardTone.GOOD
    }

    ScreenScaffold(title = "VibraText") { padding ->
        PageList(padding) {
            item {
                InfoCard(title = "Notification monitoring", tone = status.second) {
                    BodyText(status.first)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (settings.monitoringEnabled) "Monitoring is on" else "Monitoring is off")
                            HintText("VibraText never vibrates on its own until you switch this on.")
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = settings.monitoringEnabled,
                            onCheckedChange = { on ->
                                if (on && !settings.privacyAcknowledged) askPrivacy = true else vm.setMonitoring(on)
                            },
                        )
                    }
                    if (!granted) {
                        Button(onClick = { NotificationAccessHelper.openAccessSettings(context) }) { Text("Enable Notification Access") }
                        HintText("If the switch is greyed out, open Help for the “restricted settings” step.")
                    } else if (!connected) {
                        OutlinedButton(onClick = vm::reconnect) { Text("Reconnect") }
                    }
                }
            }

            if (playback.isBusy || playback.lastError != null) {
                item { PlaybackCard(playback, onStop = vm::stopPlayback) }
            }

            val steps = listOf(
                "Grant notification access" to granted,
                "Choose the apps to monitor" to settings.selectedPackages.isNotEmpty(),
                "Switch monitoring on" to settings.monitoringEnabled,
            )
            if (steps.any { !it.second }) {
                item {
                    InfoCard(title = "Getting started") {
                        steps.forEachIndexed { i, (label, done) -> BodyText("${if (done) "✓" else "${i + 1}."} $label") }
                        HintText("Then use “Test Vibration” to learn the patterns before relying on them.")
                    }
                }
            }

            item {
                InfoCard(title = "Selected applications") {
                    if (settings.selectedPackages.isEmpty()) {
                        BodyText("None yet.")
                    } else {
                        settings.selectedPackages.map { vm.labelFor(it) }.sortedBy { it.lowercase() }.forEach { BodyText("• $it") }
                    }
                }
            }

            item {
                InfoCard(title = "Current profile") {
                    BodyText(profiles.default.name)
                    HintText("The profile used when no rule picks another one.")
                }
            }

            item {
                InfoCard(title = "Last notification") {
                    val l = last
                    if (l == null) {
                        BodyText("No notification has been played since the app started.")
                    } else {
                        val text = listOfNotNull(l.sender, l.preview.ifBlank { null }).joinToString(": ")
                        BodyText(l.applicationName + if (text.isNotEmpty()) " – $text" else "")
                        HintText(Format.time(l.timeMs))
                    }
                    diagnostics.lastSkipped?.let { HintText("Most recent skipped notification: ${it.label}") }
                }
            }

            item { SectionHeader("Quick actions") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onNavigate(Routes.TEST) }) { Text("Test Vibration") }
                    Button(onClick = vm::stopPlayback, enabled = playback.isBusy) { Text("Stop Playback") }
                    FilledTonalButton(onClick = vm::replay) { Text("Replay last") }
                    FilledTonalButton(onClick = { onNavigate(Routes.PROFILES) }) { Text("Customize Encoding") }
                    FilledTonalButton(onClick = { onNavigate(Routes.APPS) }) { Text("Manage Applications") }
                    FilledTonalButton(onClick = { onNavigate(Routes.HISTORY) }) { Text("History") }
                    FilledTonalButton(onClick = { onNavigate(Routes.RULES) }) { Text("Rules") }
                    FilledTonalButton(onClick = { onNavigate(Routes.DECODER) }) { Text("Decoder") }
                    OutlinedButton(onClick = { onNavigate(Routes.HELP) }) { Text("Help & reliability") }
                }
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
}
