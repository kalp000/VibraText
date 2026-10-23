package com.example.vibratext.playback

import com.example.vibratext.encoding.MessageSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Plays [PlaybackRequest]s one at a time on a [VibrationSink].
 *
 * ## Single active job, never overlapping
 * At most one request owns the vibrator at any moment. Ownership is a *generation number*
 * (`activeGen`). New requests either start immediately, interrupt (tests / user setting) or
 * wait in the [PlaybackQueue] according to its policy.
 *
 * ## Cancellation is airtight
 * - Every call to `sink.vibrate` happens inside `synchronized(lock)` **and** only if the
 *   request's generation is still the active one.
 * - [stop] (and interruption) change the generation and call `sink.cancel()` inside the same
 *   lock, then cancel the coroutine. So even if the coroutine was between its last check and
 *   its next vibrate call, it cannot vibrate afterwards, and the completion tick (the last
 *   pulse of the final batch) is never started once cancelled.
 * - A cancelled coroutine that wakes up later only sees a stale generation and does nothing.
 *
 * ## Timing
 * A script is cut into batches (see [VibrationScript.batches]); each batch is handed to the
 * sink as one waveform. The controller walks the batch's segments against the [PlaybackClock]
 * to update [state] (current character/symbol/progress) and to start the next batch exactly
 * when the previous one ends. Scheduling work happens on [scope]'s dispatcher, not the main
 * thread; [state] is a StateFlow that the UI collects on the main thread.
 */
