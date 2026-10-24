package com.example.vibratext.playback

import com.example.vibratext.encoding.CodeSymbols
import com.example.vibratext.encoding.ControlCode
import com.example.vibratext.encoding.EncodedUnit
import com.example.vibratext.encoding.SymbolMapping
import com.example.vibratext.encoding.VibSymbol

enum class SegmentRole {
    /** A vibrating pulse that is part of a symbol. */
    PULSE,
    /** Silence between the pulses of a DOUBLE / TRIPLE. */
    PULSE_GAP,
    /** Silence between the symbols of one character. */
    SYMBOL_GAP,
    /** Silence after a whole character/marker (char gap, word gap or marker pause). */
    UNIT_GAP,
    /** Silence between repetitions. */
    REPEAT_GAP,
    /** The two quick ticks that separate repetitions (only if enabled). */
    REPEAT_MARKER,
    /** The single final tick that confirms the message finished. */
    COMPLETION_TICK,
}

/** One step of the waveform: vibrator ON or OFF for [durationMs]. */
data class Segment(
    val on: Boolean,
    val durationMs: Int,
    val role: SegmentRole,
    val repeatIndex: Int,
    /** Index into [VibrationScript.units], or -1 for repetition markers and the final tick. */
    val unitIndex: Int,
    val symbolIndex: Int,
    val symbol: VibSymbol?,
)

/**
 * The complete, timed vibration plan for one notification. Pure data: it contains every pulse
 * and every gap, so the same input and settings always produce the same script.
 */
class VibrationScript(
    val units: List<EncodedUnit>,
    val segments: List<Segment>,
    val repeatCount: Int,
) {
    val totalDurationMs: Long = segments.sumOf { it.durationMs.toLong() }

    private val offsets: LongArray = LongArray(segments.size + 1).also { arr ->
        var t = 0L
        segments.forEachIndexed { i, s ->
            arr[i] = t
            t += s.durationMs
        }
        arr[segments.size] = t
    }

    /** Start time of segment [index], relative to the start of the script. */
    fun startOffsetMs(index: Int): Long = offsets[index]

    val pulseCount: Int get() = segments.count { it.on }

    /**
     * Splits the script into batches that are handed to the vibrator one after another.
     * Cuts are only made *after a gap that ends a character or repetition*, so a batch always
     * ends in silence and the next one starts with the next pulse: character and word
     * boundaries are never altered by batching.
     */
    fun batches(maxSegments: Int = DEFAULT_BATCH_SEGMENTS): List<Batch> {
        require(maxSegments >= 8) { "maxSegments too small" }
        val out = ArrayList<Batch>()
        var start = 0
        var count = 0
        for ((i, seg) in segments.withIndex()) {
            count++
            val canCut = seg.role == SegmentRole.UNIT_GAP || seg.role == SegmentRole.REPEAT_GAP
            if (count >= maxSegments && canCut) {
                out += Batch(this, start, i + 1)
                start = i + 1
                count = 0
            }
        }
        if (start < segments.size) out += Batch(this, start, segments.size)
        return out
    }

    companion object {
        const val DEFAULT_BATCH_SEGMENTS = 120
    }
}

/** A contiguous slice [fromIndex, toIndex) of a script, played as one VibrationEffect waveform. */
class Batch(private val script: VibrationScript, val fromIndex: Int, val toIndex: Int) {
    val segments: List<Segment> get() = script.segments.subList(fromIndex, toIndex)
    val startMs: Long get() = script.startOffsetMs(fromIndex)
    val durationMs: Long get() = script.startOffsetMs(toIndex) - script.startOffsetMs(fromIndex)

    /**
     * Android waveform timings: alternating OFF, ON, OFF, ON … starting with OFF (0 ms if the
     * batch starts with a pulse). Neighbouring segments with the same state are merged and a
     * trailing OFF is dropped (the controller still waits for the full [durationMs]).
     */
    fun timings(): LongArray {
        val out = ArrayList<Long>()
        out.add(0L)
        var lastOn = false
        for (seg in segments) {
            if (seg.on == lastOn) {
                out[out.size - 1] = out[out.size - 1] + seg.durationMs
            } else {
                out.add(seg.durationMs.toLong())
                lastOn = seg.on
            }
        }
        if (!lastOn && out.size > 1) out.removeAt(out.size - 1)
        return out.toLongArray()
    }

    /** Amplitudes matching [timings]: 0 for OFF entries, [onAmplitude] (1..255) for ON entries. */
    fun amplitudes(onAmplitude: Int): IntArray {
        val t = timings()
        return IntArray(t.size) { i -> if (i % 2 == 1) onAmplitude else 0 }
    }
}

object VibrationPatternGenerator {

