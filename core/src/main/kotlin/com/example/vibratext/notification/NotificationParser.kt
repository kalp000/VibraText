package com.example.vibratext.notification

/**
 * Interprets a [RawNotification] using only what the notification actually exposes.
 *
 * Priority (as specified):
 *  1. MessagingStyle: the latest *incoming* message with its explicit sender and text.
 *  2. Title and text of an ordinary notification.
 *  3. Expanded (big) text when the short text is missing or cut off with an ellipsis.
 *  4. Conversation title (used as the sender only for one-to-one chats, never for groups).
 *  5. Nothing readable → messageText = null; the composer then plays only what exists.
 *
 * Who is "the sender" for an ordinary notification?
 *  - Person-to-person categories (msg, email, social) or MessagingStyle: the title.
 *  - Anything else (a download finished, a delivery update…): there is no sender. The title is
 *    part of the message instead, so we never present a headline as if it were a person.
 */
object NotificationParser {
    const val CATEGORY_MESSAGE = "msg"
    const val CATEGORY_EMAIL = "email"
    const val CATEGORY_SOCIAL = "social"
    const val CATEGORY_CALL = "call"

    private val personCategories = setOf(CATEGORY_MESSAGE, CATEGORY_EMAIL, CATEGORY_SOCIAL)

    fun parse(raw: RawNotification, applicationName: String): ParsedNotification {
        val subText = clean(raw.subText)
        var sender: String?
        var message: String?
        var source = SenderSource.NONE
        var timestamp = raw.postTime
        val conversationTitle = clean(raw.conversationTitle)

        val incoming = raw.messages.lastOrNull { !it.fromSelf && !clean(it.text).isNullOrBlank() }
        if (incoming != null) {
            sender = clean(incoming.sender)
            message = clean(incoming.text)
            if (sender != null) source = SenderSource.MESSAGING_STYLE
            if (incoming.timestamp > 0) timestamp = incoming.timestamp
            if (sender == null && !raw.isGroupConversation) {
                // one-to-one chat: the conversation partner is the sender
                val partner = clean(raw.title) ?: conversationTitle
                if (partner != null) {
                    sender = partner
                    source = SenderSource.TITLE
                }
            }
        } else {
            val title = clean(raw.title) ?: if (raw.isGroupConversation) null else conversationTitle
            message = chooseMessage(clean(raw.text), clean(raw.bigText))
            if (raw.isMessagingStyle || raw.category in personCategories) {
                sender = title
                if (sender != null) source = SenderSource.TITLE
            } else {
                sender = null
                message = combineHeadline(title, message)
            }
        }

        // "Alex: hello" in a message whose sender is already Alex: don't say the name twice.
        if (sender != null && message != null) {
            val prefix = "$sender:"
            if (message.startsWith(prefix, ignoreCase = true)) {
                val rest = message.substring(prefix.length).trim()
                if (rest.isNotEmpty()) message = rest
            }
        }

        return ParsedNotification(
            packageName = raw.packageName,
            applicationName = applicationName,
            senderName = sender,
            messageText = message,
            conversationTitle = conversationTitle,
            timestamp = timestamp,
            notificationKey = raw.key,
            notificationCategory = raw.category,
            summaryText = subText,
            senderSource = source,
            isGroupConversation = raw.isGroupConversation,
            importance = raw.importance,
            isMessaging = raw.isMessagingStyle || raw.messages.isNotEmpty(),
        )
    }

    /** True if the notification exposes anything at all that could be played. */
    fun hasReadableContent(raw: RawNotification): Boolean =
        clean(raw.title) != null || clean(raw.text) != null || clean(raw.bigText) != null ||
            raw.messages.any { !clean(it.text).isNullOrBlank() }

    /** Trims, removes invisible direction marks and returns null for blank text. */
    fun clean(s: String?): String? {
        if (s == null) return null
        val sb = StringBuilder(s.length)
        for (c in s) {
            val type = Character.getType(c)
            if (type == Character.FORMAT.toInt() || (Character.isISOControl(c) && !c.isWhitespace())) continue
            sb.append(c)
        }
        val t = sb.toString().trim()
        return t.ifEmpty { null }
    }

    private fun chooseMessage(text: String?, big: String?): String? {
        if (text == null) return big
        if (big == null) return text
        val stem = text.trimEnd('…', '.', ' ')
        val cutOff = text.endsWith("…") || text.endsWith("...")
        return if (cutOff && big.length > stem.length && big.startsWith(stem)) big else text
    }

    private fun combineHeadline(title: String?, message: String?): String? = when {
        title == null -> message
        message == null -> title
        title.equals(message, ignoreCase = true) -> message
        title.last() in ".!?:" -> "$title $message"
        else -> "$title. $message"
    }
}