class PlaybackController(
    private val scope: CoroutineScope,
    private val sink: VibrationSink,
    private val clock: PlaybackClock = SystemPlaybackClock,
) {
    private val lock = Any()
    private val queue = PlaybackQueue()

    private var genCounter = 0L
    private var activeGen = 0L
    private var activeJob: Job? = null
    private var activeRequest: PlaybackRequest? = null
    private var lastRequest: PlaybackRequest? = null
    private var lastFinishedAtMs = Long.MIN_VALUE / 4

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    @Volatile
    var listener: PlaybackListener? = null

    /** Minimum silence between the end of one notification and the start of the next. */
    @Volatile
    var minIntervalMs: Long = 0

    var queuePolicy: QueuePolicy
        get() = synchronized(lock) { queue.policy }
        set(value) = synchronized(lock) { queue.policy = value }

    var maxQueueSize: Int
        get() = synchronized(lock) { queue.maxSize }
        set(value) = synchronized(lock) { queue.maxSize = value }

    val isIdle: Boolean get() = synchronized(lock) { activeGen == 0L }

    /**
     * Plays [request] now if idle; otherwise interrupts the current playback when
     * [interruptCurrent] (or the request bypasses pacing, as manual tests do); otherwise
     * queues it according to the queue policy.
     */
    fun submit(request: PlaybackRequest, interruptCurrent: Boolean = false) {
        val finished = ArrayList<Pair<PlaybackRequest, PlaybackOutcome>>()
        synchronized(lock) {
            when {
                activeGen == 0L -> startLocked(request)
                interruptCurrent || request.bypassPacing -> {
                    cancelActiveLocked(finished, PlaybackOutcome.CANCELLED)
                    startLocked(request)
                }
                else -> {
                    finished += queue.offer(request)
                    _state.value = _state.value.copy(queueSize = queue.size)
                }
            }
        }
        report(finished)
    }

    /**
     * Stops everything *now*: cancels the active job and its pending delays, clears the queue,
     * silences the vibrator, resets the state. Nothing cancelled can resume.
     */
    fun stop() {
        val finished = ArrayList<Pair<PlaybackRequest, PlaybackOutcome>>()
        synchronized(lock) {
            queue.clear().forEach { finished += it to PlaybackOutcome.DROPPED }
            val hadActive = activeRequest != null
            cancelActiveLocked(finished, PlaybackOutcome.CANCELLED)
            if (!hadActive) sink.cancel() // nothing was active: still silence any stray waveform
            _state.value = PlaybackState()
        }
        report(finished)
    }

    /** Restarts the current (or most recent) message from the beginning. False if there is none. */
    fun replay(): Boolean {
        val finished = ArrayList<Pair<PlaybackRequest, PlaybackOutcome>>()
        var started = false
        synchronized(lock) {
            val source = activeRequest ?: lastRequest
            if (source != null) {
                cancelActiveLocked(finished, PlaybackOutcome.CANCELLED)
                startLocked(source.copy(id = RequestIds.next(), bypassPacing = true, startDelayMs = 0))
                started = true
            }
        }
        report(finished)
        return started
    }

    // ---- internals --------------------------------------------------------------------------

    /** Must hold [lock]. */
    private fun startLocked(request: PlaybackRequest) {
        val gen = ++genCounter
        activeGen = gen
        activeRequest = request
        lastRequest = request
        _state.value = PlaybackState(
            phase = PlaybackPhase.WAITING,
            requestId = request.id,
            currentApplication = request.meta.applicationName,
            currentSender = request.meta.senderName,
            totalMessageLength = request.script.units.size,
            totalRepeats = request.script.repeatCount,
            queueSize = queue.size,
            truncated = request.meta.truncated,
            isTest = request.meta.source == RequestSource.TEST,
        )
        val job = scope.launch { run(request, gen) }
        // With an unconfined dispatcher the job may already have finished (or even started the
        // next request) before launch() returns; only keep the handle if we still own the vibrator.
        if (activeGen == gen) activeJob = job
    }

    /** Must hold [lock]. Revokes ownership, silences the vibrator, cancels the coroutine. */
    private fun cancelActiveLocked(finished: MutableList<Pair<PlaybackRequest, PlaybackOutcome>>, outcome: PlaybackOutcome) {
        val request = activeRequest
        val job = activeJob
        activeGen = 0L
        activeJob = null
        activeRequest = null
        if (request != null) {
            sink.cancel()
            job?.cancel()
            finished += request to outcome
        }
    }

    private suspend fun run(request: PlaybackRequest, gen: Long) {
        var outcome = PlaybackOutcome.COMPLETED
        var error: Throwable? = null
        try {
            awaitPacing(request)
            if (!isCurrent(gen)) {
                outcome = PlaybackOutcome.CANCELLED
                return
            }
            runCatching { listener?.onStarted(request) }
            play(request, gen)
        } catch (e: CancellationException) {
            outcome = PlaybackOutcome.CANCELLED
            throw e
        } catch (t: Throwable) {
            outcome = PlaybackOutcome.FAILED
            error = t
        } finally {
            finish(gen, request, outcome, error)
        }
    }

    private suspend fun awaitPacing(request: PlaybackRequest) {
        if (request.bypassPacing) return
        val now = clock.nowMs()
        val earliest = max(lastFinishedAtMs + minIntervalMs, now + request.startDelayMs)
        val wait = earliest - now
        if (wait > 0) clock.delayMs(wait)
    }

    private suspend fun play(request: PlaybackRequest, gen: Long) {
        check(sink.isAvailable()) { "This device has no vibrator." }
        val script = request.script
        val total = max(1L, script.totalDurationMs).toFloat()
        val onAmplitude = amplitudeFor(request.intensityPercent)

        updateIfCurrent(gen) { it.copy(phase = PlaybackPhase.PLAYING) }

        for (batch in script.batches(request.maxSegmentsPerBatch)) {
            val batchStart = clock.nowMs()
            val timings = batch.timings()
            val amplitudes = batch.amplitudes(onAmplitude)
            val accepted = synchronized(lock) {
                if (activeGen != gen) {
                    false
                } else {
                    sink.vibrate(timings, amplitudes)
                    true
                }
            }
            if (!accepted) throw CancellationException("playback superseded")

            var offset = 0L
            for ((i, seg) in batch.segments.withIndex()) {
                if (seg.on) {
                    val progress = script.startOffsetMs(batch.fromIndex + i) / total
                    updateIfCurrent(gen) { s ->
                        val unit = if (seg.unitIndex >= 0) script.units[seg.unitIndex] else null
                        val marker = seg.role == SegmentRole.COMPLETION_TICK || seg.role == SegmentRole.REPEAT_MARKER
                        s.copy(
                            currentCharacter = when (seg.role) {
                                SegmentRole.COMPLETION_TICK -> "✓"
                                SegmentRole.REPEAT_MARKER -> "↻"
                                else -> unit?.display
                            },
                            currentSymbol = seg.symbol,
                            currentSection = if (marker) MessageSection.FRAME else unit?.section,
                            currentMessageIndex = if (unit != null) seg.unitIndex + 1 else s.currentMessageIndex,
                            repeatIndex = seg.repeatIndex + 1,
                            progress = progress,
                        )
                    }
                }
                offset += seg.durationMs
                val wait = batchStart + offset - clock.nowMs()
                if (wait > 0) clock.delayMs(wait)
            }
        }
        updateIfCurrent(gen) { it.copy(progress = 1f) }
    }

    private fun finish(gen: Long, request: PlaybackRequest, outcome: PlaybackOutcome, error: Throwable?) {
        var owned = false
        synchronized(lock) {
            if (activeGen == gen) {
                owned = true
                activeGen = 0L
                activeJob = null
                activeRequest = null
                if (outcome != PlaybackOutcome.CANCELLED) lastFinishedAtMs = clock.nowMs()
                if (outcome != PlaybackOutcome.COMPLETED) runCatching { sink.cancel() }
                _state.value = PlaybackState(
                    queueSize = queue.size,
                    lastError = if (outcome == PlaybackOutcome.FAILED) (error?.message ?: "Playback failed") else null,
                )
                val next = queue.poll()
                if (next != null) startLocked(next)
            }
        }
        // Only the party that owned the vibrator reports; interrupted jobs were already reported
        // by whoever interrupted them.
        if (owned) runCatching { listener?.onFinished(request, outcome) }
    }

    private fun isCurrent(gen: Long): Boolean = synchronized(lock) { activeGen == gen }

    private inline fun updateIfCurrent(gen: Long, block: (PlaybackState) -> PlaybackState) {
        synchronized(lock) {
            if (activeGen == gen) _state.value = block(_state.value)
        }
    }

    private fun report(finished: List<Pair<PlaybackRequest, PlaybackOutcome>>) {
        val l = listener ?: return
        finished.forEach { (r, o) -> runCatching { l.onFinished(r, o) } }
    }

    companion object {
        /** Intensity 1..100 % → vibrator amplitude 1..255. */
        fun amplitudeFor(intensityPercent: Int): Int =
            (intensityPercent.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)
    }
}
