package com.example.vibratext.app.ui.help

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vibratext.app.notification.DiagnosticsSnapshot
import com.example.vibratext.app.ui.GraphViewModel
import com.example.vibratext.app.ui.components.BodyText
import com.example.vibratext.app.ui.components.CardTone
import com.example.vibratext.app.ui.components.HintText
import com.example.vibratext.app.ui.components.InfoCard
import com.example.vibratext.app.ui.components.OnResume
import com.example.vibratext.app.ui.components.PageList
import com.example.vibratext.app.ui.components.ScreenScaffold
import com.example.vibratext.app.util.NotificationAccessHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SystemState(
    val accessGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val hasVibrator: Boolean = false,
    val hasAmplitudeControl: Boolean = false,
)

class HelpViewModel(app: Application) : GraphViewModel(app) {
    private val _system = MutableStateFlow(SystemState())
    val system: StateFlow<SystemState> = _system.asStateFlow()
    val connected = graph.listenerConnected
    val diagnostics: StateFlow<DiagnosticsSnapshot> = graph.diagnostics.snapshot

    fun refresh() {
        val ctx = getApplication<Application>()
        _system.value = SystemState(
            accessGranted = NotificationAccessHelper.isGranted(ctx),
            ignoringBatteryOptimizations = NotificationAccessHelper.isIgnoringBatteryOptimizations(ctx),
            hasVibrator = graph.sink.isAvailable(),
            hasAmplitudeControl = graph.sink.supportsAmplitude(),
        )
    }

    fun resetDiagnostics() = graph.diagnostics.reset()
}

private val AGGRESSIVE_BATTERY_BRANDS = setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "samsung", "asus", "meizu", "nokia", "tecno", "infinix")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelpScreen(onBack: () -> Unit, vm: HelpViewModel = viewModel()) {
    val context = LocalContext.current
    val system by vm.system.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val diag by vm.diagnostics.collectAsStateWithLifecycle()
    OnResume { vm.refresh() }

    ScreenScaffold(title = "Help & reliability", onBack = onBack) { padding ->
        PageList(padding) {
            item {
                InfoCard(title = "Status", tone = if (system.accessGranted && connected) CardTone.GOOD else CardTone.WARNING) {
                    BodyText("Notification access: " + if (system.accessGranted) "granted" else "NOT granted")
                    BodyText("Listener connected: " + if (connected) "yes" else "no")
                    BodyText("Battery optimisation: " + if (system.ignoringBatteryOptimizations) "VibraText is exempt" else "VibraText is optimised (may be limited in the background)")
                    val vibratorText = when {
                        !system.hasVibrator -> "NOT found"
                        system.hasAmplitudeControl -> "present, with strength control"
                        else -> "present, without strength control (intensity settings have no effect)"
                    }
                    BodyText("Vibrator: $vibratorText")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { NotificationAccessHelper.openAccessSettings(context) }) { Text("Notification access") }
                        OutlinedButton(onClick = { NotificationAccessHelper.openBatteryOptimizationSettings(context) }) { Text("Battery settings") }
                        OutlinedButton(onClick = { NotificationAccessHelper.openSoundSettings(context) }) { Text("Sound & vibration") }
                        OutlinedButton(onClick = { NotificationAccessHelper.requestRebind(context) }) { Text("Reconnect") }
                    }
                }
            }

            item {
                InfoCard(title = "Granting notification access") {
                    BodyText("Android shows no pop-up for this. Open “Notification access”, find VibraText in the list and switch it on. Android asks you to confirm.")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        BodyText("Installed from an APK file (not from an app store)? On Android 13 and newer the switch can be greyed out (“Restricted setting”). To allow it:")
                        BodyText("1. Open the App info page below.\n2. Tap the ⋮ menu (top right).\n3. Choose “Allow restricted settings” and confirm with your screen lock.\n4. Go back and switch notification access on.")
                        OutlinedButton(onClick = { NotificationAccessHelper.openAppInfo(context) }) { Text("Open App info") }
                    }
                }
            }

            item {
                InfoCard(title = "If a notification did not vibrate") {
                    BodyText("• Monitoring is on (Home) and the app is selected (Applications).")
                    BodyText("• The notification exposed readable text. Apps with “hide message content” or lock-screen previews turned off may expose only “New message”; VibraText cannot read what Android does not give it.")
                    BodyText("• The phone's own settings allow vibration. Silent mode and Do Not Disturb can suppress vibration; try the “Accessibility” or “Alarm” vibration type in Settings.")
                    BodyText("• Screen state, duplicate filter, Do Not Disturb and call options in Settings are not skipping it (see the counters below).")
                    BodyText("• The battery manager is not stopping the app (see below).")
                    BodyText("• Play the Test screen: if tests vibrate, the vibrator works.")
                }
            }

            item {
                val brand = Build.MANUFACTURER.lowercase()
                InfoCard(title = "Battery management", tone = if (!system.ignoringBatteryOptimizations && brand in AGGRESSIVE_BATTERY_BRANDS) CardTone.WARNING else CardTone.NORMAL) {
                    BodyText("Some phone makers stop background apps aggressively, which can stop notifications from reaching VibraText. If vibrations are missed:")
                    BodyText("• Exempt VibraText from battery optimisation (button above).\n• In your phone's app settings, set battery usage to “Unrestricted” / “No restrictions” and allow auto-start if there is such a switch.\n• Do not swipe VibraText away on phones that treat that as force-stop.")
                    if (brand in AGGRESSIVE_BATTERY_BRANDS) HintText("This looks like a “${Build.MANUFACTURER}” phone, a brand known for strict background limits.")
                    OutlinedButton(onClick = { NotificationAccessHelper.openWebPage(context, "https://dontkillmyapp.com") }) { Text("Per-brand instructions (opens browser)") }
                }
            }

            item {
                InfoCard(title = "What VibraText can and cannot do") {
                    BodyText("• It only sees what an app puts into its notification. Hidden previews, “New message”, or end-to-end-encrypted apps that show no text give nothing to encode.")
                    BodyText("• A sender name is only known if the app exposes it (messaging-style notifications, or the title of a message/email notification). Otherwise the app name is used, or the sender field stays empty, as you choose in Settings.")
                    BodyText("• Long messages take long to feel. Use the maximum length, speed and repeat settings to keep it practical.")
                    BodyText("• Phone vibration motors differ: very short pulses may feel weak, and a few phones limit very long vibration patterns. Use the Test screen to calibrate the timing.")
                    BodyText("• Vibration strength control works only on phones whose vibrator supports it.")
                    BodyText("• Capital letters are lost unless a profile preserves case; characters outside the table are replaced (or the notification is skipped), as the profile says.")
                }
            }

            item {
                InfoCard(title = "Counters since the app started") {
                    DiagnosticsBody(diag)
                    OutlinedButton(onClick = vm::resetDiagnostics) { Text("Reset counters") }
                    HintText("Only counts and reasons are kept, never notification content.")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsBody(d: DiagnosticsSnapshot) {
    BodyText("Notifications from selected apps: ${d.received}   played: ${d.played}")
    if (d.skipped.isEmpty()) {
        HintText("None skipped.")
    } else {
        d.skipped.entries.sortedByDescending { it.value }.forEach { (reason, count) -> BodyText("• ${reason.label}: $count") }
    }
    d.lastError?.let { BodyText("Last internal error: $it") }
}
