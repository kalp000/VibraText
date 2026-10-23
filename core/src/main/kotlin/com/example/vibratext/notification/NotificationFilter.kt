package com.example.vibratext.notification

import com.example.vibratext.data.AppSettings

/** Why a notification was not played. Only used for diagnostics; never stores notification content. */
enum class RejectReason(val label: String) {
    MONITORING_OFF("Monitoring is off"),
    OWN_APP("Notification from VibraText itself"),
    NOT_SELECTED("App is not selected"),
    ONGOING("Ongoing notification"),
    GROUP_SUMMARY("Group summary"),
    SILENT("Silent notification"),
    NO_READABLE_CONTENT("Nothing readable in the notification"),
    SCREEN_LOCKED("Disabled while the screen is locked"),
    SCREEN_UNLOCKED("Disabled while the screen is unlocked"),
    DO_NOT_DISTURB("Do Not Disturb is on"),
    IN_CALL("A phone call is active"),
    MUTED_BY_RULE("Muted by a rule"),
    NOT_IMPORTANT("Not marked important"),
    DUPLICATE("Duplicate of a recent notification"),
    UNSUPPORTED_CHARACTER("Text contains characters this profile cannot encode"),
}

/** The phone state the filter needs; supplied by the Android layer. */
data class NotificationContext(
    val ownPackage: String,
    /** Screen on and device unlocked. */
    val deviceInUse: Boolean,
    val doNotDisturbActive: Boolean = false,
    val inCall: Boolean = false,
    val nowMs: Long = 0L,
)

object NotificationFilter {
    /** Cheap checks that need no parsing. Returns the reason to reject, or null to accept. */
    fun check(raw: RawNotification, settings: AppSettings, ctx: NotificationContext): RejectReason? {
        if (!settings.monitoringEnabled) return RejectReason.MONITORING_OFF
        if (raw.packageName == ctx.ownPackage) return RejectReason.OWN_APP
        if (raw.packageName !in settings.selectedPackages) return RejectReason.NOT_SELECTED
        if (raw.isOngoing && !settings.playOngoing) return RejectReason.ONGOING
        if (raw.isGroupSummary && !settings.playGroupSummaries) return RejectReason.GROUP_SUMMARY
        if (raw.isSilent && !settings.playSilent) return RejectReason.SILENT
        if (settings.requireReadableContent && !NotificationParser.hasReadableContent(raw)) return RejectReason.NO_READABLE_CONTENT
        if (ctx.deviceInUse && !settings.playWhileUnlocked) return RejectReason.SCREEN_UNLOCKED
        if (!ctx.deviceInUse && !settings.playWhileLocked) return RejectReason.SCREEN_LOCKED
        if (ctx.doNotDisturbActive && settings.ignoreWhenDnd) return RejectReason.DO_NOT_DISTURB
        if (ctx.inCall && settings.pauseDuringCalls) return RejectReason.IN_CALL
        return null
    }
}

/**
 * Detects repeated posts of the same notification (apps re-post on every ranking or progress
 * update). The signature of a MessagingStyle notification includes its latest message's
 * timestamp, so a genuinely new message in the same conversation is never treated as a duplicate.
 * Thread-safe, bounded.
 */
class NotificationDeduplicator(private val maxEntries: Int = 256) {
    private val seen = LinkedHashMap<String, Long>()

    /** True if [signature] was already seen within [windowMs]. Records it otherwise. */
    @Synchronized
    fun isDuplicate(signature: String, nowMs: Long, windowMs: Long): Boolean {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().value > windowMs) iterator.remove() else break
        }
        val previous = seen[signature]
        if (previous != null && nowMs - previous <= windowMs) return true
        seen.remove(signature)
        seen[signature] = nowMs
        while (seen.size > maxEntries) {
            val eldest = seen.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return false
    }

    @Synchronized
    fun clear() = seen.clear()

    companion object {
        fun signature(raw: RawNotification): String {
            val latest = raw.messages.lastOrNull { !it.fromSelf }
            return if (latest != null) {
                "${raw.packageName}|${raw.key}|m|${latest.timestamp}|${latest.sender}|${latest.text.hashCode()}"
            } else {
                "${raw.packageName}|${raw.key}|g|${raw.title.hashCode()}|${raw.text.hashCode()}|${raw.bigText.hashCode()}"
            }
        }
    }
}