    /**
     * Builds the script for [units].
     *
     * Per repetition: every unit is its 3 symbols (SHORT = one pulse, LONG = one long pulse,
     * DOUBLE/TRIPLE = 2/3 pulses separated by the pulse gap), with the symbol gap between
     * symbols and a unit gap after the unit: marker pause after START/SEPARATOR/END, word gap
     * after a space, character gap otherwise.
     * Between repetitions: the repeat pause (plus, optionally, a two-tick repeat marker).
     * After the final repetition: the END pause, then one completion tick (if enabled).
     */
    fun generate(
        units: List<EncodedUnit>,
        mapping: SymbolMapping,
        timing: TimingSettings,
        repeat: RepeatSettings = RepeatSettings(),
        completionTick: Boolean = true,
    ): VibrationScript {
        val segs = ArrayList<Segment>(units.size * 20 * repeat.count)

        fun on(ms: Int, role: SegmentRole, rep: Int, unit: Int, symIdx: Int, symbol: VibSymbol?) {
            segs += Segment(true, ms, role, rep, unit, symIdx, symbol)
        }

        fun off(ms: Int, role: SegmentRole, rep: Int, unit: Int, symIdx: Int, symbol: VibSymbol?) {
            if (ms > 0) segs += Segment(false, ms, role, rep, unit, symIdx, symbol)
        }

        for (rep in 0 until repeat.count) {
            for ((u, unit) in units.withIndex()) {
                val symbols = CodeSymbols.toSymbols(unit.code, mapping)
                for ((s, symbol) in symbols.withIndex()) {
                    val pulses = timing.pulseCount(symbol)
                    for (p in 0 until pulses) {
                        on(timing.pulseMs(symbol), SegmentRole.PULSE, rep, u, s, symbol)
                        if (p < pulses - 1) off(timing.pulseGapMs, SegmentRole.PULSE_GAP, rep, u, s, symbol)
                    }
                    if (s < symbols.size - 1) off(timing.symbolGapMs, SegmentRole.SYMBOL_GAP, rep, u, s, symbol)
                }
                val isLastUnit = u == units.size - 1
                val isFinalRepetition = rep == repeat.count - 1
                when {
                    !isLastUnit -> off(unitGap(unit, timing), SegmentRole.UNIT_GAP, rep, u, CodeSymbols.CODE_LENGTH - 1, symbols.last())
                    !isFinalRepetition -> {
                        off(repeat.pauseMs, SegmentRole.REPEAT_GAP, rep, u, CodeSymbols.CODE_LENGTH - 1, symbols.last())
                        if (repeat.repeatMarker) {
                            on(timing.completionTickMs, SegmentRole.REPEAT_MARKER, rep, -1, -1, null)
                            off(timing.pulseGapMs, SegmentRole.REPEAT_MARKER, rep, -1, -1, null)
                            on(timing.completionTickMs, SegmentRole.REPEAT_MARKER, rep, -1, -1, null)
                            off(repeat.pauseMs, SegmentRole.REPEAT_GAP, rep, -1, -1, null)
                        }
                    }
                    completionTick -> {
                        off(unitGap(unit, timing), SegmentRole.UNIT_GAP, rep, u, CodeSymbols.CODE_LENGTH - 1, symbols.last())
                        on(timing.completionTickMs, SegmentRole.COMPLETION_TICK, rep, -1, -1, null)
                    }
                }
            }
        }
        return VibrationScript(units, segs, repeat.count)
    }

    /** One bare symbol (for the calibration screen: "Test SHORT" …). Contains no units. */
    fun generateSymbol(symbol: VibSymbol, timing: TimingSettings): VibrationScript {
        val segs = ArrayList<Segment>()
        val pulses = timing.pulseCount(symbol)
        for (p in 0 until pulses) {
            segs += Segment(true, timing.pulseMs(symbol), SegmentRole.PULSE, 0, -1, 0, symbol)
            if (p < pulses - 1) segs += Segment(false, timing.pulseGapMs, SegmentRole.PULSE_GAP, 0, -1, 0, symbol)
        }
        return VibrationScript(emptyList(), segs, 1)
    }

    /** Just the completion tick (calibration screen: "Test completion tick"). */
    fun generateTick(timing: TimingSettings): VibrationScript =
        VibrationScript(emptyList(), listOf(Segment(true, timing.completionTickMs, SegmentRole.COMPLETION_TICK, 0, -1, -1, null)), 1)

    /** The gap that follows [unit]. */
    fun unitGap(unit: EncodedUnit, timing: TimingSettings): Int = when (unit.control) {
        ControlCode.START -> timing.startMarkerPauseMs
        ControlCode.SEPARATOR -> timing.separatorPauseMs
        ControlCode.END -> timing.endMarkerPauseMs
        ControlCode.CASE_SHIFT, ControlCode.TRUNCATED -> timing.charGapMs
        null -> if (unit.text == " ") timing.wordGapMs else timing.charGapMs
    }
}
