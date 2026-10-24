package com.example.vibratext.data

import com.example.vibratext.encoding.CaseMode
import com.example.vibratext.encoding.EncodingTable
import com.example.vibratext.encoding.SymbolMapping
import com.example.vibratext.encoding.UnsupportedPolicy
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.json.MiniJson
import com.example.vibratext.playback.PlaybackOutcome
import com.example.vibratext.playback.QueuePolicy
import com.example.vibratext.playback.RepeatSettings
import com.example.vibratext.playback.RequestSource
import com.example.vibratext.playback.SpeedPreset
import com.example.vibratext.playback.SpeedSettings
import com.example.vibratext.playback.TestRequests
import com.example.vibratext.playback.TimingSettings
import com.example.vibratext.rules.NotificationRule
import com.example.vibratext.rules.RuleScope
import com.example.vibratext.rules.RulesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayerTest {

    private val custom = EncodingProfile(
        id = "p1", name = "Custom \"quoted\" é",
        symbolMapping = SymbolMapping(listOf(VibSymbol.TRIPLE, VibSymbol.DOUBLE, VibSymbol.LONG, VibSymbol.SHORT)),
        charCodes = EncodingTable.defaultCharCodes().toMutableMap().also { it['a'] = 58; it['z'] = 0 },
        caseMode = CaseMode.PRESERVE, stripAccents = false, unsupportedPolicy = UnsupportedPolicy.ERROR, replacementChar = '?',
        timing = TimingSettings(shortPulseMs = 180, longPulseMs = 700, wordGapMs = 1000),
        speed = SpeedSettings(SpeedPreset.CUSTOM, 1.25),
        repeat = RepeatSettings(count = 3, pauseMs = 1500, repeatMarker = true),
        intensityPercent = 55, completionTick = false,
    )

    // ---- profiles -----------------------------------------------------------------------------

    @Test
    fun customProfileIsValidAndRoundTripsThroughExportAndImport() {
        assertTrue(custom.validate().isEmpty())
        val json = ProfileJson.export(custom)
        assertFalse("export must not leak the local id", json.contains("\"id\""))
        val result = ProfileJson.import(json, "new-id")
        assertTrue(result.toString(), result is ProfileImportResult.Success)
        val imported = (result as ProfileImportResult.Success).profile
        assertEquals(custom.copy(id = "new-id"), imported)
    }

    @Test
    fun encodedEncodingMatchesAcrossExportImport() {
        val imported = (ProfileJson.import(ProfileJson.export(custom), "x") as ProfileImportResult.Success).profile
        val a = custom.encoder().encode("Hello Zed")
        val b = imported.encoder().encode("Hello Zed")
        assertEquals(a, b)
    }

    private fun importErrors(json: String): List<String> {
        val r = ProfileJson.import(json, "x")
        assertTrue("expected failure but was $r", r is ProfileImportResult.Failure)
        return (r as ProfileImportResult.Failure).errors
    }

    @Test
    fun importRejectsGarbageWrongFormatAndFutureVersions() {
        assertTrue(importErrors("not json").isNotEmpty())
        assertTrue(importErrors("[]").isNotEmpty())
        assertTrue(importErrors("""{"format":"something-else","version":1,"profile":{}}""").single().contains("not a VibraText profile"))
        assertTrue(importErrors("""{"format":"vibratext-profile","version":99,"profile":{"name":"x"}}""").single().contains("Unsupported profile version"))
        assertTrue(importErrors("""{"format":"vibratext-profile","version":1}""").isNotEmpty())
        assertTrue(importErrors("x".repeat(ProfileJson.MAX_IMPORT_CHARS + 1)).single().contains("too large"))
    }

    @Test
    fun importValidatesEverythingBeforeAccepting() {
        fun profile(body: String) = """{"format":"vibratext-profile","version":1,"profile":{"name":"T",$body}}"""
        assertTrue(importErrors(profile(""""timing":{"shortPulseMs":5}""")).any { it.contains("Short pulse") })
        assertTrue(importErrors(profile(""""repeat":{"count":99}""")).any { it.contains("Repeat count") })
        assertTrue(importErrors(profile(""""intensityPercent":500""")).any { it.contains("Intensity") })
        assertTrue(importErrors(profile(""""symbolMapping":["SHORT","SHORT","DOUBLE","TRIPLE"]""")).any { it.contains("only one digit") })
        assertTrue(importErrors(profile(""""symbolMapping":["SHORT","NOPE","DOUBLE","TRIPLE"]""")).any { it.contains("Unknown symbol") })
        assertTrue(importErrors(profile(""""caseMode":"SHOUTY"""")).any { it.contains("Unknown value") })
        assertTrue(importErrors(profile(""""charCodes":{"ab":1}""")).any { it.contains("exactly one character") })
        assertTrue(importErrors(profile(""""timing":"fast"""")).any { it.contains("must be an object") })
        assertTrue(importErrors(profile(""""stripAccents":"yes"""")).any { it.contains("true or false") })
        assertTrue(importErrors(profile(""""replacementChar":"ab"""")).any { it.contains("exactly one character") })
        assertTrue(importErrors("""{"format":"vibratext-profile","version":1,"profile":{"name":""}}""").any { it.contains("needs a name") })
        assertTrue(importErrors("""{"format":"vibratext-profile","version":1,"profile":{"name":"T","charCodes":{"a":0,"b":0,"c":2}}}""").isNotEmpty())
    }

    @Test
    fun importedDataIsNeverInterpretedUnknownKeysAreIgnoredAndIdsAreFresh() {
        val hostile = """{"format":"vibratext-profile","version":1,"id":"default","exec":"rm -rf /","profile":{"id":"default","name":"Evil","script":"Runtime.exec(...)","${'$'}class":"java.lang.Runtime"}}"""
        val r = ProfileJson.import(hostile, "fresh-id")
        assertTrue(r is ProfileImportResult.Success)
        val p = (r as ProfileImportResult.Success).profile
        assertEquals("fresh-id", p.id) // cannot overwrite the default profile
        assertEquals("Evil", p.name)
        assertEquals(EncodingProfile("fresh-id", "Evil"), p) // everything else is plain defaults
    }

    @Test
    fun profileValidationCoversNamesAndTables() {
        assertTrue(EncodingProfile("a", "  ").validate().any { it.contains("needs a name") })
        assertTrue(EncodingProfile("a", "x".repeat(41)).validate().any { it.contains("at most") })
        assertTrue(EncodingProfile("a", "ok", charCodes = EncodingTable.defaultCharCodes() + ('b' to 0)).validate().any { it.contains("unique") })
        assertTrue(EncodingProfile("a", "ok", replacementChar = '~').validate().any { it.contains("replacement") })
        assertTrue(EncodingProfile("a", "ok", intensityPercent = 0).validate().isNotEmpty())
        assertTrue(EncodingProfile("a", "ok").validate().isEmpty())
    }

    @Test
    fun customSymbolMappingChangesEncodingAndDecodingOfTheSameProfile() {
        val d = EncodingProfile("a", "d")
        val c = d.copy(id = "b", name = "c", symbolMapping = SymbolMapping(listOf(VibSymbol.LONG, VibSymbol.SHORT, VibSymbol.TRIPLE, VibSymbol.DOUBLE)))
        val codes = d.encoder().encode("hello").units.map { it.code }
        val sd = com.example.vibratext.encoding.CharacterDecoder.formatSymbols(codes, d.symbolMapping)
        val sc = com.example.vibratext.encoding.CharacterDecoder.formatSymbols(codes, c.symbolMapping)
        assertTrue(sd != sc)
        for ((profile, symbols) in listOf(d to sd, c to sc)) {
            val r = profile.decoder().decodeSymbols(symbols)
            assertEquals("hello", (r as com.example.vibratext.encoding.DecodeResult.Success).value.fields.single().text)
        }
    }

    // ---- profile book -------------------------------------------------------------------------

    @Test
    fun profileBookOperations() {
        val b0 = ProfileBook.initial()
        assertEquals("Default English", b0.default.name)
        val b1 = b0.upsert(custom)
        assertEquals(2, b1.profiles.size)
        assertEquals("p1", b1.resolve("p1").id)
        assertEquals("default", b1.resolve("missing").id)
        assertEquals("default", b1.resolve(null).id)

        val b2 = b1.setDefault("p1")
        assertEquals("p1", b2.default.id)
        val b3 = b2.delete("p1") // deleting the default promotes another
        assertEquals("default", b3.defaultProfileId)
        assertEquals(b3, b3.delete("default")) // cannot delete the last profile
        assertEquals(b1, b1.delete("nope"))
        assertEquals(b1, b1.setDefault("nope"))

        val renamed = b1.rename("p1", "Renamed")
        assertEquals("Renamed", renamed.byId("p1")!!.name)
        val dup = b1.duplicate("p1", "p2")
        assertEquals(3, dup.profiles.size)
        assertEquals(custom.copy(id = "p2", name = dup.byId("p2")!!.name), dup.byId("p2"))
        assertTrue(dup.byId("p2")!!.name.endsWith("copy"))
        assertEquals("Default English 2", b0.uniqueName("Default English")) // names compare ignoring case
        assertEquals("default english 2", b0.uniqueName("default english")) // but the given casing is kept
        assertEquals("Fresh name", b0.uniqueName("  Fresh name "))
        assertEquals("Default English 3", b0.upsert(EncodingProfile("z", "Default English 2")).uniqueName("Default English"))
    }

    @Test
    fun profileBookRejectsInvalidStates() {
        for (bad in listOf<() -> Unit>(
            { ProfileBook(emptyList(), "x") },
            { ProfileBook(listOf(EncodingProfile("a", "A")), "b") },
            { ProfileBook(listOf(EncodingProfile("a", "A"), EncodingProfile("a", "B")), "a") },
        )) {
            try {
                bad()
                org.junit.Assert.fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun profileBookStorageRoundTripsAndToleratesDamage() {
        val book = ProfileBook.initial().upsert(custom).setDefault("p1")
        assertEquals(book, ProfileJson.decodeBook(ProfileJson.encodeBook(book)))
        assertEquals(ProfileBook.initial(), ProfileJson.decodeBook(null))
        assertEquals(ProfileBook.initial(), ProfileJson.decodeBook("{broken"))
        assertEquals(ProfileBook.initial(), ProfileJson.decodeBook("""{"profiles":[{"name":"no id"}]}"""))
        // an invalid stored profile is skipped, a valid one survives, default falls back
        val mixed = """{"default":"gone","profiles":[{"id":"bad","name":"B","repeat":{"count":99}},{"id":"ok","name":"Ok"}]}"""
        val decoded = ProfileJson.decodeBook(mixed)
        assertEquals(listOf("ok"), decoded.profiles.map { it.id })
        assertEquals("ok", decoded.defaultProfileId)
    }

    @Test
    fun templatesProduceValidDistinctProfiles() {
        val built = ProfileTemplates.all.mapIndexed { i, t -> t.build("t$i") }
        assertTrue(built.all { it.validate().isEmpty() })
        assertEquals(built.size, built.map { it.name }.toSet().size)
        assertEquals(40, ProfileTemplates.all.first { it.key == "quiet" }.build("q").intensityPercent)
        assertEquals(SpeedPreset.FAST, ProfileTemplates.all.first { it.key == "fast" }.build("f").speed.preset)
        assertEquals(CaseMode.PRESERVE, ProfileTemplates.all.first { it.key == "case" }.build("c").caseMode)
    }

    // ---- settings -----------------------------------------------------------------------------

    @Test
    fun defaultsAreSafe() {
        val s = AppSettings()
        assertFalse("must not vibrate on a fresh install", s.monitoringEnabled)
        assertTrue(s.selectedPackages.isEmpty())
        assertFalse(s.playSilent)
        assertFalse(s.playOngoing)
        assertTrue(s.ignoreDuplicates)
        assertEquals(QueuePolicy.PLAY_ALL, s.queuePolicy)
        assertTrue(s.validate().isEmpty())
    }

    @Test
    fun settingsRoundTripThroughJsonAndSanitizeBadValues() {
        val s = AppSettings(
            monitoringEnabled = true, selectedPackages = setOf("a.b", "c.d"), playbackMode = PlaybackMode.APP_SENDER_MESSAGE,
            template = "{app}: {message}", maxMessageChars = 99, queuePolicy = QueuePolicy.COMBINE_SAME_APP,
            vibrationUsage = VibrationUsage.ACCESSIBILITY, historyEnabled = false, privacyAcknowledged = true,
            longMessagePolicy = LongMessagePolicy.SUMMARY, unknownSenderBehavior = UnknownSenderBehavior.EMPTY_SENDER_FIELD,
        )
        assertEquals(s, SettingsJson.decode(SettingsJson.encode(s)))
        assertEquals(AppSettings(), SettingsJson.decode(null))
        assertEquals(AppSettings(), SettingsJson.decode("garbage"))
        val bad = SettingsJson.decode("""{"maxMessageChars":99999,"minIntervalMs":-5,"template":"{nope}","playbackMode":"???","queuePolicy":"X"}""")
        assertEquals(500, bad.maxMessageChars)
        assertEquals(0L, bad.minIntervalMs)
        assertEquals(AppSettings().template, bad.template)
        assertEquals(PlaybackMode.FULL_MESSAGE, bad.playbackMode)
        assertEquals(QueuePolicy.PLAY_ALL, bad.queuePolicy)
    }

    @Test
    fun settingsValidationExplainsProblems() {
        assertTrue(AppSettings(maxMessageChars = 0).validate().any { it.contains("message length") })
        assertTrue(AppSettings(maxQueueSize = 0).validate().any { it.contains("Queue") })
        assertTrue(AppSettings(minIntervalMs = -1).validate().isNotEmpty())
        assertTrue(AppSettings(template = "{bad}").validate().isNotEmpty())
    }

    // ---- rules / history storage --------------------------------------------------------------

    @Test
    fun rulesRoundTripThroughJson() {
        val rules = listOf(
            NotificationRule("1", RuleScope.CONTACT, "Alex \"A\"", profileId = "p", important = true, mute = false),
            NotificationRule("2", RuleScope.APP, "com.whatsapp", enabled = false, playbackMode = PlaybackMode.SENDER_ONLY),
            NotificationRule("3", RuleScope.CATEGORY, "CALL", mute = true),
        )
        assertEquals(rules, RulesJson.decode(RulesJson.encode(rules)))
        assertTrue(RulesJson.decode("nonsense").isEmpty())
        assertEquals(1, RulesJson.decode("""[{"id":"1","scope":"APP","match":"x","mute":true},{"id":"2","scope":"NOPE","match":"y"},"junk"]""").size)
    }

    @Test
    fun historyRecorderStoresNotificationPlaybacksOnlyAndMapsOutcomes() {
        val entries = ArrayList<HistoryEntry>()
        val recorder = HistoryRecorder({ 123L }) { entries += it }
        val n = TestRequests.request("hello", pkg = "p")
        val test = TestRequests.request("hello", bypass = true)
        recorder.onFinished(n, PlaybackOutcome.COMPLETED)
        recorder.onFinished(test, PlaybackOutcome.COMPLETED)
        recorder.onFinished(n, PlaybackOutcome.CANCELLED)
        recorder.onFinished(n, PlaybackOutcome.DROPPED)
        assertEquals(3, entries.size)
        assertEquals(listOf(HistoryStatus.COMPLETED, HistoryStatus.CANCELLED, HistoryStatus.DROPPED), entries.map { it.status })
        assertEquals(RequestSource.TEST, test.meta.source)
        assertEquals("Sender", entries[0].sender)
        assertEquals(123L, entries[0].timestamp)
        assertEquals(HistoryStatus.REPLACED, PlaybackOutcome.REPLACED.toHistoryStatus())
        assertEquals(HistoryStatus.FAILED, PlaybackOutcome.FAILED.toHistoryStatus())
    }

    @Test
    fun historyIsBoundedNewestFirstAndRoundTrips() {
        var list = emptyList<HistoryEntry>()
        for (i in 1..15) {
            list = HistoryJson.add(list, HistoryEntry(i.toLong(), i * 100L, "App", "pkg", if (i % 2 == 0) null else "S$i", "text \"$i\" 😀", null, HistoryStatus.COMPLETED, i == 3, 1), 10)
        }
        assertEquals(10, list.size)
        assertEquals(15L, list.first().id)
        assertEquals(6L, list.last().id)
        assertEquals(list, HistoryJson.decode(HistoryJson.encode(list)))
        assertTrue(HistoryJson.decode("junk").isEmpty())
        val decoded = HistoryJson.decode(HistoryJson.encode(list))
        assertEquals("S15", decoded[0].sender)
        assertNull(decoded[1].sender) // entry 14 had no sender
        assertTrue(MiniJson.parse(HistoryJson.encode(emptyList())) is List<*>)
    }

    @Test
    fun historyPrivacyOptionRemovesContentButKeepsTheRest() {
        val e = HistoryEntry(1, 5, "WhatsApp", "pkg", "Alex", "secret text", "Default English", HistoryStatus.COMPLETED, true, 2)
        val r = e.withoutContent()
        assertNull(r.sender)
        assertEquals("", r.preview)
        assertEquals("WhatsApp", r.applicationName)
        assertEquals(HistoryStatus.COMPLETED, r.status)
        assertEquals(2, r.combinedCount)
        val s = AppSettings(historyStoreContent = false)
        assertFalse(SettingsJson.decode(SettingsJson.encode(s)).historyStoreContent)
        assertTrue(SettingsJson.decode(null).historyStoreContent)
    }
}
