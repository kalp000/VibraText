package com.example.vibratext.playback

import com.example.vibratext.encoding.MessageSection
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.notification.ParsedNotification
import java.util.concurrent.atomic.AtomicLong

/** Android-independent output device. The :app module implements it with the system Vibrator. */
interface VibrationSink {
    /** False if the device has no vibrator (playback then fails with a clear state). */
    fun isAvailable(): Boolean

    /** True if per-pulse intensity can be controlled; otherwise the intensity setting is ignored. */
    fun supportsAmplitude(): Boolean

    /**
     * Starts one waveform and returns immediately. [timings] alternates OFF, ON, OFF, ON …
     * (first entry = initial delay). [amplitudes] has the same length (0 = off, 1..255 = on).
     * Starting a new waveform replaces whatever is still vibrating.
     */
    fun vibrate(timings: LongArray, amplitudes: IntArray)

    /** Stops any vibration immediately. Must be safe to call at any time, from any thread. */
    fun cancel()
}

/** Time source + delay, injectable so playback timing can be tested without real waiting. */
interface PlaybackClock {
    fun nowMs(): Long
    suspend fun delayMs(ms: Long)
}

object SystemPlaybackClock : PlaybackClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
    override suspend fun delayMs(ms: Long) = kotlinx.coroutines.delay(ms)
}

enum class RequestSource { NOTIFICATION, TEST }

enum class PlaybackOutcome { COMPLETED, CANCELLED, FAILED, DROPPED, REPLACED }

object RequestIds {
    private val counter = AtomicLong(0)
    fun next(): Long = counter.incrementAndGet()
}

data class RequestMeta(
    val source: RequestSource,
    val packageName: String? = null,
    val applicationName: String? = null,
    val senderName: String? = null,
    /** Short text for history / dashboard (never logged). */
    val preview: String = "",
    val profileId: String? = null,
    val profileName: String? = null,
    val truncated: Boolean = false,
    val notificationKey: String? = null,
    /** How many notifications this request stands for (> 1 after "combine" queueing). */
    val combinedCount: Int = 1,
    val parsed: ParsedNotification? = null,
)

/**
 * Everything the controller needs to play one notification.
 * [combiner] lets the queue merge a newer notification of the same app into a pending one.
 */
data class PlaybackRequest(
    val id: Long,
    val script: VibrationScript,
    val meta: RequestMeta,
    val intensityPercent: Int = 100,
    val startDelayMs: Long = 0,
    /** Manual/test playback ignores delays and minimum intervals and interrupts current playback. */
    val bypassPacing: Boolean = false,
    val maxSegmentsPerBatch: Int = VibrationScript.DEFAULT_BATCH_SEGMENTS,
    val combiner: ((previousCount: Int) -> PlaybackRequest)? = null,
)

enum class PlaybackPhase { IDLE, WAITING, PLAYING }

/** Observable playback status (UI collects this on the main thread). */
data class PlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val requestId: Long? = null,
    val currentApplication: String? = null,
    val currentSender: String? = null,
    val currentCharacter: String? = null,
    val currentSymbol: VibSymbol? = null,
    val currentSection: MessageSection? = null,
    /** 1-based index of the current character/marker within one repetition (0 = none yet). */
    val currentMessageIndex: Int = 0,
    val totalMessageLength: Int = 0,
    /** 0.0 .. 1.0 over the whole script including repetitions. */
    val progress: Float = 0f,
    /** 1-based repetition currently playing (0 = none yet). */
    val repeatIndex: Int = 0,
    val totalRepeats: Int = 0,
    val queueSize: Int = 0,
    val truncated: Boolean = false,
    val isTest: Boolean = false,
    val lastError: String? = null,
) {
    val isPlaying: Boolean get() = phase == PlaybackPhase.PLAYING
    val isBusy: Boolean get() = phase != PlaybackPhase.IDLE
}

/** Callbacks are invoked on whichever thread finished the work; keep them short. */
interface PlaybackListener {
    fun onStarted(request: PlaybackRequest) {}
    fun onFinished(request: PlaybackRequest, outcome: PlaybackOutcome) {}
}
