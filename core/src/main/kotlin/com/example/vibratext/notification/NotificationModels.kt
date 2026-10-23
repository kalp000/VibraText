package com.example.vibratext.notification

/** One message inside a MessagingStyle notification (already unwrapped from Android types). */
data class RawMessage(
    val sender: String?,
    val text: String?,
    val timestamp: Long,
    /** True when the message was written by the device owner (a reply), not received. */
    val fromSelf: Boolean = false,
)

/**
 * Everything the Android layer extracted from a StatusBarNotification, as plain data. The
 * extractor in :app builds this; all decisions about it are made in :core so they can be tested.
 */
data class RawNotification(
    val packageName: String,
    val key: String,
    val id: Int = 0,
    val postTime: Long = 0L,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val conversationTitle: String? = null,
    val messages: List<RawMessage> = emptyList(),
    /** Notification.category, e.g. "msg", "email", "call", "social". */
    val category: String? = null,
    val isMessagingStyle: Boolean = false,
    val isGroupConversation: Boolean = false,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    /** Ranking importance <= IMPORTANCE_LOW (no sound, no vibration of its own). */
    val isSilent: Boolean = false,
    /** NotificationManager importance (0..5) if known. */
    val importance: Int? = null,
)

enum class SenderSource { MESSAGING_STYLE, TITLE, NONE }

/** The interpreted notification: who said what, if the notification actually exposes it. */
data class ParsedNotification(
    val packageName: String,
    val applicationName: String,
    val senderName: String?,
    val messageText: String?,
    val conversationTitle: String?,
    val timestamp: Long,
    val notificationKey: String,
    val notificationCategory: String?,
    val summaryText: String? = null,
    val senderSource: SenderSource = SenderSource.NONE,
    val isGroupConversation: Boolean = false,
    val importance: Int? = null,
    val isMessaging: Boolean = false,
) {
    val hasMessage: Boolean get() = !messageText.isNullOrBlank()
    val hasSender: Boolean get() = !senderName.isNullOrBlank()
}
