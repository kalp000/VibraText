package com.example.vibratext.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EncodingTableTest {

    private fun symbolsOf(c: Char, mapping: SymbolMapping = SymbolMapping.DEFAULT): String {
        val code = EncodingTable.DEFAULT.codeOf(c)!!
        return CodeSymbols.toSymbols(code, mapping).joinToString("") { it.letter.toString() }
    }

    @Test
    fun everySupportedCharacterHasAValidEncoding() {
        for (c in EncodingTable.DEFAULT_CHARACTERS) {
            val code = EncodingTable.DEFAULT.codeOf(c)
            assertNotNull("No code for '$c'", code)
            assertTrue(code!! in 0 until CodeSymbols.CODE_SPACE)
            assertEquals(CodeSymbols.CODE_LENGTH, CodeSymbols.toSymbols(code, SymbolMapping.DEFAULT).size)
        }
    }

    @Test
    fun everyCharacterCodeIsUniqueAndNeverReserved() {
        val codes = EncodingTable.DEFAULT_CHARACTERS.map { EncodingTable.DEFAULT.codeOf(it)!! }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.none { it in ControlCode.reservedCodes })
        // symbol patterns are unique too
        val patterns = EncodingTable.DEFAULT_CHARACTERS.map { symbolsOf(it) }
        assertEquals(patterns.size, patterns.toSet().size)
    }

    @Test
    fun defaultTableCoversTheSpecifiedCharacters() {
        val required = "abcdefghijklmnopqrstuvwxyz0123456789 .,?!:;'\"-_()/@"
        for (c in required) assertTrue("'$c' missing", EncodingTable.DEFAULT.supports(c))
        assertEquals(58, EncodingTable.DEFAULT_CHARACTERS.length)
        assertEquals(3, CodeSymbols.CODE_LENGTH)
        assertEquals(64, CodeSymbols.CODE_SPACE)
        // 64 codes minus the 5 reserved framing codes is the most a table can hold
        assertTrue(EncodingTable.DEFAULT_CHARACTERS.length <= CodeSymbols.CODE_SPACE - ControlCode.entries.size)
    }

    @Test
    fun documentedExamplesMatch() {
        // From the specification: a → SSS, b → SSL, c → SSD
        assertEquals("SSS", symbolsOf('a'))
        assertEquals("SSL", symbolsOf('b'))
        assertEquals("SSD", symbolsOf('c'))
        assertEquals("SST", symbolsOf('d'))
        assertEquals("SLS", symbolsOf('e'))
        // '0' is index 26 = base-4 122
        assertEquals("LDD", symbolsOf('0'))
    }

    @Test
    fun framingCodesAreDistinctAndUseTheHighestCodes() {
        fun pattern(c: ControlCode) = CodeSymbols.toSymbols(c.code, SymbolMapping.DEFAULT).joinToString("") { it.letter.toString() }
        assertEquals("TTT", pattern(ControlCode.START))
        assertEquals("TTD", pattern(ControlCode.SEPARATOR))
        assertEquals("TTL", pattern(ControlCode.END))
        assertEquals("TTS", pattern(ControlCode.CASE_SHIFT))
        assertEquals("TDT", pattern(ControlCode.TRUNCATED))
        assertTrue(ControlCode.reservedCodes.all { it in 59..63 })
        assertEquals(ControlCode.entries.size, ControlCode.reservedCodes.size)
    }

    @Test
    fun customTableRejectsDuplicateCodes() {
        val bad = EncodingTable.defaultCharCodes().toMutableMap()
        bad['b'] = bad['a']!!
        val errors = EncodingTable.validate(bad)
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.joinToString().contains("unique"))
        try {
            EncodingTable.create(bad)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun customTableRejectsReservedOutOfRangeMissingAndUppercase() {
        val base = EncodingTable.defaultCharCodes()
        assertTrue(EncodingTable.validate(base + ('a' to ControlCode.START.code)).any { it.contains("reserved") })
        assertTrue(EncodingTable.validate(base + ('a' to 64)).any { it.contains("between 0 and 63") })
        assertTrue(EncodingTable.validate(base + ('a' to -1)).any { it.contains("between 0 and 63") })
        assertTrue(EncodingTable.validate(base - 'e').any { it.contains("missing") })
        assertTrue(EncodingTable.validate(base + ('Q' to 40)).any { it.contains("upper-case") })
        assertTrue(EncodingTable.validate(base + ('\n' to 40)).isNotEmpty())
    }

    @Test
    fun customTableAllowsValidReassignment() {
        val swapped = EncodingTable.defaultCharCodes().toMutableMap()
        swapped['a'] = 25
        swapped['z'] = 0
        assertTrue(EncodingTable.validate(swapped).isEmpty())
        val t = EncodingTable.create(swapped)
        assertEquals('z', t.charOf(0))
        assertEquals('a', t.charOf(25))
    }

    @Test
    fun symbolMappingMustBeOneToOne() {
        assertNotNull(SymbolMapping.validationError(listOf(VibSymbol.SHORT, VibSymbol.SHORT, VibSymbol.DOUBLE, VibSymbol.TRIPLE)))
        assertNotNull(SymbolMapping.validationError(listOf(VibSymbol.SHORT, VibSymbol.LONG)))
        assertEquals(null, SymbolMapping.validationError(listOf(VibSymbol.TRIPLE, VibSymbol.DOUBLE, VibSymbol.LONG, VibSymbol.SHORT)))
        try {
            SymbolMapping(listOf(VibSymbol.LONG, VibSymbol.LONG, VibSymbol.DOUBLE, VibSymbol.TRIPLE))
            fail("expected exception")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun codeSymbolConversionRoundTripsForEveryCodeAndMapping() {
        val mappings = listOf(
            SymbolMapping.DEFAULT,
            SymbolMapping(listOf(VibSymbol.TRIPLE, VibSymbol.DOUBLE, VibSymbol.LONG, VibSymbol.SHORT)),
            SymbolMapping(listOf(VibSymbol.LONG, VibSymbol.SHORT, VibSymbol.TRIPLE, VibSymbol.DOUBLE)),
        )
        for (m in mappings) {
            for (code in 0 until CodeSymbols.CODE_SPACE) {
                assertEquals(code, CodeSymbols.fromSymbols(CodeSymbols.toSymbols(code, m), m))
            }
        }
        assertFalse(CodeSymbols.toSymbols(1, mappings[0]) == CodeSymbols.toSymbols(1, mappings[1]))
    }
}
