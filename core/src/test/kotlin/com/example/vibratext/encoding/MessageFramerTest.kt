package com.example.vibratext.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MessageFramerTest {
    private val encoder = CharacterEncoder(EncodingTable.DEFAULT)
    private val framer = MessageFramer(encoder)
    private val decoder = CharacterDecoder(EncodingTable.DEFAULT, SymbolMapping.DEFAULT)

    private fun decode(m: FramedMessage): DecodedMessage {
        val r = decoder.decodeCodes(m.units.map { it.code })
        assertTrue("decode failed: $r", r is DecodeResult.Success)
        return (r as DecodeResult.Success).value
    }

    @Test
    fun senderAndMessageUseExplicitStartSeparatorAndEndCodes() {
        val m = framer.frame(
            listOf(
                FrameField(MessageSection.SENDER, "Alex"),
                FrameField(MessageSection.MESSAGE, "Hello."),
            ),
        )
        val codes = m.units.map { it.code }
        assertEquals(ControlCode.START.code, codes.first())
        assertEquals(ControlCode.END.code, codes.last())
        assertEquals(1, codes.count { it == ControlCode.SEPARATOR.code })
        assertEquals(1 + 4 + 1 + 6 + 1, codes.size)
        assertEquals(listOf("alex", "hello."), m.fields)

        val decoded = decode(m)
        assertTrue(decoded.framed)
        assertEquals(listOf("alex", "hello."), decoded.fields.map { it.text })
    }

    @Test
    fun sectionsAreTrackedPerUnit() {
        val m = framer.frame(listOf(FrameField(MessageSection.SENDER, "al"), FrameField(MessageSection.MESSAGE, "hi")))
        assertEquals(
            listOf(MessageSection.FRAME, MessageSection.SENDER, MessageSection.SENDER, MessageSection.FRAME, MessageSection.MESSAGE, MessageSection.MESSAGE, MessageSection.FRAME),
            m.units.map { it.section },
        )
    }

    @Test
    fun emptyFieldsAreUnambiguous() {
        val m = framer.frame(listOf(FrameField(MessageSection.SENDER, ""), FrameField(MessageSection.MESSAGE, "hi")))
        assertEquals(listOf("", "hi"), decode(m).fields.map { it.text })
        val single = framer.frame(listOf(FrameField(MessageSection.TEXT, "")))
        assertEquals(listOf(""), decode(single).fields.map { it.text })
        val three = framer.frame(listOf(FrameField(MessageSection.APP, "wa"), FrameField(MessageSection.SENDER, ""), FrameField(MessageSection.MESSAGE, "x")))
        assertEquals(listOf("wa", "", "x"), decode(three).fields.map { it.text })
    }

    @Test
    fun truncationMarkerSurvivesTheRoundTripAndAttachesToItsField() {
        val m = framer.frame(listOf(FrameField(MessageSection.SENDER, "sam"), FrameField(MessageSection.MESSAGE, "this is cut", truncated = true)))
        val d = decode(m)
        assertEquals(listOf(false, true), d.fields.map { it.truncated })
        assertTrue(d.displayText.endsWith("…"))
        assertEquals(ControlCode.TRUNCATED.code, m.units[m.units.size - 2].code)
    }

    @Test
    fun framingRoundTripsRandomFieldLists() {
        val rnd = Random(7)
        val alphabet = EncodingTable.DEFAULT_CHARACTERS + "ABC 😀é"
        repeat(300) {
            val n = rnd.nextInt(1, 5)
            val fields = (0 until n).map {
                val t = buildString { repeat(rnd.nextInt(0, 12)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
                FrameField(MessageSection.TEXT, t, truncated = rnd.nextBoolean())
            }
            val m = framer.frame(fields)
            val d = decode(m)
            assertEquals(m.fields, d.fields.map { it.text })
            assertEquals(fields.map { it.truncated }, d.fields.map { it.truncated })
        }
    }

    @Test
    fun noCharacterCodeCanBeConfusedWithAFramingCode() {
        // Every possible character in the table has a code that is not reserved, so a content
        // unit can never decode as START / SEPARATOR / END.
        for (c in EncodingTable.DEFAULT_CHARACTERS) {
            val code = EncodingTable.DEFAULT.codeOf(c)!!
            assertFalse(code in ControlCode.reservedCodes)
        }
        // text containing the characters that *look* like markers is still just content
        val m = framer.frame(listOf(FrameField(MessageSection.TEXT, "start | end <end> ⟨END⟩")))
        assertEquals(1, m.units.count { it.control == ControlCode.START })
        assertEquals(1, m.units.count { it.control == ControlCode.END })
    }

    @Test
    fun plainTextIsNotFramed() {
        val m = framer.plain("hello")
        assertTrue(m.units.none { it.isControl })
        val d = decode(m)
        assertFalse(d.framed)
        assertEquals("hello", d.fields.single().text)
    }

    @Test
    fun frameRequiresAtLeastOneField() {
        try {
            framer.frame(emptyList())
            org.junit.Assert.fail("expected exception")
        } catch (_: IllegalArgumentException) {
        }
    }
}
