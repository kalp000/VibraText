package com.example.vibratext.data

import com.example.vibratext.json.JsonException
import com.example.vibratext.json.JsonObj
import com.example.vibratext.json.MiniJson
import com.example.vibratext.notification.TemplateRenderer
import com.example.vibratext.playback.QueuePolicy

enum class PlaybackMode(val label: String, val description: String) {
    FULL_MESSAGE("Sender + message", "Plays the sender name, then the message."),
    SENDER_ONLY("Sender only", "Plays only who it is from, then the completion tick."),
    MESSAGE_ONLY("Message only", "Skips the sender and plays the message."),
    APP_SENDER_MESSAGE("App + sender + message", "Plays a short app name, the sender, then the message."),
    TEMPLATE("Custom template", "Plays one text built from your template, e.g. \"{sender}: {message}\"."),
}

enum class LongMessagePolicy(val label: String) {
    PLAY_FULL("Play the full message"),
    FIRST_N("Play the first N characters"),
    SENDER_ONLY("Play the sender only"),
    SUMMARY("Use the notification's summary text if it has one"),
}

enum class UnknownSenderBehavior(val label: String) {
    APP_AS_SENDER("Play the app name in place of the sender"),
    EMPTY_SENDER_FIELD("Leave the sender field empty"),
}

/** Which Android vibration usage the pattern is played with; affects silent mode / DND behaviour. */
enum class VibrationUsage(val label: String, val description: String) {
    NOTIFICATION("Notification", "Follows the phone's notification-vibration, silent-mode and Do Not Disturb settings."),
    ACCESSIBILITY("Accessibility", "Treated as an accessibility vibration; usually still plays when notification vibration is off."),
    ALARM("Alarm", "Strongest: ignores silent mode. Use only if the other options are suppressed on your phone."),
}

/** Global behaviour. Per-profile things (timing, speed, repeat, tick, intensity) live in [EncodingProfile]. */
data class AppSettings(
    val monitoringEnabled: Boolean = false,
    val selectedPackages: Set<String> = emptySet(),

    val playbackMode: PlaybackMode = PlaybackMode.FULL_MESSAGE,
    /** Mode E: only play notifications that match a rule marked "important". */
    val onlyImportant: Boolean = false,
    val template: String = "{sender}: {message}",

    val playWhileLocked: Boolean = true,
    val playWhileUnlocked: Boolean = true,
    val playSilent: Boolean = false,
    val playOngoing: Boolean = false,
    val playGroupSummaries: Boolean = false,
    val requireReadableContent: Boolean = true,

    val ignoreDuplicates: Boolean = true,
    val duplicateWindowMs: Long = 30_000,
    val ignoreWhenDnd: Boolean = false,
    val pauseDuringCalls: Boolean = true,
    /** If true a new notification interrupts the one being played; otherwise it waits (queue policy). */
    val interruptCurrent: Boolean = false,

    val maxMessageChars: Int = 60,
    val longMessagePolicy: LongMessagePolicy = LongMessagePolicy.FIRST_N,
    val maxSenderChars: Int = 24,
    val playTruncationMarker: Boolean = true,
    val unknownSenderBehavior: UnknownSenderBehavior = UnknownSenderBehavior.APP_AS_SENDER,

    val startDelayMs: Long = 0,
    val minIntervalMs: Long = 2_000,
    val queuePolicy: QueuePolicy = QueuePolicy.PLAY_ALL,
    val maxQueueSize: Int = 5,
    val vibrationUsage: VibrationUsage = VibrationUsage.NOTIFICATION,

    val historyEnabled: Boolean = true,
    val historyMaxEntries: Int = 200,
    /** If false, history keeps only app, time, profile and status: no sender and no message preview. */
    val historyStoreContent: Boolean = true,
    /** The user has read the notification-access privacy explanation. */
    val privacyAcknowledged: Boolean = false,
) {
    fun validate(): List<String> {
        val e = mutableListOf<String>()
        if (maxMessageChars !in MESSAGE_CHARS) e += "Maximum message length must be between ${MESSAGE_CHARS.first} and ${MESSAGE_CHARS.last}."
        if (maxSenderChars !in SENDER_CHARS) e += "Maximum sender length must be between ${SENDER_CHARS.first} and ${SENDER_CHARS.last}."
        if (startDelayMs !in 0..30_000) e += "Delay before playback must be between 0 and 30000 ms."
        if (minIntervalMs !in 0..60_000) e += "Minimum interval must be between 0 and 60000 ms."
        if (maxQueueSize !in QUEUE_SIZE) e += "Queue length must be between ${QUEUE_SIZE.first} and ${QUEUE_SIZE.last}."
        if (historyMaxEntries !in HISTORY_ENTRIES) e += "History size must be between ${HISTORY_ENTRIES.first} and ${HISTORY_ENTRIES.last}."
        if (duplicateWindowMs !in 1_000..600_000) e += "Duplicate window must be between 1 and 600 seconds."
        e += TemplateRenderer.validate(template)
        return e
    }

    /** Clamps numbers into their valid ranges and restores the default template if it is invalid. */
    fun sanitized(): AppSettings = copy(
        maxMessageChars = maxMessageChars.coerceIn(MESSAGE_CHARS.first, MESSAGE_CHARS.last),
        maxSenderChars = maxSenderChars.coerceIn(SENDER_CHARS.first, SENDER_CHARS.last),
        startDelayMs = startDelayMs.coerceIn(0, 30_000),
        minIntervalMs = minIntervalMs.coerceIn(0, 60_000),
        maxQueueSize = maxQueueSize.coerceIn(QUEUE_SIZE.first, QUEUE_SIZE.last),
        historyMaxEntries = historyMaxEntries.coerceIn(HISTORY_ENTRIES.first, HISTORY_ENTRIES.last),
        duplicateWindowMs = duplicateWindowMs.coerceIn(1_000, 600_000),
        template = if (TemplateRenderer.validate(template).isEmpty()) template else AppSettings().template,
    )

    companion object {
        val MESSAGE_CHARS = 1..500
        val SENDER_CHARS = 1..50
        val QUEUE_SIZE = 1..20
        val HISTORY_ENTRIES = 10..1000
    }
}

