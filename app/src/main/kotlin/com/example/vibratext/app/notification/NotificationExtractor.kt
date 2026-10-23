package com.example.vibratext.app.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.vibratext.notification.RawMessage
import com.example.vibratext.notification.RawNotification

/**
 * Turns an Android [StatusBarNotification] into the plain [RawNotification] that :core understands.
 * This is the only place that touches notification content, and it is called only for apps the user
 * selected. It reads exactly what the app chose to expose:
 *  - the standard title / text / big text / sub text extras,
 *  - the MessagingStyle conversation (sender and text of each message) if the app uses it.
 * Nothing here logs or stores anything.
 */
object NotificationExtractor {
    private const val MAX_FIELD_CHARS = 4_000
    private const val MAX_MESSAGES = 10
    private const val IMPORTANCE_LOW = 2

    /** [importance]: the ranking importance (0..5) the system assigned, if known. */
    fun extract(sbn: StatusBarNotification, importance: Int?): RawNotification =
        extract(sbn.notification, sbn.packageName, sbn.key, sbn.id, sbn.postTime, importance)

    /** Same, from the parts of a notification (this is the form the instrumented tests use). */
    fun extract(n: Notification, packageName: String, key: String, id: Int, postTime: Long, importance: Int?): RawNotification {
        val extras = n.extras
        fun text(extraKey: String): String? = extras?.getCharSequence(extraKey)?.toString()?.take(MAX_FIELD_CHARS)

        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (_: RuntimeException) {
            null // a malformed style bundle must never break the service
        }

        val messages = ArrayList<RawMessage>()
        var isGroup = false
        var conversationTitle = text(Notification.EXTRA_CONVERSATION_TITLE)
        if (style != null) {
            val me = style.user
            style.messages.takeLast(MAX_MESSAGES).forEach { m ->
                val person = m.person
                val name = person?.name?.toString()?.take(MAX_FIELD_CHARS)
                val fromSelf = person != null && (
                    (person.key != null && person.key == me.key) ||
                        (person.key == null && me.key == null && !name.isNullOrBlank() && name == me.name?.toString())
                    )
                messages += RawMessage(
                    sender = name,
                    text = m.text?.toString()?.take(MAX_FIELD_CHARS),
                    timestamp = m.timestamp,
                    fromSelf = fromSelf,
                )
            }
            isGroup = style.isGroupConversation
            conversationTitle = style.conversationTitle?.toString()?.take(MAX_FIELD_CHARS) ?: conversationTitle
        }

        val flags = n.flags
        return RawNotification(
            packageName = packageName,
            key = key,
            id = id,
            postTime = postTime,
            title = text(Notification.EXTRA_TITLE),
            text = text(Notification.EXTRA_TEXT),
            bigText = text(Notification.EXTRA_BIG_TEXT),
            subText = text(Notification.EXTRA_SUB_TEXT),
            conversationTitle = conversationTitle,
            messages = messages,
            category = n.category,
            isMessagingStyle = style != null,
            isGroupConversation = isGroup,
            isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0 || (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isSilent = importance != null && importance <= IMPORTANCE_LOW,
            importance = importance,
        )
    }
}
