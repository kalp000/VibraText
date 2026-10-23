package com.example.vibratext.app.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.example.vibratext.app.notification.NotificationMonitorService

/** Notification access, rebinding and the system settings pages the user may need. */
object NotificationAccessHelper {

    /** True if the user has granted VibraText notification access in system settings. */
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Opens the page where notification access is granted (the detail page for VibraText if possible). */
    fun openAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, NotificationMonitorService::class.java).flattenToString(),
                )
            if (start(context, detail)) return true
        }
        return start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Asks Android to bind the listener again (needed after the system or the user stopped it). */
    fun requestRebind(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                NotificationListenerService.requestRebind(ComponentName(context, NotificationMonitorService::class.java))
            } catch (_: RuntimeException) {
                // Not granted (yet): nothing to rebind.
            }
        }
    }

    /**
     * The App info page. On Android 13+ a sideloaded (APK-installed) app has "restricted settings":
     * notification access stays greyed out until "Allow restricted settings" is chosen in this
     * page's top-right menu.
     */
    fun openAppInfo(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system list of battery-optimisation exemptions. (The app deliberately does not hold
     * the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission; the user chooses in the list.)
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppInfo(context)

    fun openSoundSettings(context: Context): Boolean = start(context, Intent(Settings.ACTION_SOUND_SETTINGS))

    fun openWebPage(context: Context, url: String): Boolean = start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
