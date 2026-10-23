package com.example.vibratext.pipeline

import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.encoding.CharacterDecoder
import com.example.vibratext.encoding.ControlCode
import com.example.vibratext.encoding.DecodeResult
import com.example.vibratext.encoding.SymbolMapping
import com.example.vibratext.encoding.UnsupportedPolicy
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.playback.FakeClock
import com.example.vibratext.playback.FakeSink
import com.example.vibratext.playback.PlaybackController
import com.example.vibratext.playback.PlaybackOutcome
import com.example.vibratext.playback.RecordingListener
import com.example.vibratext.playback.RequestSource
import com.example.vibratext.playback.SpeedPreset
import com.example.vibratext.playback.SpeedSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPlaybackTest {
    private val profile = EncodingProfile(id = "p", name = "P")

    @Test
    fun messagePlanShowsOriginalNormalizedSymbolsAndDuration() {
        val plan = ManualPlayback.forMessage(null, "Héllo", profile)!!
        assertEquals("Héllo", plan.original)
        assertEquals(listOf("hello"), plan.normalizedFields)
        // START + 5 letters + END
        assertEquals(7, plan.units.size)
        assertTrue(plan.estimatedDurationMs > 0)
        assertEquals(RequestSource.TEST, plan.request.meta.source)
        assertTrue(plan.request.bypassPacing)
        // identical input and settings give identical output
        assertEquals(plan.symbolText, ManualPlayback.forMessage(null, "Héllo", profile)!!.symbolText)
    }

    @Test
    fun theSymbolTextOfAPlanDecodesBackToTheNormalizedMessage() {
        val plan = ManualPlayback.forMessage("Alex", "Hello, are you free?", profile)!!
        val decoded = profile.decoder().decodeSymbols(plan.symbolText)
        assertTrue(decoded is DecodeResult.Success)
        val msg = (decoded as DecodeResult.Success).value
        assertEquals(listOf("alex", "hello, are you free?"), msg.fields.map { it.text })
    }

    @Test
    fun blankInputGivesNoPlanAndUnsupportedCharactersFollowTheProfilePolicy() {
        assertNull(ManualPlayback.forMessage("  ", " ", profile))
        assertEquals(1, ManualPlayback.forMessage(null, "a中", profile)!!.replacedCount)
        val strict = profile.copy(unsupportedPolicy = UnsupportedPolicy.ERROR)
        try {
            ManualPlayback.forMessage(null, "a中", strict)
            throw AssertionError("expected an exception")
        } catch (e: com.example.vibratext.encoding.UnsupportedCharacterException) {
            // expected: the UI shows a clear message
        }
    }

    @Test
    fun changingTheMappingChangesThePlanConsistentlyAndStillDecodes() {
        val swapped = profile.copy(symbolMapping = SymbolMapping(listOf(VibSymbol.LONG, VibSymbol.SHORT, VibSymbol.TRIPLE, VibSymbol.DOUBLE)))
        val a = ManualPlayback.forMessage(null, "hi", profile)!!
        val b = ManualPlayback.forMessage(null, "hi", swapped)!!
        assertTrue(a.symbolText != b.symbolText)
        val decoded = swapped.decoder().decodeSymbols(b.symbolText) as DecodeResult.Success
        assertEquals("hi", decoded.value.displayText)
    }

    @Test
    fun characterSymbolControlAndTickRequestsAreBuiltFromTheProfile() {
        val c = ManualPlayback.forCharacter('a', profile)!!
        assertEquals(1, c.units.size)
        assertTrue(c.request.script.pulseCount >= 3) // three symbols, one pulse or more each
        assertEquals(0, c.request.script.segments.count { it.role == com.example.vibratext.playback.SegmentRole.COMPLETION_TICK })
        assertNull(ManualPlayback.forCharacter('中', profile))

        val sym = ManualPlayback.forSymbol(VibSymbol.TRIPLE, profile)
        assertEquals(3, sym.script.pulseCount)
        assertEquals(1, ManualPlayback.forSymbol(VibSymbol.LONG, profile).script.pulseCount)

        val start = ManualPlayback.forControl(ControlCode.START, profile)
        assertEquals(1, start.script.units.size)
        assertEquals(ControlCode.START, start.script.units[0].control)

        val tick = ManualPlayback.forTick(profile)
        assertEquals(50L, tick.script.totalDurationMs)
    }

    @Test
    fun speedAndRepeatSettingsAffectTheEstimate() {
        val normal = ManualPlayback.forMessage(null, "hello", profile)!!.estimatedDurationMs
        val slow = ManualPlayback.forMessage(null, "hello", profile.copy(speed = SpeedSettings(SpeedPreset.SLOW)))!!.estimatedDurationMs
        val twice = ManualPlayback.forMessage(null, "hello", profile.copy(repeat = com.example.vibratext.playback.RepeatSettings(count = 2)))!!.estimatedDurationMs
        assertTrue(slow > normal)
        assertTrue(twice > normal * 19 / 10)
    }

    @Test
    fun aSymbolRequestPlaysThroughTheControllerAndReachesTheVibrator() {
        val clock = FakeClock()
        val sink = FakeSink(clock)
        val listener = RecordingListener()
        val controller = PlaybackController(CoroutineScope(Dispatchers.Unconfined + SupervisorJob()), sink, clock).also { it.listener = listener }
        val request = ManualPlayback.forSymbol(VibSymbol.DOUBLE, profile)
        controller.submit(request)
        clock.advance(request.script.totalDurationMs + 50)
        assertEquals(PlaybackOutcome.COMPLETED, listener.outcomeOf(request.id))
        assertEquals(1, sink.vibrations.size)
        assertEquals(listOf(0L, 150L, 120L, 150L), sink.vibrations[0].timings.toList())
        assertNotNull(controller.state)
        assertTrue(controller.isIdle)
    }
}
