package com.example.vibratext.playback

import com.example.vibratext.encoding.CharacterEncoder
import com.example.vibratext.encoding.EncodingTable
import com.example.vibratext.encoding.FrameField
import com.example.vibratext.encoding.MessageFramer
import com.example.vibratext.encoding.MessageSection
import com.example.vibratext.encoding.SymbolMapping
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Virtual time: [delayMs] suspends until [advance] moves time past the wake-up point. */
class FakeClock : PlaybackClock {
    private class Waiter(val wake: Long, val cont: CancellableContinuation<Unit>)

    private val waiters = ArrayList<Waiter>()
    private var now = 0L

    override fun nowMs(): Long = synchronized(this) { now }

    override suspend fun delayMs(ms: Long) {
        if (ms <= 0) return
        suspendCancellableCoroutine<Unit> { cont ->
            val w = Waiter(nowMs() + ms, cont)
            synchronized(this) { waiters.add(w) }
            cont.invokeOnCancellation { synchronized(this) { waiters.remove(w) } }
        }
    }

    /** Moves time forward, waking every waiter that becomes due, in order. */
    fun advance(ms: Long) {
        val target = nowMs() + ms
        while (true) {
            val next = synchronized(this) {
                val due = waiters.filter { it.wake <= target }.minByOrNull { it.wake }
                if (due != null) {
                    waiters.remove(due)
                    now = maxOf(now, due.wake)
                }
                due
            } ?: break
            next.cont.resume(Unit)
        }
        synchronized(this) { now = target }
    }

    val pendingWaiters: Int get() = synchronized(this) { waiters.size }
}

sealed interface SinkEvent {
    val atMs: Long

    data class Vibrate(override val atMs: Long, val timings: LongArray, val amplitudes: IntArray) : SinkEvent {
        val durationMs: Long get() = timings.sum()
    }

    data class Cancel(override val atMs: Long) : SinkEvent
}

class FakeSink(private val clock: FakeClock) : VibrationSink {
    val events = ArrayList<SinkEvent>()
    var available = true
    var failOnVibrateCall: Int = -1
    private var calls = 0

    override fun isAvailable() = available
    override fun supportsAmplitude() = true

    override fun vibrate(timings: LongArray, amplitudes: IntArray) {
        calls++
        if (calls == failOnVibrateCall) throw IllegalStateException("vibrator exploded")
        events += SinkEvent.Vibrate(clock.nowMs(), timings, amplitudes)
    }

    override fun cancel() {
        events += SinkEvent.Cancel(clock.nowMs())
    }

    val vibrations: List<SinkEvent.Vibrate> get() = events.filterIsInstance<SinkEvent.Vibrate>()
    val cancels: List<SinkEvent.Cancel> get() = events.filterIsInstance<SinkEvent.Cancel>()
}

class RecordingListener : PlaybackListener {
    val started = ArrayList<Long>()
    val finished = ArrayList<Pair<Long, PlaybackOutcome>>()

    override fun onStarted(request: PlaybackRequest) {
        started += request.id
    }

    override fun onFinished(request: PlaybackRequest, outcome: PlaybackOutcome) {
        finished += request.id to outcome
    }

    fun outcomeOf(id: Long): PlaybackOutcome? = finished.firstOrNull { it.first == id }?.second
}

object TestRequests {
    private val framer = MessageFramer(CharacterEncoder(EncodingTable.DEFAULT))

    fun script(
        text: String,
        repeat: RepeatSettings = RepeatSettings(),
        tick: Boolean = true,
        timing: TimingSettings = TimingSettings.DEFAULT,
    ): VibrationScript {
        val framed = framer.frame(listOf(FrameField(MessageSection.TEXT, text)))
        return VibrationPatternGenerator.generate(framed.units, SymbolMapping.DEFAULT, timing, repeat, tick)
    }

    fun request(
        text: String = "hi",
        pkg: String? = "pkg.a",
        repeat: RepeatSettings = RepeatSettings(),
        tick: Boolean = true,
        maxSegments: Int = VibrationScript.DEFAULT_BATCH_SEGMENTS,
        bypass: Boolean = false,
        startDelayMs: Long = 0,
        combiner: ((Int) -> PlaybackRequest)? = null,
        combinedCount: Int = 1,
    ) = PlaybackRequest(
        id = RequestIds.next(),
        script = script(text, repeat, tick),
        meta = RequestMeta(
            source = if (bypass) RequestSource.TEST else RequestSource.NOTIFICATION,
            packageName = pkg,
            applicationName = "App",
            senderName = "Sender",
            preview = text,
            combinedCount = combinedCount,
        ),
        startDelayMs = startDelayMs,
        bypassPacing = bypass,
        maxSegmentsPerBatch = maxSegments,
        combiner = combiner,
    )
}
