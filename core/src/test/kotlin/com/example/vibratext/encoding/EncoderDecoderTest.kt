package com.example.vibratext.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class EncoderDecoderTest {

    private fun encoder(
        caseMode: CaseMode = CaseMode.NORMALIZE,
        policy: UnsupportedPolicy = UnsupportedPolicy.REPLACE,
        strip: Boolean = true,
        table: EncodingTable = EncodingTable.DEFAULT,
    ) = CharacterEncoder(table, EncodingOptions(caseMode, strip, policy, '?'))

    private fun roundTrip(enc: CharacterEncoder, text: String, mapping: SymbolMapping = SymbolMapping.DEFAULT): String {
        val encoded = enc.encode(text)
        val decoder = CharacterDecoder(enc.table, mapping)
        val result = decoder.decodeCodes(encoded.units.map { it.code })
        assertTrue("decode failed for '$text': $result", result is DecodeResult.Success)
        return (result as DecodeResult.Success).value.fields.single().text
    }

    @Test
    fun encodingIsDeterministic() {
        val e = encoder()
        val a = e.encode("Hello, are you free?")
        val b = e.encode("Hello, are you free?")
        assertEquals(a, b)
        assertEquals(a.units.map { it.code }, b.units.map { it.code })
    }

    @Test
    fun decodingReversesEncodingForSimpleText() {
        val e = encoder()
        assertEquals("hello, are you free?", roundTrip(e, "hello, are you free?"))
    }

    @Test
    fun spacesPunctuationAndDigitsArePreserved() {
        val e = encoder()
        val text = "a b  c".replace("  ", " ")
        assertEquals(text, roundTrip(e, "a b  c"))
        for (p in ".,?!:;'\"-_()/@#&+=%*\$") assertEquals(p.toString(), roundTrip(e, p.toString()))
        assertEquals("0123456789", roundTrip(e, "0123456789"))
        assertEquals("call me at 7:30 pm (maybe?)", roundTrip(e, "call me at 7:30 pm (maybe?)"))
    }

    @Test
    fun roundTripHoldsForRandomTextInBothCaseModes() {
        val rnd = Random(42)
        val alphabet = EncodingTable.DEFAULT_CHARACTERS + "ABCXYZÉÀÜ  \t\n’“”–…😀ß"
        repeat(300) {
            val text = buildString { repeat(rnd.nextInt(0, 60)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            for (mode in CaseMode.entries) {
                val e = encoder(caseMode = mode)
                val expected = e.normalize(text)
                if (expected.isEmpty()) {
                    // nothing to transmit: zero codes, and the decoder refuses to invent text
                    assertTrue(e.encode(text).units.isEmpty())
                } else {
                    assertEquals("text=<$text> mode=$mode", expected, roundTrip(e, text))
                }
            }
        }
    }

    @Test
    fun caseIsNormalizedByDefaultAndCannotBeRestored() {
        val e = encoder()
        assertEquals("hello world", e.normalize("Hello WORLD"))
        assertEquals("hello world", roundTrip(e, "Hello WORLD"))
    }

    @Test
    fun caseIsPreservedWithAnExplicitCaseMarker() {
        val e = encoder(caseMode = CaseMode.PRESERVE)
        val encoded = e.encode("Hi")
        assertEquals(
            listOf(ControlCode.CASE_SHIFT.code, EncodingTable.DEFAULT.codeOf('h')!!, EncodingTable.DEFAULT.codeOf('i')!!),
            encoded.units.map { it.code },
        )
        assertEquals("Hello World", roundTrip(e, "Hello World"))
        assertEquals("ABC xyz", roundTrip(e, "ABC xyz"))
    }

    @Test
    fun unsupportedCharactersAreRejectedWhenPolicyIsError() {
        val e = encoder(policy = UnsupportedPolicy.ERROR)
        try {
            e.encode("hi 😀")
            fail("expected UnsupportedCharacterException")
        } catch (ex: UnsupportedCharacterException) {
            assertEquals("😀", ex.character)
            assertTrue(ex.message!!.contains("no vibration code"))
        }
    }

    @Test
    fun unsupportedCharactersUseTheConfiguredReplacement() {
        val e = encoder()
        val r = e.encode("hi 😀😀 ok")
        assertEquals("hi ?? ok", r.normalized)
        assertEquals(2, r.replacedCount)
        val custom = CharacterEncoder(EncodingTable.DEFAULT, EncodingOptions(unsupportedPolicy = UnsupportedPolicy.REPLACE, replacementChar = '*'))
        assertEquals("a*b", custom.encode("a😀b").normalized)
    }

    @Test
    fun replacementCharacterMustExistInTheTable() {
        try {
            CharacterEncoder(EncodingTable.DEFAULT, EncodingOptions(replacementChar = '~'))
            fail("expected exception")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun accentsQuotesDashesAndWhitespaceAreNormalized() {
        val e = encoder()
        assertEquals("cafe", e.normalize("Café"))
        assertEquals("don't \"quote\" a-b...", e.normalize("don’t “quote” a–b…"))
        assertEquals("a b c", e.normalize("  a \t\n  b c  "))
        assertEquals("ab", e.normalize("a​b")) // zero-width space dropped
        assertEquals("", e.normalize("   "))
    }

    @Test
    fun accentStrippingCanBeDisabled() {
        val e = encoder(strip = false)
        assertEquals("caf?", e.normalize("Café"))
    }

    @Test
    fun changingSymbolMappingChangesEncodingAndDecodingConsistently() {
        val e = encoder()
        val reversed = SymbolMapping(listOf(VibSymbol.TRIPLE, VibSymbol.DOUBLE, VibSymbol.LONG, VibSymbol.SHORT))
        val codes = e.encode("hello 42").units.map { it.code }

        val defaultText = CharacterDecoder.formatSymbols(codes, SymbolMapping.DEFAULT)
        val reversedText = CharacterDecoder.formatSymbols(codes, reversed)
        assertTrue(defaultText != reversedText)

        val withDefault = CharacterDecoder(EncodingTable.DEFAULT, SymbolMapping.DEFAULT)
        val withReversed = CharacterDecoder(EncodingTable.DEFAULT, reversed)
        // each decoder reads its own symbol string correctly
        assertEquals("hello 42", ((withDefault.decodeSymbols(defaultText) as DecodeResult.Success).value.fields.single().text))
        assertEquals("hello 42", ((withReversed.decodeSymbols(reversedText) as DecodeResult.Success).value.fields.single().text))
        // reading symbols with the wrong mapping does not silently give the same text
        val wrong = withDefault.decodeSymbols(reversedText)
        assertTrue(wrong is DecodeResult.Failure || (wrong as DecodeResult.Success).value.fields.single().text != "hello 42")
        // and a full round trip with the custom mapping
        assertEquals("hello 42", roundTrip(e, "hello 42", reversed))
    }

    @Test
    fun customCharacterCodesAreUsedByBothEncoderAndDecoder() {
        val codes = EncodingTable.defaultCharCodes().toMutableMap()
        codes['a'] = 58
        codes['b'] = 0
        val table = EncodingTable.create(codes)
        val e = encoder(table = table)
        assertEquals(listOf(58, 0), e.encode("ab").units.map { it.code })
        assertEquals("ab", roundTrip(e, "ab"))
    }

    // ---- decoder error handling -------------------------------------------------------------

    private val decoder = CharacterDecoder(EncodingTable.DEFAULT, SymbolMapping.DEFAULT)

    private fun errorsOf(r: DecodeResult<DecodedMessage>): List<DecodeError> {
        assertTrue("expected failure but got $r", r is DecodeResult.Failure)
        return (r as DecodeResult.Failure).errors
    }

    @Test
    fun symbolParsingAcceptsBothFormats() {
        val withPipes = decoder.parseSymbols("S S S | S S L")
        val without = decoder.parseSymbols("SSSSSL")
        val lowercase = decoder.parseSymbols("sss ssl")
        assertEquals(DecodeResult.Success(listOf(0, 1)), withPipes)
        assertEquals(DecodeResult.Success(listOf(0, 1)), without)
        assertEquals(DecodeResult.Success(listOf(0, 1)), lowercase)
    }

    @Test
    fun invalidSymbolsAreReported() {
        val e = errorsOf(decoder.decodeSymbols("S S X S S"))
        assertTrue(e.single() is InvalidSymbol)
        assertEquals('X', (e.single() as InvalidSymbol).char)
    }

    @Test
    fun incorrectCodeLengthIsReportedWithAndWithoutPipes() {
        val missing = errorsOf(decoder.decodeSymbols("S S"))
        assertTrue(missing.single() is IncorrectCodeLength)
        assertEquals(2, (missing.single() as IncorrectCodeLength).length)

        val short = errorsOf(decoder.decodeSymbols("S S S | S S"))
        assertEquals(1, (short.single() as IncorrectCodeLength).unitIndex)

        val long = errorsOf(decoder.decodeSymbols("S S S S | S S S"))
        assertEquals(4, (long.single() as IncorrectCodeLength).length)
        assertTrue(long.single().message.contains("too many"))
    }

    @Test
    fun emptyInputIsReported() {
        assertTrue(errorsOf(decoder.decodeSymbols("   ")).single() === EmptyInput)
        assertTrue(errorsOf(decoder.decodeCodes(emptyList())).single() === EmptyInput)
    }

    @Test
    fun unknownCodesAreReportedNotGuessed() {
        // code 58 is not assigned to any character (and is not a framing code)
        val e = errorsOf(decoder.decodeCodes(listOf(0, 58)))
        assertTrue(e.single() is UnknownCode)
        assertEquals(58, (e.single() as UnknownCode).code)
    }

    @Test
    fun framedMessagesMustBeComplete() {
        val s = ControlCode.START.code
        val a = 0
        assertTrue(errorsOf(decoder.decodeCodes(listOf(s, a))).single() is IncompleteMessage)
        assertTrue(errorsOf(decoder.decodeCodes(listOf(s, a, ControlCode.END.code, a))).any { it is InvalidDelimiter })
        assertTrue(errorsOf(decoder.decodeCodes(listOf(s, a, s, ControlCode.END.code))).any { it is InvalidDelimiter })
    }

    @Test
    fun delimitersInPlainTextAreRejected() {
        assertTrue(errorsOf(decoder.decodeCodes(listOf(0, ControlCode.SEPARATOR.code, 1))).any { it is InvalidDelimiter })
        assertTrue(errorsOf(decoder.decodeCodes(listOf(0, ControlCode.END.code))).any { it is InvalidDelimiter })
    }

    @Test
    fun misplacedCaseAndTruncationMarkersAreRejected() {
        val c = ControlCode.CASE_SHIFT.code
        val digit0 = EncodingTable.DEFAULT.codeOf('0')!!
        assertTrue(errorsOf(decoder.decodeCodes(listOf(c, digit0))).any { it is InvalidDelimiter })
        assertTrue(errorsOf(decoder.decodeCodes(listOf(0, c))).any { it is InvalidDelimiter })
        assertTrue(errorsOf(decoder.decodeCodes(listOf(c, c, 0))).any { it is InvalidDelimiter })
        assertTrue(errorsOf(decoder.decodeCodes(listOf(ControlCode.TRUNCATED.code, 0))).any { it is InvalidDelimiter })
    }

    @Test
    fun formattedSymbolsRoundTripThroughTheParser() {
        val e = encoder()
        val units = e.encode("round trip 123").units
        val text = CharacterDecoder.formatUnits(units, SymbolMapping.DEFAULT)
        val parsed = decoder.parseSymbols(text) as DecodeResult.Success
        assertEquals(units.map { it.code }, parsed.value)
    }
}
