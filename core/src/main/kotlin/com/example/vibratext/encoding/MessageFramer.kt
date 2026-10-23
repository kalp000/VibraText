package com.example.vibratext.encoding

/** One field of a message (app name, sender, message body or free text). */
data class FrameField(
    val section: MessageSection,
    val text: String,
    /** The field was shortened or omitted; a TRUNCATED marker is appended to it. */
    val truncated: Boolean = false,
)

data class FramedMessage(
    val units: List<EncodedUnit>,
    /** Fields exactly as the receiver will read them (normalised, replacements applied). */
    val fields: List<String>,
    val replacedCount: Int,
)

/**
 * Wire format (documented in the README):
 *
 *   START  field1  [TRUNCATED]  (SEPARATOR  fieldN  [TRUNCATED])*  END   then the completion tick
 *
 * START, SEPARATOR, END, TRUNCATED and CASE_SHIFT are reserved codes that no character can use,
 * so field boundaries are never inferred from timing gaps alone. A field may be empty
 * (`START SEPARATOR message END` is an empty first field followed by a message).
 */
class MessageFramer(private val encoder: CharacterEncoder) {

    fun frame(fields: List<FrameField>): FramedMessage {
        require(fields.isNotEmpty()) { "A message needs at least one field" }
        val units = ArrayList<EncodedUnit>()
        val texts = ArrayList<String>(fields.size)
        var replaced = 0
        units += encoder.controlUnit(ControlCode.START, MessageSection.FRAME)
        fields.forEachIndexed { index, field ->
            if (index > 0) units += encoder.controlUnit(ControlCode.SEPARATOR, MessageSection.FRAME)
            val encoded = encoder.encode(field.text, field.section)
            units += encoded.units
            texts += encoded.normalized
            replaced += encoded.replacedCount
            if (field.truncated) units += encoder.controlUnit(ControlCode.TRUNCATED, field.section)
        }
        units += encoder.controlUnit(ControlCode.END, MessageSection.FRAME)
        return FramedMessage(units, texts, replaced)
    }

    /** A single unframed run of text (used by the manual test screen). */
    fun plain(text: String): FramedMessage {
        val encoded = encoder.encode(text, MessageSection.TEXT)
        return FramedMessage(encoded.units, listOf(encoded.normalized), encoded.replacedCount)
    }
}
