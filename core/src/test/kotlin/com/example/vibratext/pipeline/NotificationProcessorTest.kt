package com.example.vibratext.pipeline

import com.example.vibratext.data.AppSettings
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.data.PlaybackMode
import com.example.vibratext.data.ProfileBook
import com.example.vibratext.data.ProfileTemplates
import com.example.vibratext.encoding.CaseMode
import com.example.vibratext.encoding.ControlCode
import com.example.vibratext.encoding.DecodeResult
import com.example.vibratext.encoding.UnsupportedPolicy
import com.example.vibratext.notification.NotificationContext
import com.example.vibratext.notification.NotificationDeduplicator
import com.example.vibratext.notification.ParsedNotification
import com.example.vibratext.notification.RawMessage
import com.example.vibratext.notification.RawNotification
import com.example.vibratext.notification.RejectReason
import com.example.vibratext.notification.SenderSource
import com.example.vibratext.playback.QueuePolicy
import com.example.vibratext.playback.RepeatSettings
import com.example.vibratext.playback.SegmentRole
import com.example.vibratext.rules.NotificationClass
import com.example.vibratext.rules.NotificationRule
import com.example.vibratext.rules.NotificationRuleEngine
import com.example.vibratext.rules.RuleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProcessorTest {
    private val whatsapp = "com.whatsapp"
    private val signal = "org.thoughtcrime.securesms"
    private val telegram = "org.telegram.messenger"

    private val settings = AppSettings(monitoringEnabled = true, selectedPackages = setOf(whatsapp, signal, telegram), minIntervalMs = 0)
    private val book = ProfileBook.initial()
    private val ctx = NotificationContext(ownPackage = "com.example.vibratext", deviceInUse = false, nowMs = 10_000)

    private fun whatsappMessage(sender: String = "Alex", text: String = "Hello, are you free?", ts: Long = 1_000, key: String = "wa-1") = RawNotification(
        packageName = whatsapp, key = key, id = 1, postTime = ts,
        title = sender, text = text, category = "msg", isMessagingStyle = true,
        messages = listOf(RawMessage(sender, text, ts)),
    )

    private fun process(
        raw: RawNotification,
        s: AppSettings = settings,
        rules: List<NotificationRule> = emptyList(),
        b: ProfileBook = book,
        c: NotificationContext = ctx,
        processor: NotificationProcessor = NotificationProcessor(),
        app: String = "WhatsApp",
    ) = processor.process(raw, app, s, rules, b, c)

    private fun play(r: ProcessResult): ProcessResult.Play {
        assertTrue("expected Play but was $r", r is ProcessResult.Play)
        return r as ProcessResult.Play
    }

    private fun decodedFields(p: ProcessResult.Play): List<String> {
        val codes = p.request.script.units.map { it.code }
        val result = p.profile.decoder().decodeCodes(codes)
        assertTrue("decode failed: $result", result is DecodeResult.Success)
        return (result as DecodeResult.Success).value.fields.map { it.text }
    }

    // ---- the headline workflow ----------------------------------------------------------------

    @Test
    fun whatsAppMessageBecomesSenderSeparatorMessageEndAndTick() {
        val p = play(process(whatsappMessage()))
        assertEquals(listOf("alex", "hello, are you free?"), decodedFields(p))
        val units = p.request.script.units
        assertEquals(ControlCode.START, units.first().control)
        assertEquals(ControlCode.END, units.last().control)
        assertEquals(1, units.count { it.control == ControlCode.SEPARATOR })
        assertEquals(SegmentRole.COMPLETION_TICK, p.request.script.segments.last().role)
        assertEquals("WhatsApp", p.request.meta.applicationName)
        assertEquals("Alex", p.request.meta.senderName)
        assertEquals("Alex: Hello, are you free?", p.request.meta.preview)
        assertEquals("Default English", p.request.meta.profileName)
        assertEquals(whatsapp, p.request.meta.packageName)
    }

    @Test
    fun signalNotificationIsProcessedWithItsOwnProfileViaRules() {
        val quiet = ProfileTemplates.all.first { it.key == "quiet" }.build("quiet")
        val b = book.upsert(quiet)
        val rules = listOf(NotificationRule("r1", RuleScope.APP, signal, profileId = "quiet"))
        val raw = RawNotification(packageName = signal, key = "s1", title = "Sam", text = "On my way", category = "msg", isMessagingStyle = true, messages = listOf(RawMessage("Sam", "On my way", 5)))
        val p = play(process(raw, rules = rules, b = b, app = "Signal"))
        assertEquals("Quiet Notifications", p.request.meta.profileName)
        assertEquals(40, p.request.intensityPercent)
        // WhatsApp keeps the default profile
        val wa = play(process(whatsappMessage(), rules = rules, b = b))
        assertEquals("Default English", wa.request.meta.profileName)
        assertEquals(100, wa.request.intensityPercent)
    }

    @Test
    fun notificationsFromUnselectedAppsNeverPlay() {
        val raw = whatsappMessage().copy(packageName = "com.instagram.android")
        val r = process(raw)
        assertEquals(ProcessResult.Ignored(RejectReason.NOT_SELECTED), r)
    }

    @Test
    fun notificationsFromVibraTextItselfNeverPlay() {
        val s = settings.copy(selectedPackages = settings.selectedPackages + "com.example.vibratext")
        val raw = whatsappMessage().copy(packageName = "com.example.vibratext")
        assertEquals(ProcessResult.Ignored(RejectReason.OWN_APP), process(raw, s = s))
    }

    @Test
    fun nothingPlaysUntilMonitoringIsEnabled() {
        assertEquals(ProcessResult.Ignored(RejectReason.MONITORING_OFF), process(whatsappMessage(), s = settings.copy(monitoringEnabled = false)))
        assertEquals(ProcessResult.Ignored(RejectReason.NOT_SELECTED), process(whatsappMessage(), s = AppSettings(monitoringEnabled = true)))
    }

    // ---- missing / hidden data ----------------------------------------------------------------

    @Test
    fun missingSenderPlaysTheAppNameFirstThenTheMessage() {
        val raw = RawNotification(packageName = whatsapp, key = "g", text = "Pizza tonight?", isGroupConversation = true, isMessagingStyle = true, messages = listOf(RawMessage(null, "Pizza tonight?", 5)), conversationTitle = "Friends")
        val p = play(process(raw))
        assertEquals(listOf("whatsapp", "pizza tonight?"), decodedFields(p))
        assertNull(p.request.meta.senderName)
        assertEquals(SenderSource.NONE, p.parsed.senderSource)
    }

    @Test
    fun hiddenPreviewPlaysOnlyWhatTheNotificationExposes() {
        val raw = RawNotification(packageName = whatsapp, key = "h", title = "WhatsApp", text = "New message", category = "msg")
        val p = play(process(raw))
        assertEquals(listOf("whatsapp", "new message"), decodedFields(p))
    }

    @Test
    fun senderWithoutMessageTextPlaysTheSenderOnly() {
        val raw = RawNotification(packageName = whatsapp, key = "n", title = "Alex", category = "msg")
        val p = play(process(raw))
        assertEquals(listOf("alex"), decodedFields(p))
    }

    @Test
    fun notificationsWithNoReadableTextAreIgnoredOrPlayedAsAppNameByTheUsersChoice() {
        val raw = RawNotification(packageName = whatsapp, key = "e")
        assertEquals(ProcessResult.Ignored(RejectReason.NO_READABLE_CONTENT), process(raw))
        val p = play(process(raw, s = settings.copy(requireReadableContent = false)))
        assertEquals(listOf("whatsapp"), decodedFields(p))
    }

    @Test
    fun emojiAndAccentsInRealMessagesAreHandled() {
        val p = play(process(whatsappMessage(sender = "José", text = "Café? 😀 ok")))
        assertEquals(listOf("jose", "cafe? ? ok"), decodedFields(p))
    }

    @Test
    fun profilesThatRejectUnsupportedCharactersSkipTheNotificationInsteadOfCrashing() {
        val strict = EncodingProfile("strict", "Strict", unsupportedPolicy = UnsupportedPolicy.ERROR)
        val b = ProfileBook(listOf(strict), "strict")
        assertEquals(ProcessResult.Ignored(RejectReason.UNSUPPORTED_CHARACTER), process(whatsappMessage(text = "hi 😀"), b = b))
        assertTrue(process(whatsappMessage(text = "hi there"), b = b) is ProcessResult.Play)
    }

    // ---- duplicates ---------------------------------------------------------------------------

    @Test
    fun duplicateEventsAreFilteredButNewMessagesInTheSameConversationAreNot() {
        val processor = NotificationProcessor(NotificationDeduplicator())
        val first = whatsappMessage()
        assertTrue(process(first, processor = processor) is ProcessResult.Play)
        // same notification re-posted (e.g. a ranking update)
        assertEquals(ProcessResult.Ignored(RejectReason.DUPLICATE), process(first.copy(postTime = 5_000), processor = processor))
        // a new message in the same conversation (same key)
        val next = first.copy(messages = first.messages + RawMessage("Alex", "Hello?", 2_000), text = "Hello?")
        assertTrue(process(next, processor = processor) is ProcessResult.Play)
        // duplicate filtering can be turned off
        val off = settings.copy(ignoreDuplicates = false)
        assertTrue(process(first, s = off, processor = processor) is ProcessResult.Play)
    }

    @Test
    fun rejectedNotificationsDoNotConsumeTheDuplicateSlot() {
        val processor = NotificationProcessor(NotificationDeduplicator())
        val raw = whatsappMessage()
        assertEquals(ProcessResult.Ignored(RejectReason.IN_CALL), process(raw, c = ctx.copy(inCall = true), processor = processor))
        assertTrue(process(raw, processor = processor) is ProcessResult.Play)
    }

    // ---- rules --------------------------------------------------------------------------------

    private fun parsed(sender: String? = "Alex", message: String? = "Hi", importance: Int? = null, category: String? = "msg", pkg: String = whatsapp) =
        ParsedNotification(pkg, "App", sender, message, null, 0, "k", category, null, SenderSource.TITLE, false, importance, true)

    @Test
    fun rulePriorityIsContactThenAppThenCategoryThenGlobal() {
        val rules = listOf(
            NotificationRule("cat", RuleScope.CATEGORY, NotificationClass.MESSAGE.name, profileId = "p-cat", playbackMode = PlaybackMode.SENDER_ONLY),
            NotificationRule("app", RuleScope.APP, whatsapp, profileId = "p-app"),
            NotificationRule("contact", RuleScope.CONTACT, "alex", profileId = "p-contact"),
        )
        val alex = NotificationRuleEngine.resolve(parsed(), rules)
        assertEquals("p-contact", alex.profileId) // contact beats app and category
        assertEquals(PlaybackMode.SENDER_ONLY, alex.playbackMode) // only the category rule has an opinion on mode
        assertEquals(listOf("contact", "app", "cat"), alex.matchedRuleIds)

        val other = NotificationRuleEngine.resolve(parsed(sender = "Bo"), rules)
        assertEquals("p-app", other.profileId)

        val signalMsg = NotificationRuleEngine.resolve(parsed(sender = "Bo", pkg = signal), rules)
        assertEquals("p-cat", signalMsg.profileId)

        val none = NotificationRuleEngine.resolve(parsed(sender = "Bo", pkg = signal, category = "status").copy(isMessaging = false), rules)
        assertNull(none.profileId)
        assertNull(none.playbackMode)
        assertTrue(none.matchedRuleIds.isEmpty())
    }

    @Test
    fun disabledRulesAndContactMatchingRulesAreHonoured() {
        val rules = listOf(NotificationRule("c", RuleScope.CONTACT, "  ALEX ", enabled = false, profileId = "x"))
        assertNull(NotificationRuleEngine.resolve(parsed(), rules).profileId)
        val enabled = rules.map { it.copy(enabled = true) }
        assertEquals("x", NotificationRuleEngine.resolve(parsed(), enabled).profileId)
        // contacts are matched on the sender name the notification exposes: no sender, no contact rule
        assertNull(NotificationRuleEngine.resolve(parsed(sender = null), enabled).profileId)
    }

    @Test
    fun notificationClassesAreDetected() {
        assertEquals(
            setOf(NotificationClass.MESSAGE),
            NotificationRuleEngine.classify(parsed()),
        )
        assertTrue(NotificationClass.CALL in NotificationRuleEngine.classify(parsed(category = "call")))
        assertTrue(NotificationClass.HIGH_PRIORITY in NotificationRuleEngine.classify(parsed(importance = 4)))
        assertFalse(NotificationClass.HIGH_PRIORITY in NotificationRuleEngine.classify(parsed(importance = 3)))
        assertTrue(NotificationClass.UNKNOWN_SENDER in NotificationRuleEngine.classify(parsed(sender = null)))
        assertTrue(NotificationClass.NO_MESSAGE_TEXT in NotificationRuleEngine.classify(parsed(message = null)))
    }

    @Test
    fun categoryRulesApplyToCallsUnknownSendersAndEmptyMessages() {
        val emergency = ProfileTemplates.all.first { it.key == "emergency" }.build("emg")
        val b = book.upsert(emergency)
        val rules = listOf(
            NotificationRule("calls", RuleScope.CATEGORY, NotificationClass.CALL.name, profileId = "emg"),
            NotificationRule("unknown", RuleScope.CATEGORY, NotificationClass.UNKNOWN_SENDER.name, playbackMode = PlaybackMode.MESSAGE_ONLY),
        )
        val call = RawNotification(packageName = whatsapp, key = "c", title = "Incoming call", text = "Alex", category = "call")
        val p = play(process(call, rules = rules, b = b))
        assertEquals("Emergency Alerts", p.request.meta.profileName)
        assertEquals(3, p.request.script.repeatCount)
    }

    @Test
    fun muteRulesIgnoreAnAppButAContactRuleCanLetOnePersonThrough() {
        val rules = listOf(
            NotificationRule("mute-wa", RuleScope.APP, whatsapp, mute = true),
            NotificationRule("vip", RuleScope.CONTACT, "Alex", mute = false),
        )
        assertEquals(ProcessResult.Ignored(RejectReason.MUTED_BY_RULE), process(whatsappMessage(sender = "Bob"), rules = rules))
        assertTrue(process(whatsappMessage(sender = "Alex"), rules = rules) is ProcessResult.Play)
    }

    @Test
    fun importantOnlyModePlaysOnlyNotificationsMatchingImportantRules() {
        val s = settings.copy(onlyImportant = true)
        val rules = listOf(NotificationRule("vip", RuleScope.CONTACT, "Alex", important = true))
        assertTrue(process(whatsappMessage(sender = "Alex"), s = s, rules = rules) is ProcessResult.Play)
        assertEquals(ProcessResult.Ignored(RejectReason.NOT_IMPORTANT), process(whatsappMessage(sender = "Bob", key = "k2"), s = s, rules = rules))
        assertEquals(ProcessResult.Ignored(RejectReason.NOT_IMPORTANT), process(whatsappMessage(key = "k3"), s = s))
    }

    @Test
    fun ruleModeOverrideAndRuleValidation() {
        val rules = listOf(NotificationRule("m", RuleScope.APP, whatsapp, playbackMode = PlaybackMode.SENDER_ONLY))
        val p = play(process(whatsappMessage(), rules = rules))
        assertEquals(listOf("alex"), decodedFields(p))

        assertTrue(NotificationRuleEngine.validate(NotificationRule("x", RuleScope.APP, "")).isNotEmpty())
        assertTrue(NotificationRuleEngine.validate(NotificationRule("x", RuleScope.APP, whatsapp)).any { it.contains("does nothing") })
        assertTrue(NotificationRuleEngine.validate(NotificationRule("x", RuleScope.CATEGORY, "NOPE", mute = true)).any { it.contains("Unknown notification type") })
        assertTrue(NotificationRuleEngine.validate(NotificationRule("x", RuleScope.CONTACT, "Alex", mute = true)).isEmpty())
    }

    // ---- profile settings reach the script ----------------------------------------------------

    @Test
    fun profileRepeatSpeedCaseAndTickSettingsAreAppliedToTheRequest() {
        val custom = EncodingProfile(
            id = "c", name = "Custom", caseMode = CaseMode.PRESERVE,
            repeat = RepeatSettings(count = 2, pauseMs = 1000), completionTick = false,
            speed = com.example.vibratext.playback.SpeedSettings(com.example.vibratext.playback.SpeedPreset.SLOW),
        )
        val p = play(process(whatsappMessage(sender = "Alex"), b = ProfileBook(listOf(custom), "c")))
        assertEquals(listOf("Alex", "Hello, are you free?"), decodedFields(p))
        assertEquals(2, p.request.script.repeatCount)
        assertTrue(p.request.script.segments.none { it.role == SegmentRole.COMPLETION_TICK })
        val normal = play(process(whatsappMessage(key = "n2"), b = ProfileBook(listOf(custom.copy(speed = com.example.vibratext.playback.SpeedSettings())), "c")))
        assertTrue(p.request.script.segments.first().durationMs > normal.request.script.segments.first().durationMs)
    }

    @Test
    fun startDelayAndQueueCombinerAreWiredIntoTheRequest() {
        val s = settings.copy(startDelayMs = 700, queuePolicy = QueuePolicy.COMBINE_SAME_APP)
        val p = play(process(whatsappMessage(), s = s))
        assertEquals(700L, p.request.startDelayMs)
        val combined = p.request.combiner!!.invoke(2)
        assertEquals(3, combined.meta.combinedCount)
        val codes = combined.script.units.map { it.code }
        val fields = (p.profile.decoder().decodeCodes(codes) as DecodeResult.Success).value.fields.map { it.text }
        assertEquals(listOf("whatsapp", "3 new"), fields)
        assertNotNull(combined.combiner)
    }

    @Test
    fun longMessagesAreMarkedTruncatedInTheRequestMetadata() {
        val p = play(process(whatsappMessage(text = "x".repeat(200)), s = settings.copy(maxMessageChars = 30)))
        assertTrue(p.request.meta.truncated)
        assertTrue(p.composed.truncated)
        val units = p.request.script.units
        assertEquals(ControlCode.TRUNCATED, units[units.size - 2].control)
        val d = p.profile.decoder().decodeCodes(units.map { it.code }) as DecodeResult.Success
        assertTrue(d.value.fields.last().truncated)
    }
}
