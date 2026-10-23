package com.example.vibratext.data

import com.example.vibratext.json.JsonException
import com.example.vibratext.json.JsonObj
import com.example.vibratext.json.MiniJson
import com.example.vibratext.playback.PlaybackListener
import com.example.vibratext.playback.PlaybackOutcome
import com.example.vibratext.playback.PlaybackRequest
import com.example.vibratext.playback.RequestSource

enum class HistoryStatus(val label: String) {
    COMPLETED("Played"),
    CANCELLED("Cancelled"),
    FAILED("Failed"),
    DROPPED("Dropped (queue full)"),
    REPLACED("Replaced by a newer one"),
}

/**
 * One processed notification. Stored only on the device, only if the user keeps history enabled,
 * and never written to logs.
 */
data class HistoryEntry(
    val id: Long,
    val timestamp: Long,
    val applicationName: String,
    val packageName: String,
    val sender: String?,
    val preview: String,
    val profileName: String?,
    val status: HistoryStatus,
    val truncated: Boolean,
    val combinedCount: Int = 1,
)

/** The entry with everything that came from the notification's content removed (privacy option). */
fun HistoryEntry.withoutContent(): HistoryEntry = copy(sender = null, preview = "")

fun PlaybackOutcome.toHistoryStatus(): HistoryStatus = when (this) {
    PlaybackOutcome.COMPLETED -> HistoryStatus.COMPLETED
    PlaybackOutcome.CANCELLED -> HistoryStatus.CANCELLED
    PlaybackOutcome.FAILED -> HistoryStatus.FAILED
    PlaybackOutcome.DROPPED -> HistoryStatus.DROPPED
    PlaybackOutcome.REPLACED -> HistoryStatus.REPLACED
}

/** Records a history entry when a *notification* playback ends (manual tests are not recorded). */
class HistoryRecorder(
    private val clockMs: () -> Long,
    private val onEntry: (HistoryEntry) -> Unit,
) : PlaybackListener {
    override fun onFinished(request: PlaybackRequest, outcome: PlaybackOutcome) {
        val meta = request.meta
        if (meta.source != RequestSource.NOTIFICATION) return
        onEntry(
            HistoryEntry(
                id = request.id,
                timestamp = clockMs(),
                applicationName = meta.applicationName ?: "",
                packageName = meta.packageName ?: "",
                sender = meta.senderName,
                preview = meta.preview,
                profileName = meta.profileName,
                status = outcome.toHistoryStatus(),
                truncated = meta.truncated,
                combinedCount = meta.combinedCount,
            ),
        )
    }
}

object HistoryJson {
    /** Newest first, capped at [max]. */
    fun add(list: List<HistoryEntry>, entry: HistoryEntry, max: Int): List<HistoryEntry> =
        (listOf(entry) + list).take(max)

    fun encode(list: List<HistoryEntry>): String = MiniJson.stringify(
        list.map { e ->
            linkedMapOf<String, Any?>(
                "id" to e.id,
                "t" to e.timestamp,
                "app" to e.applicationName,
                "pkg" to e.packageName,
                "sender" to e.sender,
                "preview" to e.preview,
                "profile" to e.profileName,
                "status" to e.status.name,
                "truncated" to e.truncated,
                "count" to e.combinedCount.toLong(),
            )
        },
    )

    fun decode(json: String?): List<HistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            (MiniJson.parse(json) as? List<*> ?: return emptyList()).mapNotNull { item ->
                try {
                    val o = JsonObj.of(item)
                    HistoryEntry(
                        id = o.long("id"),
                        timestamp = o.long("t"),
                        applicationName = o.string("app", ""),
                        packageName = o.string("pkg", ""),
                        sender = o.optString("sender"),
                        preview = o.string("preview", ""),
                        profileName = o.optString("profile"),
                        status = HistoryStatus.entries.firstOrNull { it.name == o.string("status") } ?: return@mapNotNull null,
                        truncated = o.bool("truncated", false),
                        combinedCount = o.int("count", 1),
                    )
                } catch (_: JsonException) {
                    null
                }
            }
        } catch (_: JsonException) {
            emptyList()
        }
    }
}
