package com.example.vibratext.playback

import com.example.vibratext.encoding.CharacterEncoder
import com.example.vibratext.encoding.ControlCode
import com.example.vibratext.encoding.EncodedUnit
import com.example.vibratext.encoding.EncodingTable
import com.example.vibratext.encoding.FrameField
import com.example.vibratext.encoding.MessageFramer
import com.example.vibratext.encoding.MessageSection
import com.example.vibratext.encoding.SymbolMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PatternGeneratorTest {
    private val timing = TimingSettings.DEFAULT
    private val encoder = CharacterEncoder(EncodingTable.DEFAULT)

    private fun unit(code: Int, text: String? = "x") = EncodedUnit(code, text, null, MessageSection.TEXT)

    private fun onOff(script: VibrationScript) = script.segments.map { (if (it.on) "ON" else "OFF") to it.durationMs }

    @Test
    fun symbolWaveformsMatchTheSpecification() {
        // code 45 = base-4 2,3,1 = DOUBLE TRIPLE LONG with the default mapping
        val script = VibrationPatternGenerator.generate(listOf(unit(45)), SymbolMapping.DEFAULT, timing, completionTick = false)
        assertEquals(
            listOf(
                // DOUBLE: ON 150, OFF 120, ON 150
                "ON" to 150, "OFF" to 120, "ON" to 150, "OFF" to 250,
                // TRIPLE
                "ON" to 150, "OFF" to 120, "ON" to 150, "OFF" to 120, "ON" to 150, "OFF" to 250,
                // LONG (last symbol: no trailing gap without completion tick)
                "ON" to 500,
            ),
            onOff(script),
        )
        // code 0 = SHORT SHORT SHORT
        val shorts = VibrationPatternGenerator.generate(listOf(unit(0)), SymbolMapping.DEFAULT, timing, completionTick = false)
        assertEquals(
            listOf("ON" to 150, "OFF" to 250, "ON" to 150, "OFF" to 250, "ON" to 150),
            onOff(shorts),
        )
    }

    @Test
    fun waveformTimingsStartWithOffAndAlternate() {
        val script = VibrationPatternGenerator.generate(listOf(unit(45)), SymbolMapping.DEFAULT, timing, completionTick = false)
        val batch = script.batches().single()
        val t = batch.timings()
        assertEquals(0L, t[0])
        assertEquals(listOf(0L, 150, 120, 150, 250, 150, 120, 150, 120, 150, 250, 500), t.toList())
        assertEquals(t.size, batch.amplitudes(200).size)
        assertEquals(List(t.size) { if (it % 2 == 1) 200 else 0 }, batch.amplitudes(200).toList())
    }

    @Test
    fun characterWordAndMarkerGapsAreDistinct() {
        val framed = MessageFramer(encoder).frame(listOf(FrameField(MessageSection.SENDER, "a b"), FrameField(MessageSection.MESSAGE, "c")))
        val script = VibrationPatternGenerator.generate(framed.units, SymbolMapping.DEFAULT, timing)
        // collect the gap that follows each unit
        val gaps = HashMap<Int, Int>()
        for (seg in script.segments) {
            if (seg.role == SegmentRole.UNIT_GAP) gaps[seg.unitIndex] = seg.durationMs
        }
        val units = framed.units
        for ((i, u) in units.withIndex()) {
            val expected = when {
                u.control == ControlCode.START -> 800
                u.control == ControlCode.SEPARATOR -> 800
                u.control == ControlCode.END -> 800 // pause before the completion tick
                u.text == " " -> 800
                else -> 450
            }
            assertEquals("gap after unit $i (${u.display})", expected, gaps[i])
        }
        // letters get the shorter character gap, the space gets the word gap
        assertEquals(450, gaps[1])
        assertEquals(800, gaps[2])
    }

    @Test
    fun customMarkerPausesAreUsed() {
        val t = timing.copy(startMarkerPauseMs = 1111, separatorPauseMs = 2222, endMarkerPauseMs = 3333, wordGapMs = 999)
        val framed = MessageFramer(encoder).frame(listOf(FrameField(MessageSection.SENDER, "a"), FrameField(MessageSection.MESSAGE, "b")))
        val script = VibrationPatternGenerator.generate(framed.units, SymbolMapping.DEFAULT, t)
        val gaps = script.segments.filter { it.role == SegmentRole.UNIT_GAP }.associate { it.unitIndex to it.durationMs }
        assertEquals(1111, gaps[0])
        assertEquals(2222, gaps[2])
        assertEquals(3333, gaps[4])
    }

    @Test
    fun completionTickOccursOnceAtTheEndAndOnlyWhenEnabled() {
        val with = TestRequests.script("hi", tick = true)
        val ticks = with.segments.filter { it.role == SegmentRole.COMPLETION_TICK }
        assertEquals(1, ticks.size)
        assertEquals(with.segments.last(), ticks.single())
        assertEquals(50, ticks.single().durationMs)

        val without = TestRequests.script("hi", tick = false)
        assertTrue(without.segments.none { it.role == SegmentRole.COMPLETION_TICK })
        assertTrue(without.segments.last().on) // ends with the final pulse, no dangling gap
    }

    @Test
    fun repeatCountsAreRespectedAndTickOnlyFollowsTheFinalRepetition() {
        val one = TestRequests.script("ab", RepeatSettings(count = 1))
        val three = TestRequests.script("ab", RepeatSettings(count = 3, pauseMs = 1500))
        val pulsesPerRepetition = one.segments.count { it.on && it.role == SegmentRole.PULSE }
        assertEquals(pulsesPerRepetition * 3, three.segments.count { it.on && it.role == SegmentRole.PULSE })
        assertEquals(3, three.repeatCount)
        assertEquals(setOf(0, 1, 2), three.segments.map { it.repeatIndex }.toSet())
        assertEquals(1, three.segments.count { it.role == SegmentRole.COMPLETION_TICK })
        assertEquals(2, three.segments.count { it.role == SegmentRole.REPEAT_GAP })
        assertTrue(three.segments.filter { it.role == SegmentRole.REPEAT_GAP }.all { it.durationMs == 1500 })
        assertEquals(2, three.segments.last { it.role == SegmentRole.COMPLETION_TICK }.repeatIndex)
        assertEquals(0, three.segments.count { it.role == SegmentRole.REPEAT_MARKER })
    }

    @Test
    fun repeatMarkerIsOptionalAndDistinctFromTheCompletionTick() {
        val s = TestRequests.script("a", RepeatSettings(count = 3, pauseMs = 500, repeatMarker = true))
        val markerPulses = s.segments.filter { it.role == SegmentRole.REPEAT_MARKER && it.on }
        assertEquals(2 * 2, markerPulses.size) // two ticks after each of the 2 intermediate repetitions
        assertEquals(1, s.segments.count { it.role == SegmentRole.COMPLETION_TICK })
    }

    @Test
    fun generationIsDeterministicAndDurationIsTheSumOfSegments() {
        val a = TestRequests.script("hello world")
        val b = TestRequests.script("hello world")
        assertEquals(a.segments, b.segments)
        assertEquals(a.segments.sumOf { it.durationMs.toLong() }, a.totalDurationMs)
        assertEquals(0L, a.startOffsetMs(0))
        assertEquals(a.totalDurationMs, a.startOffsetMs(a.segments.size))
    }

    @Test
    fun batchingNeverChangesTimingOrOrdering() {
        val script = TestRequests.script("this is a fairly long message 12345", RepeatSettings(count = 2))
        val batches = script.batches(maxSegments = 16)
        assertTrue(batches.size > 5)
        assertEquals(script.segments, batches.flatMap { it.segments })
        assertEquals(script.totalDurationMs, batches.sumOf { it.durationMs })
        var expectedStart = 0L
        for ((i, b) in batches.withIndex()) {
            assertEquals(expectedStart, b.startMs)
            expectedStart += b.durationMs
            if (i < batches.size - 1) {
                // every cut happens right after a gap that ends a character / repetition
                assertTrue(b.segments.last().role == SegmentRole.UNIT_GAP || b.segments.last().role == SegmentRole.REPEAT_GAP)
                assertFalse(b.segments.last().on)
            }
            val t = b.timings()
            assertEquals(0L, t[0])
            assertTrue("odd length: ends with ON", t.size % 2 == 0)
            // total of the waveform + the trailing gap that was dropped == batch duration
            val trailingOff = b.segments.takeLastWhile { !it.on }.sumOf { it.durationMs.toLong() }
            assertEquals(b.durationMs, t.sum() + trailingOff)
        }
    }

    @Test
    fun lastBatchEndsWithTheCompletionTick() {
        val script = TestRequests.script("hello", RepeatSettings(count = 2))
        val last = script.batches(16).last()
        assertEquals(SegmentRole.COMPLETION_TICK, last.segments.last().role)
        assertEquals(50L, last.timings().last())
    }

    @Test
    fun customMappingChangesThePulseStructureButNotTheCode() {
        val reversed = SymbolMapping(
            listOf(
                com.example.vibratext.encoding.VibSymbol.TRIPLE,
                com.example.vibratext.encoding.VibSymbol.DOUBLE,
                com.example.vibratext.encoding.VibSymbol.LONG,
                com.example.vibratext.encoding.VibSymbol.SHORT,
            ),
        )
        val u = listOf(unit(0)) // digits 0,0,0
        val def = VibrationPatternGenerator.generate(u, SymbolMapping.DEFAULT, timing, completionTick = false)
        val rev = VibrationPatternGenerator.generate(u, reversed, timing, completionTick = false)
        assertEquals(3, def.pulseCount) // three SHORT
        assertEquals(9, rev.pulseCount) // three TRIPLE
    }

    @Test
    fun singleSymbolAndTickScriptsForTheCalibrationScreen() {
        fun shape(s: com.example.vibratext.encoding.VibSymbol) =
            onOff(VibrationPatternGenerator.generateSymbol(s, timing))
        assertEquals(listOf("ON" to 150), shape(com.example.vibratext.encoding.VibSymbol.SHORT))
        assertEquals(listOf("ON" to 500), shape(com.example.vibratext.encoding.VibSymbol.LONG))
        assertEquals(listOf("ON" to 150, "OFF" to 120, "ON" to 150), shape(com.example.vibratext.encoding.VibSymbol.DOUBLE))
        assertEquals(listOf("ON" to 150, "OFF" to 120, "ON" to 150, "OFF" to 120, "ON" to 150), shape(com.example.vibratext.encoding.VibSymbol.TRIPLE))
        val tick = VibrationPatternGenerator.generateTick(timing.copy(completionTickMs = 77))
        assertEquals(listOf("ON" to 77), onOff(tick))
        assertEquals(listOf(0L, 77L), tick.batches().single().timings().toList())
        assertTrue(VibrationPatternGenerator.generateSymbol(com.example.vibratext.encoding.VibSymbol.LONG, timing).units.isEmpty())
    }

    // ---- timing settings --------------------------------------------------------------------

    @Test
    fun defaultTimingsMatchTheSpecificationAndAreValid() {
        val t = TimingSettings.DEFAULT
        assertEquals(150, t.shortPulseMs)
        assertEquals(500, t.longPulseMs)
        assertEquals(120, t.pulseGapMs)
        assertEquals(250, t.symbolGapMs)
        assertEquals(450, t.charGapMs)
        assertEquals(800, t.wordGapMs)
        assertEquals(50, t.completionTickMs)
        assertTrue(t.validate().isEmpty())
    }

    @Test
    fun invalidTimingValuesAreRejectedWithExplanations() {
        assertTrue(timing.copy(shortPulseMs = 5).validate().any { it.contains("Short pulse") })
        assertTrue(timing.copy(longPulseMs = 99999).validate().any { it.contains("Long pulse") })
        assertTrue(timing.copy(completionTickMs = 0).validate().any { it.contains("Completion tick") })
        assertTrue(timing.copy(wordGapMs = -1).validate().isNotEmpty())
        assertTrue(timing.copy(startMarkerPauseMs = 20000).validate().isNotEmpty())
        // ordering rules that keep symbols recognisable
        assertTrue(timing.copy(symbolGapMs = 100, pulseGapMs = 120).validate().any { it.contains("DOUBLE") })
        assertTrue(timing.copy(charGapMs = 200).validate().any { it.contains("characters") })
        assertTrue(timing.copy(wordGapMs = 300).validate().any { it.contains("words") })
        assertTrue(timing.copy(longPulseMs = 200).validate().any { it.contains("long pulse") })
        assertTrue(RepeatSettings(count = 0).validate().isNotEmpty())
        assertTrue(RepeatSettings(count = 11).validate().isNotEmpty())
        assertTrue(RepeatSettings(pauseMs = 5).validate().isNotEmpty())
        assertTrue(SpeedSettings(SpeedPreset.CUSTOM, 9.0).validate().isNotEmpty())
        assertTrue(SpeedSettings(SpeedPreset.CUSTOM, 0.1).validate().isNotEmpty())
    }

    @Test
    fun speedPresetsScaleDurationsConsistently() {
        assertEquals(1.5, SpeedSettings(SpeedPreset.SLOW).multiplier, 0.0)
        assertEquals(1.0, SpeedSettings(SpeedPreset.NORMAL).multiplier, 0.0)
        assertEquals(0.75, SpeedSettings(SpeedPreset.FAST).multiplier, 0.0)
        assertEquals(1.25, SpeedSettings(SpeedPreset.CUSTOM, 1.25).multiplier, 0.0)

        val slow = timing.scaled(1.5)
        assertEquals(225, slow.shortPulseMs)
        assertEquals(750, slow.longPulseMs)
        assertEquals(375, slow.symbolGapMs)
        assertEquals(675, slow.charGapMs)
        assertEquals(1200, slow.wordGapMs)
        val fast = timing.scaled(0.75)
        assertEquals(113, fast.shortPulseMs)
        assertEquals(90, fast.pulseGapMs)
        assertEquals(1.0, timing.scaled(1.0).shortPulseMs / 150.0, 0.0)
        assertEquals(timing, timing.scaled(1.0))

        // total playback time follows the multiplier
        val normalTotal = TestRequests.script("hello", timing = timing).totalDurationMs
        val slowTotal = TestRequests.script("hello", timing = slow).totalDurationMs
        val fastTotal = TestRequests.script("hello", timing = fast).totalDurationMs
        assertTrue(slowTotal > normalTotal && normalTotal > fastTotal)
        assertEquals(1.5, slowTotal.toDouble() / normalTotal, 0.03)
    }

    @Test
    fun everyScaledTimingStaysValidAndRecognisable() {
        var m = 0.25
        while (m <= 4.0) {
            val s = timing.scaled(m)
            assertTrue("multiplier $m: ${s.validate()}", s.validate().isEmpty())
            assertTrue(s.symbolGapMs > s.pulseGapMs)
            m += 0.05
        }
        try {
            timing.scaled(10.0)
            fail("expected exception")
        } catch (_: IllegalArgumentException) {
        }
    }
}
