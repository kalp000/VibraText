package com.example.vibratext.playback

enum class QueuePolicy(val label: String, val description: String) {
    PLAY_ALL("Play every notification", "Notifications wait in line and play one after another (oldest are dropped if the line gets too long)."),
    REPLACE_WITH_NEWEST("Newest only", "While one is playing, only the newest waiting notification is kept."),
    COMBINE_SAME_APP("Combine per app", "Waiting notifications from the same app are merged into one short \"N new\" announcement."),
}

/**
 * Pending notifications. Not thread-safe: [PlaybackController] guards it with its lock.
 * Every method that removes requests returns them with the reason, so history can record them.
 */
class PlaybackQueue(var policy: QueuePolicy = QueuePolicy.PLAY_ALL, var maxSize: Int = DEFAULT_MAX_SIZE) {
    private val items = ArrayDeque<PlaybackRequest>()

    val size: Int get() = items.size

    fun offer(request: PlaybackRequest): List<Pair<PlaybackRequest, PlaybackOutcome>> {
        val removed = ArrayList<Pair<PlaybackRequest, PlaybackOutcome>>()
        when (policy) {
            QueuePolicy.PLAY_ALL -> {
                items.addLast(request)
                trim(removed)
            }
            QueuePolicy.REPLACE_WITH_NEWEST -> {
                while (items.isNotEmpty()) removed += items.removeFirst() to PlaybackOutcome.REPLACED
                items.addLast(request)
            }
            QueuePolicy.COMBINE_SAME_APP -> {
                val pkg = request.meta.packageName
                val index = if (pkg == null) -1 else items.indexOfFirst { it.meta.packageName == pkg }
                if (index >= 0) {
                    val old = items.removeAt(index)
                    val merged = request.combiner?.invoke(old.meta.combinedCount) ?: request
                    items.add(index, merged)
                    removed += old to PlaybackOutcome.REPLACED
                } else {
                    items.addLast(request)
                    trim(removed)
                }
            }
        }
        return removed
    }

    fun poll(): PlaybackRequest? = if (items.isEmpty()) null else items.removeFirst()

    fun clear(): List<PlaybackRequest> {
        val all = items.toList()
        items.clear()
        return all
    }

    private fun trim(removed: MutableList<Pair<PlaybackRequest, PlaybackOutcome>>) {
        val limit = maxOf(1, maxSize)
        while (items.size > limit) removed += items.removeFirst() to PlaybackOutcome.DROPPED
    }

    companion object {
        const val DEFAULT_MAX_SIZE = 5
    }
}
