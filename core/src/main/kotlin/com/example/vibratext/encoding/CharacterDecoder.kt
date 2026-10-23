package com.example.vibratext.encoding

sealed interface DecodeError {
    val message: String
}

data class InvalidSymbol(val char: Char, val position: Int) : DecodeError {
    override val message get() = "Invalid symbol '$char' at position ${position + 1}. Use S (short), L (long), D (double) or T (triple)."
}

data class IncorrectCodeLength(val unitIndex: Int, val length: Int) : DecodeError {
    override val message
        get() = "Character ${unitIndex + 1} has $length symbol(s) but every character needs exactly ${CodeSymbols.CODE_LENGTH}" +
            if (length < CodeSymbols.CODE_LENGTH) " (missing symbols)." else " (too many symbols; separate characters with '|')."
}

data class UnknownCode(val unitIndex: Int, val code: Int) : DecodeError {
    override val message get() = "Character ${unitIndex + 1} (code $code) is not in the encoding table."
}

data class InvalidDelimiter(val unitIndex: Int, override val message: String) : DecodeError

data class IncompleteMessage(override val message: String) : DecodeError

object EmptyInput : DecodeError {
    override val message get() = "Nothing to decode."
}

sealed interface DecodeResult<out T> {
    data class Success<T>(val value: T) : DecodeResult<T>
    data class Failure(val errors: List<DecodeError>) : DecodeResult<Nothing> {
        val summary: String get() = errors.joinToString("\n") { it.message }
    }
}

data class DecodedField(val text: String, val truncated: Boolean)

data class DecodedMessage(val fields: List<DecodedField>, val framed: Boolean) {
    /** Fields joined with " | " (truncated fields get a trailing "…"). */
    val displayText: String
        get() = fields.joinToString(" | ") { it.text + if (it.truncated) "…" else "" }
}

/**
 * Inverse of [CharacterEncoder] + [MessageFramer], using the *same* table and symbol mapping.
 * Never guesses: any problem is reported as a [DecodeError] and no text is produced.
 *
 * Case: if the profile normalises case, capitals were never transmitted and cannot be
 * reconstructed; the decoded text is lower-case.
 */
class CharacterDecoder(val table: EncodingTable, val mapping: SymbolMapping) {

    /**
     * Parses the textual symbol format used by the decoder screen:
     * letters S L D T (any case), whitespace ignored, optional '|' between characters.
     * Without '|' the stream is cut into groups of 3. With '|' every group must have exactly 3.
     */
    fun parseSymbols(input: String): DecodeResult<List<Int>> {
        val errors = ArrayList<DecodeError>()
        val groups = ArrayList<MutableList<VibSymbol>>()
        var current = ArrayList<VibSymbol>()
        var sawPipe = false
        input.forEachIndexed { index, c ->
            when {
                c.isWhitespace() -> Unit
                c == '|' -> {
                    sawPipe = true
                    if (current.isNotEmpty()) groups += current
                    current = ArrayList()
                }
                else -> {
                    val s = VibSymbol.fromLetter(c)
                    if (s == null) errors += InvalidSymbol(c, index) else current.add(s)
                }
            }
        }
        if (current.isNotEmpty()) groups += current
        if (errors.isNotEmpty()) return DecodeResult.Failure(errors)
        if (groups.isEmpty()) return DecodeResult.Failure(listOf(EmptyInput))

        val unitSymbols: List<List<VibSymbol>> = if (sawPipe) {
            groups
        } else {
            val all = groups.flatten()
            val chunks = all.chunked(CodeSymbols.CODE_LENGTH)
            chunks
        }
        unitSymbols.forEachIndexed { i, g ->
            if (g.size != CodeSymbols.CODE_LENGTH) errors += IncorrectCodeLength(i, g.size)
        }
        if (errors.isNotEmpty()) return DecodeResult.Failure(errors)
        return DecodeResult.Success(unitSymbols.map { CodeSymbols.fromSymbols(it, mapping) })
    }

