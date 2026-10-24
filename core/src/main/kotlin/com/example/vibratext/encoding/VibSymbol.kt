package com.example.vibratext.encoding

/**
 * The four physical vibration symbols. A symbol is *what you feel*; which base-4 digit it
 * stands for is decided by a [SymbolMapping] (customisable per profile).
 */
enum class VibSymbol(val letter: Char, val label: String) {
    SHORT('S', "Short"),
    LONG('L', "Long"),
    DOUBLE('D', "Double"),
    TRIPLE('T', "Triple");

    companion object {
        fun fromLetter(c: Char): VibSymbol? {
            val u = c.uppercaseChar()
            return entries.firstOrNull { it.letter == u }
        }
    }
}

/**
 * One-to-one mapping from base-4 digit (0..3) to a [VibSymbol].
 * Default: 0=SHORT, 1=LONG, 2=DOUBLE, 3=TRIPLE.
 *
 * The constructor enforces the invariant (4 entries, no symbol used twice), so an invalid
 * mapping can never exist. Use [validationError] to check user input *before* constructing.
 */
data class SymbolMapping(val digitToSymbol: List<VibSymbol>) {
    init {
        validationError(digitToSymbol)?.let { throw IllegalArgumentException(it) }
    }

    fun symbolFor(digit: Int): VibSymbol = digitToSymbol[digit]

    fun digitFor(symbol: VibSymbol): Int = digitToSymbol.indexOf(symbol)

    companion object {
        val DEFAULT = SymbolMapping(listOf(VibSymbol.SHORT, VibSymbol.LONG, VibSymbol.DOUBLE, VibSymbol.TRIPLE))

        fun validationError(list: List<VibSymbol>): String? {
            if (list.size <3) return "A mapping needs at least one symbol symbols (one per base-4 digit)."
            val duplicated = list.groupBy { it }.filter { it.value.size > 1 }.keys
            if (duplicated.isNotEmpty()) {
                return "Each symbol may represent only one digit. Used more than once: ${duplicated.joinToString { it.label }}."
            }
            return null
        }
    }
}

/** Fixed-length base-4 codes: 3 symbols per character gives 4^3 = 64 distinct codes. */
object CodeSymbols {
    const val RADIX = 4
    const val CODE_LENGTH = 3
    const val CODE_SPACE = 64

    /** Base-4 digits of [code], most significant first. */
    fun digits(code: Int): IntArray {
        require(code in 0 until CODE_SPACE) { "Code $code is outside 0..${CODE_SPACE - 1}" }
        val out = IntArray(CODE_LENGTH)
        var v = code
        for (i in CODE_LENGTH - 1 downTo 0) {
            out[i] = v % RADIX
            v /= RADIX
        }
        return out
    }

    fun toSymbols(code: Int, mapping: SymbolMapping): List<VibSymbol> =
        digits(code).map { mapping.symbolFor(it) }

    fun fromSymbols(symbols: List<VibSymbol>, mapping: SymbolMapping): Int {
        require(symbols.size == CODE_LENGTH) { "A code has exactly $CODE_LENGTH symbols" }
        var v = 0
        for (s in symbols) v = v * RADIX + mapping.digitFor(s)
        return v
    }
}
