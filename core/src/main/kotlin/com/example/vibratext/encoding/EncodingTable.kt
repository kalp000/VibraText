package com.example.vibratext.encoding

/**
 * Reserved protocol codes. These live in the same 0..63 code space as characters but can
 * never be assigned to a character, so a decoder can always tell framing from content.
 *
 * They occupy the five highest codes. With the default symbol mapping (0=S, 1=L, 2=D, 3=T):
 *
 *   START      = 63 = TRIPLE TRIPLE TRIPLE
 *   SEPARATOR  = 62 = TRIPLE TRIPLE DOUBLE
 *   END        = 61 = TRIPLE TRIPLE LONG
 *   CASE_SHIFT = 60 = TRIPLE TRIPLE SHORT   (next letter is upper-case)
 *   TRUNCATED  = 59 = TRIPLE DOUBLE TRIPLE  (this field was shortened)
 *
 * The default table (a–z, 0–9, space, punctuation) uses codes 0..57, so the framing markers
 * are always the most "heavy" patterns and feel different from ordinary letters.
 */
enum class ControlCode(val code: Int, val displayName: String, val glyph: String) {
    START(63, "START", "⟨START⟩"),
    SEPARATOR(62, "SEPARATOR", "⟨SEP⟩"),
    END(61, "END", "⟨END⟩"),
    CASE_SHIFT(60, "CASE_SHIFT", "⇧"),
    TRUNCATED(59, "TRUNCATED", "…");

    companion object {
        private val byCode = entries.associateBy { it.code }
        val reservedCodes: Set<Int> = byCode.keys
        fun fromCode(code: Int): ControlCode? = byCode[code]
    }
}

/**
 * Character ↔ code table. Immutable. Build with [create] (validates) or use [DEFAULT].
 *
 * Default table: a–z = 0..25, 0–9 = 26..35, then space and punctuation (see [DEFAULT_CHARACTERS]).
 * Upper-case letters are not table entries: they are written as CASE_SHIFT + the lower-case
 * letter when a profile preserves case.
 */
class EncodingTable private constructor(val charToCode: Map<Char, Int>) {
    private val codeToChar: Map<Int, Char> = charToCode.entries.associate { (c, code) -> code to c }

    fun codeOf(c: Char): Int? = charToCode[c]

    fun charOf(code: Int): Char? = codeToChar[code]

    fun supports(c: Char): Boolean = charToCode.containsKey(c)

    companion object {
        /** Order defines the default code: index in this string == code. 58 characters (codes 0..57). */
        const val DEFAULT_CHARACTERS =
            "abcdefghijklmnopqrstuvwxyz0123456789 .,?!:;'\"-_()/@#&+=%*\$"

        private const val REQUIRED = "abcdefghijklmnopqrstuvwxyz0123456789 "

        fun defaultCharCodes(): Map<Char, Int> {
            val m = LinkedHashMap<Char, Int>()
            DEFAULT_CHARACTERS.forEachIndexed { i, c -> m[c] = i }
            return m
        }

        /** Returns human-readable problems; empty list means the table is valid. */
        fun validate(charCodes: Map<Char, Int>): List<String> {
            val errors = mutableListOf<String>()
            for (r in REQUIRED) {
                if (!charCodes.containsKey(r)) errors += "Required character '${display(r)}' is missing from the table."
            }
            val owner = HashMap<Int, Char>()
            for ((c, code) in charCodes) {
                if (Character.isISOControl(c) || Character.isSurrogate(c) || (c.isWhitespace() && c != ' ')) {
                    errors += "Character U+${c.code.toString(16).uppercase().padStart(4, '0')} cannot be used in the table."
                    continue
                }
                if (c.isUpperCase()) {
                    errors += "'$c' is upper-case. Upper-case letters are written with the case marker, so table entries must be lower-case."
                    continue
                }
                if (code !in 0 until CodeSymbols.CODE_SPACE) {
                    errors += "Code for '${display(c)}' must be between 0 and ${CodeSymbols.CODE_SPACE - 1} (was $code)."
                    continue
                }
                val control = ControlCode.fromCode(code)
                if (control != null) {
                    errors += "Code $code for '${display(c)}' is reserved for the ${control.displayName} framing marker."
                    continue
                }
                val other = owner[code]
                if (other != null) {
                    errors += "Code $code is used by both '${display(other)}' and '${display(c)}'. Every character needs a unique code."
                } else {
                    owner[code] = c
                }
            }
            return errors
        }

        fun create(charCodes: Map<Char, Int>): EncodingTable {
            val errors = validate(charCodes)
            require(errors.isEmpty()) { errors.joinToString(" ") }
            return EncodingTable(LinkedHashMap(charCodes))
        }

        val DEFAULT: EncodingTable = create(defaultCharCodes())

        fun display(c: Char): String = if (c == ' ') "space" else c.toString()
    }
}
