package com.example.vibratext.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {
    private fun assertFails(text: String) {
        try {
            MiniJson.parse(text)
            fail("expected JsonException for: $text")
        } catch (_: JsonException) {
        }
    }

    @Test
    fun parsesAllValueTypes() {
        val v = MiniJson.parse("""{"a":1,"b":-2.5,"c":"x\n\u0041","d":[true,false,null],"e":{"f":1e2}}""") as Map<*, *>
        assertEquals(1L, v["a"])
        assertEquals(-2.5, v["b"])
        assertEquals("x\nA", v["c"])
        assertEquals(listOf(true, false, null), v["d"])
        assertEquals(100.0, (v["e"] as Map<*, *>)["f"])
    }

    @Test
    fun stringifyThenParseIsIdentity() {
        val data = linkedMapOf<String, Any?>(
            "s" to "quote\" back\\slash \t tab é 😀",
            "n" to 12L,
            "d" to 0.25,
            "b" to true,
            "z" to null,
            "l" to listOf(1L, "two", linkedMapOf("k" to listOf<Any?>())),
        )
        assertEquals(data, MiniJson.parse(MiniJson.stringify(data)))
        assertEquals(data, MiniJson.parse(MiniJson.stringify(data, pretty = true)))
    }

    @Test
    fun rejectsMalformedInput() {
        assertFails("")
        assertFails("{")
        assertFails("""{"a":1,}""")
        assertFails("""{"a" 1}""")
        assertFails("[1,2")
        assertFails("""{"a":1} extra""")
        assertFails("""{'a':1}""")
        assertFails("01")
        assertFails("1.2.3")
        assertFails("\"unterminated")
        assertFails("\"bad \\q escape\"")
        assertFails("\"\\u12\"")
        assertFails("\"raw\ncontrol\"")
        assertFails("nul")
    }

    @Test
    fun rejectsExcessiveNestingAndSize() {
        assertFails("[".repeat(100) + "]".repeat(100))
        assertFails("\"" + "a".repeat(MiniJson.MAX_INPUT_CHARS) + "\"")
    }

    @Test
    fun stringifyRejectsUnsupportedValues() {
        try {
            MiniJson.stringify(Any())
            fail()
        } catch (_: JsonException) {
        }
        try {
            MiniJson.stringify(Double.NaN)
            fail()
        } catch (_: JsonException) {
        }
    }

    @Test
    fun objAccessorGivesTypedErrorsWithPaths() {
        val o = JsonObj.of(MiniJson.parse("""{"n":5,"s":"x","b":true,"f":2.5,"inner":{"k":1}}"""))
        assertEquals(5, o.int("n"))
        assertEquals("x", o.string("s"))
        assertTrue(o.bool("b"))
        assertEquals(2.5, o.double("f"), 0.0)
        assertEquals(1, o.obj("inner")!!.int("k"))
        assertEquals(7, o.int("missing", 7))
        try {
            o.string("n")
            fail()
        } catch (e: JsonException) {
            assertTrue(e.message!!.contains("\$.n"))
        }
        try {
            o.int("f")
            fail()
        } catch (_: JsonException) {
        }
        try {
            o.int("missing")
            fail()
        } catch (_: JsonException) {
        }
    }
}
