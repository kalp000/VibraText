package com.example.vibratext.app.notification

import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.vibratext.app.AppGraph
import com.example.vibratext.app.LastNotification
import com.example.vibratext.app.VibraTextApp
import com.example.vibratext.notification.NotificationContext
import com.example.vibratext.pipeline.ProcessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receives every notification the system posts, but looks inside only the ones from apps the user
 * selected (checked before any content is read). Work is done off the main thread and strictly one
 * notification at a time, in arrival order.
 *
 * Android binds this service only while notification access is granted. It reports whether it is
 * currently bound through [AppGraph.listenerConnected] so the dashboard can say "Active" only when
 * that is really true.
 */
class NotificationMonitorService : NotificationListenerService() {

    private class Incoming(val sbn: StatusBarNotification, val importance: Int?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val incoming = Channel<Incoming>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val graph: AppGraph get() = (application as VibraTextApp).graph

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (item in incoming) {
                try {
                    handle(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only the class name is kept: messages of exceptions could contain notification text.
                    graph.diagnostics.recordError(e)
                    Log.w(TAG, "A notification could not be processed (${e.javaClass.simpleName})")
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        graph.setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        graph.setListenerConnected(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, NotificationMonitorService::class.java))
            } catch (_: RuntimeException) {
                // The user may have revoked access; nothing to rebind then.
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: NotificationListenerService.RankingMap?) {
        if (sbn == null) return
        // Cheap and content-free: only the package name and the ranking importance are read here.
        var importance: Int? = null
        if (rankingMap != null) {
            val ranking = NotificationListenerService.Ranking()
            if (rankingMap.getRanking(sbn.key, ranking)) importance = ranking.importance
        }
        incoming.trySend(Incoming(sbn, importance))
    }

    override fun onDestroy() {
        graph.setListenerConnected(false)
        incoming.close()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handle(item: Incoming) {
        val g = graph
        val pkg = item.sbn.packageName
        val settings = g.store.settings.first()

        // Selection is checked BEFORE any content is read: other apps' notifications are never opened.
        if (!settings.monitoringEnabled || pkg == packageName || pkg !in settings.selectedPackages) return

        val raw = NotificationExtractor.extract(item.sbn, item.importance)
        val ctx = NotificationContext(
            ownPackage = packageName,
            deviceInUse = DeviceState.deviceInUse(this),
            doNotDisturbActive = DeviceState.doNotDisturbActive(this),
            inCall = DeviceState.inCall(this),
            nowMs = System.currentTimeMillis(),
        )
        val result = g.processor.process(
            raw = raw,
            applicationName = g.installedApps.labelFor(pkg),
            settings = settings,
            rules = g.store.rules.first(),
            book = g.store.profiles.first(),
            ctx = ctx,
        )
        when (result) {
            is ProcessResult.Ignored -> g.diagnostics.recordSkipped(result.reason)
            is ProcessResult.Play -> {
                g.diagnostics.recordPlayed()
                g.setLastNotification(
                    LastNotification(
                        applicationName = result.parsed.applicationName,
                        sender = if (settings.historyStoreContent) result.parsed.senderName else null,
                        preview = if (settings.historyStoreContent) result.composed.preview else "",
                        timeMs = System.currentTimeMillis(),
                    ),
                )
                g.controller.submit(result.request, interruptCurrent = settings.interruptCurrent)
            }
        }
    }

    private companion object {
        const val TAG = "VibraText"
    }
}
