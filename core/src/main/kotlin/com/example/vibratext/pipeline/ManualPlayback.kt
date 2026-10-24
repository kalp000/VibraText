package com.example.vibratext.pipeline

import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.encoding.CharacterDecoder
import com.example.vibratext.encoding.ControlCode
import com.example.vibratext.encoding.EncodedUnit
import com.example.vibratext.encoding.FrameField
import com.example.vibratext.encoding.MessageFramer
import com.example.vibratext.encoding.MessageSection
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.playback.PlaybackRequest
import com.example.vibratext.playback.RequestIds
import com.example.vibratext.playback.RequestMeta
import com.example.vibratext.playback.RequestSource
import com.example.vibratext.playback.VibrationPatternGenerator
import com.example.vibratext.playback.VibrationScript

/** What the test screen shows for a message, computed without touching the vibrator. */
class ManualPlan(
    val request: PlaybackRequest,
    val original: String,
    /** The fields exactly as a receiver will read them (normalised, replacements applied). */
    val normalizedFields: List<String>,
    val units: List<EncodedUnit>,
    /** Decoder notation, e.g. "LSS | SLD …". */
    val symbolText: String,
    val replacedCount: Int,
) {
    val estimatedDurationMs: Long get() = request.script.totalDurationMs
}

/**
 * Builds the playback requests used by the testing / calibration screen and the profile editor.
 * Everything here uses exactly the same encoder, mapping, timing and generator as real
 * notifications; the requests only differ in [RequestSource.TEST] and in ignoring pacing, so a
 * test always starts at once and never waits behind a queued notification.
 */
object ManualPlayback {

    /**
     * A framed message: START, optional sender, SEPARATOR, message, END, completion tick.
     * Returns null if both [sender] and [message] are blank. Throws
     * [com.example.vibratext.encoding.UnsupportedCharacterException] if the profile's policy is ERROR.
     */
    fun forMessage(sender: String?, message: String, profile: EncodingProfile): ManualPlan? {
        val fields = ArrayList<FrameField>()
        if (!sender.isNullOrBlank()) fields += FrameField(MessageSection.SENDER, sender)
        if (message.isNotBlank()) fields += FrameField(MessageSection.MESSAGE, message)
        if (fields.isEmpty()) return null
        val framed = MessageFramer(profile.encoder()).frame(fields)
        val script = generate(framed.units, profile, profile.completionTick)
        val original = listOfNotNull(sender?.takeIf { it.isNotBlank() }, message.takeIf { it.isNotBlank() }).joinToString(" | ")
        return ManualPlan(
            request = request(script, profile, preview = ""),
            original = original,
            normalizedFields = framed.fields,
            units = framed.units,
            symbolText = CharacterDecoder.formatUnits(framed.units, profile.symbolMapping),
            replacedCount = framed.replacedCount,
        )
    }

    /** The three symbols of one character's code, without framing or tick. Null if not in the table. */
    fun forCharacter(c: Char, profile: EncodingProfile): ManualPlan? {
        if (!profile.table().supports(c)) return null
        val framed = MessageFramer(profile.encoder()).plain(c.toString())
        if (framed.units.isEmpty()) return null
        val script = generate(framed.units, profile, completionTick = false)
        return ManualPlan(
            request = request(script, profile, preview = ""),
            original = c.toString(),
            normalizedFields = framed.fields,
            units = framed.units,
            symbolText = CharacterDecoder.formatUnits(framed.units, profile.symbolMapping),
            replacedCount = framed.replacedCount,
        )
    }

    /** One bare symbol (SHORT, LONG, DOUBLE or TRIPLE) with the profile's current timing. */
    fun forSymbol(symbol: VibSymbol, profile: EncodingProfile): PlaybackRequest =
        request(VibrationPatternGenerator.generateSymbol(symbol, profile.effectiveTiming()), profile)

    /** One reserved marker (START, SEPARATOR, END, CASE_SHIFT or TRUNCATED) as it is sent in messages. */
    fun forControl(control: ControlCode, profile: EncodingProfile): PlaybackRequest {
        val unit = profile.encoder().controlUnit(control, MessageSection.FRAME)
        return request(generate(listOf(unit), profile, completionTick = false), profile)
    }

    /** The completion tick alone. */
    fun forTick(profile: EncodingProfile): PlaybackRequest =
        request(VibrationPatternGenerator.generateTick(profile.effectiveTiming()), profile)

    private fun generate(units: List<EncodedUnit>, profile: EncodingProfile, completionTick: Boolean): VibrationScript =
        VibrationPatternGenerator.generate(
            units = units,
            mapping = profile.symbolMapping,
            timing = profile.effectiveTiming(),
            repeat = profile.repeat,
            completionTick = completionTick,
        )

    private fun request(script: VibrationScript, profile: EncodingProfile, preview: String = ""): PlaybackRequest =
        PlaybackRequest(
            id = RequestIds.next(),
            script = script,
            meta = RequestMeta(
                source = RequestSource.TEST,
                applicationName = "Test",
                preview = preview,
                profileId = profile.id,
                profileName = profile.name,
            ),
            intensityPercent = profile.intensityPercent,
            startDelayMs = 0,
            bypassPacing = true,
        )
}
