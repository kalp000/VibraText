package com.example.vibratext.notification

import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.LongMessagePolicy
import com.example.vibratext.data.PlaybackMode
import com.example.vibratext.data.UnknownSenderBehavior
import com.example.vibratext.encoding.FrameField
import com.example.vibratext.encoding.MessageSection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ComposedMessage(
    val fields: List<FrameField>,
    /** The message was shortened or left out (the user is told in the UI; a marker may be played). */
    val truncated: Boolean,
    val originalMessageLength: Int,
    /** Short readable summary for the dashboard / history. */
    val preview: String,
    val mode: PlaybackMode,
)

/**
 * Turns a [ParsedNotification] into the fields that are framed and played, according to the
 * playback mode and the length policy. Nothing is silently shortened: [ComposedMessage.truncated]
 * is set and (optionally) a TRUNCATED marker is appended to the shortened field.
 */
object MessageComposer {
    const val HARD_MAX_MESSAGE_CHARS = 500
    const val APP_ID_MAX_CHARS = 12

    private data class Limited(val text: String?, val truncated: Boolean)

    fun compose(
        parsed: ParsedNotification,
        mode: PlaybackMode,
        settings: AppSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ComposedMessage {
        val marker = settings.playTruncationMarker
        val app = appId(parsed.applicationName)

        val senderLimited = parsed.senderName?.takeIf { it.isNotBlank() }?.let { limit(it, settings.maxSenderChars) }
        val message = decideMessage(parsed, settings)
        val fields = ArrayList<FrameField>()
        var truncated = message.truncated || (senderLimited?.truncated == true)

        fun senderField(): FrameField? {
            return when {
                senderLimited != null -> FrameField(MessageSection.SENDER, senderLimited.text!!, senderLimited.truncated && marker)
                settings.unknownSenderBehavior == UnknownSenderBehavior.APP_AS_SENDER -> FrameField(MessageSection.SENDER, app)
                else -> FrameField(MessageSection.SENDER, "")
            }
        }

        fun messageField(): FrameField? = message.text?.let { FrameField(MessageSection.MESSAGE, it, message.truncated && marker) }

        /** The message was dropped by the "sender only" long-message policy: flag the last field. */
        fun markOmitted() {
            if (message.truncated && message.text == null && fields.isNotEmpty() && marker) {
                fields[fields.size - 1] = fields.last().copy(truncated = true)
            }
        }

        when (mode) {
            PlaybackMode.FULL_MESSAGE -> {
                when {
                    !parsed.hasSender && message.text == null && !message.truncated -> fields += FrameField(MessageSection.APP, app)
                    else -> {
                        fields += senderField()!!
                        messageField()?.let { fields += it }
                    }
                }
                markOmitted()
            }
            PlaybackMode.SENDER_ONLY -> {
                fields += if (senderLimited != null) senderField()!! else FrameField(MessageSection.APP, app)
                if (message.truncated && message.text == null) markOmitted()
            }
            PlaybackMode.MESSAGE_ONLY -> {
                val m = messageField()
                if (m != null) fields += m
                else fields += if (senderLimited != null) senderField()!! else FrameField(MessageSection.APP, app)
                markOmitted()
            }
            PlaybackMode.APP_SENDER_MESSAGE -> {
                fields += FrameField(MessageSection.APP, app)
                val s = senderLimited?.let { FrameField(MessageSection.SENDER, it.text!!, it.truncated && marker) }
                val m = messageField()
                if (s != null || m != null) fields += s ?: FrameField(MessageSection.SENDER, "")
                if (m != null) fields += m
                markOmitted()
            }
            PlaybackMode.TEMPLATE -> {
                val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(parsed.timestamp))
                val body = TemplateRenderer.render(
                    settings.template,
                    TemplateRenderer.Values(app = parsed.applicationName, sender = senderLimited?.text, message = message.text, time = time),
                )
                fields += FrameField(MessageSection.TEXT, body, (message.truncated || senderLimited?.truncated == true) && marker)
            }
        }
        if (fields.isEmpty()) fields += FrameField(MessageSection.APP, app)

        val preview = buildPreview(parsed)
        return ComposedMessage(fields, truncated, parsed.messageText?.length ?: 0, preview, mode)
    }

    /** "N new" announcement used when queued notifications of one app are combined. */
    fun composeCombined(parsed: ParsedNotification, count: Int, mode: PlaybackMode = PlaybackMode.FULL_MESSAGE): ComposedMessage {
        val app = appId(parsed.applicationName)
        return ComposedMessage(
            fields = listOf(FrameField(MessageSection.APP, app), FrameField(MessageSection.MESSAGE, "$count new")),
            truncated = false,
            originalMessageLength = 0,
            preview = "$count new notifications from ${parsed.applicationName}",
            mode = mode,
        )
    }

    fun appId(applicationName: String): String {
        val cleaned = applicationName.trim()
        return if (cleaned.length <= APP_ID_MAX_CHARS) cleaned else cleaned.take(APP_ID_MAX_CHARS).trimEnd()
    }

    private fun decideMessage(parsed: ParsedNotification, settings: AppSettings): Limited {
        val text = parsed.messageText?.trim()
        if (text.isNullOrEmpty()) return Limited(null, false)
        val max = settings.maxMessageChars
        if (text.length <= max && text.length <= HARD_MAX_MESSAGE_CHARS) return Limited(text, false)
        return when (settings.longMessagePolicy) {
            LongMessagePolicy.PLAY_FULL ->
                if (text.length <= HARD_MAX_MESSAGE_CHARS) Limited(text, false)
                else Limited(cut(text, HARD_MAX_MESSAGE_CHARS), true)
            LongMessagePolicy.FIRST_N -> Limited(cut(text, max), true)
            LongMessagePolicy.SENDER_ONLY -> Limited(null, true)
            LongMessagePolicy.SUMMARY -> {
                val summary = parsed.summaryText?.trim()
                if (!summary.isNullOrEmpty()) Limited(cut(summary, max), true) else Limited(cut(text, max), true)
            }
        }
    }

    private fun limit(text: String, max: Int): Limited =
        if (text.length <= max) Limited(text, false) else Limited(cut(text, max), true)

    /** First [n] characters without splitting a surrogate pair or leaving trailing spaces. */
    private fun cut(text: String, n: Int): String {
        if (text.length <= n) return text
        var end = n
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd()
    }

    private fun buildPreview(parsed: ParsedNotification): String {
        val sender = parsed.senderName?.takeIf { it.isNotBlank() }
        val message = parsed.messageText?.takeIf { it.isNotBlank() }
        val text = when {
            sender != null && message != null -> "$sender: $message"
            message != null -> message
            sender != null -> sender
            else -> parsed.applicationName
        }
        return if (text.length <= 80) text else text.take(79).trimEnd() + "…"
    }
}
