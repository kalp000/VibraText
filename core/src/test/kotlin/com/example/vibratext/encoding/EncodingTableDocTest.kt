package com.example.vibratext.encoding

import com.example.vibratext.playback.TimingSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * docs/ENCODING_TABLE.md is generated from the real table, so the documentation can never drift
 * from the code. Run the tests with UPDATE_DOCS=1 to regenerate it; otherwise the committed file
 * must match.
 */
class EncodingTableDocTest {

    private fun letters(code: Int) = CodeSymbols.toSymbols(code, SymbolMapping.DEFAULT).joinToString(" ") { it.letter.toString() }

    private fun names(code: Int) = CodeSymbols.toSymbols(code, SymbolMapping.DEFAULT).joinToString(", ") { it.label }

    fun render(): String = buildString {
        val t = TimingSettings.DEFAULT
        appendLine("# VibraText default encoding table")
        appendLine()
        appendLine("_Generated from the code by `EncodingTableDocTest`. Do not edit by hand._")
        appendLine()
        appendLine("## How a character becomes vibration")
        appendLine()
        appendLine("Every character is one **code** (0–63). The code is written as **3 base-4 digits**, and each digit is")
        appendLine("one of four vibration symbols. With the default symbol mapping:")
        appendLine()
        appendLine("| Digit | Symbol | Letter in the decoder | Feel |")
        appendLine("|---|---|---|---|")
        appendLine("| 0 | SHORT | S | one ${t.shortPulseMs} ms pulse |")
        appendLine("| 1 | LONG | L | one ${t.longPulseMs} ms pulse |")
        appendLine("| 2 | DOUBLE | D | two ${t.doublePulseMs} ms pulses, ${t.pulseGapMs} ms apart |")
        appendLine("| 3 | TRIPLE | T | three ${t.triplePulseMs} ms pulses, ${t.pulseGapMs} ms apart |")
        appendLine()
        appendLine("The mapping can be changed per profile (it must stay one-to-one). Gaps: ${t.symbolGapMs} ms between symbols,")
        appendLine("${t.charGapMs} ms between characters, ${t.wordGapMs} ms after a space.")
        appendLine()
        appendLine("## Characters")
        appendLine()
        appendLine("| Character | Code | Symbols (default mapping) | Names |")
        appendLine("|---|---|---|---|")
        for (c in EncodingTable.DEFAULT_CHARACTERS) {
            val code = EncodingTable.DEFAULT.codeOf(c)!!
            val shown = when (c) {
                ' ' -> "space"
                '|' -> "\\|"
                else -> "`$c`"
            }
            appendLine("| $shown | $code | `${letters(code)}` | ${names(code)} |")
        }
        appendLine()
        appendLine("Upper-case letters: by default case is **normalised** (everything is sent as lower case and capitals")
        appendLine("cannot be restored when decoding). A profile may instead **preserve** case: a capital is sent as the")
        appendLine("CASE_SHIFT code followed by the lower-case letter.")
        appendLine()
        appendLine("Accents are removed (é → e), typographic quotes and dashes become ASCII, whitespace runs become one")
        appendLine("space, and any other character becomes `?` (or the message is skipped, if the profile says so).")
        appendLine()
        appendLine("## Reserved framing codes")
        appendLine()
        appendLine("These can never be assigned to a character, so framing can never be confused with content.")
        appendLine()
        appendLine("| Marker | Code | Symbols (default mapping) | Meaning | Pause after |")
        appendLine("|---|---|---|---|---|")
        val meaning = mapOf(
            ControlCode.START to "message starts" to t.startMarkerPauseMs,
            ControlCode.SEPARATOR to "next field begins (sender → message …)" to t.separatorPauseMs,
            ControlCode.END to "message ends (the completion tick follows)" to t.endMarkerPauseMs,
            ControlCode.CASE_SHIFT to "next letter is upper case" to t.charGapMs,
            ControlCode.TRUNCATED to "this field was shortened or left out" to t.charGapMs,
        )
        for ((pair, pause) in meaning) {
            val (control, text) = pair
            appendLine("| ${control.displayName} | ${control.code} | `${letters(control.code)}` | $text | $pause ms |")
        }
        appendLine()
        appendLine("## Message format")
        appendLine()
        appendLine("```")
        appendLine("START  field1  [TRUNCATED]  SEPARATOR  field2  [TRUNCATED] …  END   (then one ${t.completionTickMs} ms completion tick)")
        appendLine("```")
        appendLine()
        appendLine("Example, sender `Alex`, message `Hello.` in the default mode:")
        appendLine()
        val framed = MessageFramer(CharacterEncoder(EncodingTable.DEFAULT)).frame(
            listOf(FrameField(MessageSection.SENDER, "Alex"), FrameField(MessageSection.MESSAGE, "Hello.")),
        )
        appendLine("| # | Unit | Symbols |")
        appendLine("|---|---|---|")
        framed.units.forEachIndexed { i, u ->
            val label = if (u.isControl) u.control!!.displayName else if (u.text == " ") "space" else "`${u.text}`"
            appendLine("| ${i + 1} | $label | `${letters(u.code)}` |")
        }
        appendLine()
        appendLine("The decoder screen accepts this notation: symbols `S L D T`, optionally `|` between characters, e.g.")
        appendLine("`${CharacterDecoder.formatUnits(framed.units.take(3), SymbolMapping.DEFAULT)}`.")
    }

    @Test
    fun documentationMatchesTheCode() {
        val dir = System.getProperty("docs.dir") ?: return
        val file = File(dir, "ENCODING_TABLE.md")
        val expected = render()
        if (System.getenv("UPDATE_DOCS") == "1" || !file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(expected)
        }
        assertEquals("docs/ENCODING_TABLE.md is stale; run the tests with UPDATE_DOCS=1", expected, file.readText())
    }
}
