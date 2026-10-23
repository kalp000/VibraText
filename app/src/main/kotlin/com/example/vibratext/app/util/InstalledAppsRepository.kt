package com.example.vibratext.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class InstalledApp(
    val packageName: String,
    val label: String,
    /** A pre-installed app that was never updated (hidden by default in the picker). */
    val isSystem: Boolean,
)

/**
 * Finds the apps the user can choose to monitor: every installed app with a launcher icon (the
 * manifest declares the matching `<queries>` entry, so no broad package permission is needed).
 */
class InstalledAppsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val labelCache = ConcurrentHashMap<String, String>()

    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val pkg = activity.packageName
            if (pkg == appContext.packageName) return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            labelCache[pkg] = label
            val flags = activity.applicationInfo?.flags ?: 0
            val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            InstalledApp(pkg, label, isSystem)
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** The display name of [packageName], or the package name if the app cannot be seen or is gone. */
    fun labelFor(packageName: String): String = labelCache.getOrPut(packageName) {
        try {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().ifBlank { packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    companion object {
        /** Messaging apps offered first in the picker when they are installed. */
        val KNOWN_MESSENGERS: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.thoughtcrime.securesms",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.google.android.apps.messaging",
            "com.facebook.orca",
            "com.discord",
            "org.signal.signal",
        )
    }
}
