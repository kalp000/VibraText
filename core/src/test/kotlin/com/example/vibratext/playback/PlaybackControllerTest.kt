package com.example.vibratext.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic controller tests: an unconfined scope + virtual clock means every coroutine
 * step runs on the test thread, exactly when [FakeClock.advance] wakes it.
 */
class PlaybackControllerTest {
    private val clock = FakeClock()
    private val sink = FakeSink(clock)
    private val listener = RecordingListener()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val controller = PlaybackController(scope, sink, clock).also { it.listener = listener }

    private fun runToEnd(extra: Long = 10_000) = clock.advance(extra)

    private fun totalOnTimeOf(request: PlaybackRequest): Long = request.script.segments.filter { it.on }.sumOf { it.durationMs.toLong() }

    private fun totalOnTimeSent(): Long =
        sink.vibrations.sumOf { v -> v.timings.filterIndexed { i, _ -> i % 2 == 1 }.sum() }

    @Test
    fun playsAScriptToCompletionAndGoesIdle() {
        val r = TestRequests.request("hi")
        controller.submit(r)
        assertEquals(PlaybackPhase.PLAYING, controller.state.value.phase)
        assertEquals(r.script.units.size, controller.state.value.totalMessageLength)
        runToEnd(r.script.totalDurationMs + 100)
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(r.id))
        assertEquals(listOf(r.id), listener.started)
        assertEquals(PlaybackState(), controller.state.value)
        assertTrue(controller.isIdle)
        // every pulse of the script reached the vibrator, nothing more
        assertEquals(totalOnTimeOf(r), totalOnTimeSent())
        assertTrue(sink.cancels.isEmpty())
    }

    @Test
    fun theCompletionTickIsTheLastPulseAfterSuccessfulCompletion() {
        val r = TestRequests.request("ok", maxSegments = 16)
        controller.submit(r)
        runToEnd(r.script.totalDurationMs + 100)
        val last = sink.vibrations.last()
        assertEquals(50L, last.timings.last())
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(r.id))
    }

    @Test
    fun batchesAreStartedAtTheirScheduledTimesWithoutOverlap() {
        val r = TestRequests.request("hello world", maxSegments = 16)
        controller.submit(r)
        runToEnd(r.script.totalDurationMs + 100)
        val batches = r.script.batches(16)
        assertEquals(batches.size, sink.vibrations.size)
        batches.forEachIndexed { i, b -> assertEquals("batch $i start", b.startMs, sink.vibrations[i].atMs) }
    }

    @Test
    fun progressAndCurrentCharacterAreTrackedWhilePlaying() {
        val r = TestRequests.request("ab")
        controller.submit(r)
        val first = controller.state.value
        assertEquals(1, first.currentMessageIndex)
        assertEquals("⟨START⟩", first.currentCharacter)
        assertNotNull(first.currentSymbol)
        // advance into the second unit ('a')
        clock.advance(r.script.startOffsetMs(r.script.segments.indexOfFirst { it.unitIndex == 1 }) + 1)
        val second = controller.state.value
        assertEquals(2, second.currentMessageIndex)
        assertEquals("a", second.currentCharacter)
        assertTrue(second.progress > 0f && second.progress < 1f)
        assertEquals(1, second.repeatIndex)
        assertEquals(1, second.totalRepeats)
        runToEnd(r.script.totalDurationMs)
    }

    @Test
    fun repeatCountIsReflectedInStateAndOutput() {
        val r = TestRequests.request("a", repeat = RepeatSettings(count = 3, pauseMs = 300))
        controller.submit(r)
        assertEquals(3, controller.state.value.totalRepeats)
        runToEnd(r.script.totalDurationMs + 100)
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(r.id))
        assertEquals(totalOnTimeOf(r), totalOnTimeSent())
        assertEquals(1, sink.vibrations.count { it.timings.last() == 50L && it === sink.vibrations.last() })
    }

    // ---- cancellation -------------------------------------------------------------------------

    @Test
    fun stopCancelsImmediatelyAndNothingResumesNotEvenTheFinalTick() {
        val r = TestRequests.request("hello world", maxSegments = 16)
        controller.submit(r)
        runToEnd(r.script.totalDurationMs / 2)
        val sentBefore = sink.vibrations.size
        assertTrue(sentBefore >= 1 && sentBefore < r.script.batches(16).size)
        assertTrue(controller.state.value.isPlaying)

        controller.stop()
        assertEquals(PlaybackState(), controller.state.value)
        assertEquals(1, sink.cancels.size)
        assertEquals(PlaybackOutcome.CANCELLED, listener.outcomeOf(r.id))
        assertEquals("pending coroutine delays must be cancelled", 0, clock.pendingWaiters)

        runToEnd(r.script.totalDurationMs * 3) // time passes: nothing may start again
        assertEquals(sentBefore, sink.vibrations.size)
        assertTrue(sink.vibrations.none { it.timings.last() == 50L && it.atMs > clock.nowMs() - r.script.totalDurationMs * 3 })
        assertEquals(1, listener.finished.size)
    }

    @Test
    fun cancellingBeforeTheLastBatchMeansTheCompletionTickIsNeverSent() {
        val r = TestRequests.request("hi", maxSegments = 16)
        val batches = r.script.batches(16)
        controller.submit(r)
        // stop just before the final batch would start
        clock.advance(batches.last().startMs - 1)
        controller.stop()
        runToEnd(r.script.totalDurationMs * 2)
        assertTrue("final batch (with tick) must not be sent", sink.vibrations.none { it.timings.last() == 50L })
        assertEquals(batches.size - 1, sink.vibrations.size)
    }

    @Test
    fun stopClearsTheQueueAndReportsDroppedRequests() {
        val a = TestRequests.request("aaa")
        val b = TestRequests.request("bbb")
        val c = TestRequests.request("ccc")
        controller.submit(a)
        controller.submit(b)
        controller.submit(c)
        assertEquals(2, controller.state.value.queueSize)
        controller.stop()
        assertEquals(PlaybackOutcome.CANCELLED, listener.outcomeOf(a.id))
        assertEquals(PlaybackOutcome.DROPPED, listener.outcomeOf(b.id))
        assertEquals(PlaybackOutcome.DROPPED, listener.outcomeOf(c.id))
        runToEnd(100_000)
        assertEquals(1, sink.vibrations.size) // only the first batch of 'a' ever played
        assertTrue(controller.isIdle)
    }

    @Test
    fun stopWhileWaitingForTheMinimumIntervalNeverVibrates() {
        controller.minIntervalMs = 5_000
        val a = TestRequests.request("a")
        controller.submit(a)
        runToEnd(a.script.totalDurationMs + 10)
        val before = sink.vibrations.size
        val b = TestRequests.request("b")
        controller.submit(b)
        assertEquals(PlaybackPhase.WAITING, controller.state.value.phase)
        controller.stop()
        runToEnd(60_000)
        assertEquals(before, sink.vibrations.size)
        assertEquals(PlaybackOutcome.CANCELLED, listener.outcomeOf(b.id))
        assertFalse(listener.started.contains(b.id))
    }

    @Test
    fun interruptingReplacesTheCurrentPlaybackWithoutOverlap() {
        val a = TestRequests.request("a long first message", maxSegments = 16)
        val b = TestRequests.request("b")
        controller.submit(a)
        clock.advance(2_000)
        val sentOfA = sink.vibrations.size
        controller.submit(b, interruptCurrent = true)
        assertEquals(PlaybackOutcome.CANCELLED, listener.outcomeOf(a.id))
        assertEquals(1, sink.cancels.size)
        runToEnd(a.script.totalDurationMs + b.script.totalDurationMs)
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(b.id))
        // after the interruption only B's batches were sent
        val bBatches = b.script.batches().size
        assertEquals(sentOfA + bBatches, sink.vibrations.size)
    }

    @Test
    fun testRequestsBypassPacingAndInterrupt() {
        val a = TestRequests.request("a notification")
        controller.submit(a)
        val t = TestRequests.request("t", bypass = true)
        controller.submit(t)
        assertEquals(PlaybackOutcome.CANCELLED, listener.outcomeOf(a.id))
        assertTrue(controller.state.value.isTest)
        runToEnd(t.script.totalDurationMs + 10)
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(t.id))
    }

    // ---- queue policies through the controller ------------------------------------------------

    @Test
    fun playAllPlaysQueuedRequestsInOrderOneAtATime() {
        val a = TestRequests.request("a")
        val b = TestRequests.request("b")
        val c = TestRequests.request("c")
        controller.queuePolicy = QueuePolicy.PLAY_ALL
        controller.submit(a)
        controller.submit(b)
        controller.submit(c)
        runToEnd(a.script.totalDurationMs + b.script.totalDurationMs + c.script.totalDurationMs + 100)
        assertEquals(listOf(a.id, b.id, c.id), listener.started)
        assertEquals(listOf(a.id, b.id, c.id), listener.finished.map { it.first })
        assertTrue(listener.finished.all { it.second == PlaybackOutcome.COMPLETED })
        // never overlapping: each batch starts after the previous waveform has ended
        sink.vibrations.zipWithNext().forEach { (x, y) ->
            assertTrue("overlap: ${x.atMs}+${x.durationMs} > ${y.atMs}", y.atMs >= x.atMs + x.durationMs - 1_000_000)
        }
        val startOfB = sink.vibrations.first { it.atMs >= a.script.totalDurationMs }
        assertTrue(startOfB.atMs >= a.script.totalDurationMs)
    }

    @Test
    fun replaceWithNewestKeepsOnlyTheLatestWaitingRequest() {
        controller.queuePolicy = QueuePolicy.REPLACE_WITH_NEWEST
        val a = TestRequests.request("a")
        val b = TestRequests.request("b")
        val c = TestRequests.request("c")
        controller.submit(a)
        controller.submit(b)
        controller.submit(c)
        assertEquals(1, controller.state.value.queueSize)
        runToEnd(100_000)
        assertEquals(PlaybackOutcome.REPLACED, listener.outcomeOf(b.id))
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(a.id))
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(c.id))
        assertEquals(listOf(a.id, c.id), listener.started)
    }

    @Test
    fun combinePerAppMergesWaitingRequestsOfTheSameApp() {
        controller.queuePolicy = QueuePolicy.COMBINE_SAME_APP
        val merged = ArrayList<PlaybackRequest>()
        fun combiner(): (Int) -> PlaybackRequest = { prev ->
            TestRequests.request("${prev + 1} new", pkg = "pkg.x", combinedCount = prev + 1).also { merged += it }
        }
        val playing = TestRequests.request("first", pkg = "pkg.x")
        val w1 = TestRequests.request("second", pkg = "pkg.x", combiner = combiner())
        val w2 = TestRequests.request("third", pkg = "pkg.x", combiner = combiner())
        val other = TestRequests.request("other", pkg = "pkg.y", combiner = combiner())
        controller.submit(playing)
        controller.submit(w1) // nothing to merge with: queued as is
        controller.submit(w2) // merged with w1 -> "2 new"
        controller.submit(other)
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().meta.combinedCount)
        runToEnd(1_000_000)
        assertEquals(PlaybackOutcome.REPLACED, listener.outcomeOf(w1.id))
        assertNull(listener.outcomeOf(w2.id)) // w2 itself was replaced by the merged request
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(merged.single().id))
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(other.id))
        assertEquals(listOf(playing.id, merged.single().id, other.id), listener.started)
    }

    @Test
    fun queueIsBoundedAndDropsTheOldestWaitingRequest() {
        controller.queuePolicy = QueuePolicy.PLAY_ALL
        controller.maxQueueSize = 2
        val playing = TestRequests.request("p")
        val q = (1..4).map { TestRequests.request("q$it") }
        controller.submit(playing)
        q.forEach { controller.submit(it) }
        assertEquals(2, controller.state.value.queueSize)
        assertEquals(PlaybackOutcome.DROPPED, listener.outcomeOf(q[0].id))
        assertEquals(PlaybackOutcome.DROPPED, listener.outcomeOf(q[1].id))
        runToEnd(1_000_000)
        assertEquals(listOf(playing.id, q[2].id, q[3].id), listener.started)
    }

    // ---- pacing -------------------------------------------------------------------------------

    @Test
    fun minimumIntervalIsEnforcedBetweenNotifications() {
        controller.minIntervalMs = 3_000
        val a = TestRequests.request("a")
        val b = TestRequests.request("b")
        controller.submit(a)
        controller.submit(b)
        runToEnd(a.script.totalDurationMs + 10) // A done, B waiting
        val endOfA = a.script.totalDurationMs
        assertEquals(PlaybackPhase.WAITING, controller.state.value.phase)
        runToEnd(3_000 + b.script.totalDurationMs + 100)
        val firstOfB = sink.vibrations.first { it.atMs >= endOfA }
        assertTrue("B started ${firstOfB.atMs - endOfA} ms after A", firstOfB.atMs - endOfA >= 3_000)
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(b.id))
    }

    @Test
    fun startDelayPostponesThePlayback() {
        val r = TestRequests.request("a", startDelayMs = 1_500)
        controller.submit(r)
        assertEquals(PlaybackPhase.WAITING, controller.state.value.phase)
        assertTrue(sink.vibrations.isEmpty())
        clock.advance(1_499)
        assertTrue(sink.vibrations.isEmpty())
        clock.advance(2)
        assertEquals(1, sink.vibrations.size)
        runToEnd(r.script.totalDurationMs)
    }

    // ---- failure handling ---------------------------------------------------------------------

    @Test
    fun vibratorFailureRestoresASafeStateAndContinuesWithTheQueue() {
        sink.failOnVibrateCall = 1
        val a = TestRequests.request("a")
        val b = TestRequests.request("b")
        controller.submit(a)
        controller.submit(b)
        assertEquals(PlaybackOutcome.FAILED, listener.outcomeOf(a.id))
        assertTrue(sink.cancels.isNotEmpty())
        assertEquals(PlaybackPhase.PLAYING, controller.state.value.phase) // B has started
        runToEnd(b.script.totalDurationMs + 100)
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(b.id))
        assertTrue(controller.isIdle)
    }

    @Test
    fun errorMessageIsExposedWhenNothingFollows() {
        sink.failOnVibrateCall = 1
        controller.submit(TestRequests.request("a"))
        assertEquals("vibrator exploded", controller.state.value.lastError)
        assertEquals(PlaybackPhase.IDLE, controller.state.value.phase)
    }

    @Test
    fun missingVibratorFailsClearly() {
        sink.available = false
        val r = TestRequests.request("a")
        controller.submit(r)
        assertEquals(PlaybackOutcome.FAILED, listener.outcomeOf(r.id))
        assertEquals("This device has no vibrator.", controller.state.value.lastError)
        assertTrue(sink.vibrations.isEmpty())
    }

    // ---- replay -------------------------------------------------------------------------------

    @Test
    fun replayRestartsTheLastMessage() {
        val r = TestRequests.request("hi")
        controller.submit(r)
        runToEnd(r.script.totalDurationMs + 10)
        val firstRunCount = sink.vibrations.size
        assertTrue(controller.replay())
        runToEnd(r.script.totalDurationMs + 10)
        assertEquals(firstRunCount * 2, sink.vibrations.size)
        assertEquals(2, listener.finished.count { it.second == PlaybackOutcome.COMPLETED })
    }

    @Test
    fun replayWithNothingToReplayReturnsFalse() {
        assertFalse(controller.replay())
    }

    @Test
    fun amplitudeMapping() {
        assertEquals(255, PlaybackController.amplitudeFor(100))
        assertEquals(127, PlaybackController.amplitudeFor(50))
        assertEquals(2, PlaybackController.amplitudeFor(0)) // clamped to 1 % -> 255 * 1 / 100
        assertEquals(255, PlaybackController.amplitudeFor(500))
        val r = TestRequests.request("a").copy(intensityPercent = 40)
        controller.submit(r)
        val v = sink.vibrations.first()
        assertEquals(102, v.amplitudes[1])
        assertEquals(0, v.amplitudes[0])
        runToEnd(r.script.totalDurationMs)
    }
}
