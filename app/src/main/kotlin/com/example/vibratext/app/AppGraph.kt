package com.example.vibratext.app

import android.app.Application
import com.example.vibratext.app.data.LocalStore
import com.example.vibratext.app.notification.Diagnostics
import com.example.vibratext.app.util.InstalledAppsRepository
import com.example.vibratext.app.vibration.AndroidVibrationSink
import com.example.vibratext.app.vibration.PlaybackWakeLock
import com.example.vibratext.data.HistoryRecorder
import com.example.vibratext.data.VibrationUsage
import com.example.vibratext.data.withoutContent
import com.example.vibratext.pipeline.NotificationProcessor
import com.example.vibratext.pipeline.applySettings
import com.example.vibratext.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the dashboard shows as "last notification". Kept in memory only, never written to disk. */
data class LastNotification(
    val applicationName: String,
    val sender: String?,
    val preview: String,
    val timeMs: Long,
)

/**
 * The single set of long-lived objects shared by the UI and the notification service. There is
 * exactly one [PlaybackController], so a manual test and a real notification can never vibrate at
 * the same time.
 */
class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store = LocalStore(app)
    val installedApps = InstalledAppsRepository(app)
    val processor = NotificationProcessor()
    val diagnostics = Diagnostics()

    @Volatile
    private var vibrationUsage = VibrationUsage.NOTIFICATION

    val sink = AndroidVibrationSink(app) { vibrationUsage }
    val controller = PlaybackController(appScope, sink)
    private val wakeLock = PlaybackWakeLock(app)

    private val _listenerConnected = MutableStateFlow(false)

    /** True while Android has our listener service bound and is delivering notifications. */
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _lastNotification = MutableStateFlow<LastNotification?>(null)
    val lastNotification: StateFlow<LastNotification?> = _lastNotification.asStateFlow()

    init {
        // Queue / pacing / vibration-usage settings follow the saved settings live.
        appScope.launch {
            store.settings.collect { s ->
                vibrationUsage = s.vibrationUsage
                controller.applySettings(s)
            }
        }
        // Only the owner of a finished request reports it, so each notification is recorded once.
        controller.listener = HistoryRecorder(System::currentTimeMillis) { entry ->
            appScope.launch {
                val settings = store.settings.first()
                if (settings.historyEnabled) {
                    store.addHistory(if (settings.historyStoreContent) entry else entry.withoutContent(), settings.historyMaxEntries)
                }
            }
        }
        // Stay awake exactly while something is playing or waiting to play.
        appScope.launch {
            controller.state.map { it.isBusy }.distinctUntilChanged().collect { busy ->
                if (busy) wakeLock.acquire() else wakeLock.release()
            }
        }
    }

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun setLastNotification(value: LastNotification?) {
        _lastNotification.value = value
    }
}