    fun decodeSymbols(input: String): DecodeResult<DecodedMessage> =
        when (val parsed = parseSymbols(input)) {
            is DecodeResult.Failure -> parsed
            is DecodeResult.Success -> decodeCodes(parsed.value)
        }

    /**
     * Decodes codes. If the first code is START the stream is treated as a framed message and
     * must be well-formed (START … END, nothing after END). Otherwise it is plain text and
     * must not contain START / SEPARATOR / END.
     */
    fun decodeCodes(codes: List<Int>): DecodeResult<DecodedMessage> {
        if (codes.isEmpty()) return DecodeResult.Failure(listOf(EmptyInput))
        val framed = codes.first() == ControlCode.START.code
        val errors = ArrayList<DecodeError>()
        val fields = ArrayList<DecodedField>()
        val text = StringBuilder()
        var truncated = false
        var upperNext = false
        var ended = false
        var index = 0

        fun closeField() {
            if (upperNext) {
                errors += InvalidDelimiter(index, "A case marker must be directly followed by a letter.")
                upperNext = false
            }
            fields += DecodedField(text.toString(), truncated)
            text.setLength(0)
            truncated = false
        }

        for ((i, code) in codes.withIndex()) {
            index = i
            if (ended) {
                errors += InvalidDelimiter(i, "Unexpected data after the END marker.")
                break
            }
            val control = ControlCode.fromCode(code)
            if (control != null) {
                when (control) {
                    ControlCode.START -> {
                        if (i != 0) errors += InvalidDelimiter(i, "START marker found in the middle of a message.")
                    }
                    ControlCode.SEPARATOR -> {
                        if (!framed) errors += InvalidDelimiter(i, "Field separator found in unframed text.") else closeField()
                    }
                    ControlCode.END -> {
                        if (!framed) errors += InvalidDelimiter(i, "END marker found in unframed text.") else {
                            closeField()
                            ended = true
                        }
                    }
                    ControlCode.CASE_SHIFT -> {
                        if (upperNext) errors += InvalidDelimiter(i, "Two case markers in a row.")
                        upperNext = true
                    }
                    ControlCode.TRUNCATED -> {
                        if (upperNext) {
                            errors += InvalidDelimiter(i, "A case marker must be directly followed by a letter.")
                            upperNext = false
                        }
                        truncated = true
                    }
                }
                continue
            }
            val c = table.charOf(code)
            if (c == null) {
                errors += UnknownCode(i, code)
                upperNext = false
                continue
            }
            if (truncated) {
                errors += InvalidDelimiter(i, "The truncation marker must be the last item of a field.")
                truncated = false
            }
            if (upperNext) {
                upperNext = false
                if (c.isLetter()) text.append(c.uppercaseChar())
                else errors += InvalidDelimiter(i, "A case marker must be directly followed by a letter.")
            } else {
                text.append(c)
            }
        }

        if (errors.isNotEmpty()) return DecodeResult.Failure(errors)
        if (framed) {
            if (!ended) return DecodeResult.Failure(listOf(IncompleteMessage("The message has no END marker; it may be cut off.")))
        } else {
            index = codes.size
            closeField()
            if (errors.isNotEmpty()) return DecodeResult.Failure(errors)
        }
        return DecodeResult.Success(DecodedMessage(fields, framed))
    }

    companion object {
        /** Formats units in the decoder's input format, e.g. "S S S S S | S S S S L". */
        fun formatSymbols(codes: List<Int>, mapping: SymbolMapping): String =
            codes.joinToString(" | ") { code ->
                CodeSymbols.toSymbols(code, mapping).joinToString(" ") { it.letter.toString() }
            }

        fun formatUnits(units: List<EncodedUnit>, mapping: SymbolMapping): String =
            formatSymbols(units.map { it.code }, mapping)
    }
}
