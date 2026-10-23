package com.example.vibratext.playback

import com.example.vibratext.encoding.VibSymbol
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * All durations in milliseconds. Defaults follow the specification.
 *
 * Gap hierarchy (what the user feels between things):
 *  - [pulseGapMs]   between the pulses *inside* DOUBLE / TRIPLE
 *  - [symbolGapMs]  between the 3 symbols of one character
 *  - [charGapMs]    between characters
 *  - [wordGapMs]    after a space character (word boundary)
 *  - marker pauses  after START / SEPARATOR / END, so fields are clearly separated
 *
 * Because [pulseGapMs] < [symbolGapMs], a DOUBLE can never be mistaken for two SHORTs.
 */
data class TimingSettings(
    val shortPulseMs: Int = 150,
    val longPulseMs: Int = 500,
    /** Length of each individual pulse inside a DOUBLE symbol. */
    val doublePulseMs: Int = 150,
    /** Length of each individual pulse inside a TRIPLE symbol. */
    val triplePulseMs: Int = 150,
    val pulseGapMs: Int = 120,
    val symbolGapMs: Int = 250,
    val charGapMs: Int = 450,
    val wordGapMs: Int = 800,
    val startMarkerPauseMs: Int = 800,
    val separatorPauseMs: Int = 800,
    val endMarkerPauseMs: Int = 800,
    val completionTickMs: Int = 50,
) {
    fun pulseMs(symbol: VibSymbol): Int = when (symbol) {
        VibSymbol.SHORT -> shortPulseMs
        VibSymbol.LONG -> longPulseMs
        VibSymbol.DOUBLE -> doublePulseMs
        VibSymbol.TRIPLE -> triplePulseMs
    }

    fun pulseCount(symbol: VibSymbol): Int = when (symbol) {
        VibSymbol.SHORT, VibSymbol.LONG -> 1
        VibSymbol.DOUBLE -> 2
        VibSymbol.TRIPLE -> 3
    }

    /** Human-readable problems; empty means valid. */
    fun validate(): List<String> {
        val e = mutableListOf<String>()
        fun range(name: String, v: Int, r: IntRange) {
            if (v !in r) e += "$name must be between ${r.first} and ${r.last} ms (was $v)."
        }
        range("Short pulse", shortPulseMs, PULSE_RANGE)
        range("Long pulse", longPulseMs, PULSE_RANGE)
        range("Double pulse", doublePulseMs, PULSE_RANGE)
        range("Triple pulse", triplePulseMs, PULSE_RANGE)
        range("Gap between pulses", pulseGapMs, PULSE_GAP_RANGE)
        range("Gap between symbols", symbolGapMs, SYMBOL_GAP_RANGE)
        range("Gap between characters", charGapMs, CHAR_GAP_RANGE)
        range("Gap between words", wordGapMs, WORD_GAP_RANGE)
        range("Pause after START marker", startMarkerPauseMs, MARKER_PAUSE_RANGE)
        range("Pause after field separator", separatorPauseMs, MARKER_PAUSE_RANGE)
        range("Pause after END marker", endMarkerPauseMs, MARKER_PAUSE_RANGE)
        range("Completion tick", completionTickMs, TICK_RANGE)
        if (e.isNotEmpty()) return e
        if (longPulseMs < shortPulseMs + MIN_LONG_MARGIN_MS) {
            e += "The long pulse must be at least $MIN_LONG_MARGIN_MS ms longer than the short pulse so they feel different."
        }
        if (symbolGapMs <= pulseGapMs) {
            e += "The gap between symbols must be longer than the gap between pulses, otherwise a DOUBLE feels like two SHORT symbols."
        }
        if (charGapMs < symbolGapMs) e += "The gap between characters must not be shorter than the gap between symbols."
        if (wordGapMs < charGapMs) e += "The gap between words must not be shorter than the gap between characters."
        return e
    }

    /**
     * Multiplies every duration by [multiplier] (speed presets: Slow 1.5, Normal 1.0, Fast 0.75),
     * clamps to the allowed ranges and re-establishes the ordering rules so a scaled profile
     * is still readable by touch.
     */
    fun scaled(multiplier: Double): TimingSettings {
        require(multiplier in SpeedSettings.MULTIPLIER_RANGE) { "Speed multiplier must be within ${SpeedSettings.MULTIPLIER_RANGE}" }
        fun s(v: Int, r: IntRange) = (v * multiplier).roundToInt().coerceIn(r.first, r.last)
        val shortP = s(shortPulseMs, PULSE_RANGE)
        val pulseGap = s(pulseGapMs, PULSE_GAP_RANGE)
        val symbolGap = max(s(symbolGapMs, SYMBOL_GAP_RANGE), pulseGap + 10)
        val charGap = max(s(charGapMs, CHAR_GAP_RANGE), symbolGap)
        val wordGap = max(s(wordGapMs, WORD_GAP_RANGE), charGap)
        return TimingSettings(
            shortPulseMs = shortP,
            longPulseMs = min(PULSE_RANGE.last, max(s(longPulseMs, PULSE_RANGE), shortP + MIN_LONG_MARGIN_MS)),
            doublePulseMs = s(doublePulseMs, PULSE_RANGE),
            triplePulseMs = s(triplePulseMs, PULSE_RANGE),
            pulseGapMs = pulseGap,
            symbolGapMs = min(SYMBOL_GAP_RANGE.last, symbolGap),
            charGapMs = min(CHAR_GAP_RANGE.last, charGap),
            wordGapMs = min(WORD_GAP_RANGE.last, wordGap),
            startMarkerPauseMs = s(startMarkerPauseMs, MARKER_PAUSE_RANGE),
            separatorPauseMs = s(separatorPauseMs, MARKER_PAUSE_RANGE),
            endMarkerPauseMs = s(endMarkerPauseMs, MARKER_PAUSE_RANGE),
            completionTickMs = s(completionTickMs, TICK_RANGE),
        )
    }

    companion object {
        val DEFAULT = TimingSettings()
        const val MIN_LONG_MARGIN_MS = 100
        val PULSE_RANGE = 20..2000
        val PULSE_GAP_RANGE = 30..1000
        val SYMBOL_GAP_RANGE = 50..3000
        val CHAR_GAP_RANGE = 50..5000
        val WORD_GAP_RANGE = 50..10000
        val MARKER_PAUSE_RANGE = 0..10000
        val TICK_RANGE = 10..500
    }
}

