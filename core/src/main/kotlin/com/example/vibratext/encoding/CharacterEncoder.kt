package com.example.vibratext.encoding

import java.text.Normalizer

/** How upper-case letters are treated. NORMALIZE is lossy: decoding cannot restore capitals. */
enum class CaseMode { NORMALIZE, PRESERVE }

enum class UnsupportedPolicy { ERROR, REPLACE }

data class EncodingOptions(
    val caseMode: CaseMode = CaseMode.NORMALIZE,
    val stripAccents: Boolean = true,
    val unsupportedPolicy: UnsupportedPolicy = UnsupportedPolicy.REPLACE,
    val replacementChar: Char = '?',
)

/** Which part of a notification a unit belongs to (drives playback status display). */
enum class MessageSection { APP, SENDER, MESSAGE, TEXT, FRAME }

/**
 * One transmitted code: either a character or a protocol control code.
 * [text] is what the receiver should read for CHAR units (already case-resolved).
 */
data class EncodedUnit(
    val code: Int,
    val text: String?,
    val control: ControlCode?,
    val section: MessageSection,
) {
    val isControl: Boolean get() = control != null
    val display: String get() = text ?: control?.glyph ?: "?"
}

data class EncodedText(val units: List<EncodedUnit>, val normalized: String, val replacedCount: Int)

class UnsupportedCharacterException(val character: String, val index: Int) :
    IllegalArgumentException("Character '$character' at position ${index + 1} has no vibration code in this profile.")

/**
 * Turns text into [EncodedUnit]s. Deterministic: the same text and options always give the
 * same units.
 *
 * Normalisation steps (in order), all documented in the README:
 *  1. Compatibility-decompose and drop combining marks if [EncodingOptions.stripAccents]
 *     ("Café" → "Cafe", "…" → "...").
 *  2. Fold typographic quotes and dashes to their ASCII equivalents.
 *  3. Collapse every run of whitespace to one space, trim, and drop invisible format and
 *     control characters (zero-width joiners, bidi marks, ...).
 *  4. NORMALIZE: lower-case everything. PRESERVE: keep case (capitals use CASE_SHIFT).
 *  5. Characters still not in the table are replaced (one replacement per code point,
 *     e.g. each emoji becomes '?') or rejected, depending on [EncodingOptions.unsupportedPolicy].
 */
class CharacterEncoder(val table: EncodingTable, val options: EncodingOptions = EncodingOptions()) {
    init {
        if (options.unsupportedPolicy == UnsupportedPolicy.REPLACE) {
            require(table.supports(options.replacementChar)) {
                "Replacement character '${options.replacementChar}' is not in the encoding table."
            }
        }
    }

    /** Steps 1–4. Unsupported characters are still present. */
    fun prepare(text: String): String {
        var s = text
        if (options.stripAccents) {
            s = Normalizer.normalize(s, Normalizer.Form.NFKD)
        }
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        var i = 0
        while (i < s.length) {
            var cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (options.stripAccents && Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            cp = fold(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> pendingSpace = true
                Character.getType(cp) == Character.FORMAT.toInt() || Character.isISOControl(cp) -> Unit
                else -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    pendingSpace = false
                    sb.appendCodePoint(cp)
                }
            }
        }
        val prepared = sb.toString()
        return if (options.caseMode == CaseMode.NORMALIZE) prepared.lowercase() else prepared
    }

    /**
     * The text exactly as it will be received: [prepare] plus replacements (when the policy is
     * REPLACE). `decode(encode(x)).text == normalize(x)` always holds.
     */
    fun normalize(text: String): String {
        val prepared = prepare(text)
        if (options.unsupportedPolicy == UnsupportedPolicy.ERROR) return prepared
        val sb = StringBuilder(prepared.length)
        var i = 0
        while (i < prepared.length) {
            val cp = prepared.codePointAt(i)
            i += Character.charCount(cp)
            if (cp <= 0xFFFF && canEncode(cp.toChar())) sb.append(cp.toChar()) else sb.append(options.replacementChar)
        }
        return sb.toString()
    }

    fun encode(text: String, section: MessageSection = MessageSection.TEXT): EncodedText {
        val prepared = prepare(text)
        val units = ArrayList<EncodedUnit>(prepared.length + 2)
        val normalized = StringBuilder(prepared.length)
        var replaced = 0
        var i = 0
        while (i < prepared.length) {
            val cp = prepared.codePointAt(i)
            val width = Character.charCount(cp)
            val ch: Char? = if (cp <= 0xFFFF) cp.toChar() else null
            if (ch != null && addChar(ch, section, units)) {
                normalized.append(ch)
            } else {
                when (options.unsupportedPolicy) {
                    UnsupportedPolicy.ERROR -> throw UnsupportedCharacterException(String(Character.toChars(cp)), i)
                    UnsupportedPolicy.REPLACE -> {
                        addChar(options.replacementChar, section, units)
                        normalized.append(options.replacementChar)
                        replaced++
                    }
                }
            }
            i += width
        }
        return EncodedText(units, normalized.toString(), replaced)
    }

    fun controlUnit(control: ControlCode, section: MessageSection): EncodedUnit =
        EncodedUnit(control.code, null, control, section)

    private fun canEncode(c: Char): Boolean {
        if (table.supports(c)) return true
        if (options.caseMode == CaseMode.PRESERVE && c.isUpperCase()) {
            val lower = c.lowercaseChar()
            return lower != c && table.supports(lower)
        }
        return false
    }

    private fun addChar(c: Char, section: MessageSection, out: MutableList<EncodedUnit>): Boolean {
        val direct = table.codeOf(c)
        if (direct != null) {
            out += EncodedUnit(direct, c.toString(), null, section)
            return true
        }
        if (options.caseMode == CaseMode.PRESERVE && c.isUpperCase()) {
            val lower = c.lowercaseChar()
            val code = if (lower != c) table.codeOf(lower) else null
            if (code != null) {
                out += controlUnit(ControlCode.CASE_SHIFT, section)
                out += EncodedUnit(code, c.toString(), null, section)
                return true
            }
        }
        return false
    }

    private fun fold(cp: Int): Int = when (cp) {
        0x2018, 0x2019, 0x201A, 0x201B, 0x02BC -> '\''.code
        0x201C, 0x201D, 0x201E -> '"'.code
        0x2013, 0x2014, 0x2212 -> '-'.code
        else -> cp
    }
}