/** Local storage format for [AppSettings] (one JSON document). Lenient: bad values fall back to defaults. */
object SettingsJson {
    fun encode(s: AppSettings): String = MiniJson.stringify(
        linkedMapOf<String, Any?>(
            "monitoringEnabled" to s.monitoringEnabled,
            "selectedPackages" to s.selectedPackages.sorted(),
            "playbackMode" to s.playbackMode.name,
            "onlyImportant" to s.onlyImportant,
            "template" to s.template,
            "playWhileLocked" to s.playWhileLocked,
            "playWhileUnlocked" to s.playWhileUnlocked,
            "playSilent" to s.playSilent,
            "playOngoing" to s.playOngoing,
            "playGroupSummaries" to s.playGroupSummaries,
            "requireReadableContent" to s.requireReadableContent,
            "ignoreDuplicates" to s.ignoreDuplicates,
            "duplicateWindowMs" to s.duplicateWindowMs,
            "ignoreWhenDnd" to s.ignoreWhenDnd,
            "pauseDuringCalls" to s.pauseDuringCalls,
            "interruptCurrent" to s.interruptCurrent,
            "maxMessageChars" to s.maxMessageChars.toLong(),
            "longMessagePolicy" to s.longMessagePolicy.name,
            "maxSenderChars" to s.maxSenderChars.toLong(),
            "playTruncationMarker" to s.playTruncationMarker,
            "unknownSenderBehavior" to s.unknownSenderBehavior.name,
            "startDelayMs" to s.startDelayMs,
            "minIntervalMs" to s.minIntervalMs,
            "queuePolicy" to s.queuePolicy.name,
            "maxQueueSize" to s.maxQueueSize.toLong(),
            "vibrationUsage" to s.vibrationUsage.name,
            "historyEnabled" to s.historyEnabled,
            "historyMaxEntries" to s.historyMaxEntries.toLong(),
            "historyStoreContent" to s.historyStoreContent,
            "privacyAcknowledged" to s.privacyAcknowledged,
        ),
    )

    fun decode(json: String?): AppSettings {
        if (json.isNullOrBlank()) return AppSettings()
        return try {
            val o = JsonObj.of(MiniJson.parse(json))
            val d = AppSettings()
            AppSettings(
                monitoringEnabled = o.bool("monitoringEnabled", d.monitoringEnabled),
                selectedPackages = (o.list("selectedPackages") ?: emptyList()).filterIsInstance<String>().toSet(),
                playbackMode = enumOr(o.optString("playbackMode"), d.playbackMode),
                onlyImportant = o.bool("onlyImportant", d.onlyImportant),
                template = o.string("template", d.template),
                playWhileLocked = o.bool("playWhileLocked", d.playWhileLocked),
                playWhileUnlocked = o.bool("playWhileUnlocked", d.playWhileUnlocked),
                playSilent = o.bool("playSilent", d.playSilent),
                playOngoing = o.bool("playOngoing", d.playOngoing),
                playGroupSummaries = o.bool("playGroupSummaries", d.playGroupSummaries),
                requireReadableContent = o.bool("requireReadableContent", d.requireReadableContent),
                ignoreDuplicates = o.bool("ignoreDuplicates", d.ignoreDuplicates),
                duplicateWindowMs = o.long("duplicateWindowMs", d.duplicateWindowMs),
                ignoreWhenDnd = o.bool("ignoreWhenDnd", d.ignoreWhenDnd),
                pauseDuringCalls = o.bool("pauseDuringCalls", d.pauseDuringCalls),
                interruptCurrent = o.bool("interruptCurrent", d.interruptCurrent),
                maxMessageChars = o.int("maxMessageChars", d.maxMessageChars),
                longMessagePolicy = enumOr(o.optString("longMessagePolicy"), d.longMessagePolicy),
                maxSenderChars = o.int("maxSenderChars", d.maxSenderChars),
                playTruncationMarker = o.bool("playTruncationMarker", d.playTruncationMarker),
                unknownSenderBehavior = enumOr(o.optString("unknownSenderBehavior"), d.unknownSenderBehavior),
                startDelayMs = o.long("startDelayMs", d.startDelayMs),
                minIntervalMs = o.long("minIntervalMs", d.minIntervalMs),
                queuePolicy = enumOr(o.optString("queuePolicy"), d.queuePolicy),
                maxQueueSize = o.int("maxQueueSize", d.maxQueueSize),
                vibrationUsage = enumOr(o.optString("vibrationUsage"), d.vibrationUsage),
                historyEnabled = o.bool("historyEnabled", d.historyEnabled),
                historyMaxEntries = o.int("historyMaxEntries", d.historyMaxEntries),
                historyStoreContent = o.bool("historyStoreContent", d.historyStoreContent),
                privacyAcknowledged = o.bool("privacyAcknowledged", d.privacyAcknowledged),
            ).sanitized()
        } catch (_: JsonException) {
            AppSettings()
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
