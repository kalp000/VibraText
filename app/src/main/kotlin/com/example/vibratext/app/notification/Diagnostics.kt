package com.example.vibratext.app.notification

import com.example.vibratext.notification.RejectReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DiagnosticsSnapshot(
    val received: Int = 0,
    val played: Int = 0,
    val skipped: Map<RejectReason, Int> = emptyMap(),
    val lastSkipped: RejectReason? = null,
    val lastError: String? = null,
)

/**
 * Counters that help the user (and the README's troubleshooting section) understand why a
 * notification did not vibrate. It holds only counts and reasons, never notification content,
 * and is reset when the process restarts.
 */
class Diagnostics {
    private val _snapshot = MutableStateFlow(DiagnosticsSnapshot())
    val snapshot: StateFlow<DiagnosticsSnapshot> = _snapshot.asStateFlow()

    fun recordPlayed() = _snapshot.update { it.copy(received = it.received + 1, played = it.played + 1) }

    fun recordSkipped(reason: RejectReason) = _snapshot.update {
        it.copy(
            received = it.received + 1,
            skipped = it.skipped + (reason to ((it.skipped[reason] ?: 0) + 1)),
            lastSkipped = reason,
        )
    }

    /** Only the exception class name is kept, never its message (which could contain content). */
    fun recordError(error: Throwable) = _snapshot.update { it.copy(lastError = error.javaClass.simpleName) }

    fun reset() {
        _snapshot.value = DiagnosticsSnapshot()
    }
}