enum class SpeedPreset(val label: String, val fixedMultiplier: Double?) {
    SLOW("Slow", 1.5),
    NORMAL("Normal", 1.0),
    FAST("Fast", 0.75),
    CUSTOM("Custom", null),
}

/** Playback speed expressed as a *duration* multiplier: 1.5 = everything takes 50% longer. */
data class SpeedSettings(
    val preset: SpeedPreset = SpeedPreset.NORMAL,
    val customMultiplier: Double = 1.0,
) {
    val multiplier: Double get() = preset.fixedMultiplier ?: customMultiplier

    fun validate(): List<String> =
        if (multiplier in MULTIPLIER_RANGE) emptyList()
        else listOf("Speed multiplier must be between ${MULTIPLIER_RANGE.start} and ${MULTIPLIER_RANGE.endInclusive}.")

    companion object {
        val MULTIPLIER_RANGE = 0.25..4.0
    }
}

/**
 * [count] total plays per notification. The completion tick is only played after the final
 * repetition. Optionally a repetition marker (two quick ticks) separates the repetitions.
 */
data class RepeatSettings(
    val count: Int = 1,
    val pauseMs: Int = 2000,
    val repeatMarker: Boolean = false,
) {
    fun validate(): List<String> {
        val e = mutableListOf<String>()
        if (count !in COUNT_RANGE) e += "Repeat count must be between ${COUNT_RANGE.first} and ${COUNT_RANGE.last}."
        if (pauseMs !in PAUSE_RANGE) e += "Pause between repetitions must be between ${PAUSE_RANGE.first} and ${PAUSE_RANGE.last} ms."
        return e
    }

    companion object {
        val COUNT_RANGE = 1..10
        val PAUSE_RANGE = 200..10_000
    }
}
